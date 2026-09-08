package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * LAN sharing bridge: lets OTHER devices on the same Wi-Fi / hotspot use this
 * phone's Aether tunnel as a normal proxy.
 *
 * Two listeners are exposed while sharing is on:
 *  - SOCKS5  0.0.0.0:[SOCKS_SHARE_PORT] — a transparent TCP relay into the
 *    engine's local SOCKS5 (127.0.0.1:1819, loopback-only), so the full SOCKS5
 *    protocol (including remote DNS) is served by the engine itself.
 *  - HTTP    0.0.0.0:[HTTP_SHARE_PORT] — a minimal HTTP/1.1 proxy (CONNECT for
 *    HTTPS + absolute-form for plain HTTP) that dials upstream THROUGH that
 *    SOCKS5 proxy. This is what the "system proxy" settings on Windows/macOS
 *    (and most phones) expect, so laptops work out of the box.
 *
 * Loop safety: this code runs inside the app process, which is excluded from
 * the TUN via addDisallowedApplication(), so proxied traffic always leaves via
 * the engine and never re-enters the VPN.
 *
 * ## Access control (audit 1.2.9-r3, F-5)
 *
 * Until 1.2.9-r3 both listeners accepted connections from ANY device on the local
 * network with no credential at all. Sharing was off by default and the UI warned
 * about it, which is not the same thing as a control: on a cafe or hotel network,
 * every other guest could push their traffic out of this user's exit IP, and it
 * would be attributed to this user.
 *
 * Two rules now apply to every accepted socket, both implemented in [LanGuard] and
 * unit-tested there:
 *
 *  1. **Source scope.** A client must come from a genuinely local address
 *     (RFC1918 / RFC4193 / link-local / loopback). Carrier-grade NAT and public
 *     addresses are refused outright.
 *  2. **Authentication.** A non-loopback client must present the device's
 *     generated credential - SOCKS5 username/password (RFC 1929) or HTTP
 *     `Proxy-Authorization: Basic`. See
 *     [studio.cluvex.aether.data.ShareCredentials].
 *
 * **Loopback is deliberately exempt from rule 2.** `127.0.0.1` means "this phone":
 * on-device apps pointed at the proxy, and the app's own proxy-mode self-test,
 * behave exactly as they did before. The credential exists to gate the LAN, and
 * an on-device attacker able to open a loopback socket is already inside the
 * sandbox boundary this listener could defend.
 *
 * A concurrency cap ([MAX_LIVE_CONNECTIONS]) and a rate-limited rejection log
 * complete it: a hostile LAN peer can no longer spend the phone's threads, nor
 * fill the size-capped diagnostics log with one line per probe.
 */
object ShareBridge {

    /**
     * FIXED local proxy ports. These NEVER change at runtime: users type them
     * once into another app (Psiphon, Telegram, a browser) and the address
     * keeps working across every reconnect.
     *
     * ### Why these numbers changed in 1.2.2 (v2rayNG conflict — root cause)
     *
     * 1.2.1 used 10808/10809. Those are not "neutral" numbers: they are
     * **v2rayNG's own defaults** (10808 = SOCKS5, 10809 = HTTP), and Clash/
     * NekoBox derivatives reuse them too. Any user with v2rayNG installed and
     * running therefore hit a hard collision:
     *
     *  - If v2rayNG bound first, Aether's sharing/proxy mode failed with
     *    EADDRINUSE and the user saw "could not open the proxy ports".
     *  - If Aether bound first, v2rayNG failed to start, which is how this got
     *    reported as "Aether breaks v2rayNG".
     *  - Worst case, an app configured for 127.0.0.1:10808 silently sent its
     *    traffic into whichever tunnel happened to own the port that minute —
     *    a routing conflict with real privacy consequences.
     *
     * 1.2.2 moves one slot up to **10810/10811**, which sit in the same
     * easy-to-remember block but are claimed by no mainstream client, and adds
     * an explicit pre-bind conflict check ([describePortHolder]) so a genuine
     * collision is reported in plain language instead of a bare stack trace.
     *
     * Note the engine's own SOCKS5 listener (127.0.0.1:1819, see TunnelConfig)
     * never overlapped with v2rayNG and is unchanged.
     */
    const val SOCKS_SHARE_PORT = 10810
    const val HTTP_SHARE_PORT = 10811

    /**
     * Ports owned by well-known neighbouring tunnels. Used only to produce a
     * helpful diagnostic message — Aether never binds these.
     */
    private val KNOWN_NEIGHBOUR_PORTS = mapOf(
        10808 to "v2rayNG (SOCKS5)",
        10809 to "v2rayNG (HTTP)",
        7890 to "Clash (mixed)",
        1080 to "Psiphon / generic SOCKS",
        8118 to "Privoxy",
    )

    private const val TAG = "share"
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val DIAL_TIMEOUT_MS = 10_000

    /**
     * How long a remote client gets to complete authentication. Short on purpose:
     * an unauthenticated socket must not be able to hold a thread open.
     */
    private const val AUTH_TIMEOUT_MS = 10_000

    /**
     * Ceiling on simultaneously relayed connections. Generous for the real use
     * case (a laptop and a phone browsing) and low enough that a LAN peer cannot
     * spawn threads until the app dies.
     */
    private const val MAX_LIVE_CONNECTIONS = 96

    /** At most one rejection line per window, so a probe loop cannot flood the log. */
    private const val REJECT_LOG_INTERVAL_MS = 30_000L

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Actual bound ports for the current session (null while that listener is down). */
    private val _socksPort = MutableStateFlow<Int?>(null)
    val socksPort: StateFlow<Int?> = _socksPort.asStateFlow()

    private val _httpPort = MutableStateFlow<Int?>(null)
    val httpPort: StateFlow<Int?> = _httpPort.asStateFlow()

    /**
     * Cumulative byte counters for THIS sharing session (reset on every
     * [startSync]). In proxy mode the system TUN (and therefore
     * hev-socks5-tunnel's stats API) is not running, so these counters are the
     * ONLY source of download/upload numbers for the traffic meter.
     *
     * Direction mapping matches HevTunnel.traffic():
     *  - upload   = bytes from proxy clients relayed INTO the engine (device -> internet)
     *  - download = bytes from the engine relayed BACK to proxy clients (internet -> device)
     */
    private val uploadBytesCounter = AtomicLong(0L)
    private val downloadBytesCounter = AtomicLong(0L)

    /** Snapshot of the bridge's cumulative session traffic. */
    data class Traffic(val downloadBytes: Long, val uploadBytes: Long)

    fun traffic(): Traffic = Traffic(
        downloadBytes = downloadBytesCounter.get(),
        uploadBytes = uploadBytesCounter.get(),
    )

    /**
     * The credential remote clients must present. Published so the Share card can
     * show it; the password itself lives sealed in
     * [studio.cluvex.aether.data.SecretStore].
     */
    private val _proxyUser = MutableStateFlow(DEFAULT_USER)
    val proxyUser: StateFlow<String> = _proxyUser.asStateFlow()

    private val _proxyPassword = MutableStateFlow("")
    val proxyPassword: StateFlow<String> = _proxyPassword.asStateFlow()

    /** Live relayed connections, for [MAX_LIVE_CONNECTIONS]. */
    private val liveConnections = AtomicInteger(0)

    /** Refusals since the session started, and when we last said so. */
    private val refusals = AtomicLong(0L)

    @Volatile
    private var lastRefusalLogMs = 0L

    private var socksServer: ServerSocket? = null
    private var httpServer: ServerSocket? = null

    /**
     * Monotonic session id. Every [startSync] / [stop] bumps it, so a stale
     * asynchronous stop can never close the listeners of a NEWER session —
     * the race that used to fail rebinding with EADDRINUSE or leave
     * "sharing ON" with already-dead sockets after a quick reconnect.
     */
    private var session = 0

    /** Bind address for the current session: loopback-only or all interfaces. */
    @Volatile
    private var bindHost = "127.0.0.1"

    /**
     * The loopback SOCKS5 port every shared connection is relayed INTO.
     *
     * Normally the Aether engine's own listener. In a chained
     * `Aether -> Psiphon` session the engine is only the first hop, so the
     * bridge has to hand traffic to the SECOND stage's listener instead --
     * otherwise proxy mode would quietly share the Aether exit while the UI
     * (correctly) reported a Psiphon exit.
     */
    @Volatile
    private var upstreamPort = TunnelConfig.SOCKS_PORT

    /**
     * Turn sharing on. Safe to call from ANY thread — including the UI thread:
     * binding sockets is a network operation and Android throws
     * NetworkOnMainThreadException when it happens on the main thread, so the
     * actual work runs on a short-lived background thread and [active] flips
     * to true once both listeners are ready.
     */
    /**
     * SECURITY (audit 1.2.7): [localOnly] defaults to **true**, i.e. loopback.
     * It used to default to `false`, so any call that forgot the argument bound
     * an UNAUTHENTICATED proxy on `0.0.0.0` for the whole LAN. Every current
     * caller passes the flag explicitly, so this changes no behaviour - it makes
     * the dangerous case the one you have to ask for, instead of the one you get
     * by omission.
     */
    fun start(localOnly: Boolean = true, upstreamPort: Int? = null) {
        thread(name = "share-start", isDaemon = true) { startSync(localOnly, upstreamPort) }
    }

    /**
     * Turn sharing on and WAIT until the listeners are bound. Returns true when
     * BOTH fixed listeners (SOCKS5 and HTTP) are actually accepting connections.
     * MUST be called from a background thread (binding is a network operation).
     *
     * Unlike the old fire-and-forget start, callers such as the VpnService can
     * use the return value as ground truth instead of assuming success — in
     * proxy mode these listeners ARE the product, so a swallowed bind failure
     * meant "connected" with nothing listening on 1080/8118.
     */
    fun startSync(
        localOnly: Boolean = true,
        /**
         * Loopback SOCKS5 port to relay into, or null to keep whatever the
         * running session configured.
         *
         * Null is what the user-facing sharing toggle passes: it must never
         * silently retarget a live chained session back at the first hop, which
         * a plain `Int = SOCKS_PORT` default would have done.
         */
        upstreamPort: Int? = null,
    ): Boolean = synchronized(this) {
        if (upstreamPort != null) this.upstreamPort = upstreamPort
        // Already up with a healthy listener? Nothing to do.
        if (_active.value && (socksServer?.isClosed == false || httpServer?.isClosed == false)) {
            return@synchronized true
        }

        // New session: invalidates any in-flight async [stop] and clears
        // leftovers so rebinding is deterministic.
        session++
        closeServers()
        uploadBytesCounter.set(0L)
        downloadBytesCounter.set(0L)

        // SECURITY: when not explicitly sharing to the LAN, bind loopback
        // only so no other device on the network can use us as an open
        // proxy. LAN exposure is opt-in via the user's "share" toggle.
        bindHost = if (localOnly) "127.0.0.1" else "0.0.0.0"
        liveConnections.set(0)
        refusals.set(0L)

        // FAIL CLOSED. If something ever exposes the bridge to the LAN before the
        // stored credential was loaded, we mint an ephemeral one here rather than
        // opening an unauthenticated proxy. It is useless to the user (nothing
        // shows it), which is the correct trade: a share that does not work is a
        // support question, an open proxy is somebody else's traffic on this
        // user's exit.
        if (!localOnly && _proxyPassword.value.isBlank()) {
            _proxyPassword.value = LanGuard.randomPassword()
            DiagnosticsLog.w(
                TAG,
                "LAN sharing was started before the saved proxy password was loaded - " +
                    "using a temporary one for this session. Open the Share card to see " +
                    "the credential to enter on the other device.",
            )
        }

        // Bind the FIXED standard ports. NO fallback: the address users typed
        // into other apps must never silently change between sessions. A short
        // retry loop absorbs a transient EADDRINUSE from a just-closed listener
        // (TIME_WAIT / async teardown); a genuinely occupied port fails loudly.
        // 1.2.2: log which neighbouring tunnels are live BEFORE binding, so a
        // port clash is diagnosable from the in-app log alone.
        reportNeighbours()

        socksServer = bindWithRetry("SOCKS5", SOCKS_SHARE_PORT)
        httpServer = bindWithRetry("HTTP", HTTP_SHARE_PORT)
        _socksPort.value = socksServer?.localPort
        _httpPort.value = httpServer?.localPort

        if (socksServer == null || httpServer == null) {
            DiagnosticsLog.e(
                TAG,
                "Could not open the fixed proxy ports ($SOCKS_SHARE_PORT/$HTTP_SHARE_PORT) — " +
                    "close the app holding them and reconnect.",
            )
            closeServers()
            _active.value = false
            return@synchronized false
        }

        socksServer?.let { server -> acceptLoop("share-socks", server) { serveSocksClient(it) } }
        httpServer?.let { server -> acceptLoop("share-http", server) { serveHttpClient(it) } }
        _active.value = true

        val scope = if (bindHost == "127.0.0.1") {
            "loopback only"
        } else {
            "all interfaces (LAN) - remote clients must authenticate as " +
                "${_proxyUser.value} and come from a local address"
        }
        DiagnosticsLog.i(
            TAG,
            "Sharing ON — SOCKS5 :${_socksPort.value ?: "unavailable"} + HTTP :${_httpPort.value ?: "unavailable"} ($scope)",
        )
        true
    }

    /** Turn sharing off. Safe to call from any thread. */
    fun stop() {
        _active.value = false
        val stopSession = synchronized(this) { ++session }
        thread(name = "share-stop", isDaemon = true) {
            synchronized(this) {
                // Only close if no NEWER session started meanwhile: a stale
                // async stop must never kill a fresh session's listeners.
                if (session == stopSession) {
                    val hadServers = socksServer != null || httpServer != null
                    closeServers()
                    if (hadServers) DiagnosticsLog.i(TAG, "Sharing OFF")
                }
            }
        }
    }

    /**
     * Installs the credential remote clients must authenticate with.
     *
     * Called by [studio.cluvex.aether.data.ShareCredentials] once per process (and
     * again after a rotation). Takes effect immediately for NEW connections;
     * sockets already relaying are untouched, which is what a user rotating the
     * password expects - their own laptop should not drop mid-download.
     */
    fun setCredentials(user: String, password: String) {
        if (user.isNotBlank()) _proxyUser.value = user
        if (password.isNotBlank()) _proxyPassword.value = password
    }

    /** Best local (site-local IPv4) address other devices can reach us on. */
    fun lanAddress(): String? =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .filterNot { it.name.startsWith("tun") || it.name.startsWith("ppp") }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

    // ------------------------------------------------------------- internals

    private fun bind(port: Int): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindHost, port), 32)
        }

    /**
     * Binds a FIXED port, retrying briefly so a listener that is still being
     * torn down by the previous session never pushes users onto a different
     * port. The port either opens or sharing fails loudly — it NEVER moves.
     */
    private fun bindWithRetry(
        label: String,
        port: Int,
        attempts: Int = 10,
        delayMs: Long = 300,
    ): ServerSocket? {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return bind(port)
            } catch (e: Exception) {
                lastError = e
                if (attempt < attempts - 1) Thread.sleep(delayMs)
            }
        }
        DiagnosticsLog.e(
            TAG,
            "$label port $port is busy${describePortHolder(port)}: $lastError",
        )
        return null
    }

    /** Names the usual suspect for [port], for human-readable diagnostics. */
    private fun describePortHolder(port: Int): String =
        KNOWN_NEIGHBOUR_PORTS[port]?.let { " — this port belongs to $it" }
            ?: " (held by another app?)"

    /**
     * Detects other local tunnels listening on the classic proxy ports and
     * notes them in the log. Purely informational: 1.2.2 deliberately binds
     * ports nobody else claims, so co-existing with v2rayNG/Clash is expected
     * to just work — this line simply proves it in the diagnostics.
     */
    private fun reportNeighbours() {
        val live = KNOWN_NEIGHBOUR_PORTS.filterKeys { port ->
            runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 120) }
                true
            }.getOrDefault(false)
        }.values.distinct()
        if (live.isEmpty()) return
        DiagnosticsLog.i(
            TAG,
            "Other local proxies detected (${live.joinToString(", ")}) — Aether uses " +
                "$SOCKS_SHARE_PORT/$HTTP_SHARE_PORT, so they can run side by side.",
        )
    }

    private fun closeServers() {
        runCatching { socksServer?.close() }
        socksServer = null
        runCatching { httpServer?.close() }
        httpServer = null
        _socksPort.value = null
        _httpPort.value = null
    }

    private fun acceptLoop(name: String, server: ServerSocket, handler: (Socket) -> Unit) {
        thread(name = name, isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break // server closed -> sharing stopped
                }
                // ADMISSION CONTROL, before a thread is spent on the socket.
                if (!admit(client)) {
                    runCatching { client.close() }
                    continue
                }
                thread(name = "$name-conn", isDaemon = true) {
                    try {
                        client.tcpNoDelay = true
                        handler(client)
                    } catch (_: Exception) {
                        // Per-connection errors are non-fatal by design.
                    } finally {
                        liveConnections.decrementAndGet()
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    /**
     * Decides whether a freshly accepted socket may be served at all.
     *
     * Source scope first (it costs nothing and rules out the whole public
     * internet), then the concurrency cap. Both refusals are counted and logged at
     * most once per [REJECT_LOG_INTERVAL_MS] - a scanner hitting the port ten times
     * a second must not be able to fill a size-capped log with its own noise.
     */
    private fun admit(client: Socket): Boolean {
        val remote = client.inetAddress
        if (!LanGuard.isLocalNetworkAddress(remote)) {
            noteRefusal("a source address that is not on a local network")
            return false
        }
        if (liveConnections.get() >= MAX_LIVE_CONNECTIONS) {
            noteRefusal("the $MAX_LIVE_CONNECTIONS simultaneous-connection limit")
            return false
        }
        liveConnections.incrementAndGet()
        return true
    }

    /**
     * Counts a refusal and logs a summary at most once per window.
     *
     * The peer address is deliberately NOT logged: the diagnostics log is the file
     * users are asked to attach to a bug report, and "who else was on your Wi-Fi"
     * is not something it should carry.
     */
    private fun noteRefusal(reason: String) {
        val total = refusals.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastRefusalLogMs < REJECT_LOG_INTERVAL_MS) return
        lastRefusalLogMs = now
        DiagnosticsLog.w(
            TAG,
            "Refused a shared-proxy connection because of $reason ($total refused so far this session).",
        )
    }

    /**
     * SOCKS5 share.
     *
     * Loopback keeps the original byte-for-byte relay: the client's own greeting
     * reaches the engine, so every SOCKS5 feature the engine has (including remote
     * DNS and UDP ASSOCIATE) is served by the engine itself and nothing in this
     * file can get in the way.
     *
     * A remote client is authenticated FIRST (RFC 1929), and only then is the
     * upstream dialled and the rest of the conversation relayed verbatim. The
     * bridge answers the method negotiation itself in that case, then performs the
     * engine's no-auth greeting on the client's behalf, so from the engine's point
     * of view nothing changed.
     */
    private fun serveSocksClient(client: Socket) {
        if (LanGuard.isLoopback(client.inetAddress)) {
            relayToLocalSocks(client, greetUpstream = false)
            return
        }
        if (!authenticateSocks(client)) return
        relayToLocalSocks(client, greetUpstream = true)
    }

    /**
     * RFC 1929 username/password negotiation against the device credential.
     *
     * Only method `0x02` is offered. A client that will not do user/pass gets
     * `0xFF` (no acceptable methods) and is dropped - deliberately NOT a silent
     * fall-through to no-auth, which is how "we added a password" becomes "we
     * added a password unless the attacker prefers not to use it".
     */
    private fun authenticateSocks(client: Socket): Boolean {
        val user = _proxyUser.value
        val password = _proxyPassword.value
        if (password.isBlank()) {
            noteRefusal("a missing proxy credential on this device")
            return false
        }
        return try {
            client.soTimeout = AUTH_TIMEOUT_MS
            val input = client.getInputStream()
            val out = client.getOutputStream()

            val greeting = input.readExact(2) ?: return false
            if (greeting[0] != 5.toByte()) return false
            val methodCount = greeting[1].toInt() and 0xFF
            if (methodCount == 0) return false
            val methods = input.readExact(methodCount) ?: return false
            if (methods.none { it == 0x02.toByte() }) {
                out.write(byteArrayOf(0x05, 0xFF.toByte()))
                out.flush()
                noteRefusal("a remote SOCKS5 client that refused to authenticate")
                return false
            }
            out.write(byteArrayOf(0x05, 0x02))
            out.flush()

            val authHeader = input.readExact(2) ?: return false
            if (authHeader[0] != 0x01.toByte()) return false
            val userLen = authHeader[1].toInt() and 0xFF
            val offeredUser = String(input.readExact(userLen) ?: return false, Charsets.UTF_8)
            val passLen = (input.readExact(1) ?: return false)[0].toInt() and 0xFF
            val offeredPass = String(input.readExact(passLen) ?: return false, Charsets.UTF_8)

            val ok = LanGuard.secretEquals(user, offeredUser) &&
                LanGuard.secretEquals(password, offeredPass)
            out.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
            out.flush()
            if (!ok) noteRefusal("a wrong shared-proxy password")
            // Back to blocking for the relay itself: a long-lived tunnel must not
            // die because it was idle for ten seconds.
            client.soTimeout = 0
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** Relays into the engine's loopback SOCKS5, optionally greeting it first. */
    private fun relayToLocalSocks(client: Socket, greetUpstream: Boolean) {
        val upstream = Socket()
        try {
            upstream.tcpNoDelay = true
            upstream.connect(
                InetSocketAddress(TunnelConfig.SOCKS_HOST, upstreamPort),
                DIAL_TIMEOUT_MS,
            )
            if (greetUpstream && !greetNoAuth(upstream)) return
            relay(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /** The `05 01 00` / `05 00` no-auth handshake the engine expects. */
    private fun greetNoAuth(upstream: Socket): Boolean = runCatching {
        upstream.soTimeout = DIAL_TIMEOUT_MS
        val out = upstream.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val reply = upstream.getInputStream().readExact(2)
        upstream.soTimeout = 0
        reply != null && reply[0] == 5.toByte() && reply[1] == 0.toByte()
    }.getOrDefault(false)

    /** Minimal HTTP proxy: CONNECT tunnels + absolute-form plain requests. */
    private fun serveHttpClient(client: Socket) {
        val input = client.getInputStream()
        val header = readHeaderBlock(input) ?: return
        val lines = header.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 3) return

        val method = parts[0]
        val target = parts[1]

        // A remote client must authenticate before anything is dialled on its
        // behalf. 407 + Proxy-Authenticate is what every OS proxy dialog knows how
        // to answer, so the laptop simply asks for the password.
        if (!LanGuard.isLoopback(client.inetAddress) && !httpAuthorized(lines)) {
            noteRefusal("an unauthenticated HTTP proxy request")
            client.getOutputStream().writeAscii(
                "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"Aether\", charset=\"UTF-8\"\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n",
            )
            return
        }

        if (method.equals("CONNECT", ignoreCase = true)) {
            val host = target.substringBeforeLast(':')
            val port = target.substringAfterLast(':').toIntOrNull() ?: 443
            val upstream = socksOpen(host, port) ?: run {
                client.getOutputStream().writeAscii("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
                return
            }
            try {
                client.getOutputStream().writeAscii("HTTP/1.1 200 Connection Established\r\n\r\n")
                relay(client, upstream)
            } finally {
                runCatching { upstream.close() }
            }
            return
        }

        // Plain HTTP with an absolute URI, e.g. "GET http://example.com/x HTTP/1.1".
        val url = target.removePrefix("http://")
        if (url == target) { // https:// or malformed — TLS must use CONNECT
            client.getOutputStream().writeAscii("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
            return
        }
        val hostPort = url.substringBefore('/')
        val path = "/" + url.substringAfter('/', "")
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "80").toIntOrNull() ?: 80

        val upstream = socksOpen(host, port) ?: run {
            client.getOutputStream().writeAscii("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
            return
        }
        try {
            val rebuilt = buildString {
                append("$method $path ${parts[2]}\r\n")
                lines.drop(1).forEach { line ->
                    if (line.isEmpty()) return@forEach
                    val lower = line.lowercase()
                    if (lower.startsWith("proxy-connection:") ||
                        lower.startsWith("proxy-authorization:") ||
                        lower.startsWith("connection:")
                    ) {
                        return@forEach
                    }
                    append(line).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            upstream.getOutputStream().writeAscii(rebuilt)
            uploadBytesCounter.addAndGet(rebuilt.length.toLong())
            relay(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /**
     * True when the request carries the device's `Proxy-Authorization: Basic`
     * credential.
     *
     * The header is dropped again when the request is rebuilt for the upstream
     * (see [serveHttpClient]), so the proxy password never travels into the
     * tunnel with the user's traffic.
     */
    private fun httpAuthorized(headerLines: List<String>): Boolean {
        val password = _proxyPassword.value
        if (password.isBlank()) return false
        val header = headerLines.firstOrNull {
            it.length > PROXY_AUTH_HEADER.length &&
                it.regionMatches(0, PROXY_AUTH_HEADER, 0, PROXY_AUTH_HEADER.length, ignoreCase = true)
        } ?: return false
        val offered = LanGuard.parseBasicCredential(
            header.substring(PROXY_AUTH_HEADER.length),
        ) ?: return false
        return LanGuard.secretEquals(_proxyUser.value, offered.first) &&
            LanGuard.secretEquals(password, offered.second)
    }

    /** Opens a TCP stream to host:port THROUGH the engine's SOCKS5 proxy. */
    private fun socksOpen(host: String, port: Int): Socket? {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(
                InetSocketAddress(TunnelConfig.SOCKS_HOST, upstreamPort),
                DIAL_TIMEOUT_MS,
            )
            socket.soTimeout = 30_000
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            // Greeting: version 5, one method, no-auth.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greet = inp.readExact(2)
            if (greet == null || greet[0] != 5.toByte() || greet[1] != 0.toByte()) {
                throw IllegalStateException("SOCKS5 greeting failed")
            }

            // CONNECT with a DOMAIN address -> DNS resolves inside the tunnel.
            val hostBytes = host.toByteArray(Charsets.ISO_8859_1)
            val request = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00, 0x03))
                write(hostBytes.size)
                write(hostBytes)
                write((port shr 8) and 0xFF)
                write(port and 0xFF)
            }
            out.write(request.toByteArray())
            out.flush()

            val reply = inp.readExact(4) ?: throw IllegalStateException("SOCKS5 reply truncated")
            if (reply[1] != 0.toByte()) throw IllegalStateException("SOCKS5 connect refused (${reply[1]})")
            val remaining = when (reply[3].toInt()) {
                0x01 -> 4 + 2
                0x03 -> (inp.readExact(1)?.get(0)?.toInt()?.and(0xFF)
                    ?: throw IllegalStateException("SOCKS5 reply truncated")) + 2
                0x04 -> 16 + 2
                else -> throw IllegalStateException("Bad SOCKS5 address type")
            }
            inp.readExact(remaining) ?: throw IllegalStateException("SOCKS5 reply truncated")

            socket.soTimeout = 0
            socket
        } catch (e: Exception) {
            // SECURITY (audit 1.2.7-r2): the destination is NOT logged. One line
            // per failed flow turned the persisted diagnostics log into a partial
            // browsing history, which is the one file users are asked to attach to
            // a bug report. The port is enough to tell a broken upstream from a
            // blocked destination, and the reason still names the failure.
            DiagnosticsLog.e(TAG, "Upstream dial failed (dest port $port) — $e")
            runCatching { socket.close() }
            null
        }
    }

    /** Reads raw bytes up to and including the CRLFCRLF header terminator. */
    private fun readHeaderBlock(input: InputStream): ByteArrayOutputStream? {
        val buf = ByteArrayOutputStream()
        var run = 0
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            run = when {
                b == '\r'.code && (run == 0 || run == 2) -> run + 1
                b == '\n'.code && (run == 1 || run == 3) -> run + 1
                else -> 0
            }
            if (run == 4) return buf
        }
        return null
    }

    /**
     * Full-duplex pipe between the proxy client and the engine; returns when
     * either side ends. Every relayed byte is added to the session traffic
     * counters so the UI meter works in proxy mode too:
     * client -> upstream = upload, upstream -> client = download.
     */
    private fun relay(client: Socket, upstream: Socket) {
        val reverse = thread(isDaemon = true) { pipe(upstream, client, downloadBytesCounter) }
        pipe(client, upstream, uploadBytesCounter)
        runCatching { reverse.join(1_000) }
    }

    private fun pipe(from: Socket, to: Socket, counter: AtomicLong) {
        val buffer = ByteArray(16 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: Exception) {
        } finally {
            runCatching { to.shutdownOutput() }
            runCatching { from.shutdownInput() }
        }
    }

    private fun InputStream.readExact(n: Int): ByteArray? {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            val r = read(out, done, n - done)
            if (r < 0) return null
            done += r
        }
        return out
    }

    private fun OutputStream.writeAscii(s: String) {
        write(s.toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    private const val PROXY_AUTH_HEADER = "proxy-authorization:"

    /** Default username; the real one comes from [setCredentials]. */
    private const val DEFAULT_USER = "aether"
}
