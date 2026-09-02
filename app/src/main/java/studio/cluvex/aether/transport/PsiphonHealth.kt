package studio.cluvex.aether.transport

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Watches ONE Psiphon session for a server that is up, fast and useless.
 *
 * ## ROOT CAUSE this exists to fix
 *
 * The field report is "on some servers Telegram opens every app but Google does
 * not". That is not luck, and it is not the tunnel: the log names the mechanism,
 * always from the same server:
 *
 * ```
 * 08:03:41.281 Psiphon: LocalProxyError: {"message":"... psiphon.(*Tunnel).dialChannel...:
 *              ssh: rejected: administratively prohibited (administratively prohibited)"}
 * 08:03:41.712 Psiphon: Info: {"message":"port forward failures for OsBTQokd: 3"}
 * ```
 *
 * `administratively prohibited` is the SSH server refusing to open the port
 * forward. The tunnel is healthy, the exit is healthy, TCP to other destinations
 * is healthy - this one *server* declines to dial these particular destinations.
 *
 * ## ROOT CAUSE #2 (the one behind the self-disconnects and the ping spikes)
 *
 * The first version of this watchdog detected the filtering server correctly and
 * then rotated onto **the same server again**, three times in a row, and the
 * third attempt tore the whole VPN down. From the log, verbatim:
 *
 * ```
 * 08:03:43.044 rotating off server OsBTQokd - reconnect 1/3
 * 08:03:44.393 ConnectingServer: {"candidateNumber":0,"diagnosticID":"OsBTQokd", ...}
 * 08:03:45.759 ConnectedServer:  {"candidateNumber":0,"diagnosticID":"OsBTQokd", ...}
 * 08:03:46.050 ActiveTunnel:     {"diagnosticID":"OsBTQokd","protocol":"SSH"}
 * ```
 *
 * psiphon-tunnel-core ranks the server it was last connected to FIRST - dial
 * parameter replay (`isReplay:true`) plus server affinity (`moved-to-front`) -
 * and from the library's point of view `OsBTQokd` is a perfectly good server: the
 * tunnel established, the handshake succeeded, bytes moved. It has no idea the
 * server is refusing port forwards. So `reconnectPsiphon()` dropped a working
 * tunnel and rebuilt the identical one, and each rebuild dropped the udpgw
 * session and every in-flight flow with it. That is the user-visible "the ping
 * shoots up, it drops, it reconnects over and over, and it is slow".
 *
 * Three further bugs made it worse:
 *
 *  - **Nothing remembered the bad server.** The counters were keyed on the
 *    *active* server id, and after re-landing on the same id the code had to
 *    earn six fresh refusals before it would act again.
 *  - **The counters re-armed instantly.** Refusals still draining out of the
 *    tunnel that was just abandoned were counted against its replacement, which
 *    is why the log shows "refused 6 different destinations" five times inside
 *    300 ms right after a rotation, burning the rotation budget in seconds.
 *  - **The last rotation was a hard `restartPsiphon()`**, which stops the
 *    library, which fires `onExiting`, which cleared `PsiphonTransport.connected`
 *    - and the service's chained supervisor polls `transport.isAlive()` once a
 *    second and tears the session down when it reads false. Log: `Exiting: {}` at
 *    08:06:38.535, `hev-socks5-tunnel stop requested` at 08:06:39.274. The
 *    watchdog was killing the connection it was supposed to be saving. That half
 *    is fixed in [PsiphonTransport.isAlive].
 *
 * ## ROOT CAUSE #3 (1.2.7-r2): the watchdog fired on LOAD, not on filtering
 *
 * The rule "25 tunnel-reported port-forward failures in 45 s" cannot tell a
 * censoring server from a busy one, because the counter it reads is a raw count
 * of every declined port forward for ANY reason. Opening one video was enough:
 * 26 refusals in 4.2 s (IPv6 dials the exit cannot complete, DNS-over-TCP on a
 * port the server does not allow out, and the player's own fan-out), a rotation
 * 4 s into playback, `no active tunnels`, and a six-second hole in the middle of
 * the video - three times in two minutes. See [FAILURE_DELTA_TRIGGER].
 *
 * The counter is now a CORROBORATING signal: it can only rotate when the front
 * has ALSO reported refusals for [FAILURE_CORROBORATION] distinct destinations
 * in the same window, and the front no longer reports refusals that are
 * structural rather than political (IPv6, the udpgw intercept, TCP/53).
 *
 * ## What this does now
 *
 * It counts refusals and, when a server proves it is filtering rather than
 * hiccupping, rotates off it - and then **verifies the rotation actually moved**.
 * Signals feeding the decision:
 *
 *  - **Distinct refused destinations** ([onDestinationRefused], reported by the
 *    front, the only place that knows *what* was refused). One dead host is
 *    normal internet; [DISTINCT_TARGETS_TRIGGER] different ones inside the window
 *    is a policy.
 *  - **The tunnel's own `port forward failures for <id>: <n>` counter**
 *    ([onNotice]). Monotonic and authoritative, and it also catches refusals for
 *    flows the front never saw.
 *  - **Landing back on a server already proven to filter.** No new evidence is
 *    required: the verdict is already in, so the rotation is re-issued at once
 *    instead of after another minute of broken browsing.
 *
 * Every convicted server goes on a session-scoped blacklist, and its exit region
 * with it, so [PsiphonTransport] can steer the next establishment somewhere else
 * instead of hoping the library shuffles the deck.
 *
 * Guard rails, so this can never become a reconnect loop: counters live in a
 * sliding [WINDOW_MS] window, refusals arriving in the first [SETTLE_MS] of a new
 * tunnel are attributed to the tunnel that was left rather than the one that
 * replaced it, rotations are [COOLDOWN_MS] apart ([REPEAT_COOLDOWN_MS] when we
 * demonstrably landed back on a blacklisted server), and there are at most
 * [MAX_ROTATIONS] of them. A server that then stays clean for [HEALTHY_MS]
 * refunds the budget, so an eight-hour session is not left with a filtering exit
 * just because it burned its rotations in the first two minutes.
 */
object PsiphonHealth {

    /** Refusals inside this sliding window are what counts as "this server filters". */
    private const val WINDOW_MS = 45_000L

    /** Distinct refused destinations in one window that trigger a rotation. */
    private const val DISTINCT_TARGETS_TRIGGER = 6

    /**
     * Tunnel-reported port-forward failures in one window that CAN trigger a
     * rotation - but only together with [FAILURE_CORROBORATION], never alone.
     *
     * ## ROOT CAUSE this number and that rule fix
     *
     * The field log for "video makes the ping jump over 1000 and everything
     * stops" is unambiguous. A perfectly healthy tunnel ran for 86 s, the user
     * opened a video, and then:
     *
     * ```
     * 09:36:52.520 port forward failures for vpDb1v+6: 1
     * ...                                       (26 of them in 4.2 s)
     * 09:36:56.708 port forward failures for vpDb1v+6: 26
     * 09:36:56.719 PsiphonHealth rotating off server vpDb1v+6 (25 refused port forwards)
     * 09:36:58.378 psiphon...Dial: no active tunnels
     * 09:37:04.079 ActiveTunnel: {"diagnosticID":"RAanom/G"}
     * ```
     *
     * The watchdog tore down a working tunnel 4 s into a video and handed the user
     * a six-second total outage - twice more over the next two minutes. It was the
     * cause of the symptom it was built to detect.
     *
     * Why the old threshold was guaranteed to fire: this counter is a RAW,
     * monotonic count of every port forward the server declined, for ANY reason.
     * A media player opens dozens of parallel flows, and in this app a large share
     * of them were IPv6 dials the exit could never complete (see
     * [studio.cluvex.aether.transport.PsiphonSocksFront]) plus DNS-over-TCP
     * lookups on a port the server does not allow out. None of that is
     * censorship, all of it is load-proportional, and 25 of them is a normal
     * second of video.
     *
     * So the counter is now a CORROBORATING signal only. What convicts a server
     * is the front's distinct-destination evidence, which is the only signal that
     * can distinguish "this server refuses many DIFFERENT places" from "this
     * session is simply busy".
     */
    private const val FAILURE_DELTA_TRIGGER = 60

    /**
     * Distinct refused destinations that must ALSO be on record in the same window
     * before [FAILURE_DELTA_TRIGGER] is allowed to rotate anything.
     *
     * Set below [DISTINCT_TARGETS_TRIGGER] on purpose: the counter's job is to
     * catch a filtering server FASTER than the distinct-destination signal alone
     * would (it sees refusals for flows the front never handled), not to be able
     * to act without it.
     */
    private const val FAILURE_CORROBORATION = 3

    /** Minimum gap between two rotations. */
    private const val COOLDOWN_MS = 60_000L

    /**
     * Minimum gap when the rotation demonstrably failed to move: the new tunnel
     * is carried by a server already on the blacklist. Deliberately short - the
     * user is staring at a broken connection and there is nothing to learn by
     * waiting a full minute to re-issue a verdict that is already in.
     */
    private const val REPEAT_COOLDOWN_MS = 12_000L

    /**
     * Grace period at the start of every new tunnel.
     *
     * A rotation abandons a tunnel that still has flows in flight, and their
     * refusals arrive over the next few hundred milliseconds. Counting those
     * against the replacement server is what let the old code burn every
     * rotation inside three seconds.
     */
    private const val SETTLE_MS = 6_000L

    /** A clean streak this long on one server refunds the rotation budget. */
    private const val HEALTHY_MS = 180_000L

    /** Hard cap per session, before any [HEALTHY_MS] refund. */
    private const val MAX_ROTATIONS = 4

    /**
     * Rotation strategy for one attempt.
     *
     * [RECONNECT] is cheap and in-place: `reconnectPsiphon()` drops the tunnel and
     * re-runs establishment inside the live controller. It cannot change the
     * config, so it cannot steer - the right first move for a server that might
     * just be having a bad minute.
     *
     * [RESTART] rebuilds the controller, which is the only path that re-reads
     * `getPsiphonConfig()` and therefore the only one that can turn the blacklist
     * into an actual filter. Used from the second attempt on, and immediately
     * whenever a rotation lands back on a blacklisted server.
     */
    enum class Rotation { RECONNECT, RESTART }

    /** The server being left behind, so the transport can steer around it. */
    data class Target(val serverId: String, val region: String)

    private val lock = Any()

    /** Set by [PsiphonTransport] while a session is live. */
    private val action = AtomicReference<((Rotation, Target) -> Unit)?>(null)

    /** diagnosticID of the server currently carrying the session. */
    private val activeServer = AtomicReference("")

    /** Exit region of [activeServer], as reported by the notices. */
    private val activeRegion = AtomicReference("")

    /** diagnosticID -> exit region, learned from `ConnectedServer` notices. */
    private val serverRegions = ConcurrentHashMap<String, String>()

    /** Servers convicted of filtering during this session. */
    private val filtering = ConcurrentHashMap.newKeySet<String>()

    /** Exit regions of [filtering] servers, for [PsiphonTransport]'s steering. */
    private val filteringRegions = ConcurrentHashMap.newKeySet<String>()

    /** Servers already named in the log, so the verdict is printed once each. */
    private val announced = ConcurrentHashMap.newKeySet<String>()

    private val rotations = AtomicInteger(0)
    private val lastRotationAt = AtomicLong(0)

    /** Refusals before this instant belong to the tunnel we just left. */
    private val settleUntil = AtomicLong(0)

    /** When the current tunnel came up, for the [HEALTHY_MS] refund. */
    private val tunnelSince = AtomicLong(0)

    /** Refused destination -> first time it was refused inside the current window. */
    private val refusedTargets = ConcurrentHashMap<String, Long>()

    /**
     * Destinations THIS APP dials for its own health checks, which must never be
     * allowed to convict a server.
     *
     * ## ROOT CAUSE this fixes (1.2.8-r3)
     *
     * The watchdog and the latency badge both dialled anycast resolvers on
     * **TCP port 53**, and a large share of Psiphon exits refuse that port
     * outright (`ssh: rejected: administratively prohibited`). Three probe
     * targets per watchdog cycle plus the badge is four DIFFERENT destinations
     * refused every fifteen seconds, for free, on a completely healthy server -
     * and [DISTINCT_TARGETS_TRIGGER] is six.
     *
     * The field log for 1.2.8-r2 shows the result end to end. At 09:33:36 the
     * connectivity self-test passes over the paths real traffic uses
     * (`dns+http OK, exit ip=146.59.70.6 cc=PL`). One quarter of a second later
     * the first probe is refused. Forty-five seconds after that:
     *
     * ```text
     * 09:34:59.391 refused 6 different destinations in 45s ... excluded for the
     *              rest of this session
     * 09:34:59.396 udpgw session closed        <- every UDP/QUIC flow dies here
     * 09:35:03.297 up but carries nothing (2/2)
     * 09:35:03.298 Rebuilding the session: the data path is wedged
     * ```
     *
     * Nothing was wedged. The app convicted its exit, dropped every UDP
     * association and then tore down its own working pipeline, on a schedule,
     * forever - which is the "ping spikes, it disconnects, it comes back, over
     * and over" report, and it is why a long-lived symmetric stream (live
     * dubbing) could never survive more than about a minute.
     *
     * The probes now use port 443 (see AetherVpnService and PingMonitor), and
     * they register here so that they can never be counted as evidence about the
     * server no matter which port they end up on.
     */
    private val selfProbeTargets = ConcurrentHashMap.newKeySet<String>()

    /**
     * Declares `host:port` as one of the app's own health-probe destinations.
     * Idempotent, and safe to call before a session exists.
     */
    fun registerSelfProbe(host: String, port: Int) {
        selfProbeTargets.add("$host:$port")
    }

    /** True when `host:port` is one of the app's own probes. */
    fun isSelfProbe(host: String, port: Int): Boolean =
        selfProbeTargets.contains("$host:$port")

    /** Window start, and the tunnel's failure counter as it read at that moment. */
    private val windowStartedAt = AtomicLong(0)
    private val windowBaseFailures = AtomicLong(-1)

    /** Uncorroborated failure bursts, so the "this is load" note is logged rarely. */
    private val loudFailureBursts = AtomicInteger(0)

    /** One log line per this many uncorroborated bursts. */
    private const val BURST_LOG_EVERY = 20

    /** Registers the live session. Called when a Psiphon controller is created. */
    fun bind(onRotate: (Rotation, Target) -> Unit) {
        reset()
        action.set(onRotate)
    }

    /** Clears everything. Called on every stop, so counters never cross sessions. */
    fun reset() {
        action.set(null)
        activeServer.set("")
        activeRegion.set("")
        serverRegions.clear()
        filtering.clear()
        filteringRegions.clear()
        announced.clear()
        rotations.set(0)
        lastRotationAt.set(0)
        settleUntil.set(0)
        tunnelSince.set(0)
        refusedTargets.clear()
        windowStartedAt.set(0)
        windowBaseFailures.set(-1)
        loudFailureBursts.set(0)
    }

    /**
     * Servers proven to filter during this session.
     *
     * Read by [PsiphonTransport] when it rebuilds the Psiphon config: it cannot
     * hand the library a list of servers to avoid (there is no such config key),
     * but it CAN stop the library replaying its way back to one, and it can steer
     * the egress region away from where they live.
     */
    fun filteringServers(): Set<String> = filtering.toSet()

    /** Exit regions of [filteringServers], for the transport's region steering. */
    fun filteringRegions(): Set<String> = filteringRegions.toSet()

    /** True when [serverId] has already been convicted in this session. */
    fun isFiltering(serverId: String): Boolean = filtering.contains(serverId)

    /**
     * `elapsedRealtime`-independent wall clock instant until which a deliberate
     * in-place rotation is still settling, or 0 when none is in flight.
     */
    fun settlingUntil(): Long = settleUntil.get()

    /**
     * True while a deliberate rotation is still settling.
     *
     * ## ROOT CAUSE this fixes (1.2.8-r3) - the two watchdogs fighting
     *
     * A rotation is a CONTROLLED exit change: the pipeline stays up, only the
     * Psiphon server moves, and it takes a few seconds during which nothing can
     * be dialled. [PsiphonTransport.isAlive] already masks that window. The
     * service's pipeline probe did not, and it is the one holding the axe. The
     * 1.2.8-r2 field log catches them four seconds apart:
     *
     * ```text
     * 09:34:59.393 rotating off server n+WE7s6A - reconnect 1/4.
     *              The pipeline stays up; only the exit server changes.
     * 09:35:00.690 Dial ... no active tunnels          <- expected, mid-rotation
     * 09:35:03.297 up but carries nothing (2/2)
     * 09:35:03.298 Rebuilding the session: the data path is wedged
     * 09:35:03.331 hev-socks5-tunnel stop requested    <- the whole session dies
     * ```
     *
     * The repair was working. The watchdog counted the repair as the failure and
     * destroyed everything instead, which turns one clean 3-second exit change
     * into a full teardown, a fresh Psiphon bootstrap and 10+ seconds of dead
     * air - the "it disconnects and comes back, over and over" report, on a
     * loop, because the replacement session hits the same sequence.
     */
    fun isSettling(): Boolean = System.currentTimeMillis() < settleUntil.get()

    /**
     * One destination Psiphon refused to dial, reported by [PsiphonSocksFront].
     *
     * Keyed by `host:port` so a browser retrying the same blocked host twenty
     * times still counts once: what distinguishes a filtering server from a dead
     * site is the NUMBER OF DIFFERENT destinations it turns down.
     */
    fun onDestinationRefused(host: String, port: Int) {
        if (action.get() == null) return
        // 1.2.8-r3: our own probes are not evidence about anything but us.
        // See [selfProbeTargets] for the log that made this mandatory.
        if (isSelfProbe(host, port)) return
        val now = System.currentTimeMillis()
        // Refusals draining out of a tunnel we already abandoned say nothing
        // about the one that replaced it.
        if (now < settleUntil.get()) return
        synchronized(lock) {
            openOrRollWindow(now)
            refusedTargets[host + ":" + port] = now
            val distinct = refusedTargets.size
            if (distinct >= DISTINCT_TARGETS_TRIGGER) {
                val why = "refused $distinct different destinations"
                convict(why)
                maybeRotate(now, why, repeat = false)
            }
        }
    }

    /**
     * Every Psiphon diagnostic notice, verbatim.
     *
     * Matched on stable prefixes rather than parsed as JSON: the notices arrive
     * at a few hundred per session and a regex over a short string costs nothing
     * next to building a JSONObject for each one.
     */
    fun onNotice(message: String) {
        if (message.isEmpty()) return

        // Learn diagnosticID -> region, so a conviction can blacklist the region
        // too. `ConnectedServer` is the only notice carrying both.
        CONNECTED_SERVER.find(message)?.let { match ->
            val id = match.groupValues[1]
            SERVER_REGION.find(message)?.let { region -> serverRegions[id] = region.groupValues[1] }
        }

        val established = ACTIVE_TUNNEL.find(message)
        if (established != null) {
            onTunnelEstablished(established.groupValues[1])
            return
        }

        if (action.get() == null) return
        val failures = PORT_FORWARD_FAILURES.find(message) ?: return
        val server = failures.groupValues[1]
        val count = failures.groupValues[2].toLongOrNull() ?: return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (activeServer.get().isEmpty()) adoptServer(server)
            // A counter reported for a server that is no longer carrying the
            // session is the tail of an abandoned tunnel, not evidence.
            if (server != activeServer.get()) return
            if (now < settleUntil.get()) return
            openOrRollWindow(now)
            if (windowBaseFailures.get() < 0) windowBaseFailures.set(count)
            // The counter is monotonic per server and resets when the server
            // changes, so a NEGATIVE delta means a new server started counting
            // from a lower number: rebase instead of reading it as progress.
            val delta = count - windowBaseFailures.get()
            if (delta < 0) {
                windowBaseFailures.set(count)
                return
            }
            if (delta < FAILURE_DELTA_TRIGGER) return
            // CORROBORATION GATE. A raw failure count says how BUSY the session
            // is, not whether the server is filtering: a video player alone
            // produced 26 of them in 4.2 s against a server that was working
            // fine. Only the front's distinct-destination evidence can tell the
            // two apart, so the counter may accelerate a verdict but never reach
            // one on its own.
            val distinct = refusedTargets.size
            if (distinct < FAILURE_CORROBORATION) {
                if (loudFailureBursts.incrementAndGet() % BURST_LOG_EVERY == 1) {
                    ConnectionLog.record(
                        "$TAG ${serverLabel()} declined $delta port forwards in " +
                            "${WINDOW_MS / 1000}s but only $distinct distinct destination(s) - " +
                            "that is load, not filtering (a media player opens dozens of flows " +
                            "at once), so the session is left alone.",
                    )
                }
                return
            }
            val why = "$delta refused port forwards across $distinct distinct destinations"
            convict(why)
            maybeRotate(now, why, repeat = false)
        }
    }

    /**
     * A tunnel came up (`ActiveTunnel`), which is the ONLY moment at which we can
     * check whether a rotation achieved anything.
     *
     * Fires on every established tunnel, not only on a change of server id: the
     * whole bug was that the id did NOT change, and the old code keyed its reset
     * on `getAndSet(id) != id` and therefore did nothing at all in exactly the
     * case that mattered.
     */
    private fun onTunnelEstablished(serverId: String) {
        val now = System.currentTimeMillis()
        val known = filtering.contains(serverId)
        synchronized(lock) {
            adoptServer(serverId)
            settleUntil.set(now + SETTLE_MS)
            tunnelSince.set(now)
            if (!known) return
            // The rotation did not move us. Re-issue it at once with the stronger
            // strategy rather than waiting to re-earn evidence we already have.
            ConnectionLog.record(
                "$TAG the new tunnel is carried by ${serverLabel()} again, which already " +
                    "proved it filters - rotating again with that server excluded.",
            )
            maybeRotate(now, "landed back on the filtering server ${serverLabel()}", repeat = true)
        }
    }

    /** Points the counters at [serverId]. Caller holds [lock]. */
    private fun adoptServer(serverId: String) {
        if (activeServer.getAndSet(serverId) != serverId) {
            refusedTargets.clear()
            windowStartedAt.set(0)
            windowBaseFailures.set(-1)
        }
        activeRegion.set(serverRegions[serverId].orEmpty())
    }

    /**
     * Records the verdict against the active server, once.
     *
     * The blacklist is what makes a rotation verifiable: without it the app
     * cannot tell "the library moved me somewhere else" from "the library
     * replayed its way straight back to the server I asked it to leave".
     *
     * Caller holds [lock].
     */
    private fun convict(why: String) {
        val server = activeServer.get()
        if (server.isEmpty()) return
        filtering.add(server)
        serverRegions[server]?.takeIf { it.isNotEmpty() }?.let { filteringRegions.add(it) }
        if (!announced.add(server)) return
        ConnectionLog.record(
            "$TAG server ${serverLabel()} has $why in ${WINDOW_MS / 1000}s " +
                "(ssh 'administratively prohibited'); this server filters rather than fails - " +
                "excluded for the rest of this session",
        )
    }

    /** Opens the window, or rolls it over once it has expired. Caller holds [lock]. */
    private fun openOrRollWindow(now: Long) {
        val started = windowStartedAt.get()
        if (started == 0L || now - started > WINDOW_MS) {
            windowStartedAt.set(now)
            windowBaseFailures.set(-1)
            refusedTargets.clear()
        } else {
            // Drop entries that aged out of the window even though it is still
            // open, so a slow trickle over ten minutes never adds up to a
            // trigger.
            refusedTargets.entries.removeAll { now - it.value > WINDOW_MS }
        }
    }

    /** Caller holds [lock]. */
    private fun maybeRotate(now: Long, why: String, repeat: Boolean) {
        val rotate = action.get() ?: return
        refundIfHealthy(now)

        val done = rotations.get()
        if (done >= MAX_ROTATIONS) {
            // Deliberate dead end: a user staring at a working-but-filtered
            // tunnel is still better off than one whose tunnel is torn down
            // every minute. The refund above is what gets the budget back.
            return
        }
        val last = lastRotationAt.get()
        val cooldown = if (repeat) REPEAT_COOLDOWN_MS else COOLDOWN_MS
        if (last != 0L && now - last < cooldown) return

        val attempt = done + 1
        // Attempt 1 is the cheap in-place reconnect; from attempt 2 on the
        // controller has to be rebuilt, because that is the only path that
        // re-reads the config and can actually steer away from the bad server.
        val strategy = if (attempt == 1 && !repeat) Rotation.RECONNECT else Rotation.RESTART
        val target = Target(activeServer.get(), activeRegion.get())
        rotations.set(attempt)
        lastRotationAt.set(now)
        // Start a fresh window: the counters describe the server being left, and
        // the settle window keeps its dying flows from framing its replacement.
        windowStartedAt.set(0)
        windowBaseFailures.set(-1)
        refusedTargets.clear()
        settleUntil.set(now + SETTLE_MS)

        ConnectionLog.record(
            "$TAG rotating off server ${serverLabel()} ($why) - " +
                "${strategy.name.lowercase()} $attempt/$MAX_ROTATIONS. The pipeline stays up; " +
                "only the exit server changes.",
        )
        // UDP is given a clean slate on the new server. A refusal of the udpgw
        // intercept is remembered for the whole session by design, but that
        // memory belongs to the SERVER that refused it - keeping it would leave
        // a perfectly capable replacement stuck on DNS-over-TCP.
        PsiphonSocksFront.onServerRotated()
        runCatching { rotate(strategy, target) }
            .onFailure { ConnectionLog.record("$TAG rotation failed: ${it.message}") }
    }

    /**
     * Gives the rotation budget back once the session has been quietly healthy
     * for [HEALTHY_MS] on a server that is not blacklisted.
     *
     * Without this, [MAX_ROTATIONS] is a per-session cap that a bad first two
     * minutes can exhaust, leaving the next eight hours defenceless. Caller holds
     * [lock].
     */
    private fun refundIfHealthy(now: Long) {
        if (rotations.get() == 0) return
        val since = tunnelSince.get()
        if (since == 0L || now - since < HEALTHY_MS) return
        if (filtering.contains(activeServer.get())) return
        rotations.set(0)
        ConnectionLog.record(
            "$TAG ${serverLabel()} has been clean for ${HEALTHY_MS / 60_000} min - " +
                "rotation budget restored.",
        )
    }

    private fun serverLabel(): String = activeServer.get().ifEmpty { "(unknown)" }

    private const val TAG = "PsiphonHealth"

    /** `ActiveTunnel: {"diagnosticID":"99Fh7IiB","protocol":"TLS-OSSH"}` */
    private val ACTIVE_TUNNEL = Regex("""ActiveTunnel:.*?"diagnosticID"\s*:\s*"([^"]+)"""")

    /** `ConnectedServer: {...,"diagnosticID":"OsBTQokd",...,"region":"DE",...}` */
    private val CONNECTED_SERVER = Regex("""ConnectedServer:.*?"diagnosticID"\s*:\s*"([^"]+)"""")

    /** The `"region":"DE"` field of a `ConnectedServer` notice. */
    private val SERVER_REGION = Regex(""""region"\s*:\s*"([A-Za-z]{2})"""")

    /** `Info: {"message":"port forward failures for 99Fh7IiB: 115"}` */
    private val PORT_FORWARD_FAILURES =
        Regex("""port forward failures for ([A-Za-z0-9+/=]+):\s*(\d+)""")
}
