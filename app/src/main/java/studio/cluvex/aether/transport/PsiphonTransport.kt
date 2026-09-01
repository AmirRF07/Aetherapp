package studio.cluvex.aether.transport

import android.content.Context
import android.net.VpnService
import ca.psiphon.PsiphonTunnel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.PortProbe
import studio.cluvex.aether.core.TunnelConfig
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns one Psiphon controller and exposes its local SOCKS5 listener.
 *
 * ## What was wrong before
 *
 * 1. **A pinned exit country was a dead end.** `EgressRegion` is a HARD filter
 *    inside psiphon-tunnel-core: if no server is currently reachable in that
 *    country the controller keeps hunting for an egress that will never appear,
 *    and the app sits on "Connecting" until the timeout with nothing in the log
 *    that explains why. [start] now retries with an automatic exit instead of
 *    failing, because a working tunnel in the wrong country beats no tunnel.
 * 2. **The caller demanded port 1819.** The service used to `check(port == 1819)`
 *    and abort the session if Psiphon had to bind elsewhere (a leftover listener
 *    in `TIME_WAIT`, or the chained mode where the Aether engine already owns
 *    1819). Psiphon reports its real port through
 *    [onListeningSocksProxyPort]; that is now the port the whole pipeline
 *    follows.
 * 3. **A wedged datastore was unrecoverable.** Psiphon's BoltDB datastore lived
 *    directly in `filesDir` alongside `hev.yaml`. It now
 *    has its own directory, and a failed first attempt wipes it before the
 *    retry — a corrupted or lock-held datastore is a classic cause of a Psiphon
 *    that never establishes and cannot say why.
 * 4. **tun2socks talked to Psiphon directly, and Psiphon refuses UDP.** The
 *    session connected, reported a healthy exit, moved 267 bytes and opened no
 *    site at all, because hev-socks5-tunnel carries UDP (and therefore all DNS)
 *    over SOCKS5 `UDP ASSOCIATE` while psiphon-tunnel-core's local proxy is
 *    CONNECT-only: `socks5ReadCommand: SOCKS message field command was 0x03,
 *    not 0x01`, 636 times in 50 seconds. Psiphon now binds
 *    [TunnelConfig.PSIPHON_SOCKS_PORT] and [PsiphonSocksFront] owns the port
 *    tun2socks talks to, carrying UDP over Psiphon's remote udpgw with a
 *    DNS-over-TCP fallback. [start] returns the FRONT's port for that reason.
 * 5. **The VPN interface kept killing the tunnel.** With `setVpnMode(false)`
 *    PsiphonTunnel's own network monitor treats our TUN coming up as a network
 *    change and terminates the active tunnel (`NetworkMonitor: set current
 *    active network VPN with DNS` -> `terminated tunnel` -> `tunnel failed`,
 *    seconds after connecting), and it adopts the TUN's advertised resolvers as
 *    its own — which point back through the tunnel it is trying to build. VPN
 *    mode is now declared, which is what it is for: PsiphonTunnel 2.x runs no
 *    tun2socks and no routing of its own, the flag only tells its network
 *    monitor to look past the VPN interface at the real network underneath.
 */
class PsiphonTransport(
    private val service: VpnService,
    private val region: String,
    /**
     * `socks5://host:port` Psiphon must dial out through, or null for a direct
     * dial. Set by the chained `Aether -> Psiphon` mode: psiphon-tunnel-core
     * honours this as `UpstreamProxyUrl` for every tunnel it builds, so the exit
     * IP is Psiphon's while the hop the local network sees is Aether's.
     */
    private val upstreamProxy: String? = null,
    /** Local SOCKS5 port Psiphon itself is asked to bind, BEHIND the front. */
    private val localSocksPort: Int = TunnelConfig.PSIPHON_SOCKS_PORT,
    /** Port [PsiphonSocksFront] binds for tun2socks; this is what [start] returns. */
    private val frontPort: Int = TunnelConfig.CHAIN_SOCKS_PORT,
) : ExternalTransport, PsiphonTunnel.HostService {

    private val connected = AtomicBoolean(false)

    /** Replaced per establish attempt, so a failed attempt cannot poison the retry. */
    @Volatile private var ready = CompletableDeferred<Int>()
    @Volatile private var socksPort = localSocksPort
    @Volatile private var tunnel: PsiphonTunnel? = null
    @Volatile private var config = "{}"

    /**
     * Deadline (epoch ms) until which an in-flight server rotation is allowed to
     * look ALIVE to the service's supervisor, or 0 when no rotation is running.
     *
     * ROOT CAUSE of the "it suddenly disconnects on its own" report. A rotation
     * to [PsiphonHealth.Rotation.RESTART] stops and restarts the Psiphon library
     * in place; stopping it fires [onExiting], which clears [connected]; and
     * `AetherVpnService` polls [isAlive] once a second and tears the whole
     * session down the moment it reads false. The log caught it exactly:
     * `Psiphon: Exiting: {}` at 08:06:38.535, `hev-socks5-tunnel stop requested`
     * 0.74 s later. The pipeline (TUN, tun2socks, the front, the share bridge) is
     * untouched by a rotation, so it must not be read as death - but only for a
     * BOUNDED time, because a restart that never comes back really is death and
     * the supervisor is the right thing to handle it.
     */
    @Volatile private var rotationDeadline = 0L

    /**
     * Set once any server has been convicted of filtering. Turns off
     * psiphon-tunnel-core's dial-parameter replay for the rest of the session,
     * because replay is one of the two mechanisms that kept putting the SAME
     * refusing server back at `candidateNumber: 0` (`isReplay: true` in the log).
     */
    @Volatile private var avoidReplay = false

    /** Egress regions the library says it can actually reach, for steering. */
    private val availableRegions = CopyOnWriteArrayList<String>()

    /** Round-robin cursor over [availableRegions], so steering never ping-pongs. */
    private val steerCursor = AtomicInteger(0)

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        val wanted = region.trim().uppercase()
        // Per-session steering state. A fresh connect starts from a clean slate:
        // yesterday's bad servers are not this session's problem.
        rotationDeadline = 0L
        avoidReplay = false
        availableRegions.clear()
        steerCursor.set(0)
        val entries = runCatching {
            service.assets.open("server_entries.txt").bufferedReader().use { it.readText().trim() }
        }.getOrElse {
            throw IllegalStateException("Psiphon server list is missing from the app assets")
        }
        check(entries.isNotEmpty()) { "Psiphon server list is empty" }

        // Pass 1 honours the user's country, pass 2 drops the filter and resets
        // the datastore. Nothing is retried when the user already chose
        // Automatic: there is no filter left to relax.
        val attempts = if (wanted.isEmpty()) listOf("") else listOf(wanted, "")
        var lastError: Exception? = null

        for ((index, egress) in attempts.withIndex()) {
            if (index > 0) {
                DiagnosticsLog.w(
                    TAG,
                    "Psiphon could not establish in ${ExitRegions.name(wanted)} " +
                        "(${lastError?.message ?: "timed out"}) - retrying with an automatic exit " +
                        "and a fresh datastore.",
                )
                stopTunnelQuietly()
                resetDataStore()
                PortProbe.awaitClosed("127.0.0.1", localSocksPort, PORT_RELEASE_WAIT_MS)
            }
            try {
                return@withContext establish(egress, entries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                stopTunnelQuietly()
            }
        }

        throw lastError ?: IllegalStateException("Psiphon did not establish a tunnel")
    }

    private suspend fun establish(egress: String, entries: String): Int {
        val deferred = CompletableDeferred<Int>()
        ready = deferred
        socksPort = localSocksPort
        config = buildConfig(egress)
        DiagnosticsLog.i(
            TAG,
            "Starting Psiphon (exit=${ExitRegions.name(egress)}, socks=$localSocksPort, " +
                "front=$frontPort, upstream=${upstreamProxy ?: "direct"})",
        )
        val created = PsiphonTunnel.newPsiphonTunnel(this)
        tunnel = created
        // Watch this controller for a server that is up but filtering (see
        // [PsiphonHealth]). Bound per controller, so the rotation budget is
        // per session and cannot be reset by Psiphon's own re-establishes.
        PsiphonHealth.bind { strategy, target -> rotateServer(strategy, target) }
        // TRUE, deliberately. PsiphonTunnel 2.x does not run tun2socks or touch
        // routing -- this flag only tells its NetworkMonitor that a VpnService is
        // ours, so it (a) computes the network id from the real network instead
        // of flagging our own TUN as a network change and terminating the tunnel
        // it just built, and (b) does NOT adopt the TUN's advertised resolvers,
        // which point back through the very tunnel it is establishing.
        created.setVpnMode(true)
        created.startTunneling(entries)
        // TimeoutCancellationException IS a CancellationException, so letting it
        // escape would hit the `catch (CancellationException) -> rethrow` guard in
        // [start] and skip the automatic-exit retry entirely -- the exact bug this
        // retry exists to fix. Translate it into a plain failure here.
        val boundPort = try {
            withTimeout(ESTABLISH_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException(
                "Psiphon found no usable server within ${ESTABLISH_TIMEOUT_MS / 1000}s",
            )
        }

        // The tunnel is up; now put the UDP-capable front in front of it. Started
        // HERE and not in the service because the front is meaningless without a
        // live Psiphon listener behind it, and because a bind failure has to fail
        // this establish attempt so the retry above can have another go.
        check(PsiphonSocksFront.start(frontPort, boundPort)) {
            "Psiphon SOCKS front could not bind 127.0.0.1:$frontPort"
        }
        return frontPort
    }

    /**
     * Psiphon config JSON.
     *
     * Unknown keys are ignored by the Go side, and every key here is one
     * psiphon-tunnel-core actually reads. `EgressRegion` is OMITTED rather than
     * sent as `""` when the user wants Automatic, which is the documented way to
     * express "no region filter".
     */
    private fun buildConfig(egress: String): String = JSONObject().apply {
        put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        put("SponsorId", "1111111111111111")
        if (egress.isNotEmpty()) put("EgressRegion", egress)
        put("EstablishTunnelTimeoutSeconds", ESTABLISH_TIMEOUT_SECONDS)
        put("DataDirectory", dataStoreDir().absolutePath)
        put("ClientVersion", "127")
        put("LocalSocksProxyPort", localSocksPort)
        // The HTTP proxy is dead weight here: tun2socks, the share bridge and
        // the self-test all speak SOCKS5, and one fewer listener is one fewer
        // port clash with the engine's own listener and the share bridge.
        put("DisableLocalHTTPProxy", true)
        put("RemoteServerListSignaturePublicKey", REMOTE_SERVER_LIST_PUBLIC_KEY)
        put("ServerEntrySignaturePublicKey", "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=")
        put("ExchangeObfuscationKey", "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU=")
        put("EmitBytesTransferred", true)
        put("EmitDiagnosticNotices", true)
        put("DeviceRegion", "IR")
        put("ConnectionWorkerPoolSize", 12)
        // ROTATION STEERING (see [rotateServer] and [PsiphonHealth]). Both keys
        // are omitted entirely until a server has actually been convicted of
        // filtering, so a normal session behaves exactly as before.
        if (avoidReplay) {
            // Replay re-dials the last server with its remembered dial
            // parameters and puts it at candidate 0 (`isReplay: true`). That is
            // half the reason a rotation kept landing back on the server it was
            // told to leave.
            put("DisableReplay", true)
            // The other half is server affinity: the previously connected server
            // gets an exclusive head start before the other workers are allowed
            // to race. Zero means no head start, so a healthy server can win.
            put("EstablishTunnelServerAffinityGracePeriodMilliseconds", 0)
        }
        put(
            "DNSResolverPreferredAlternateServers",
            JSONArray(listOf("1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53")),
        )
        put("DNSResolverPreferAlternateServerProbability", 1.0)
        // SECURITY/ROUTING: with an upstream proxy set, psiphon-tunnel-core
        // routes EVERY connection it makes through it, including its remote
        // server-list fetches, so the chain cannot leak a direct dial.
        upstreamProxy?.let { put("UpstreamProxyUrl", it) }
    }.toString()

    /** Psiphon's own directory, kept out of `filesDir` root (hev.yaml, tor/, ...). */
    private fun dataStoreDir(): File =
        File(service.filesDir, "psiphon").apply { mkdirs() }

    private fun resetDataStore() {
        runCatching { dataStoreDir().deleteRecursively() }
        dataStoreDir()
    }

    private fun stopTunnelQuietly() {
        // The front chains onto a specific Psiphon listener, so it must not
        // outlive the controller it was pointed at -- including between the two
        // establish attempts, where Psiphon may well bind a different port.
        PsiphonSocksFront.stop()
        connected.set(false)
        val old = tunnel
        tunnel = null
        runCatching { old?.stop() }
    }

    /**
     * Alive means "usable", not merely "the controller exists". A dead front is
     * a dead pipeline even with a perfectly healthy Psiphon behind it, so the
     * service's supervisor gets to rebuild the session instead of holding a
     * Connected badge over a proxy nothing can reach.
     */
    override fun isAlive(): Boolean {
        // An intentional in-place rotation is not a dead transport. See
        // [rotationDeadline] for the disconnect this prevents.
        if (rotating()) return true
        return connected.get() && tunnel != null && PsiphonSocksFront.isRunning
    }

    /**
     * True while a rotation is still within its budget.
     *
     * Self-expiring: once the deadline passes the flag is cleared and the next
     * [isAlive] tells the truth, so a restart that never establishes is handed
     * back to the supervisor instead of holding a Connected badge forever.
     */
    private fun rotating(): Boolean {
        val until = rotationDeadline
        if (until == 0L) return false
        if (System.currentTimeMillis() < until) return true
        rotationDeadline = 0L
        ConnectionLog.record(
            "Psiphon rotation did not come back within ${ROTATION_GRACE_MS / 1000}s - " +
                "handing the session back to the supervisor.",
        )
        return false
    }

    override fun stop() {
        // Counters and the rotation budget must never survive into the next
        // session: a fresh connect starts from a clean slate.
        rotationDeadline = 0L
        PsiphonHealth.reset()
        PsiphonSocksFront.stop()
        stopTunnelQuietly()
        if (!ready.isCompleted) {
            ready.completeExceptionally(CancellationException("Psiphon stopped"))
        }
    }

    override fun bindToDevice(fd: Long) {
        if (!service.protect(fd.toInt())) throw PsiphonTunnel.Exception("protect failed")
    }

    override fun onListeningSocksProxyPort(port: Int) {
        socksPort = port
        if (port != localSocksPort) {
            DiagnosticsLog.w(
                TAG,
                "Psiphon bound SOCKS5 on $port instead of $localSocksPort - the tunnel follows it.",
            )
        }
        // A rotation restarts the library, and the restarted controller can bind
        // a different local port (the previous one may still be in TIME_WAIT).
        // The front is deliberately NOT restarted - tun2socks is connected to it
        // and restarting it would be the very disconnect this all exists to
        // avoid - so it is re-pointed at the new port instead.
        PsiphonSocksFront.retarget(port)
    }

    override fun onListeningHttpProxyPort(port: Int) = Unit

    /**
     * Deliberately does NOT clear [connected]: psiphon-tunnel-core emits this
     * again for its own internal re-establish, and flipping the flag would make
     * the service tear a healthy session down and rebuild it from scratch.
     */
    override fun onConnecting() = ConnectionLog.record("Psiphon connecting")

    override fun onConnected() {
        connected.set(true)
        // The rotation completed: stop pretending, the transport is genuinely up
        // again and the supervisor can go back to reading the real state.
        if (rotationDeadline != 0L) {
            rotationDeadline = 0L
            ConnectionLog.record("Psiphon rotation complete - session continues on a new server.")
        }
        ready.complete(socksPort)
    }

    override fun onExiting() {
        connected.set(false)
        if (!ready.isCompleted) {
            ready.completeExceptionally(
                IllegalStateException("Psiphon stopped before it established a tunnel"),
            )
        }
    }

    override fun onClientAddress(address: String?) = Unit
    override fun onHomepage(homepage: String?) = Unit
    override fun onClientRegion(region: String?) = Unit

    /**
     * Logged rather than ignored: when a user's chosen country is not in this
     * list, the automatic-exit retry above is the reason the session still
     * comes up, and the log now says so out loud.
     */
    override fun onAvailableEgressRegions(regions: MutableList<String>?) {
        val list = regions?.filterNotNull()?.filter { it.isNotBlank() }?.sorted().orEmpty()
        if (list.isEmpty()) return
        // Kept because region steering may only ever pick a region the library
        // has just told us it can actually reach: `EgressRegion` is a HARD filter,
        // and steering into an empty region would hang establishment instead of
        // fixing anything.
        availableRegions.clear()
        availableRegions.addAll(list)
        ConnectionLog.record("Psiphon egress regions available: ${list.joinToString(", ")}")
    }

    override fun onConnectedServerRegion(region: String?) {
        ConnectionLog.record("Psiphon exit region: ${region ?: "automatic"}")
    }

    override fun onBytesTransferred(sent: Long, received: Long) = Unit

    override fun onDiagnosticMessage(message: String) {
        ConnectionLog.record("Psiphon: $message")
        // The notices carry the only authoritative count of refused port
        // forwards ("port forward failures for <id>: <n>") and the id of the
        // server carrying the session. See [PsiphonHealth].
        PsiphonHealth.onNotice(message)
    }

    /**
     * Moves the session onto a different Psiphon server WITHOUT tearing the app's
     * pipeline down.
     *
     * Both calls are library signals rather than blocking work, but they are
     * still issued off the notice thread: they are invoked from inside
     * [onDiagnosticMessage]'s call stack, and psiphon-tunnel-core delivers
     * notices from its own goroutines, so re-entering the library there is a
     * deadlock waiting to happen.
     *
     * Nothing else in the pipeline moves. Psiphon keeps its local SOCKS listener
     * on the same port, so [PsiphonSocksFront], tun2socks, the TUN and the share
     * bridge all stay exactly as they are and the user sees a brief stall instead
     * of a disconnect.
     */
    private fun rotateServer(strategy: PsiphonHealth.Rotation, target: PsiphonHealth.Target) {
        val active = tunnel ?: return
        // Claim the grace window BEFORE anything is torn down, so the supervisor
        // can never observe the gap between `stopPsiphon` and `startPsiphon`.
        rotationDeadline = System.currentTimeMillis() + ROTATION_GRACE_MS
        if (strategy == PsiphonHealth.Rotation.RESTART) prepareSteeredConfig(target)
        Thread({
            runCatching {
                when (strategy) {
                    // Drops the active tunnel and re-runs establishment inside the
                    // live controller. Cheap and invisible, but it re-reads
                    // nothing: it cannot apply the steering above.
                    PsiphonHealth.Rotation.RECONNECT -> active.reconnectPsiphon()
                    // Rebuilds the controller, which calls getPsiphonConfig()
                    // again - the only path on which the steered config takes
                    // effect, and therefore the only one that can guarantee a
                    // different server.
                    PsiphonHealth.Rotation.RESTART -> active.restartPsiphon()
                }
            }.onFailure {
                ConnectionLog.record("Psiphon server rotation failed: ${it.message}")
                // A rotation that could not even be issued must not keep the
                // session pinned as alive: let the supervisor take it.
                rotationDeadline = 0L
            }
        }, "psiphon-rotate").apply { isDaemon = true }.start()
    }

    /**
     * Rewrites [config] so the restarted controller cannot land back on the
     * server it was just told to leave.
     *
     * psiphon-tunnel-core has no "avoid these servers" config key, and from its
     * own point of view the filtering server is excellent: the tunnel
     * established, the handshake passed, bytes moved. So the exclusion is built
     * out of the three levers that DO exist:
     *
     *  1. `DisableReplay` - stops the library re-dialling the last server with
     *     its remembered dial parameters (`isReplay: true`, candidate 0).
     *  2. `EstablishTunnelServerAffinityGracePeriodMilliseconds: 0` - removes the
     *     head start the previously connected server gets over the other twelve
     *     concurrent establishment workers.
     *  3. `EgressRegion` - the one HARD filter in the library. When the user
     *     asked for Automatic, steering to a different reachable region
     *     GUARANTEES a different server. A country the user picked by hand is
     *     never silently changed: levers 1 and 2 have to carry that case.
     */
    private fun prepareSteeredConfig(target: PsiphonHealth.Target) {
        avoidReplay = true
        val userEgress = region.trim().uppercase()
        val egress = if (userEgress.isNotEmpty()) {
            ConnectionLog.record(
                "Psiphon staying in ${ExitRegions.name(userEgress)} because it was chosen by " +
                    "hand - replay and server affinity are disabled instead so establishment " +
                    "cannot settle on ${target.serverId} again.",
            )
            userEgress
        } else {
            nextEgressAway(target.region)
        }
        config = buildConfig(egress)
    }

    /**
     * Picks the next reachable egress region that is not home to a server already
     * convicted of filtering.
     *
     * Round-robin rather than random so two consecutive rotations cannot send the
     * session back where it came from, and "" (no filter) is returned when there
     * is nothing better to pick - an unfiltered establishment is always better
     * than one pinned to a region with no servers in it.
     */
    private fun nextEgressAway(badRegion: String): String {
        val bad = PsiphonHealth.filteringRegions() + setOfNotNull(badRegion.takeIf { it.isNotEmpty() })
        val pool = availableRegions.filter { it !in bad }
        if (pool.isEmpty()) {
            ConnectionLog.record(
                "Psiphon has no clean exit region left to steer to - restarting with replay and " +
                    "server affinity disabled and no region filter.",
            )
            return ""
        }
        val pick = pool[steerCursor.getAndIncrement().mod(pool.size)]
        ConnectionLog.record(
            "Psiphon steering the exit away from ${ExitRegions.name(badRegion.ifEmpty { "??" })} " +
                "to ${ExitRegions.name(pick)} - ${regionCountLabel(bad)} filtering, ${pool.size} clean " +
                "regions to choose from.",
        )
        return pick
    }

    /** Human-readable count of blacklisted regions for the steering log line. */
    private fun regionCountLabel(bad: Set<String>): String =
        if (bad.size == 1) "1 region is" else "${bad.size} regions are"
    override fun getContext(): Context = service
    override fun getPsiphonConfig(): String = config

    private companion object {
        const val TAG = "Transport"

        /**
         * Establish budget. The Go side gives up at
         * [ESTABLISH_TIMEOUT_SECONDS] and reports it through `onExiting`, so the
         * coroutine timeout only has to be a little longer than that: it is the
         * backstop for a controller that never calls back at all.
         */
        const val ESTABLISH_TIMEOUT_SECONDS = 180
        const val ESTABLISH_TIMEOUT_MS = 200_000L
        const val PORT_RELEASE_WAIT_MS = 3_000L

        /**
         * How long an in-flight server rotation may report ALIVE.
         *
         * A healthy restart is back in about two seconds (measured in the log
         * that prompted this fix), so this is generous by two orders of
         * magnitude - and still far short of the establish budget, because a
         * rotation that takes a minute and a half is a broken session that
         * belongs to the supervisor, not to the watchdog.
         */
        const val ROTATION_GRACE_MS = 90_000L

        const val REMOTE_SERVER_LIST_PUBLIC_KEY =
            "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
    }
}
