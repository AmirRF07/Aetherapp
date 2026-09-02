package studio.cluvex.aether.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.cluvex.aether.AetherApp
import studio.cluvex.aether.MainActivity
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.AetherProcess
import studio.cluvex.aether.core.Diagnostics
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.AutoCandidate
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.PortProbe
import studio.cluvex.aether.core.ProfileCodec
import studio.cluvex.aether.core.HevTunnel
import studio.cluvex.aether.core.RoutingEngine
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.core.SmartAuto
import studio.cluvex.aether.core.SocksTunBridge
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.data.SecretStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TransportBackend
import studio.cluvex.aether.transport.ExternalTransport
import studio.cluvex.aether.transport.ExternalTransportFactory
import studio.cluvex.aether.transport.PsiphonHealth
import studio.cluvex.aether.widget.AetherWidgetProvider
import java.io.File

/**
 * The heart of the app. On connect it:
 *   1. launches the bundled `aether` engine (opens SOCKS5 on 127.0.0.1:1819),
 *   2. waits until that port is actually reachable (ground-truth check),
 *   3. builds the VPN TUN interface,
 *   4. starts the embedded hev-socks5-tunnel core (libhev-socks5-tunnel.so) to forward all
 *      traffic through the proxy — replacing the need for v2rayNG entirely,
 *   5. supervises both processes and auto-reconnects on failure.
 */
class AetherVpnService : VpnService() {

    /**
     * The notification this service posts is user-visible text, so it has to obey
     * the in-app language choice exactly like the UI does. Without this the
     * status text would follow the phone's locale while the app showed another
     * language.
     */
    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base?.let { LanguagePrefs.wrap(it) } ?: base)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var engine: AetherProcess? = null
    private var externalTransport: ExternalTransport? = null
    private var tunnelStarted: Boolean = false
    private var runJob: Job? = null

    /**
     * The teardown coroutine of the PREVIOUS session, if one is still
     * finishing. A new connect waits for it instead of racing it (1.2.2
     * protocol-switch fix).
     */
    private var stopJob: Job? = null

    /** Active userspace filter bridge (only when per-app blocking is on). */
    private var tunBridge: SocksTunBridge? = null

    /** Last profile the service ran with (kill-switch decisions). */
    private var lastProfile: ConnectionProfile? = null

    /** True while the kill-switch blackhole TUN is up. */
    @Volatile
    private var lockdownTunActive = false

    /** Consecutive failed watchdog probes (1.2.4 stability watchdog). */
    private var probeFailures = 0

    /** One-shot guard for [registerSelfProbes]. */
    private var selfProbesRegistered = false

    /**
     * Data-path byte total as it read at the previous watchdog decision, so a
     * failed probe can be checked against whether anything is actually moving.
     * See [dataPathBytes] and DATA_PATH_ALIVE_BYTES.
     */
    private var lastDataPathBytes = -1L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                // STRICT KILL SWITCH (1.2.4): a manual disconnect must not
                // open a leak window. With strict mode on, the first
                // disconnect engages lockdown instead; disconnecting FROM
                // lockdown lifts it.
                val last = lastProfile
                when {
                    lockdownTunActive -> stopEverything()
                    last != null && last.strictKillSwitch -> enterLockdown(last)
                    else -> stopEverything()
                }
                return START_NOT_STICKY
            }
            else -> {
                val profile = ProfileCodec.decode(intent?.getStringExtra(EXTRA_PROFILE))
                startForeground(NOTIF_ID, buildNotification(getString(R.string.state_launching)))
                startTunnel(profile)
            }
        }
        return START_STICKY
    }

    /**
     * Puts the Zero Trust secrets back on the profile the Intent delivered.
     *
     * They are the two values [ProfileCodec] deliberately does not carry: an
     * organization credential must not travel inside an Intent extra. The
     * service reads them straight from the hardware-backed store instead, which
     * is also what makes the Zero Trust section work at all - before 1.2.7 the
     * enrolment fields never reached the engine in any form.
     */
    private fun hydrateSecrets(profile: ConnectionProfile): ConnectionProfile {
        if (profile.teamAuth == studio.cluvex.aether.model.TeamAuth.OFF) return profile
        val secrets = SecretStore(applicationContext)
        return profile.copy(
            accessClientSecret = secrets.read(SecretStore.ACCESS_SECRET),
            accessToken = secrets.read(SecretStore.ACCESS_TOKEN),
        )
    }

    private fun startTunnel(rawProfile: ConnectionProfile) {
        val profile = hydrateSecrets(rawProfile)
        lastProfile = profile
        // 1.2.2 PROTOCOL-SWITCH FIX: this used to bail out silently whenever a
        // previous run coroutine was still winding down ("if active, return"),
        // so a connect tapped right after a disconnect — or right after
        // switching protocol — was simply DROPPED. The user then waited,
        // tapped again, and the app looked like it took forever to start.
        // Now the new session takes ownership: it waits for the old one to
        // finish, tears its natives down, and only then launches the engine.
        val previousRun = runJob
        val previousStop = stopJob
        runJob = scope.launch {
            if (previousRun != null) {
                // Same ordering rule as the disconnect path: cancel, kill the
                // natives (which unblocks the old session immediately), and
                // only then wait for it to finish. Joining first would stall
                // the new connect for as long as the old session's engine wait
                // still had to run.
                previousRun.cancel()
                cleanupNativeOnly()
                previousRun.join()
            }
            previousStop?.join()
            try {
                connectFlow(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AetherController.setState(
                    ConnectionState.Error(e.message ?: getString(R.string.state_error)),
                )
                updateNotification(getString(R.string.state_error))
                cleanupNativeOnly()
            }
        }
    }

    private suspend fun connectFlow(profile: ConnectionProfile) {
        DiagnosticsLog.clear()
        // STALE-CIRCLES ROOT-CAUSE FIX: the self-test circles were only
        // reset inside Diagnostics.run(), which starts AFTER the engine has
        // launched AND finished its endpoint scan — so on a reconnect the
        // previous session's green circles sat on screen for the entire scan
        // and appeared to "reset late". Reset them the INSTANT a new connect
        // starts, so the panel always reflects the current attempt on time.
        // The circles must describe the port the FINISHED pipeline exposes: in a
        // chained session that is the second stage's listener, not the engine's.
        Diagnostics.resetChecks(SOCKS_HOST, effectiveSocksPort(profile))
        EngineMeta.reset()
        DiagnosticsLog.i(
            TAG,
            "Connect requested: backend=${profile.backend.pipelineLabel} " +
                "protocol=${profile.protocol} exit=${profile.exitRegion.ifBlank { "auto" }}",
        )

        if (profile.backend.usesExternal) {
            connectExternal(profile)
            return
        }

        val resolved: ConnectionProfile =
            if (profile.protocol == Protocol.AUTO) {
                connectSmartAuto(profile)
            } else {
                // An explicitly chosen protocol keeps that protocol; the
                // engine still selects its own endpoint (see [directPlan]).
                AetherController.setState(ConnectionState.Launching)
                runLadder(directPlan(profile), getString(R.string.err_protocol_failed))
            }

        // Desktop-parity info row (1.2.4): publish the protocol that actually
        // won (Smart Auto resolves AUTO to a concrete protocol). The endpoint
        // arrives through EngineMeta's engine-log parser; for a pinned peer we
        // already know it here (no selection line is logged).
        EngineMeta.setProtocol(resolved.protocol.name)
        if (resolved.manualPeer.isNotBlank()) EngineMeta.setEndpoint(resolved.manualPeer)

        AetherController.setState(ConnectionState.Connected("$SOCKS_HOST:$SOCKS_PORT"))
        updateNotification(getString(R.string.state_connected))
        DiagnosticsLog.i(TAG, "All checks passed — tunnel is ready.")

        superviseEngine(resolved)
    }

    /**
     * Drives a CHAINED session (`Aether -> Psiphon`), then reuses Aether's
     * existing TUN/hev path. The order is the whole point:
     *
     * ```
     *   stage 1  Aether engine     -> SOCKS5 127.0.0.1:1819   (no TUN yet!)
     *   stage 2  Psiphon           -> SOCKS5 127.0.0.1:1827, dialling via 1819
     *   front    PsiphonSocksFront -> SOCKS5 127.0.0.1:1825
     *   then     TUN + tun2socks   -> 1825                    (exit = Psiphon)
     * ```
     *
     * Stage 1 must NOT build the TUN, because stage 2 has to reach the engine
     * over loopback while the engine itself still reaches the internet over the
     * phone's real network. That works because the TUN, once built, excludes this
     * package (see [applyAppFilter]).
     *
     * The FRONT is what tun2socks is handed, never the transport's own listener:
     * the front is the only listener here that answers SOCKS5 `UDP ASSOCIATE`,
     * and without that the device resolves no names at all. The transport reports
     * its front's port from `start()`, so this method just follows it.
     *
     * There is no longer an unchained branch: the single-hop PSIPHON and TOR
     * backends were removed in 1.2.7 because they could not get their own first
     * hop past the networks this app exists for, and Tor was dropped altogether
     * in the same release, so Psiphon is the only external stage left.
     */
    private suspend fun connectExternal(profile: ConnectionProfile) {
        AetherController.setState(ConnectionState.Connecting)
        updateNotification(getString(R.string.state_connecting))
        cleanupNativeOnly()

        val stageProfile: ConnectionProfile = connectAetherStage(profile)

        val transport = ExternalTransportFactory.create(this, profile)
        externalTransport = transport

        // Never let the new listener race a dying one. The single-hop path used
        // to skip this and Psiphon would silently bind a random port because
        // 1819 was still held by the previous session -- which the old
        // `check(port == SOCKS_PORT)` then turned into a hard failure.
        val wantedPort = effectiveSocksPort(profile)
        if (!PortProbe.awaitClosed(SOCKS_HOST, wantedPort, PORT_RELEASE_WAIT_MS)) {
            DiagnosticsLog.w(
                TAG,
                "Local port $wantedPort is still busy after ${PORT_RELEASE_WAIT_MS / 1000}s - starting anyway.",
            )
        }

        AetherController.setState(ConnectionState.Connecting)
        updateNotification(getString(R.string.state_connecting))
        // The transport reports the port it ACTUALLY bound. Following it instead
        // of demanding one is what makes a chained session possible at all, and
        // it turns a port clash from a failed connection into a logged warning.
        val port = transport.start()
        if (port != wantedPort) {
            DiagnosticsLog.w(TAG, "${profile.backend.pipelineLabel} exposed SOCKS5 on $port (expected $wantedPort).")
        }

        if (profile.proxyMode) {
            check(ShareBridge.startSync(localOnly = !profile.lanShare, upstreamPort = port)) {
                getString(R.string.err_proxy_ports)
            }
        } else {
            establishTun(profile)
            startTun2Socks(profile, port)
            if (profile.lanShare) ShareBridge.start(localOnly = false, upstreamPort = port)
        }

        AetherController.setState(ConnectionState.Verifying)
        updateNotification(getString(R.string.state_verifying))
        val diagPort = if (profile.proxyMode) ShareBridge.socksPort.value ?: port else port
        val healthy = runCatching {
            Diagnostics.run(port = diagPort, graceMs = Diagnostics.EXTERNAL_GRACE_MS)
        }.getOrDefault(false)
        check(healthy) { getString(R.string.err_selftest) }

        EngineMeta.setProtocol(
            "${stageProfile.protocol.name} \u2192 ${profile.backend.externalKind?.name ?: ""}",
        )
        // The latency badge must measure the port the WHOLE pipeline exposes. In a
        // chained session that is the front (stage 2's exit), not the engine's own
        // listener - probing 1819 there measured stage 1 alone and reported a
        // number that had nothing to do with what the user was browsing through.
        PingMonitor.setTunnelPort(port)
        // The warm probe session belongs to the OLD pipeline; a retarget or a
        // server rotation invalidates it. Dropping it here costs one dial and
        // stops the badge reporting a dead connection's timeout as latency.
        PingMonitor.reset()
        AetherController.setState(ConnectionState.Connected("$SOCKS_HOST:$port"))
        updateNotification(getString(R.string.state_connected))
        DiagnosticsLog.i(
            TAG,
            "${profile.backend.pipelineLabel} tunnel ready, exit=${profile.exitRegion.ifBlank { "automatic" }}",
        )

        // Supervise BOTH hops. A chained session is only as alive as its weakest
        // stage, and a dead stage 1 would leave stage 2 holding a proxy that
        // cannot dial -- "connected" with nothing moving.
        //
        // 1.2.7 STABILITY: the transport is required to read dead on
        // TRANSPORT_DEAD_CONFIRMATIONS CONSECUTIVE checks before the session is
        // torn down. A single false reading used to be enough, and the external
        // stage briefly reports not-alive on purpose while it moves itself onto a
        // different exit server -- so the app tore a perfectly recoverable
        // session down mid-rotation and the user saw a sudden disconnect.
        // PsiphonTransport already masks its own rotation window; this is the
        // second, dumber net underneath it, and it costs one extra second of
        // patience in the case that really is dead.
        // 1.2.8 MEDIA-STALL FIX: liveness is not health. Everything above only
        // ever asked "is the Psiphon library still running?", and it always was
        // -- the stall the user reported (fine for a minute, then a video pins
        // the ping at ~2000 ms and the session carries nothing at all, while
        // both stages and every port stay up) is invisible to that question. So
        // a chained session now ALSO gets the end-to-end probe, aimed at the
        // pipeline's own listener, which is the exact path the user's traffic
        // takes. Two consecutive dead readings rebuild the session instead of
        // leaving the user to toggle the switch by hand.
        var transportDead = 0
        var pipelineDead = 0
        var nextPipelineProbe = SystemClock.elapsedRealtime() + PIPELINE_PROBE_INTERVAL_MS
        while (currentScopeActive() && engine?.isAlive() == true) {
            if (transport.isAlive()) {
                transportDead = 0
            } else if (++transportDead >= TRANSPORT_DEAD_CONFIRMATIONS) {
                break
            } else {
                DiagnosticsLog.i(
                    TAG,
                    "${profile.backend.pipelineLabel} stage 2 is not answering " +
                        "($transportDead/$TRANSPORT_DEAD_CONFIRMATIONS) - giving it a moment " +
                        "before rebuilding the session.",
                )
            }

            if (SystemClock.elapsedRealtime() >= nextPipelineProbe &&
                PsiphonHealth.isSettling()
            ) {
                // A deliberate exit rotation is in flight: nothing can be dialled
                // through it for a few seconds BY DESIGN. Probing it now measures
                // the repair, not the fault. See PsiphonHealth.isSettling().
                if (pipelineDead > 0) pipelineDead = 0
                val settleFor = (PsiphonHealth.settlingUntil() - System.currentTimeMillis())
                    .coerceIn(0L, PIPELINE_PROBE_INTERVAL_MS)
                nextPipelineProbe =
                    SystemClock.elapsedRealtime() + settleFor + PIPELINE_PROBE_RETRY_MS
            } else if (SystemClock.elapsedRealtime() >= nextPipelineProbe) {
                // Sampled on EVERY probe decision so the delta always covers the
                // interval since the last one, success or failure.
                val dataPathMoving = dataPathIsMoving()
                if (probeTunnelCycle(port)) {
                    if (pipelineDead > 0) {
                        DiagnosticsLog.i(
                            TAG,
                            "${profile.backend.pipelineLabel} is carrying traffic again.",
                        )
                    }
                    pipelineDead = 0
                    nextPipelineProbe = SystemClock.elapsedRealtime() + PIPELINE_PROBE_INTERVAL_MS
                } else if (dataPathMoving) {
                    // 1.2.8-r3: A PROBE IS AN OPINION, THE METER IS A FACT.
                    //
                    // The r2 log shows the old code tearing down a session that
                    // was moving hundreds of kilobytes (Psiphon's own
                    // TotalBytesTransferred: received 703150, sent 385706)
                    // because one probe destination timed out. A path carrying
                    // traffic is not wedged, by definition, so the verdict is
                    // now gated on the tunnel core's byte counters and a probe
                    // failure alone can never rebuild anything.
                    if (pipelineDead > 0) pipelineDead = 0
                    DiagnosticsLog.i(
                        TAG,
                        "${profile.backend.pipelineLabel} probe did not answer but the data " +
                            "path is still carrying traffic - not a wedge, leaving the session " +
                            "alone.",
                    )
                    nextPipelineProbe = SystemClock.elapsedRealtime() + PIPELINE_PROBE_INTERVAL_MS
                } else {
                    pipelineDead++
                    DiagnosticsLog.w(
                        TAG,
                        "${profile.backend.pipelineLabel} is up but carries nothing " +
                            "($pipelineDead/$PIPELINE_DEAD_CONFIRMATIONS).",
                    )
                    if (pipelineDead >= PIPELINE_DEAD_CONFIRMATIONS) {
                        DiagnosticsLog.w(TAG, "Rebuilding the session: the data path is wedged.")
                        break
                    }
                    // Re-check sooner while it looks broken.
                    nextPipelineProbe =
                        SystemClock.elapsedRealtime() + PIPELINE_PROBE_RETRY_MS
                }
            }

            delay(1_000L)
        }
        if (currentScopeActive()) {
            if (engine?.isAlive() != true) {
                throw IllegalStateException(getString(R.string.err_engine_died))
            }
            throw IllegalStateException("${profile.backend.pipelineLabel} transport stopped")
        }
    }

    /**
     * Chained stage 1: run the Aether engine until its local SOCKS5 port is
     * genuinely carrying TCP, and stop there.
     *
     * Reuses the existing Smart Auto / hand-picked ladders unchanged, so a
     * chained session gets exactly the same DPI fingerprinting, protocol
     * hardening and retry behaviour as a plain Aether one. Returns the strategy
     * that won.
     */
    private suspend fun connectAetherStage(profile: ConnectionProfile): ConnectionProfile {
        DiagnosticsLog.i(
            TAG,
            "Chained mode: stage 1 = Aether engine on $SOCKS_HOST:$SOCKS_PORT, " +
                "stage 2 = ${profile.backend.externalKind?.name} behind its UDP-capable " +
                "front on $SOCKS_HOST:$CHAIN_SOCKS_PORT",
        )
        // Stage 1 owns neither the TUN nor the share bridge: those belong to the
        // finished chain, and letting stage 1 build them would capture the
        // engine's own traffic and deadlock the tunnel inside itself.
        val stage = profile.copy(
            backend = TransportBackend.AETHER,
            proxyMode = false,
            lanShare = false,
            // A chained session pays this hop's latency on every packet and then
            // again inside Psiphon's own hop, so the cached-endpoint budget is
            // tighter here than for a plain Aether session. See
            // ConnectionProfile.chainedStage.
            chainedStage = true,
        )
        val plan = if (stage.protocol == Protocol.AUTO) {
            AetherController.setState(ConnectionState.Launching)
            updateNotification(getString(R.string.state_analyzing))
            SmartAuto.buildPlan(stage, SmartAuto.fingerprint(this))
        } else {
            directPlan(stage)
        }
        val resolved = runLadder(plan, getString(R.string.err_protocol_failed), stageOnly = true)
        DiagnosticsLog.i(TAG, "Stage 1 up (${resolved.protocol.name}) - handing the exit to stage 2.")
        return resolved
    }

    /**
     * SOCKS5 port the finished pipeline exposes for [profile].
     *
     * For an external backend that is always the second stage's FRONT, because
     * every external backend is chained now and the front is the listener that
     * speaks UDP.
     */
    private fun effectiveSocksPort(profile: ConnectionProfile): Int =
        if (profile.backend.usesExternal) CHAIN_SOCKS_PORT else SOCKS_PORT

    /**
     * SMART AUTO (root-cause rework of the broken Auto protocol): fingerprint
     * the network's DPI first (see [SmartAuto]), then walk an ordered ladder
     * of concrete strategies — protocol + obfuscation + the IP ranges that
     * actually answered on THIS network — until one passes the full 5-step
     * self-test. Returns the strategy that won so the supervisor restarts the
     * engine with the SAME working configuration.
     */
    private suspend fun connectSmartAuto(userProfile: ConnectionProfile): ConnectionProfile {
        AetherController.setState(ConnectionState.Launching)
        updateNotification(getString(R.string.state_analyzing))
        val fingerprint = SmartAuto.fingerprint(this)
        val plan = SmartAuto.buildPlan(userProfile, fingerprint)
        return runLadder(plan, getString(R.string.err_auto_failed))
    }

    /**
     * Two-pass plan for a protocol the user picked by hand (MASQUE, WireGuard
     * or Gool).
     *
     * 1.2.2 "MASQUE hangs forever" FIX: a hand-picked protocol used to get ONE
     * attempt with the full scan budget of the selected scan mode — up to 150 s
     * on Balanced and 300 s on Thorough — with no second chance. On a network
     * where QUIC/UDP is throttled that means the user stares at "Connecting"
     * for minutes and then just fails, while Smart mode (which walks a ladder
     * of shorter, hardened attempts) connects in seconds. So the chosen
     * protocol now gets:
     *   1. a first pass exactly as configured, on a capped budget, and
     *   2. if that fails, the SAME protocol again with anti-DPI hardening
     *      (obfuscation on, plus HTTP/2 + TLS fragmentation + ECH for MASQUE)
     *      on the full budget.
     * The protocol the user chose is never swapped for another one.
     */
    private fun directPlan(profile: ConnectionProfile): List<AutoCandidate> {
        val fullBudget = profile.connectTimeoutMs()
        val hardenedNoize = if (profile.noize == Noize.OFF) Noize.FIREWALL else profile.noize
        val masque = profile.protocol == Protocol.MASQUE
        val hardened = profile.copy(
            noize = hardenedNoize,
            masqueHttp2 = profile.masqueHttp2 || masque,
            fragment = profile.fragment || masque,
            ech = profile.ech || masque,
        )
        if (hardened == profile) {
            return listOf(
                AutoCandidate(profile, fullBudget, "${profile.protocol.name} · as configured"),
            )
        }
        return listOf(
            AutoCandidate(
                profile,
                fullBudget.coerceAtMost(FIRST_PASS_MAX_MS),
                "${profile.protocol.name} · as configured",
            ),
            AutoCandidate(
                hardened,
                fullBudget,
                "${profile.protocol.name} · noize=${hardenedNoize.name.lowercase()}" +
                    (if (masque) " · h2 · fragment · ech" else "") + " (anti-DPI pass)",
            ),
        )
    }

    /**
     * Walks a ladder of strategies until one comes up and passes the full
     * self-test. Each failed rung is torn down before the next is tried.
     */
    private suspend fun runLadder(
        plan: List<AutoCandidate>,
        failureMessage: String,
        /** Chained stage 1: bring the engine up as a proxy only, no TUN, no exit check. */
        stageOnly: Boolean = false,
    ): ConnectionProfile {
        var lastError: Exception? = null

        plan.forEachIndexed { index, candidate ->
            DiagnosticsLog.i(TAG, "Attempt ${index + 1}/${plan.size} → ${candidate.label}")
            try {
                connectAttempt(candidate.profile, candidate.timeoutMs, stageOnly)
                DiagnosticsLog.i(TAG, "Connected using ${candidate.label}")
                return candidate.profile
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                DiagnosticsLog.w(
                    TAG,
                    "${candidate.label} failed (${e.message}) — moving to the next strategy.",
                )
                cleanupNativeOnly()
                Diagnostics.resetChecks()
            }
        }

        throw IllegalStateException(failureMessage, lastError)
    }

    /**
     * One full connect attempt with a CONCRETE protocol: launch engine, wait
     * for SOCKS5, bring up TUN/proxy, and gate on the 5-step self-test.
     * Throws on any failure; the caller decides whether to retry differently.
     */
    private suspend fun connectAttempt(
        profile: ConnectionProfile,
        timeoutMs: Long,
        stageOnly: Boolean = false,
    ) {
        AetherController.setState(ConnectionState.Launching)
        updateNotification(getString(R.string.state_launching))
        // 1.2.2 PROTOCOL-SWITCH FIX: never start an engine on top of a dying
        // one. Tear the previous natives down and wait for the local SOCKS5
        // port to be released first, otherwise the probe below can "see" the
        // old listener and the whole attempt is verified against a socket that
        // is about to disappear.
        // Leaving lockdown (if any): the blackhole TUN is torn down here.
        lockdownTunActive = false
        cleanupNativeOnly()
        if (!PortProbe.awaitClosed(SOCKS_HOST, SOCKS_PORT, PORT_RELEASE_WAIT_MS)) {
            DiagnosticsLog.w(
                TAG,
                "Local port $SOCKS_PORT is still busy after ${PORT_RELEASE_WAIT_MS / 1000}s — starting anyway.",
            )
        }
        DiagnosticsLog.i(TAG, "Launching engine (libaether.so)…")
        engine = AetherProcess(applicationInfo.nativeLibraryDir, filesDir).also { it.start(profile) }

        AetherController.setState(ConnectionState.Connecting)
        updateNotification(getString(R.string.state_connecting))
        // Timeout comes from the caller: the profile's scan-mode budget for a
        // direct connect, or the per-candidate budget in the Smart Auto ladder.
        DiagnosticsLog.i(
            TAG,
            "Waiting for SOCKS5 on $SOCKS_HOST:$SOCKS_PORT… (scan=${profile.scanMode}, timeout=${timeoutMs / 1000}s)",
        )
        val opened = PortProbe.awaitOpen(SOCKS_HOST, SOCKS_PORT, timeoutMs) { engine?.isAlive() == true }
        if (!opened) {
            val engineDied = engine?.isAlive() != true
            if (engineDied) {
                DiagnosticsLog.e(TAG, "Engine exited before it opened the SOCKS5 port.")
                throw IllegalStateException(getString(R.string.err_engine_died))
            }
            DiagnosticsLog.e(TAG, "Engine still scanning after ${timeoutMs / 1000}s — SOCKS5 port never opened.")
            throw IllegalStateException(getString(R.string.err_engine_timeout))
        }
        DiagnosticsLog.i(TAG, "SOCKS5 port is up.")

        if (stageOnly) {
            // CHAINED STAGE 1. An open port is not a working proxy: the engine's
            // inner tunnel can still be building, and handing a half-ready proxy
            // to Psiphon makes stage 2 fail for a reason that looks like
            // stage 2's fault. Gate on real outbound TCP, then stop -- no TUN,
            // no DNS/geo lookup, because the exit belongs to stage 2.
            AetherController.setState(ConnectionState.Verifying)
            updateNotification(getString(R.string.state_verifying))
            val stageOk = runCatching {
                Diagnostics.runProxyStage(SOCKS_HOST, SOCKS_PORT)
            }.getOrDefault(false)
            if (!stageOk) {
                DiagnosticsLog.e(TAG, "Stage 1 cannot open outbound connections - trying the next strategy.")
                throw IllegalStateException(getString(R.string.err_selftest))
            }
            return
        }

        if (profile.proxyMode) {
            // Proxy mode: DON'T capture the whole device through a system TUN.
            // Instead expose the engine's SOCKS5 + an HTTP proxy so individual
            // apps (or the Wi-Fi proxy setting) can opt in. This is ideal when
            // only one app (e.g. Telegram) needs the tunnel. LAN exposure only
            // happens when the user explicitly turned sharing on.
            //
            // startSync is ground truth: in proxy mode these listeners ARE the
            // product, so a bind failure must fail the connection loudly
            // instead of claiming "Local proxy ready" over dead ports (the old
            // fire-and-forget start swallowed EADDRINUSE and still reported
            // 1080/8118 as ready — external apps then couldn't connect).
            val shareReady =
                ShareBridge.startSync(localOnly = !profile.lanShare, upstreamPort = SOCKS_PORT)
            if (!shareReady) {
                DiagnosticsLog.e(TAG, "Proxy mode: the fixed local proxy ports could not be opened (see errors above).")
                throw IllegalStateException(getString(R.string.err_proxy_ports))
            }
            // Ports are FIXED (v2rayNG-style standard) — the same values are
            // shown as copyable rows under the Proxy-mode toggle in the UI.
            DiagnosticsLog.i(
                TAG,
                "Proxy mode: system TUN skipped. Local proxy ready — " +
                    "SOCKS5 127.0.0.1:${ShareBridge.SOCKS_SHARE_PORT}, HTTP 127.0.0.1:${ShareBridge.HTTP_SHARE_PORT}",
            )
        } else {
            establishTun(profile)
            startTun2Socks(profile, SOCKS_PORT)
            // LAN sharing: if the user enabled it, expose the tunnel to other
            // devices on the same Wi-Fi/hotspot (HTTP + SOCKS5 bridge).
            if (profile.lanShare) ShareBridge.start(localOnly = false, upstreamPort = SOCKS_PORT)
        }

        // GATING FIX: the app used to report Connected the moment the TUN /
        // proxy was up while the 5-step self-test still ran in the background —
        // users saw "Connected" long before the tunnel could actually carry
        // traffic (and before the IP + flag appeared). The state is now held at
        // Verifying, and Connected is reported ONLY after all four checks pass,
        // so Connected == genuinely ready to browse.
        AetherController.setState(ConnectionState.Verifying)
        updateNotification(getString(R.string.state_verifying))
        DiagnosticsLog.i(
            TAG,
            if (profile.proxyMode) "Proxy started. Verifying end-to-end connectivity…"
            else "TUN + hev tunnel started. Verifying end-to-end connectivity…",
        )

        // In proxy mode, test THROUGH the shared SOCKS5 listener — the exact
        // endpoint external apps connect to — so a dead bridge can no longer
        // hide behind a passing engine-port (1819) self-test.
        val diagPort =
            if (profile.proxyMode) ShareBridge.socksPort.value ?: SOCKS_PORT
            else SOCKS_PORT
        val healthy = runCatching { Diagnostics.run(port = diagPort) }.getOrDefault(false)
        if (!healthy) {
            DiagnosticsLog.e(TAG, "Self-test failed — refusing to report Connected.")
            throw IllegalStateException(getString(R.string.err_selftest))
        }

        // Informational only: report where the tunnel actually came out.
        // WARP edges are anycast, so the exit location is decided by the
        // engine's endpoint selection and the operator's routing, not by the
        // app. Nothing here can reject or override that choice.
        val exit = AetherController.ipInfo.value?.takeIf { it.viaTunnel }
        if (exit != null) {
            DiagnosticsLog.i(
                TAG,
                "Exit verified through the tunnel: ${exit.ip} (${exit.countryCode ?: "??"})",
            )
        }
    }

    /** Keeps the engine alive; retries with backoff if it dies. */
    private suspend fun superviseEngine(profile: ConnectionProfile) {
        var attempt = 0
        while (currentScopeActive()) {
            if (engine?.isAlive() == true) {
                attempt = 0
                // 1.2.2 CPU FIX: the supervisor used to wake up every 2 s for
                // the ENTIRE lifetime of the tunnel just to ask "is the engine
                // still alive?" — 1,800 wake-ups per hour of a healthy,
                // otherwise idle connection, each one preventing the CPU from
                // settling into a deep idle state and quietly draining the
                // battery. Instead we now BLOCK on the process itself: the OS
                // wakes us the instant the engine exits and never before, so a
                // healthy tunnel costs exactly zero polling.
                engine?.awaitExit(WATCHDOG_INTERVAL_MS)
                // STABILITY WATCHDOG (1.2.4, hardened): the engine process can
                // stay alive while its session silently dies -- the classic
                // "connected, but after a minute or two no site opens"
                // symptom. Probe end-to-end THROUGH the local SOCKS5 port and
                // restart the engine only on SUSTAINED failure; see
                // probeTunnelCycle() for why the bar is deliberately high.
                if (engine?.isAlive() == true) {
                    val dataPathMoving = dataPathIsMoving()
                    if (probeTunnelCycle()) {
                        probeFailures = 0
                    } else if (dataPathMoving) {
                        // Same rule as the chained watchdog: see the comment
                        // there. A tunnel that is moving bytes is not dead, and
                        // restarting the engine under a live stream is exactly
                        // the "it disconnects and comes back over and over"
                        // symptom this watchdog was blamed for.
                        probeFailures = 0
                        DiagnosticsLog.i(
                            TAG,
                            "Watchdog: probe did not answer but the tunnel is still carrying " +
                                "traffic - leaving the engine alone.",
                        )
                    } else if (++probeFailures >= WATCHDOG_FAIL_CYCLES) {
                        DiagnosticsLog.w(
                            TAG,
                            "Watchdog: tunnel dead across $WATCHDOG_FAIL_CYCLES consecutive checks -- restarting the engine.",
                        )
                        probeFailures = 0
                        engine?.stop()
                    }
                }
                continue
            }

            if (attempt >= maxRetries(profile)) {
                // KILL SWITCH (1.2.4): instead of tearing the VPN down and
                // leaking direct, engage the blackhole lockdown.
                if (profile.killSwitch || profile.strictKillSwitch) {
                    enterLockdown(profile)
                    return
                }
                throw IllegalStateException(getString(R.string.err_engine_died))
            }
            val backoff = BACKOFF[attempt.coerceAtMost(BACKOFF.size - 1)]
            attempt++
            AetherController.setState(ConnectionState.Reconnecting(attempt, maxRetries(profile)))
            updateNotification(getString(R.string.state_reconnecting))
            delay(backoff)

            engine = AetherProcess(applicationInfo.nativeLibraryDir, filesDir).also { it.start(profile) }
            if (PortProbe.awaitOpen(SOCKS_HOST, SOCKS_PORT, profile.connectTimeoutMs()) { engine?.isAlive() == true }) {
                // Same gate as the initial connect: never claim Connected after
                // a silent engine restart until traffic really flows again.
                AetherController.setState(ConnectionState.Verifying)
                updateNotification(getString(R.string.state_verifying))
                if (runCatching { Diagnostics.run() }.getOrDefault(false)) {
                    attempt = 0
                    AetherController.setState(ConnectionState.Connected("$SOCKS_HOST:$SOCKS_PORT"))
                    updateNotification(getString(R.string.state_connected))
                } else {
                    DiagnosticsLog.w(TAG, "Self-test failed after engine restart — retrying.")
                    engine?.stop()
                }
            }
        }
    }

    private fun currentScopeActive(): Boolean = runJob?.isActive ?: false

    private fun establishTun(profile: ConnectionProfile) {
        // 1.2.7-r3: a chained session's exit is a Psiphon server, and those are
        // IPv4-only in practice. See [buildTun] for why the TUN then carries no
        // IPv6 ADDRESS while still routing ::/0.
        var withoutIpv6Address = profile.backend.isChained
        var descriptor = runCatching { buildTun(profile, withoutIpv6Address).establish() }
            .getOrNull()
        if (descriptor == null && withoutIpv6Address) {
            // Some ROMs refuse an interface that routes a family it has no
            // address for. Falling back keeps the mode working exactly as it did
            // before; the front's AAAA suppression then carries the fix alone.
            DiagnosticsLog.w(
                TAG,
                "This device would not establish an IPv4-only-addressed TUN; re-establishing " +
                    "with the IPv6 address. AAAA suppression in the Psiphon front still applies.",
            )
            withoutIpv6Address = false
            descriptor = runCatching { buildTun(profile, false).establish() }.getOrNull()
        }
        tun = descriptor ?: throw IllegalStateException("Failed to establish the VPN interface")

        val mtu = profile.mtu.coerceIn(576, 9000)
        DiagnosticsLog.i(
            TAG,
            "TUN established: ipv4=${TunnelConfig.TUN_IPV4}/${TunnelConfig.TUN_IPV4_PREFIX} " +
                "ipv6=" + (
                if (withoutIpv6Address) {
                    "none (::/0 routed, so nothing leaks and the resolver stops returning AAAA)"
                } else {
                    "${TunnelConfig.TUN_IPV6}/${TunnelConfig.TUN_IPV6_PREFIX}"
                }
                ) + " mtu=$mtu split=${profile.splitMode} apps=${profile.splitApps.size} " +
                "dns=${TunnelConfig.DNS_SERVERS}",
        )
    }

    /**
     * Builds the TUN interface.
     *
     * ## Why [withoutIpv6Address] exists (1.2.7-r3 root-cause fix)
     *
     * The TUN used to carry an IPv6 address AND a `::/0` route unconditionally.
     * The route is right - it is what stops IPv6 from leaking past the tunnel with
     * the phone's real address, which is what produces a sanctions page instead of
     * the exit's answer. The ADDRESS is what causes the damage: it is the single
     * thing that tells Android's resolver "this network has IPv6", and from that
     * moment `getaddrinfo` returns AAAA records to every app on the device and
     * Happy Eyeballs prefers them. A chained session's exit is a Psiphon server,
     * and those cannot dial IPv6, so every one of those preferences is a
     * connection attempt that has to fail before the app tries IPv4 - and the apps
     * in the report (Gemini, ChatGPT, CapCut, TikTok) do not retry, they say "no
     * internet". The field log counted 65 of those refusals in two minutes.
     *
     * Dropping just the address is exactly the right lever, because Android's own
     * resolver already does the work: `netd` decides whether to hand out AAAA by
     * testing whether the network has a usable IPv6 SOURCE address. With the route
     * present but no address, that test fails, AAAA is filtered per-network by the
     * platform, every app settles on IPv4 by itself - and `::/0` still swallows any
     * IPv6 an app produces on its own, so there is still no leak.
     *
     * Plain Aether keeps its IPv6 address: WARP has real, working IPv6 and there is
     * nothing to protect against.
     */
    private fun buildTun(profile: ConnectionProfile, withoutIpv6Address: Boolean): Builder {
        // User-tunable MTU (defaults to 1280 -- safe for Iranian mobile/DPI).
        // Clamped to a sane range so a bad saved value can't break establish().
        val mtu = profile.mtu.coerceIn(576, 9000)
        val builder = Builder()
            .setSession("Aether")
            .setMtu(mtu)
            // The TUN address MUST match hev's tunnel.ipv4/ipv6 (see writeHevConfig).
            .addAddress(TunnelConfig.TUN_IPV4, TunnelConfig.TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)

        if (!withoutIpv6Address) {
            builder.addAddress(TunnelConfig.TUN_IPV6, TunnelConfig.TUN_IPV6_PREFIX)
        }

        // IPv6 LEAK PROTECTION (1.2.4): on by default -- the v6 default
        // route keeps IPv6 traffic inside the tunnel. Can be disabled for
        // networks where a default v6 route breaks connectivity.
        //
        // 1.2.7-r3 ROOT-CAUSE FIX (the "it shows a sanctions error" report): in a
        // CHAINED session that switch is not honoured. Without a ::/0 route the
        // phone's real IPv6 address is used directly for every AAAA destination,
        // so Gemini, ChatGPT and every sanctioned service sees the user's actual
        // address and answers with a country block - while the app still says
        // Connected, and while the very same session works perfectly from a
        // USB-tethered laptop, which has no IPv6 at all. That is not "a network
        // where v6 breaks connectivity", it is a leak with a user-visible
        // consequence, so here the route is unconditional.
        //
        // Nothing is lost by forcing it: with no IPv6 address on the interface the
        // device does not ask for AAAA in the first place, and the front answers
        // whatever IPv6 an app produces anyway in microseconds.
        val forceIpv6Route = profile.backend.isChained
        if (profile.ipv6LeakProtection || forceIpv6Route) {
            builder.addRoute("::", 0)
            if (forceIpv6Route && !profile.ipv6LeakProtection) {
                DiagnosticsLog.w(
                    TAG,
                    "IPv6 leak protection is off in the profile, but a chained session routes " +
                        "::/0 anyway: an uncaptured IPv6 flow would leave with the phone's real " +
                        "address and be answered with a sanctions block, not by the exit.",
                )
            }
        }

        // KILL SWITCH (1.2.4): a blocking interface never falls back to
        // direct traffic while the tunnel is not forwarding.
        if (profile.killSwitch || profile.strictKillSwitch) {
            builder.setBlocking(true)
        }

        TunnelConfig.DNS_SERVERS.forEach { builder.addDnsServer(it) }

        // Split tunneling + loop prevention (keeps the engine's own traffic off
        // the TUN, equivalent to v2rayNG's in-process protect()).
        applyAppFilter(builder, profile)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        return builder
    }

    /**
     * Applies the split-tunnel policy and always keeps the app's own engine
     * traffic off the TUN (loop prevention).
     *
     * - OFF     : everything routes through the VPN except our own package.
     * - INCLUDE : ONLY the chosen apps route through the VPN. Our own package is
     *             implicitly excluded because it is never added to the allow-list.
     * - EXCLUDE : everything routes through the VPN except the chosen apps + us.
     */
    private fun applyAppFilter(builder: Builder, profile: ConnectionProfile) {
        val apps = profile.splitApps.filter { it.isNotBlank() && it != packageName }
        when (profile.splitMode) {
            SplitMode.INCLUDE -> {
                if (apps.isEmpty()) {
                    // Nothing selected -> fall back to OFF so we don't build a
                    // tunnel that carries no traffic at all.
                    safeDisallow(builder, packageName)
                    return
                }
                apps.forEach { safeAllow(builder, it) }
            }
            SplitMode.EXCLUDE -> {
                safeDisallow(builder, packageName)
                // Blocked apps must stay INSIDE the TUN so the filter bridge
                // can drop their traffic; excluding them would give them
                // direct internet instead of none.
                apps.filter { it !in profile.blockedApps }.forEach { safeDisallow(builder, it) }
            }
            SplitMode.OFF -> safeDisallow(builder, packageName)
        }
    }

    private fun safeAllow(builder: Builder, pkg: String) {
        try {
            builder.addAllowedApplication(pkg)
        } catch (_: Exception) {
            DiagnosticsLog.w(TAG, "addAllowedApplication failed for $pkg (not installed?)")
        }
    }

    private fun safeDisallow(builder: Builder, pkg: String) {
        try {
            builder.addDisallowedApplication(pkg)
        } catch (_: Exception) {
            if (pkg != packageName) DiagnosticsLog.w(TAG, "addDisallowedApplication failed for $pkg")
        }
    }

    /**
     * Points the forwarder at [socksPort].
     *
     * The port is a parameter rather than the [SOCKS_PORT] constant because a
     * chained session's exit lives on [CHAIN_SOCKS_PORT]; hardcoding 1819 here
     * would silently forward the whole device through stage 1 and hand the user
     * an Aether exit IP while the UI promised a Psiphon exit.
     */
    private fun startTun2Socks(profile: ConnectionProfile, socksPort: Int) {
        if (profile.blockedApps.isNotEmpty()) {
            // PER-APP BLOCKING (1.2.4): hev-socks5-tunnel cannot filter per
            // UID, so a userspace filter bridge (merged into Aether's
            // SocksTunBridge) reads the TUN itself, resolves each flow's
            // owning app and drops blocked apps' packets. It is activated
            // ONLY when blocking is configured; the battle-tested hev path
            // below stays the default for everyone else.
            val pfd = tun ?: throw IllegalStateException("TUN descriptor is null")
            val bridge = SocksTunBridge(
                vpnService = this,
                tunDescriptor = pfd,
                socksHost = SOCKS_HOST,
                socksPort = socksPort,
                mtu = profile.mtu.coerceIn(576, 9000),
                blockedPackagesProvider = { profile.blockedApps.toSet() },
                routingEngine = RoutingEngine(emptyList()),
            )
            DiagnosticsLog.i(TAG, "Starting userspace filter bridge (blocked apps=${profile.blockedApps.size})")
            bridge.start()
            tunBridge = bridge
            return
        }
        val config = writeHevConfig(profile.mtu.coerceIn(576, 9000), socksPort)
        // Use the LIVE fd of the ParcelFileDescriptor (do NOT detach): hev uses it
        // while running and we close the pfd ourselves on teardown. The fd is only
        // valid inside THIS process, which is exactly why hev must run in-process.
        val fd = tun?.fd ?: throw IllegalStateException("TUN descriptor is null")
        DiagnosticsLog.i(TAG, "Starting hev-socks5-tunnel in-process (fd=$fd)")
        HevTunnel.start(config.absolutePath, fd)
        tunnelStarted = true
    }

    /**
     * Writes the hev-socks5-tunnel config in the exact shape v2rayNG uses.
     *
     * The critical difference from the previous (broken) version is the
     * `tunnel.ipv4` / `tunnel.ipv6` fields. hev configures its internal lwIP
     * netif from these; without them packets are pulled off the TUN fd but have
     * nowhere to be routed, so the tunnel "connects" but no site ever loads.
     * These MUST equal the VpnService addAddress values.
     */
    private fun writeHevConfig(mtu: Int, socksPort: Int): File {
        val file = File(filesDir, "hev.yaml")
        val yaml = """
            tunnel:
              mtu: $mtu
              ipv4: ${TunnelConfig.TUN_IPV4}
              ipv6: '${TunnelConfig.TUN_IPV6}'
            socks5:
              address: $SOCKS_HOST
              port: $socksPort
              udp: 'udp'
            misc:
              task-stack-size: 86016
              # 1.2.7-r2 MEDIA FIX: 5 s was a direct-dial budget, and a chained
              # session pays stage 1's latency AND Psiphon's own channel dial on
              # every flow. Under the connection fan-out of a video player those
              # dials routinely need longer, and hev abandoning them mid-load is
              # itself a stall the user reads as "it went slow and stopped".
              connect-timeout: 12000
              # 1.2.4 stability: the old 60s idle timeout killed long-lived
              # sessions ("works 1-2 minutes, then no site opens").
              tcp-read-write-timeout: 300000
              # 1.2.7-r3: back to 120000. r2 lowered this to 60 s because QUIC was
              # not carried, so UDP/443 associations were all dead weight. Now that
              # UDP/443 rides the bulk lane (see PsiphonSocksFront.CARRY_QUIC) a
              # live QUIC connection can legitimately idle: a paused video, a
              # backgrounded app, an idle HTTP/3 pool. Reaping it at 60 s forces a
              # new association with a new source port, which the peer sees as a
              # different client and answers with a fresh handshake - the "effects
              # never load / feed stops mid-scroll" symptom.
              udp-read-write-timeout: 120000
              # 1.2.7-r2: a video player opens flows in bursts of dozens; the
              # default descriptor budget is what runs out first when it does.
              limit-nofile: 65535
              log-level: warn
        """.trimIndent()
        file.writeText(yaml)
        DiagnosticsLog.i(TAG, "hev.yaml written:\n$yaml")
        return file
    }

    private fun stopEverything() {
        AetherController.setState(ConnectionState.Disconnecting)
        updateNotification(getString(R.string.state_disconnecting))
        val job = runJob
        runJob = null
        // DISCONNECT MUST BE INSTANT. Order matters:
        //   1. cancel the session coroutine (does not wait for it),
        //   2. kill the natives right away — this is what actually makes the
        //      tunnel stop, and it also unblocks any wait the session
        //      coroutine is parked in,
        //   3. flip the UI to Idle and drop the foreground notification,
        //   4. only THEN join the finished coroutine, off the critical path.
        // The previous order (join → cleanup) made the button sit on
        // "Disconnecting…" for as long as the supervisor's engine wait had
        // left to run — up to a full minute.
        job?.cancel()
        stopJob = scope.launch(Dispatchers.IO) {
            cleanupNativeOnly()
            // STALE-CIRCLES FIX (part 2): clear the finished session's results
            // right at disconnect, so the panel never carries green circles
            // from a dead session into the next connect.
            Diagnostics.resetChecks()
            EngineMeta.reset()
            PingMonitor.resetTunnelPort()
            PingMonitor.reset()
            AetherController.setState(ConnectionState.Idle)
            AetherTileService.requestUpdate(this@AetherVpnService)
            stopForegroundCompat()
            stopSelf()
            job?.join()
        }
    }

    /** Max automatic engine restarts (Smart Reconnect, 1.2.4). */
    private fun maxRetries(profile: ConnectionProfile): Int =
        if (profile.smartReconnect) profile.reconnectRetryLimit.coerceIn(1, 50) else 50

    /**
     * WATCHDOG PROBE, hardened (1.2.4 periodic-outage root-cause fix).
     *
     * The old probe was a single TCP connect to 1.1.1.1:53 with a 5 s
     * timeout. On high-RTT, lossy links (the tunnel's own baseline RTT is
     * 350-550 ms and DPI throttling causes multi-second UDP stalls that heal
     * by themselves) that lone probe fails SPURIOUSLY -- two unlucky probes
     * 30 s apart were enough to kill a perfectly healthy engine and force a
     * full endpoint rescan, which is itself a 30-90 s total outage. The cure
     * had become the disease: the periodic "no site opens, then it works
     * again" the user saw every few minutes was the watchdog restarting a
     * tunnel that was only briefly stalled.
     *
     * A check now only counts as failed when THREE attempts in a row --
     * spread over three different anycast resolvers, 8 s timeout each, 1.5 s
     * apart -- all fail, and the engine is restarted only after THREE
     * consecutive failed checks (90 s+ of continuously proven dead tunnel).
     * Brief self-healing stalls no longer trigger restarts, a genuinely dead
     * session still recovers automatically, and MASQUE's in-engine reconnect
     * loop gets room to finish before the app steps in.
     */
    private suspend fun probeTunnelCycle(port: Int = SOCKS_PORT): Boolean {
        registerSelfProbes()
        repeat(PROBE_ATTEMPTS) { attempt ->
            if (probeTunnelOnce(PROBE_TARGETS[attempt % PROBE_TARGETS.size], port)) return true
            if (attempt < PROBE_ATTEMPTS - 1) delay(PROBE_RETRY_GAP_MS)
        }
        return false
    }

    /**
     * Tells [PsiphonHealth] which destinations belong to the app's own health
     * checks, so a refusal of one can never be read as "this exit filters".
     * See [PROBE_TARGETS].
     */
    private fun registerSelfProbes() {
        if (selfProbesRegistered) return
        selfProbesRegistered = true
        for (target in PROBE_TARGETS) {
            PsiphonHealth.registerSelfProbe(target.first, target.second)
        }
        PingMonitor.probeTargets().forEach { (host, probePort) ->
            PsiphonHealth.registerSelfProbe(host, probePort)
        }
    }

    /**
     * Cumulative bytes the device data path has carried, straight from the
     * tunnel core's own counters, or -1 when they are unavailable.
     *
     * This is the ONLY honest answer to "is the data path moving?", and it is
     * the same number in both a plain and a chained session because hev is
     * always the thing between the TUN and the first SOCKS hop. A probe to one
     * destination is an opinion; this is the meter.
     */
    private fun dataPathBytes(): Long {
        val traffic = HevTunnel.traffic() ?: return -1L
        return traffic.downloadBytes + traffic.uploadBytes
    }

    /**
     * One end-to-end probe of [target] ("host:port") through the local SOCKS5
     * listener on [port]: a real DNS query, and a real answer required back.
     *
     * ROOT CAUSE this fixes (1.2.8) - the whole reason "connected but nothing
     * loads" could last until the user toggled the switch by hand. This probe
     * used to be a bare `connect()`, and a SOCKS5 connect is answered by the
     * engine's ACCEPT path, which is a different code path from the one that
     * carries payload. A tunnel whose data plane was wedged still completed
     * every handshake perfectly, so the watchdog kept marking the session
     * healthy, forever, while not one byte moved. Requiring a full round trip
     * means the watchdog now measures exactly what the user experiences.
     */
    private fun probeTunnelOnce(
        target: Triple<String, Int, String>,
        port: Int = SOCKS_PORT,
    ): Boolean = runCatching {
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.SOCKS,
            java.net.InetSocketAddress(SOCKS_HOST, port),
        )
        java.net.Socket(proxy).use { socket ->
            socket.connect(
                java.net.InetSocketAddress(target.first, target.second),
                PROBE_TIMEOUT_MS,
            )
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.tcpNoDelay = true

            // A real TLS handshake on 443: ClientHello out, ServerHello +
            // certificate + Finished back. Bytes have to make the round trip in
            // both directions through the payload path for this to complete, and
            // 443 is the one port an exit cannot refuse without being useless.
            val factory = javax.net.ssl.SSLSocketFactory.getDefault()
                as javax.net.ssl.SSLSocketFactory
            val tls = factory.createSocket(socket, target.third, target.second, false)
                as javax.net.ssl.SSLSocket
            tls.soTimeout = PROBE_TIMEOUT_MS
            try {
                tls.startHandshake()
                tls.session.isValid
            } finally {
                runCatching { tls.close() }
            }
        }
    }.getOrDefault(false)

    /**
     * True when the device data path has carried real traffic since the previous
     * call, i.e. when a failed probe cannot possibly mean "wedged".
     *
     * Deliberately stateful and deliberately cheap: it reads the tunnel core's
     * cumulative counters and compares them with the last reading. Unavailable
     * counters return false, so a platform without them behaves exactly as
     * before instead of becoming un-restartable.
     */
    private fun dataPathIsMoving(): Boolean {
        val now = dataPathBytes()
        val previous = lastDataPathBytes
        lastDataPathBytes = now
        if (now < 0 || previous < 0) return false
        return now - previous >= DATA_PATH_ALIVE_BYTES
    }

    /**
     * KILL SWITCH lockdown (1.2.4): stop the engine and the forwarder but
     * KEEP a blocking full-tunnel TUN up, so every packet is blackholed
     * instead of leaking direct. The service stays foreground; connecting
     * again or disconnecting lifts the lockdown.
     */
    private fun enterLockdown(profile: ConnectionProfile) {
        val job = runJob
        runJob = null
        job?.cancel()
        stopJob = scope.launch(Dispatchers.IO) {
            cleanupForwardingOnly()
            ensureLockdownTun(profile)
            lockdownTunActive = true
            Diagnostics.resetChecks()
            EngineMeta.reset()
            AetherController.setState(ConnectionState.Error(getString(R.string.state_killswitch)))
            updateNotification(getString(R.string.state_killswitch))
            AetherTileService.requestUpdate(this@AetherVpnService)
            job?.join()
        }
    }

    /** Stops sharing, the forwarder and the engine but deliberately KEEPS [tun]. */
    private fun cleanupForwardingOnly() {
        try {
            ShareBridge.stop()
        } catch (_: Throwable) {
        }
        tunBridge?.let { runCatching { it.stop() } }
        tunBridge = null
        if (tunnelStarted) {
            try {
                HevTunnel.stop()
            } catch (_: Throwable) {
            }
            tunnelStarted = false
        }
        try {
            externalTransport?.stop()
        } catch (_: Throwable) {
        }
        externalTransport = null
        try {
            engine?.stop()
        } catch (_: Throwable) {
        }
        engine = null
    }

    /** (Re)builds the TUN as a full-tunnel blackhole: routes everything, reads nothing. */
    private fun ensureLockdownTun(profile: ConnectionProfile) {
        runCatching { tun?.close() }
        tun = null
        val builder = Builder()
            .setSession("Aether KillSwitch")
            .setMtu(profile.mtu.coerceIn(576, 9000))
            .addAddress(TunnelConfig.TUN_IPV4, TunnelConfig.TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
        if (profile.ipv6LeakProtection) {
            builder.addAddress(TunnelConfig.TUN_IPV6, TunnelConfig.TUN_IPV6_PREFIX)
            builder.addRoute("::", 0)
        }
        tun = runCatching { builder.establish() }.getOrNull()
    }

    private fun cleanupNativeOnly() {
        // Stop sharing first: without the tunnel the bridge would leak direct.
        try {
            ShareBridge.stop()
        } catch (_: Throwable) {
        }
        tunBridge?.let { runCatching { it.stop() } }
        tunBridge = null
        if (tunnelStarted) {
            try {
                HevTunnel.stop()
            } catch (_: Throwable) {
            }
            tunnelStarted = false
        }
        try {
            externalTransport?.stop()
        } catch (_: Throwable) {
        }
        externalTransport = null
        try {
            engine?.stop()
        } catch (_: Throwable) {
        }
        engine = null
        try {
            tun?.close()
        } catch (_: Throwable) {
        }
        tun = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onRevoke() {
        stopEverything()
        super.onRevoke()
    }

    override fun onDestroy() {
        runJob?.cancel()
        cleanupNativeOnly()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AetherVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AetherApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.state_disconnecting), disconnectIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
        // Keep the Quick Settings tile in sync with every state transition.
        AetherTileService.requestUpdate(this)
        // Keep the home-screen widget (feature merge) in sync too.
        // Cheap: returns immediately when no widget is placed.
        AetherWidgetProvider.updateAllWidgets(this)
    }

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aether.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aether.DISCONNECT"
        const val EXTRA_PROFILE = "profile"

        private const val NOTIF_ID = 0x4145
        private const val TAG = "vpn"
        private const val SOCKS_HOST = TunnelConfig.SOCKS_HOST
        private const val SOCKS_PORT = TunnelConfig.SOCKS_PORT

        /** Where a chained session's SECOND stage listens (see connectExternal). */
        private const val CHAIN_SOCKS_PORT = TunnelConfig.CHAIN_SOCKS_PORT
        private const val MTU = TunnelConfig.MTU
        private const val MAX_RETRIES = 3
        private val BACKOFF = longArrayOf(2000L, 5000L, 10000L)

        /**
         * Upper bound for one blocking wait on the engine process (1.2.2).
         * The supervisor no longer polls; it parks on the process itself and
         * only wakes up this often to re-check its own cancellation state.
         */
        private const val SUPERVISOR_WAIT_MS = 60_000L

        /**
         * Consecutive one-second checks the external stage must fail before the
         * chained session is rebuilt (1.2.7). See connectExternal().
         */
        const val TRANSPORT_DEAD_CONFIRMATIONS = 5

        /**
         * Watchdog probe cadence while the tunnel is up.
         *
         * 1.2.8: 30 s -> 15 s. The probe now proves that bytes actually make the
         * round trip (see probeTunnelOnce), so a failure means something, and
         * waiting half a minute between meaningful checks is time the user
         * spends staring at a tunnel that says Connected and does nothing.
         */
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        /**
         * Consecutive failed checks before the engine is restarted. 1.2.8: 3 ->
         * 2, for the same reason. Each check is already three round trips over
         * three different resolvers, so two failed checks is six independent
         * failures - plenty of evidence, and roughly half the dead air.
         */
        private const val WATCHDOG_FAIL_CYCLES = 2

        /**
         * Attempts per watchdog check, rotating over anycast endpoints so one
         * blocked or slow target can never fake a dead tunnel (1.2.4 fix).
         */
        private const val PROBE_ATTEMPTS = 3

        /**
         * Probe destinations as `ip`, `port`, `sni`.
         *
         * ## ROOT CAUSE this fixes (1.2.8-r3) - the self-inflicted reconnect loop
         *
         * These used to be `1.1.1.1:53`, `1.0.0.1:53`, `9.9.9.9:53`: DNS over
         * **TCP port 53**. A large share of Psiphon exits refuse TCP/53 outright,
         * and the app's own front says so in the log:
         *
         * ```text
         * PsiphonSocksFront this server does not allow outbound TCP/53
         * Psiphon: LocalProxyError ... ssh: rejected: administratively prohibited
         * ```
         *
         * So on those exits EVERY watchdog check failed, forever, on a tunnel
         * that was carrying traffic normally. The 1.2.8-r2 field log has both
         * verdicts one second apart, which is the proof:
         *
         * ```text
         * 09:33:36.081 diag: device dns via socks5 udp = true
         * 09:33:36.353 diag: dns+http OK, exit ip=146.59.70.6 cc=PL   <- real paths: fine
         * 09:33:36.288 ssh: rejected: administratively prohibited      <- probe: refused
         * ...
         * 09:34:39.090 ping: Latency probe failed (viaTunnel=true): Connect timed out
         * 09:34:59.391 PsiphonHealth ... refused 6 different destinations in 45s
         * 09:34:59.396 PsiphonSocksFront udpgw session closed
         * 09:35:03.298 Rebuilding the session: the data path is wedged
         * ```
         *
         * The probe failed, the failures also convicted the exit as a filtering
         * server, the rotation dropped the udpgw association (killing every
         * UDP/QUIC flow on the device) and then the pipeline watchdog tore the
         * whole session down. On a cadence. Which is precisely what the user
         * reported for *every* app and site: ping spikes, it drops, it comes
         * back, it repeats - and why nothing with a long-lived stream (a live
         * dubbing session, a video) ever survived a minute.
         *
         * Port 443 is the one port every exit must allow, and a TLS handshake to
         * it is a genuine payload round trip through the data plane, which is
         * what the probe was supposed to be measuring in the first place. Each
         * target is also registered with [PsiphonHealth] so it can never count
         * as evidence against a server again, whatever port it lands on.
         */
        private val PROBE_TARGETS = arrayOf(
            Triple("1.1.1.1", 443, "cloudflare-dns.com"),
            Triple("8.8.8.8", 443, "dns.google"),
            Triple("9.9.9.9", 443, "dns.quad9.net"),
        )
        private const val PROBE_TIMEOUT_MS = 8_000
        private const val PROBE_RETRY_GAP_MS = 1_000L

        /**
         * Bytes the device data path must have carried since the previous check
         * for a failed probe to be dismissed as "busy, not broken".
         *
         * A wedged tunnel moves nothing at all; anything above idle keepalive
         * noise means the path works and the probe is the thing that is wrong.
         */
        private const val DATA_PATH_ALIVE_BYTES = 16 * 1024L

        /** How often a CHAINED session's full pipeline is probed end to end (1.2.8). */
        private const val PIPELINE_PROBE_INTERVAL_MS = 15_000L

        /** And how soon it is re-probed once a check has failed. */
        private const val PIPELINE_PROBE_RETRY_MS = 5_000L

        /** Consecutive dead pipeline readings before the session is rebuilt. */
        private const val PIPELINE_DEAD_CONFIRMATIONS = 2

        /**
         * How long to wait for the previous engine to release the local SOCKS5
         * port before starting a new one (1.2.2 protocol-switch fix).
         */
        private const val PORT_RELEASE_WAIT_MS = 3_000L

        /**
         * Cap for the FIRST attempt of a hand-picked protocol, so a throttled
         * network cannot hold the user on "Connecting" for the whole scan
         * budget before the hardened second pass is even tried.
         */
        private const val FIRST_PASS_MAX_MS = 75_000L
    }
}
