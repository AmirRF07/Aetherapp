package studio.cluvex.aether.transport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The SOCKS5 server that tun2socks talks to in `Aether -> Psiphon` mode.
 *
 * ## ROOT CAUSE this class exists to fix
 *
 * `Aether -> Psiphon` connected and then opened NOTHING. The field log says why,
 * 636 times in a 50-second session:
 *
 * ```
 * 18:15:13.861 Psiphon: Warning: {"message":"SOCKS proxy accept error:
 *              socks5ReadCommand: SOCKS message field command was 0x03, not 0x01"}
 * ...
 * 18:16:03.878 Psiphon: TotalBytesTransferred: {"received":267,"sent":939}
 * ```
 *
 * `0x03` is `UDP ASSOCIATE`. The forwarder this app ships is
 * **hev-socks5-tunnel**, configured with `udp: 'udp'`, so it carries every UDP
 * flow — and therefore **every DNS query the device makes** — over standard
 * SOCKS5 `UDP ASSOCIATE`. psiphon-tunnel-core's local SOCKS proxy implements
 * `CONNECT` only (`socks5ReadCommand` rejects anything that is not `0x01`), so
 * tun2socks was pointed straight at a proxy that refuses the one command name
 * resolution depends on. The result was a fully established Psiphon tunnel with
 * a healthy exit (`exit ip=37.46.121.91 cc=SE`), 267 bytes of real traffic, and
 * not one page that loads. TCP was never broken — DNS never had a path at all.
 *
 * That also explains why the self-test passed: it resolves through a SOCKS5
 * `CONNECT` with a HOSTNAME, which Psiphon resolves remotely, so it never
 * touched the UDP path the rest of the device uses. `Diagnostics` now probes
 * `UDP ASSOCIATE` directly for exactly this reason.
 *
 * The retired Tor backend hit the identical wall and got a front of its own; it
 * went with the rest of the Tor runtime in 1.2.7. Psiphon kept
 * pointing tun2socks at psiphon's own listener because that WAS correct back
 * when this app drove **badvpn tun2socks**, which reaches UDP through a plain
 * `CONNECT` to the udpgw address rather than `UDP ASSOCIATE`. Moving to hev
 * silently removed the only UDP path Psiphon mode had. This class closes that
 * regression.
 *
 * ## What this does instead
 *
 * ```
 *   hev ──CONNECT <ip:port>──────────► relay ──► Psiphon SOCKS ──► tunnel
 *       └─UDP ASSOCIATE──► udp relay ──► udpgw frame ──┘
 *                              (one shared stream, multiplexed by conid)
 *       └─CONNECT 127.0.0.1:7300──► relayed verbatim (badvpn's own udpgw path)
 * ```
 *
 * ### Why udpgw, and not "answer DNS and drop the rest"
 *
 * Tor genuinely could not carry a UDP datagram, so its front had to answer DNS
 * out of Tor's `DNSPort` and drop everything else. **Psiphon can.** Its server
 * intercepts a port forward aimed at exactly `127.0.0.1:7300`
 * (`UDPInterceptUdpgwServerAddress` in the server's `tunnelServer.go`) and does
 * the UDP forwarding remotely — that is how the official Psiphon Android client
 * has always carried UDP. So rather than crippling the mode, this front opens
 * ONE udpgw stream through Psiphon and multiplexes every association onto it by
 * connection id, exactly as badvpn's `SocksUdpGwClient` does. The framing is
 * byte-identical to what the Psiphon server already speaks.
 *
 * ### One protocol is deliberately NOT carried: QUIC
 *
 * udpgw is ONE TCP stream for the whole device. A video stream on UDP/443 both
 * head-of-line-blocks every DNS query behind it AND melts down against the
 * tunnel's own congestion control, which is exactly where "the ping goes over
 * 1000 and everything stops the moment a video plays" came from. UDP/443 is
 * therefore dropped from the first datagram, so browsers and the YouTube player
 * fall straight back to HTTP/2 over TCP where every flow gets its own Psiphon
 * channel with its own flow control. See [SUPPRESS_QUIC].
 *
 * ### And a fallback, because a server may refuse the intercept
 *
 * If that port forward comes back refused (the same log shows Psiphon answering
 * `ssh: rejected: administratively prohibited` for other targets), udpgw is
 * marked unavailable for the rest of the session and port-53 datagrams are
 * answered over **DNS-over-TCP** through the same tunnel (RFC 1035 §4.2.2
 * framing) - and when the server does not allow outbound TCP/53 either, over
 * **DNS-over-HTTPS on 443**, which no Psiphon server blocks. Non-DNS UDP is then
 * dropped. Name resolution therefore never depends on anything the server can
 * refuse, so this mode can no longer fail the way it did.
 *
 * ## Ports
 *
 * Psiphon's own listener moved off the port tun2socks uses. In a chained session
 * the Aether engine owns 1819, this front owns
 * [studio.cluvex.aether.core.TunnelConfig.CHAIN_SOCKS_PORT] (what hev, the share
 * bridge and the self-test all talk to), and Psiphon binds
 * [studio.cluvex.aether.core.TunnelConfig.PSIPHON_SOCKS_PORT] behind it.
 */
object PsiphonSocksFront {

    private const val TAG = "PsiphonSocksFront"

    /** udpgw flags, from badvpn's `protocol/udpgw_proto.h` and psiphon's `server/udpgw.go`. */
    private const val FLAG_KEEPALIVE = 1 shl 0
    private const val FLAG_DNS = 1 shl 2
    private const val FLAG_IPV6 = 1 shl 3

    /**
     * The address the Psiphon server intercepts as a udpgw session.
     *
     * NOT arbitrary and NOT ours to pick: the server compares the requested
     * port-forward address against its configured
     * `UDPInterceptUdpgwServerAddress`, and `127.0.0.1:7300` is the value the
     * Psiphon network runs. Change it and every UDP flow becomes a real dial to
     * a loopback address, which the server rightly refuses.
     */
    private const val UDPGW_HOST = "127.0.0.1"
    private const val UDPGW_PORT = 7300

    private const val SOCKS_VERSION = 5
    private const val CMD_CONNECT = 1
    private const val CMD_UDP_ASSOCIATE = 3
    private const val ATYP_IPV4 = 1
    private const val ATYP_DOMAIN = 3
    private const val ATYP_IPV6 = 4

    private const val REP_SUCCESS = 0
    private const val REP_GENERAL_FAILURE = 1

    /**
     * SOCKS5 `host unreachable`. Used to fail an IPv6 flow INSTANTLY once this
     * exit has proven it has no IPv6 (see [ipv6Usable]): lwIP turns it into an
     * immediate reset, so the app's Happy-Eyeballs timer fires in milliseconds
     * and retries over IPv4 instead of waiting out a Psiphon dial.
     */
    private const val REP_HOST_UNREACHABLE = 4
    private const val REP_CMD_NOT_SUPPORTED = 7

    /**
     * The port QUIC / HTTP-3 uses. Singled out because UDP on this port is what
     * made this mode collapse during video playback - see [SUPPRESS_QUIC] and
     * `docs/PSIPHON_MEDIA_STALL.md`.
     */
    private const val QUIC_PORT = 443

    private const val RELAY_BUFFER = 32 * 1024
    private const val PSIPHON_CONNECT_TIMEOUT_MS = 30_000
    private const val DNS_TIMEOUT_MS = 10_000
    private const val DNS_BUFFER = 4096

    /**
     * DNS-over-HTTPS resolver used when this server refuses outbound TCP/53.
     *
     * Sent as a HOSTNAME so PSIPHON resolves it inside the tunnel (this front
     * never resolves anything itself - that would leak the name to the carrier's
     * resolver). Port 443 is the one port every Psiphon server allows out, which
     * is the entire point: it removes the last way for name resolution to fail in
     * this mode.
     */
    private const val DOH_HOST = "cloudflare-dns.com"
    private const val DOH_PORT = 443
    private const val DOH_PATH = "/dns-query"
    private const val UDP_BUFFER = 8192
    private const val UDP_POLL_MS = 1_000

    /**
     * Concurrent in-flight DNS-over-TCP lookups.
     *
     * Fallback path only, and each task holds one Psiphon port forward for up to
     * [DNS_TIMEOUT_MS]. Sized like the retired Tor front's pool: a cold page load
     * easily wants a dozen names at once, and queueing them behind each other
     * times the page out.
     */
    private const val DNS_POOL_SIZE = 16

    /**
     * How many udpgw connection ids are remembered.
     *
     * badvpn reuses a conid for as long as a flow lives and the server keeps its
     * remote socket bound to it, so the mapping has to outlive a single
     * request/response pair. Bounded because a long session on a busy device
     * would otherwise keep one entry per UDP flow forever.
     */
    private const val MAX_UDPGW_FLOWS = 2048

    /** Keeps the shared udpgw port forward from being reaped as idle. */
    private const val UDPGW_KEEPALIVE_MS = 20_000L

    /**
     * Do NOT carry QUIC (UDP/443) over udpgw.
     *
     * ## ROOT CAUSE this fixes (the "video makes the ping jump over 1000 ms and
     * everything stops" report)
     *
     * udpgw is ONE TCP port forward for the WHOLE device, and every UDP flow is
     * multiplexed onto it. That is fine for DNS-sized datagrams and it is what
     * badvpn does. It is catastrophic for a video stream:
     *
     *  - **Head-of-line blocking.** A 4 Mbit/s QUIC flow and every DNS query the
     *    device makes share one byte stream. While a video segment is in the
     *    write queue, name resolution is stuck behind it. That alone explains a
     *    four-figure ping the moment playback starts.
     *  - **Congestion control fighting itself.** QUIC runs its own loss-based
     *    congestion control, and a reliable TCP tunnel HIDES loss from it. So
     *    QUIC keeps opening its window, the tunnel keeps buffering, and the RTT
     *    inflates without bound until the player's own timers give up. This is
     *    the classic TCP-over-TCP meltdown, and no amount of tuning inside this
     *    class fixes it, because the transport underneath is the wrong shape for
     *    the traffic.
     *
     * Dropping UDP/443 from the FIRST datagram makes every browser and the
     * YouTube player fall straight back to HTTP/2 over TLS, where each flow gets
     * its OWN Psiphon channel with its own flow control - which is exactly the
     * shape this tunnel is good at. Consistency is the point: intermittently
     * working QUIC is far worse than QUIC that never works, because Chromium
     * caches "HTTP/3 works for this origin" and then spends seconds per request
     * waiting for a handshake that a rotated server has silently black-holed.
     *
     * DNS, and every other UDP protocol (VoIP, game traffic, NTP), still ride
     * udpgw untouched.
     */
    private const val SUPPRESS_QUIC = true

    /**
     * Frames the udpgw writer may hold before it starts dropping.
     *
     * Bulk UDP is dropped OLDEST-FIRST, which is the correct behaviour for a
     * datagram service and the whole reason the queue exists: the datagram pump
     * must never block on a congested tunnel, because it is the only reader of
     * the forwarder's UDP socket and blocking it stalls UDP for the entire
     * device - DNS included.
     */
    private const val UDPGW_BULK_QUEUE = 256

    /** Priority (DNS + keepalive) frames queued before dropping. Never reached in practice. */
    private const val UDPGW_PRIORITY_QUEUE = 512

    /**
     * IPv6 dials allowed to fail before IPv6 is declared dead for this exit.
     *
     * ## ROOT CAUSE this fixes (the rotation storm)
     *
     * The TUN advertises an IPv6 address and a `::/0` route (IPv6 leak
     * protection), so the device happily prefers AAAA records. Psiphon's exit
     * servers are IPv4-only in practice, so every one of those flows comes back
     * `ssh: rejected: administratively prohibited`. Ordinary browsing barely
     * notices. Opening a video does: the player fans out dozens of segment
     * connections at once, and the field log shows 26 refusals in 4.2 s the
     * moment playback started - which [PsiphonHealth] then read as "this server
     * censors" and answered by tearing the tunnel down.
     *
     * Two flows are spent proving it (one is not evidence, a single destination
     * can legitimately be down), then IPv6 is refused locally and instantly for
     * the rest of the session. Re-probed once after a server rotation, because
     * the verdict describes the EXIT, not the tunnel.
     */
    private const val IPV6_PROBE_BUDGET = 2

    private val running = AtomicBoolean(false)

    /**
     * Session byte counters, kept for the same reason the Tor front kept
     * them: Psiphon's `onBytesTransferred` reports the tunnel's own totals, not
     * what this pipeline carried, and a Connected badge over a tunnel moving 0 B
     * is precisely the failure this class exists to make impossible to miss.
     */
    private val txBytes = AtomicLong(0)
    private val rxBytes = AtomicLong(0)

    val sessionTx: Long get() = txBytes.get()
    val sessionRx: Long get() = rxBytes.get()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var psiphonSocksPort: Int = 0
    @Volatile private var connPool: ExecutorService? = null
    @Volatile private var dnsPool: ExecutorService? = null

    /** Null until the first UDP flow, then a live session or a remembered refusal. */
    @Volatile private var udpgw: UdpgwSession? = null
    private val udpgwRefused = AtomicBoolean(false)
    private val udpgwLock = Any()

    /**
     * False once this exit has proven it cannot dial IPv6 ([IPV6_PROBE_BUDGET]
     * refusals). While false, IPv6 flows are refused HERE, in microseconds,
     * instead of being sent on a round trip that ends in a refusal anyway.
     */
    private val ipv6Usable = AtomicBoolean(true)
    private val ipv6Refusals = AtomicInteger(0)

    /**
     * True once this server has refused a TCP dial to port 53.
     *
     * A great many Psiphon servers do not allow port 53 out (it is not in their
     * `AllowTCPPorts` traffic rule), so the DNS-over-TCP fallback turns every
     * single lookup into one refused port forward. In the field log that is the
     * 110-refusals-in-73-seconds storm on the second server, and it fed straight
     * back into [PsiphonHealth] as fake evidence of censorship. Once this is set,
     * DNS goes over DNS-over-HTTPS on 443 instead - a port no Psiphon server
     * blocks.
     */
    private val dnsPort53Refused = AtomicBoolean(false)

    /** Session counters for the diagnostics panel; cheap and worth having. */
    private val quicDropped = AtomicLong(0)
    private val bulkUdpDropped = AtomicLong(0)
    private val ipv6Refused = AtomicLong(0)

    val isRunning: Boolean get() = running.get()

    /** True when UDP is riding a real udpgw session, so DNS and VoIP work, not only DNS. */
    val udpgwActive: Boolean get() = udpgw?.isAlive == true

    /** True while this exit is still believed to be able to dial IPv6. */
    val ipv6Reachable: Boolean get() = ipv6Usable.get()

    /** `null` when nothing was dropped, else a one-line summary for the log/UI. */
    fun dropSummary(): String? {
        val quic = quicDropped.get()
        val bulk = bulkUdpDropped.get()
        val v6 = ipv6Refused.get()
        if (quic == 0L && bulk == 0L && v6 == 0L) return null
        return "udp/443 (QUIC) dropped=$quic, congested udpgw frames dropped=$bulk, " +
            "IPv6 flows refused locally=$v6"
    }

    /**
     * Bind the front-end.
     *
     * @param listenPort what tun2socks / the share bridge dials as its SOCKS server.
     * @param socksPort Psiphon's own `LocalSocksProxyPort`.
     */
    @Synchronized
    fun start(listenPort: Int, socksPort: Int): Boolean {
        if (running.get()) {
            ConnectionLog.record("$TAG already running")
            return true
        }
        psiphonSocksPort = socksPort

        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort))
            }
        } catch (e: Exception) {
            ConnectionLog.record("$TAG could not bind 127.0.0.1:$listenPort: ${e.message}")
            return false
        }

        serverSocket = server
        connPool = Executors.newCachedThreadPool()
        dnsPool = Executors.newFixedThreadPool(DNS_POOL_SIZE)
        udpgw = null
        udpgwRefused.set(false)
        ipv6Usable.set(true)
        ipv6Refusals.set(0)
        dnsPort53Refused.set(false)
        quicDropped.set(0)
        bulkUdpDropped.set(0)
        ipv6Refused.set(0)
        // Zeroed per session so the service's traffic deltas start from a known
        // baseline; a restart must not look like a sudden burst.
        txBytes.set(0)
        rxBytes.set(0)
        running.set(true)

        Thread({
            // The accept loop outlives anything a single connection can do. It
            // is a bare thread, so an escape here would reach the process-wide
            // handler and kill the app rather than merely stop accepting.
            try {
                while (running.get()) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (running.get()) ConnectionLog.record("$TAG accept failed: ${e.message}")
                        break
                    }
                    val pool = connPool
                    if (pool == null) {
                        closeQuietly(client)
                        break
                    }
                    try {
                        pool.execute {
                            // A pool task's uncaught exception reaches the worker
                            // thread's default handler and kills the process,
                            // exactly like a bare Thread. handleClient guards
                            // itself, but the guarantee belongs here so it cannot
                            // be lost by an edit inside it.
                            try {
                                handleClient(client)
                            } catch (_: Throwable) {
                                closeQuietly(client)
                            }
                        }
                    } catch (e: Exception) {
                        // Pool shut down between accept and submit.
                        closeQuietly(client)
                    }
                }
            } catch (t: Throwable) {
                ConnectionLog.record("$TAG accept loop ended: ${t.message}")
            }
        }, "psiphon-front-accept").apply { isDaemon = true }.start()

        ConnectionLog.record(
            "$TAG listening on 127.0.0.1:$listenPort → Psiphon SOCKS $socksPort, " +
                "UDP via udpgw $UDPGW_HOST:$UDPGW_PORT"
        )
        return true
    }

    /**
     * Re-points the front at a new Psiphon `LocalSocksProxyPort`.
     *
     * A server rotation restarts the Psiphon library in place, and the restarted
     * controller may bind a different local port (the old one can still be in
     * `TIME_WAIT`). The front's own listener must NOT be rebuilt for that:
     * tun2socks holds an open connection to it, and closing it is exactly the
     * disconnect the rotation exists to avoid. So only the upstream target moves.
     *
     * Existing relays keep the socket they already have; every new connection
     * uses the new port. A no-op when the port has not changed or the front is
     * not running.
     */
    @Synchronized
    fun retarget(socksPort: Int) {
        if (!running.get()) return
        if (socksPort <= 0 || socksPort == psiphonSocksPort) return
        val previous = psiphonSocksPort
        psiphonSocksPort = socksPort
        synchronized(udpgwLock) {
            udpgw?.close()
            udpgw = null
        }
        udpgwRefused.set(false)
        ConnectionLog.record(
            "$TAG upstream Psiphon SOCKS moved $previous -> $socksPort; the front keeps its own " +
                "listener so tun2socks never sees a break",
        )
    }

    /**
     * Called after [PsiphonHealth] moves the session to a different server.
     *
     * The udpgw refusal is remembered for a whole session on purpose - retrying
     * a refused intercept per datagram means one doomed port forward per UDP
     * packet. But that memory describes the SERVER that refused it, not the
     * tunnel: keeping it after a rotation would leave a perfectly capable
     * replacement server permanently downgraded to DNS-over-TCP with all non-DNS
     * UDP dropped, so QUIC would stay broken for the rest of the session for no
     * reason at all.
     *
     * The dead session's socket is closed and the flag cleared; the next datagram
     * opens a fresh udpgw stream through the new server. Safe to call at any
     * time: with the front stopped this is a no-op.
     */
    @Synchronized
    fun onServerRotated() {
        if (!running.get()) return
        synchronized(udpgwLock) {
            udpgw?.close()
            udpgw = null
        }
        val wasRefused = udpgwRefused.getAndSet(false)
        // The IPv6 and port-53 verdicts describe the EXIT SERVER, not the
        // tunnel, so a rotation earns the replacement one fresh probe each.
        // Without this a v6-capable or 53-capable server would stay permanently
        // downgraded because of its predecessor's limitations.
        ipv6Usable.set(true)
        ipv6Refusals.set(0)
        dnsPort53Refused.set(false)
        ConnectionLog.record(
            if (wasRefused) {
                "$TAG server rotated - clearing the udpgw refusal so the new server gets " +
                    "a fresh chance at real UDP (DNS + QUIC)"
            } else {
                "$TAG server rotated - udpgw session dropped; the next datagram reopens it"
            },
        )
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        closeQuietly(serverSocket)
        serverSocket = null
        synchronized(udpgwLock) {
            udpgw?.close()
            udpgw = null
        }
        udpgwRefused.set(false)
        ipv6Usable.set(true)
        ipv6Refusals.set(0)
        dnsPort53Refused.set(false)
        dropSummary()?.let { ConnectionLog.record("$TAG session drops: $it") }
        connPool?.shutdownNow()
        connPool = null
        dnsPool?.shutdownNow()
        dnsPool = null
        ConnectionLog.record("$TAG stopped")
    }

    // ---------------------------------------------------------------- SOCKS5

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = BufferedOutputStream(client.getOutputStream())

            // Greeting. Both forwarders offer "no authentication" only, because
            // no credentials are ever passed to a loopback front.
            val version = input.read()
            if (version != SOCKS_VERSION) {
                closeQuietly(client)
                return
            }
            val methodCount = input.read()
            if (methodCount <= 0) {
                closeQuietly(client)
                return
            }
            val methods = ByteArray(methodCount)
            input.readFully(methods)
            if (methods.none { it.toInt() == 0 }) {
                // 0xFF = no acceptable method.
                output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0xFF.toByte()))
                output.flush()
                closeQuietly(client)
                return
            }
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0x00))
            output.flush()

            // Request.
            if (input.read() != SOCKS_VERSION) {
                closeQuietly(client)
                return
            }
            val command = input.read()
            input.read() // reserved
            val addressType = input.read()

            val host: String
            when (addressType) {
                ATYP_IPV4 -> {
                    val raw = ByteArray(4).also { input.readFully(it) }
                    host = InetAddress.getByAddress(raw).hostAddress ?: ""
                }
                ATYP_IPV6 -> {
                    val raw = ByteArray(16).also { input.readFully(it) }
                    host = InetAddress.getByAddress(raw).hostAddress ?: ""
                }
                ATYP_DOMAIN -> {
                    val length = input.read()
                    if (length <= 0) {
                        closeQuietly(client)
                        return
                    }
                    val raw = ByteArray(length).also { input.readFully(it) }
                    host = String(raw, Charsets.US_ASCII)
                }
                else -> {
                    replyFailure(output, REP_GENERAL_FAILURE)
                    closeQuietly(client)
                    return
                }
            }
            val port = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)

            if (command == CMD_UDP_ASSOCIATE) {
                // hev's UDP path — the command Psiphon's own listener refuses,
                // and the whole reason this front exists.
                serveUdpAssociate(client, input, output)
                return
            }

            if (command != CMD_CONNECT) {
                // BIND: no forwarder this app uses asks for it, and Psiphon
                // cannot offer it.
                replyFailure(output, REP_CMD_NOT_SUPPORTED)
                closeQuietly(client)
                return
            }

            // FAST-FAIL IPv6 once this exit has proven it has none. Sending the
            // flow anyway costs a full round trip to the Psiphon server and comes
            // back refused, and a video player opens dozens of those at once -
            // which is what the field log recorded as 26 refusals in 4.2 s and
            // what made the watchdog convict an innocent server. Answering here
            // instead lets the app's Happy-Eyeballs fallback pick IPv4 at once.
            if (isIpv6Literal(host) && !ipv6Usable.get()) {
                ipv6Refused.incrementAndGet()
                replyFailure(output, REP_HOST_UNREACHABLE)
                closeQuietly(client)
                return
            }

            // A CONNECT to the udpgw address needs no special case: relaying it
            // verbatim is exactly what badvpn's own udpgw client wants, and the
            // Psiphon server intercepts the port forward on arrival.
            relayThroughPsiphon(client, host, port, output)
        } catch (e: Exception) {
            closeQuietly(client)
        }
    }

    private fun replySuccess(output: OutputStream) {
        // BND.ADDR/BND.PORT are ignored by tun2socks for CONNECT, so a zero
        // IPv4 address is fine and is what most SOCKS servers emit.
        output.write(
            byteArrayOf(
                SOCKS_VERSION.toByte(), REP_SUCCESS.toByte(), 0, ATYP_IPV4.toByte(),
                0, 0, 0, 0, 0, 0,
            )
        )
        output.flush()
    }

    private fun replyFailure(output: OutputStream, code: Int) {
        try {
            output.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(), code.toByte(), 0, ATYP_IPV4.toByte(),
                    0, 0, 0, 0, 0, 0,
                )
            )
            output.flush()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------- TCP relay

    /**
     * A live port forward through Psiphon.
     *
     * The buffered streams travel WITH the socket and are never re-derived: the
     * SOCKS5 reply was read through a BufferedInputStream, so a fresh
     * `getInputStream()` would resume after whatever that buffer already
     * swallowed and silently lose the first bytes of the payload.
     */
    private class PsiphonStream(
        val socket: Socket,
        val input: DataInputStream,
        val output: BufferedOutputStream,
    )

    private fun isIpLiteral(host: String): Boolean =
        host.indexOf(':') >= 0 || // bare IPv6
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) // IPv4

    /** A bare IPv6 literal. tun2socks is transparent, so this is the only form seen. */
    private fun isIpv6Literal(host: String): Boolean = host.indexOf(':') >= 0

    /**
     * Records one refused IPv6 dial and latches the verdict once the probe budget
     * is spent.
     *
     * Deliberately NOT reported to [PsiphonHealth]: a refused IPv6 dial says the
     * exit has no IPv6, which is true of nearly every Psiphon server and is not
     * censorship. Counting it as censorship is precisely how a healthy tunnel got
     * torn down mid-video.
     */
    private fun noteIpv6Refusal(host: String, port: Int) {
        ipv6Refused.incrementAndGet()
        if (!ipv6Usable.get()) return
        if (ipv6Refusals.incrementAndGet() < IPV6_PROBE_BUDGET) return
        if (!ipv6Usable.compareAndSet(true, false)) return
        ConnectionLog.record(
            "$TAG this exit cannot dial IPv6 (refused $host:$port) - IPv6 flows are now refused " +
                "locally and instantly so apps fall back to IPv4 without waiting. This is a " +
                "property of the Psiphon exit, not censorship, and is not counted against the " +
                "server.",
        )
    }

    /**
     * Decides whether one Psiphon refusal is evidence of a FILTERING server.
     *
     * Three refusal classes are structural rather than political, and reporting
     * them as censorship is what produced the rotation storm in the field log:
     *
     *  - **IPv6** - the exit has no IPv6. Handled by [noteIpv6Refusal].
     *  - **The udpgw intercept** - the server simply is not configured for it,
     *    which the front already handles by falling back.
     *  - **TCP port 53** - most servers do not allow it out at all, so the
     *    DNS-over-TCP fallback would convict every server it ever ran on.
     */
    private fun reportRefusal(host: String, port: Int) {
        if (isIpv6Literal(host)) {
            noteIpv6Refusal(host, port)
            return
        }
        if (host == UDPGW_HOST && port == UDPGW_PORT) return
        if (port == 53) {
            if (dnsPort53Refused.compareAndSet(false, true)) {
                ConnectionLog.record(
                    "$TAG this server does not allow outbound TCP/53 - DNS now goes over " +
                        "DNS-over-HTTPS on 443 through the same tunnel instead of burning one " +
                        "refused port forward per lookup.",
                )
            }
            return
        }
        PsiphonHealth.onDestinationRefused(host, port)
    }

    /**
     * SOCKS5 handshake + CONNECT against Psiphon's listener.
     *
     * @param refusal optional one-element array that receives Psiphon's own
     *   reply code, so a caller can tell "the server will not do this" (a real
     *   refusal worth remembering) apart from "the proxy is not reachable".
     */
    private fun openPsiphonStream(
        host: String,
        port: Int,
        refusal: IntArray? = null,
    ): PsiphonStream? {
        val upstream = try {
            Socket().apply {
                tcpNoDelay = true
                connect(
                    InetSocketAddress("127.0.0.1", psiphonSocksPort),
                    PSIPHON_CONNECT_TIMEOUT_MS,
                )
            }
        } catch (e: Exception) {
            return null
        }
        try {
            val upIn = DataInputStream(BufferedInputStream(upstream.getInputStream()))
            val upOut = BufferedOutputStream(upstream.getOutputStream())

            upOut.write(byteArrayOf(SOCKS_VERSION.toByte(), 1, 0x00))
            upOut.flush()
            if (upIn.read() != SOCKS_VERSION || upIn.read() != 0x00) {
                closeQuietly(upstream)
                return null
            }

            // Hand the target over exactly as it arrived. An IP literal goes as
            // an IP (the common case: tun2socks is transparent and has no
            // names); a HOSTNAME goes as ATYP_DOMAIN so PSIPHON resolves it
            // inside the tunnel — resolving it here would leak the name to the
            // carrier's resolver and defeat the point of the hop.
            // ⚠️ The first three bytes are load-bearing and easy to lose in a
            // refactor: ByteArray() is zero-filled, so omitting them sends
            // `00 00 00 …`, which any SOCKS5 server reads as "version 0" and
            // rejects — for every single flow.
            val request: ByteArray = if (isIpLiteral(host)) {
                val addr = InetAddress.getByName(host).address
                val atyp = if (addr.size == 16) ATYP_IPV6 else ATYP_IPV4
                ByteArray(4 + addr.size + 2).apply {
                    this[0] = SOCKS_VERSION.toByte()
                    this[1] = CMD_CONNECT.toByte()
                    this[2] = 0
                    this[3] = atyp.toByte()
                    System.arraycopy(addr, 0, this, 4, addr.size)
                    this[4 + addr.size] = ((port shr 8) and 0xFF).toByte()
                    this[5 + addr.size] = (port and 0xFF).toByte()
                }
            } else {
                val name = host.toByteArray(Charsets.US_ASCII)
                if (name.size > 255) {
                    closeQuietly(upstream)
                    return null
                }
                ByteArray(5 + name.size + 2).apply {
                    this[0] = SOCKS_VERSION.toByte()
                    this[1] = CMD_CONNECT.toByte()
                    this[2] = 0
                    this[3] = ATYP_DOMAIN.toByte()
                    this[4] = name.size.toByte()
                    System.arraycopy(name, 0, this, 5, name.size)
                    this[5 + name.size] = ((port shr 8) and 0xFF).toByte()
                    this[6 + name.size] = (port and 0xFF).toByte()
                }
            }
            upOut.write(request)
            upOut.flush()

            // Psiphon's reply. Read it fully so the stream sits at the payload.
            if (upIn.read() != SOCKS_VERSION) {
                closeQuietly(upstream)
                return null
            }
            val reply = upIn.read()
            upIn.read() // reserved
            when (upIn.read()) {
                ATYP_IPV4 -> upIn.readFully(ByteArray(4))
                ATYP_IPV6 -> upIn.readFully(ByteArray(16))
                ATYP_DOMAIN -> {
                    val length = upIn.read()
                    if (length > 0) upIn.readFully(ByteArray(length))
                }
            }
            upIn.readFully(ByteArray(2)) // bound port

            refusal?.set(0, reply)
            if (reply != REP_SUCCESS) {
                closeQuietly(upstream)
                return null
            }
            return PsiphonStream(upstream, upIn, upOut)
        } catch (e: Exception) {
            closeQuietly(upstream)
            return null
        }
    }

    private fun relayThroughPsiphon(
        client: Socket,
        host: String,
        port: Int,
        clientOut: OutputStream,
    ) {
        val refusal = IntArray(1) { -1 }
        val stream = openPsiphonStream(host, port, refusal)
        if (stream == null) {
            // Pass Psiphon's own code back when there is one, so lwIP resets
            // this single flow instead of hammering a target the server will
            // not dial.
            if (refusal[0] > 0) {
                // A REPLY, not a dead proxy: this server declined to dial this
                // destination ("ssh: rejected: administratively prohibited").
                // One refusal is ordinary internet; a server that refuses many
                // DIFFERENT destinations is filtering, which is what makes
                // Telegram work while Google does not. [PsiphonHealth] decides -
                // but only for refusals that can actually MEAN censorship, see
                // [reportRefusal].
                reportRefusal(host, port)
            }
            replyFailure(clientOut, if (refusal[0] > 0) refusal[0] else REP_GENERAL_FAILURE)
            closeQuietly(client)
            return
        }
        val upstream = stream.socket

        try {
            replySuccess(clientOut)

            // getInputStream() is resolved HERE, on the owning thread, and NOT
            // as the first statement of the pump lambda. Either direction
            // finishing closes both sockets and stop() closes all of them at
            // once, so that call can throw "Socket is closed" — and a bare
            // thread's uncaught throw reaches the process-wide handler and kills
            // the app. Resolved here it lands in the catch below instead: one
            // dead flow, and a tunnel that stays up.
            val clientIn = client.getInputStream()
            Thread({
                try {
                    pipe(clientIn, stream.output, txBytes)
                } catch (_: Throwable) {
                    // Per-flow and unreportable, but never fatal. A relay thread
                    // for one TCP flow must not be able to end the session.
                } finally {
                    closeQuietly(upstream)
                    closeQuietly(client)
                }
            }, "psiphon-front-up").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ -> }
            }.start()

            pipe(stream.input, clientOut, rxBytes)
        } catch (e: Exception) {
            // Nothing useful to report per-flow; lwIP resets the stream.
        } finally {
            closeQuietly(upstream)
            closeQuietly(client)
        }
    }

    private fun pipe(from: InputStream, to: OutputStream, counter: AtomicLong) {
        val buffer = ByteArray(RELAY_BUFFER)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
                counter.addAndGet(read.toLong())
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------- SOCKS5 UDP ASSOCIATE

    /**
     * Serves one `UDP ASSOCIATE` association.
     *
     * Per RFC 1928 §7 the association lives exactly as long as the TCP control
     * connection that requested it, so this method holds that connection open
     * and tears the UDP socket down with it. The datagram pump runs on its own
     * thread; this one just holds the door.
     */
    private fun serveUdpAssociate(client: Socket, input: DataInputStream, output: OutputStream) {
        val relay = try {
            DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).apply {
                soTimeout = UDP_POLL_MS
            }
        } catch (e: Exception) {
            ConnectionLog.record("$TAG could not open a UDP relay socket: ${e.message}")
            replyFailure(output, REP_GENERAL_FAILURE)
            closeQuietly(client)
            return
        }

        // BND.ADDR/BND.PORT is where the client must send its datagrams. Unlike
        // the CONNECT reply this one is NOT ignored — a zero address here means
        // the forwarder never sends a single packet.
        val bound = relay.localPort
        try {
            output.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(), REP_SUCCESS.toByte(), 0, ATYP_IPV4.toByte(),
                    127, 0, 0, 1,
                    ((bound shr 8) and 0xFF).toByte(), (bound and 0xFF).toByte(),
                )
            )
            output.flush()
        } catch (e: Exception) {
            runCatching { relay.close() }
            closeQuietly(client)
            return
        }

        val pump = Thread({
            try {
                pumpUdpAssociate(relay)
            } catch (_: Throwable) {
                // Per-association and never fatal.
            } finally {
                runCatching { relay.close() }
            }
        }, "psiphon-front-udp")
        pump.isDaemon = true
        pump.setUncaughtExceptionHandler { _, _ -> }
        pump.start()

        try {
            client.soTimeout = UDP_POLL_MS
            val scratch = ByteArray(512)
            while (running.get()) {
                try {
                    if (input.read(scratch) < 0) break
                } catch (_: java.net.SocketTimeoutException) {
                    // Expected: nothing is ever sent on the control connection.
                }
            }
        } catch (e: Exception) {
            // Control connection gone — the association is over.
        } finally {
            runCatching { relay.close() }
            udpgw?.releaseFlowsFor(relay)
            closeQuietly(client)
        }
    }

    private fun pumpUdpAssociate(relay: DatagramSocket) {
        val buffer = ByteArray(UDP_BUFFER)
        while (running.get() && !relay.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                relay.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                break
            }
            // Copied out synchronously: the shared buffer is reused by the very
            // next receive, so handing a slice of it to a pool would race.
            val datagram = buffer.copyOf(packet.length)
            val from = InetSocketAddress(packet.address, packet.port)
            val request = parseUdpRequest(datagram) ?: continue
            if (request.payload.isEmpty()) continue

            // POLICY, before anything is carried. Both of these are drops on
            // purpose: UDP is a lossy service by contract, and a datagram that
            // is dropped here costs nothing, while the same datagram carried
            // over a single shared TCP stream costs every OTHER flow on the
            // device its latency. See [SUPPRESS_QUIC] and [IPV6_PROBE_BUDGET].
            if (SUPPRESS_QUIC && request.port == QUIC_PORT) {
                quicDropped.incrementAndGet()
                continue
            }
            if (request.isIpv6 && !ipv6Usable.get()) {
                ipv6Refused.incrementAndGet()
                continue
            }

            // Preferred path: real UDP through Psiphon's remote udpgw.
            if (request.address != null) {
                val session = udpgwSession()
                if (session != null && session.send(relay, from, request)) {
                    txBytes.addAndGet(request.payload.size.toLong())
                    continue
                }
            }

            // Fallback: DNS must work even when the server refuses the
            // intercept, otherwise the device resolves nothing and this mode is
            // right back to "connected, opens nothing".
            if (request.port != 53) continue
            val pool = dnsPool ?: break
            try {
                pool.execute {
                    // Same reason as the connection pool in start(): an uncaught
                    // throw here would kill the process, not just this query.
                    try {
                        answerDnsQuery(relay, from, request)
                    } catch (_: Throwable) {
                    }
                }
            } catch (e: Exception) {
                break // pool shut down
            }
        }
    }

    /**
     * One parsed `UDP ASSOCIATE` datagram.
     *
     * [header] is kept VERBATIM rather than rebuilt: the reply has to echo the
     * destination the client asked for, and re-encoding an address is a needless
     * chance to get the byte order wrong. [address] is the raw destination
     * `addr || port` for the udpgw frame, or null for a hostname — which cannot
     * be udpgw'd, because udpgw carries addresses only.
     */
    private class UdpRequest(
        val header: ByteArray,
        val address: ByteArray?,
        val isIpv6: Boolean,
        val port: Int,
        val payload: ByteArray,
    )

    /** `RSV(2) FRAG(1) ATYP(1) ADDR PORT(2) payload` — RFC 1928 §7. */
    private fun parseUdpRequest(datagram: ByteArray): UdpRequest? {
        if (datagram.size < 10) return null
        // Fragmentation is optional in the RFC and implemented by nobody; a
        // non-zero FRAG is safer to drop than to guess at.
        if (datagram[2].toInt() != 0) return null
        val atyp = datagram[3].toInt() and 0xFF
        val addressLength = when (atyp) {
            ATYP_IPV4 -> 4
            ATYP_IPV6 -> 16
            ATYP_DOMAIN -> (datagram[4].toInt() and 0xFF) + 1
            else -> return null
        }
        val portOffset = 4 + addressLength
        if (datagram.size < portOffset + 2) return null
        val port = ((datagram[portOffset].toInt() and 0xFF) shl 8) or
            (datagram[portOffset + 1].toInt() and 0xFF)
        val headerLength = portOffset + 2
        // udpgw carries `addr || port`, both in NETWORK byte order, which is
        // exactly the layout already sitting in the SOCKS5 header — so it is
        // copied out rather than re-encoded. Byte-swapping it would make the
        // server's own address comparison reject every reply.
        val address = when (atyp) {
            ATYP_IPV4, ATYP_IPV6 -> datagram.copyOfRange(4, headerLength)
            else -> null
        }
        return UdpRequest(
            header = datagram.copyOfRange(0, headerLength),
            address = address,
            isIpv6 = atyp == ATYP_IPV6,
            port = port,
            payload = datagram.copyOfRange(headerLength, datagram.size),
        )
    }

    // ---------------------------------------------------- udpgw over Psiphon

    /** One UDP flow multiplexed onto the shared udpgw stream. */
    private class UdpgwFlow(
        val relay: DatagramSocket,
        val client: InetSocketAddress,
        val header: ByteArray,
    )

    /**
     * The single udpgw port forward, shared by every association.
     *
     * One stream for the whole device is what badvpn does and what the Psiphon
     * server expects. A stream per flow would mean a port forward per UDP flow,
     * which on a phone browsing normally is hundreds of them.
     *
     * Wire format, from `protocol/packetproto.h` + `protocol/udpgw_proto.h`:
     *
     * ```
     *   uint16 LE  length of everything that follows
     *   uint8      flags        (KEEPALIVE 1, REBIND 2, DNS 4, IPV6 8)
     *   uint16 LE  conid
     *   [ipv4] uint32 addr, uint16 port      <- both NETWORK byte order
     *   [ipv6] uint8[16] addr, uint16 port
     *   uint8[]    UDP payload
     * ```
     */
    private class UdpgwSession(
        private val socket: Socket,
        private val input: DataInputStream,
        private val output: OutputStream,
    ) {
        private val alive = AtomicBoolean(true)
        private val nextConid = AtomicInteger(1)

        /**
         * Outbound frames waiting for the writer thread.
         *
         * ## ROOT CAUSE this replaces
         *
         * Every frame used to be written STRAIGHT from the datagram pump, under a
         * single lock, with a blocking `write` + `flush`. A congested tunnel
         * therefore blocked the pump - and the pump is the only reader of the
         * forwarder's UDP socket, so UDP stopped for the WHOLE DEVICE, DNS
         * included, for as long as the tunnel stayed congested. Under a video
         * stream that is continuously. That is the mechanism behind "the ping
         * goes over 1000 and everything freezes".
         *
         * Now: the pump only ever enqueues (never blocks), one dedicated thread
         * does the blocking I/O, and DNS has its own lane that a saturated bulk
         * flow cannot get in front of. When [UDPGW_BULK_QUEUE] is full, bulk
         * frames are dropped OLDEST-first - correct for a datagram service, and
         * infinitely better than stalling name resolution.
         */
        private val queueLock = ReentrantLock()
        private val notEmpty = queueLock.newCondition()
        private val priorityFrames = ArrayDeque<ByteArray>()
        private val bulkFrames = ArrayDeque<ByteArray>()

        /** conid -> flow. Bounded and insertion-ordered; the oldest is evicted. */
        private val flows = Collections.synchronizedMap(
            object : LinkedHashMap<Int, UdpgwFlow>(256, 0.75f, false) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Int, UdpgwFlow>?,
                ): Boolean = size > MAX_UDPGW_FLOWS
            }
        )

        /**
         * Flow key -> conid, so a retransmit reuses the server's remote socket.
         *
         * BOUNDED, and bounded TOGETHER with [flows] (a plain ConcurrentHashMap
         * here grew one entry per UDP flow for the life of the session, and its
         * entries outlived the flow map they pointed into - so a long session
         * leaked, and an evicted conid could be handed out again while the server
         * still had a socket bound to it). Access-ordered, so the key that gets
         * dropped is the one nothing has used for longest, and dropping it also
         * drops the matching flow.
         */
        private val conids = Collections.synchronizedMap(
            object : LinkedHashMap<String, Int>(256, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Int>?,
                ): Boolean {
                    if (size <= MAX_UDPGW_FLOWS) return false
                    eldest?.value?.let { flows.remove(it) }
                    return true
                }
            }
        )

        val isAlive: Boolean get() = alive.get() && !socket.isClosed

        fun startReader() {
            Thread({
                try {
                    readLoop()
                } catch (_: Throwable) {
                    // Any failure here ends the session, never the app.
                } finally {
                    alive.set(false)
                    runCatching { socket.close() }
                    ConnectionLog.record("$TAG udpgw session closed")
                }
            }, "psiphon-front-udpgw").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ -> }
            }.start()

            Thread({
                try {
                    writeLoop()
                } catch (_: Throwable) {
                    // Any failure here ends the session, never the app.
                } finally {
                    alive.set(false)
                    runCatching { socket.close() }
                    queueLock.lock()
                    try {
                        notEmpty.signalAll()
                    } finally {
                        queueLock.unlock()
                    }
                }
            }, "psiphon-front-udpgw-tx").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ -> }
            }.start()

            Thread({
                try {
                    while (alive.get() && !socket.isClosed) {
                        Thread.sleep(UDPGW_KEEPALIVE_MS)
                        if (!enqueueFrame(byteArrayOf(FLAG_KEEPALIVE.toByte(), 0, 0), priority = true)) break
                    }
                } catch (_: Throwable) {
                }
            }, "psiphon-front-udpgw-ka").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ -> }
            }.start()
        }

        fun send(relay: DatagramSocket, client: InetSocketAddress, request: UdpRequest): Boolean {
            val address = request.address ?: return false
            if (!isAlive) return false
            // Keyed on the association's source port AND the destination, so two
            // apps talking to the same server get their own conid and cannot
            // receive each other's replies.
            val key = "${client.port}|" + address.joinToString("") { "%02x".format(it) }
            val conid = synchronized(conids) {
                conids[key] ?: allocateConid().also { conids[key] = it }
            }
            flows[conid] = UdpgwFlow(relay, client, request.header)

            val isDns = request.port == 53
            var flags = if (request.isIpv6) FLAG_IPV6 else 0
            // The DNS flag lets the Psiphon server answer out of its own
            // resolver rather than dialling whatever address the device happened
            // to be handed, which is both faster and harder to block.
            if (isDns) flags = flags or FLAG_DNS

            val body = ByteArray(3 + address.size + request.payload.size)
            body[0] = flags.toByte()
            body[1] = (conid and 0xFF).toByte()
            body[2] = ((conid shr 8) and 0xFF).toByte()
            System.arraycopy(address, 0, body, 3, address.size)
            System.arraycopy(request.payload, 0, body, 3 + address.size, request.payload.size)
            // DNS jumps the queue. A name lookup is one small datagram that the
            // whole page load is blocked on; a bulk datagram is one frame out of
            // thousands. Putting them in one FIFO is what made browsing crawl
            // while a video buffered.
            return enqueueFrame(body, priority = isDns)
        }

        /** Drops the conids belonging to a closed association. */
        fun releaseFlowsFor(relay: DatagramSocket) {
            synchronized(flows) {
                flows.entries.filter { it.value.relay === relay }
                    .map { it.key }
                    .forEach { flows.remove(it) }
            }
            // Both maps are synchronizedMap wrappers, so an iteration has to hold
            // the map's own monitor or it can throw ConcurrentModificationException
            // on a busy device. The previous version iterated [conids] unguarded.
            synchronized(conids) {
                val stale = conids.entries.filter { !flows.containsKey(it.value) }.map { it.key }
                stale.forEach { conids.remove(it) }
            }
        }

        fun close() {
            alive.set(false)
            queueLock.lock()
            try {
                priorityFrames.clear()
                bulkFrames.clear()
                notEmpty.signalAll()
            } finally {
                queueLock.unlock()
            }
            runCatching { socket.close() }
            flows.clear()
            conids.clear()
        }

        private companion object {
            /** Frames written between two flushes. */
            const val WRITE_BATCH = 32
        }

        /**
         * Next free connection id.
         *
         * `and 0xFFFF` alone was not enough: the counter wraps after 65535 flows
         * and would then hand out an id the server still has a remote socket
         * bound to, which delivers one flow's replies to a DIFFERENT flow. Zero
         * is skipped as well - badvpn treats it as a valid id but reusing the
         * very first id on wrap is the collision most likely to actually happen.
         * Caller holds the [conids] monitor.
         */
        private fun allocateConid(): Int {
            repeat(0x10000) {
                val raw = nextConid.getAndIncrement() and 0xFFFF
                val candidate = if (raw == 0) 1 else raw
                if (!flows.containsKey(candidate)) return candidate
            }
            // 65536 live flows is impossible with MAX_UDPGW_FLOWS, but never
            // return an undefined value.
            return 1
        }

        /**
         * Frames [body] and hands it to the writer thread. NEVER blocks.
         *
         * @return false only when the session is dead; a dropped bulk datagram
         *   still returns true, because from the caller's point of view the
         *   datagram was accepted by a lossy transport - which is exactly what
         *   UDP is.
         */
        private fun enqueueFrame(body: ByteArray, priority: Boolean): Boolean {
            if (body.size > 65535) return false
            if (!alive.get()) return false
            val frame = ByteArray(2 + body.size)
            frame[0] = (body.size and 0xFF).toByte()
            frame[1] = ((body.size shr 8) and 0xFF).toByte()
            System.arraycopy(body, 0, frame, 2, body.size)
            queueLock.lock()
            try {
                if (!alive.get()) return false
                if (priority) {
                    if (priorityFrames.size >= UDPGW_PRIORITY_QUEUE) priorityFrames.removeFirst()
                    priorityFrames.addLast(frame)
                } else {
                    while (bulkFrames.size >= UDPGW_BULK_QUEUE) {
                        bulkFrames.removeFirst()
                        bulkUdpDropped.incrementAndGet()
                    }
                    bulkFrames.addLast(frame)
                }
                notEmpty.signalAll()
            } finally {
                queueLock.unlock()
            }
            return true
        }

        /**
         * The ONLY place that blocks on the udpgw socket.
         *
         * Writes are batched and flushed once per batch: one `flush` per datagram
         * is one syscall per datagram, and under a busy DNS burst that alone was
         * measurable. The priority lane is always drained first.
         */
        private fun writeLoop() {
            while (alive.get()) {
                val first: ByteArray
                queueLock.lock()
                try {
                    while (alive.get() && priorityFrames.isEmpty() && bulkFrames.isEmpty()) {
                        notEmpty.await()
                    }
                    if (!alive.get()) return
                    first = priorityFrames.removeFirstOrNull() ?: bulkFrames.removeFirst()
                } finally {
                    queueLock.unlock()
                }
                try {
                    output.write(first)
                    var batched = 1
                    while (batched < WRITE_BATCH) {
                        queueLock.lock()
                        val next = try {
                            priorityFrames.removeFirstOrNull() ?: bulkFrames.removeFirstOrNull()
                        } finally {
                            queueLock.unlock()
                        }
                        if (next == null) break
                        output.write(next)
                        batched++
                    }
                    output.flush()
                } catch (e: Exception) {
                    alive.set(false)
                    return
                }
            }
        }

        private fun readLoop() {
            while (alive.get()) {
                val low = input.read()
                if (low < 0) break
                val high = input.read()
                if (high < 0) break
                val length = (high shl 8) or low
                if (length < 3 || length > 65535) break
                val body = ByteArray(length)
                input.readFully(body)

                val flags = body[0].toInt() and 0xFF
                val conid = ((body[2].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                if (flags and FLAG_KEEPALIVE != 0) continue

                val addressLength = if (flags and FLAG_IPV6 != 0) 18 else 6
                if (body.size < 3 + addressLength) continue
                val payload = body.copyOfRange(3 + addressLength, body.size)
                if (payload.isEmpty()) continue

                val flow = flows[conid] ?: continue
                // The reply echoes the header the client sent rather than the
                // address the server reports: hev matches replies against the
                // destination it asked for, and the two are the same endpoint.
                val reply = ByteArray(flow.header.size + payload.size)
                System.arraycopy(flow.header, 0, reply, 0, flow.header.size)
                System.arraycopy(payload, 0, reply, flow.header.size, payload.size)
                rxBytes.addAndGet(payload.size.toLong())
                try {
                    flow.relay.send(
                        DatagramPacket(reply, reply.size, flow.client.address, flow.client.port)
                    )
                } catch (e: Exception) {
                    // Association torn down between request and reply.
                    flows.remove(conid)
                }
            }
        }
    }

    /**
     * Returns the shared udpgw session, opening it on first use.
     *
     * A refusal is remembered for the whole session ([udpgwRefused]): if this
     * server will not intercept `127.0.0.1:7300`, retrying per datagram would
     * mean one doomed port forward per UDP packet, which is exactly the kind of
     * churn that made the original log unreadable.
     */
    private fun udpgwSession(): UdpgwSession? {
        udpgw?.let { if (it.isAlive) return it }
        if (udpgwRefused.get()) return null
        synchronized(udpgwLock) {
            udpgw?.let { if (it.isAlive) return it }
            if (udpgwRefused.get()) return null
            udpgw?.close()
            udpgw = null

            val refusal = IntArray(1) { -1 }
            val stream = openPsiphonStream(UDPGW_HOST, UDPGW_PORT, refusal)
            if (stream == null) {
                if (refusal[0] > 0) {
                    // A real answer from the server: it will not intercept.
                    // Falling back is the correct outcome, not an error.
                    udpgwRefused.set(true)
                    ConnectionLog.record(
                        "$TAG this server refused the udpgw port forward (SOCKS reply " +
                            "${refusal[0]}) - UDP falls back to DNS-over-TCP, non-DNS UDP is dropped"
                    )
                } else {
                    ConnectionLog.record("$TAG could not open the udpgw session; will retry")
                }
                return null
            }
            val session = UdpgwSession(stream.socket, stream.input, stream.output)
            session.startReader()
            udpgw = session
            ConnectionLog.record("$TAG udpgw session up - full UDP (DNS + QUIC) via Psiphon")
            return session
        }
    }

    // --------------------------------------------------------- DNS over TCP

    /**
     * Answers one DNS query over TCP through Psiphon (RFC 1035 §4.2.2 framing: a
     * two-byte big-endian length in front of the message).
     *
     * Used only when udpgw is unavailable. TCP is the one thing Psiphon always
     * carries, so this is what makes "Psiphon connects but no site opens"
     * impossible rather than merely unlikely.
     */
    private fun answerDnsOverTcp(
        relay: DatagramSocket,
        client: InetSocketAddress,
        request: UdpRequest,
    ): Boolean {
        val host = request.address?.let { raw ->
            // The trailing two bytes are the port; only the address is wanted.
            val addr = raw.copyOfRange(0, raw.size - 2)
            runCatching { InetAddress.getByAddress(addr).hostAddress }.getOrNull()
        } ?: return false
        // The refusal code is captured NOW so a server that does not allow TCP/53
        // out is recognised on its FIRST refused lookup and every later query goes
        // straight to DoH. Without this the fallback re-earned one refused port
        // forward per lookup - 110 of them in 73 s in the field log - and fed the
        // watchdog fake evidence that the server was censoring.
        val refusal = IntArray(1) { -1 }
        val stream = openPsiphonStream(host, 53, refusal) ?: run {
            if (refusal[0] > 0) reportRefusal(host, 53)
            return false
        }
        val answer = try {
            stream.socket.soTimeout = DNS_TIMEOUT_MS
            stream.output.write((request.payload.size shr 8) and 0xFF)
            stream.output.write(request.payload.size and 0xFF)
            stream.output.write(request.payload)
            stream.output.flush()
            val hi = stream.input.read()
            val lo = stream.input.read()
            if (hi < 0 || lo < 0) return false
            val length = (hi shl 8) or lo
            if (length <= 0 || length > DNS_BUFFER) return false
            ByteArray(length).also { stream.input.readFully(it) }
        } catch (e: Exception) {
            // A failure here is one unanswered query: the caller falls through to
            // DNS-over-HTTPS, which is what makes this mode's name resolution
            // depend on nothing the server can refuse.
            return false
        } finally {
            closeQuietly(stream.socket)
        }

        val reply = ByteArray(request.header.size + answer.size)
        System.arraycopy(request.header, 0, reply, 0, request.header.size)
        System.arraycopy(answer, 0, reply, request.header.size, answer.size)
        rxBytes.addAndGet(answer.size.toLong())
        return try {
            relay.send(DatagramPacket(reply, reply.size, client.address, client.port))
            true
        } catch (e: Exception) {
            // Association torn down between the query and the answer. The answer
            // itself was fine, so this counts as handled.
            true
        }
    }

    /**
     * Answers one DNS query through the tunnel, over whichever transport this
     * server actually permits.
     *
     * TCP/53 first, because it is one round trip and no TLS. The moment a server
     * refuses it ([dnsPort53Refused], latched by [reportRefusal]) every later
     * lookup goes straight to DNS-over-HTTPS instead - which is both the fix for
     * "no site opens on this server" and the fix for the refusal storm that made
     * the watchdog convict it.
     */
    private fun answerDnsQuery(
        relay: DatagramSocket,
        client: InetSocketAddress,
        request: UdpRequest,
    ) {
        if (!dnsPort53Refused.get()) {
            if (answerDnsOverTcp(relay, client, request)) return
            // A failed :53 attempt is not proof the port is blocked (a resolver
            // can simply be slow), so nothing is latched here - only an explicit
            // SOCKS refusal, seen in [reportRefusal], flips the switch.
        }
        answerDnsOverHttps(relay, client, request)
    }

    /**
     * SOCKS5 CONNECT through Psiphon on a RAW socket, with nothing buffered.
     *
     * [openPsiphonStream] hands back a BufferedInputStream, and wrapping a
     * buffered stream in TLS loses whatever the buffer already swallowed. TLS
     * needs the socket itself, so the handshake here is read byte-exact off the
     * socket's own stream and the socket is returned untouched.
     */
    private fun openPsiphonRawStream(host: String, port: Int): Socket? {
        val upstream = try {
            Socket().apply {
                tcpNoDelay = true
                soTimeout = DNS_TIMEOUT_MS
                connect(
                    InetSocketAddress("127.0.0.1", psiphonSocksPort),
                    PSIPHON_CONNECT_TIMEOUT_MS,
                )
            }
        } catch (e: Exception) {
            return null
        }
        try {
            val out = upstream.getOutputStream()
            val input = upstream.getInputStream()

            fun readExactly(count: Int): ByteArray {
                val buffer = ByteArray(count)
                var read = 0
                while (read < count) {
                    val n = input.read(buffer, read, count - read)
                    if (n < 0) throw java.io.EOFException("Psiphon closed the SOCKS handshake")
                    read += n
                }
                return buffer
            }

            out.write(byteArrayOf(SOCKS_VERSION.toByte(), 1, 0x00))
            out.flush()
            val greeting = readExactly(2)
            if (greeting[0].toInt() != SOCKS_VERSION || greeting[1].toInt() != 0x00) {
                closeQuietly(upstream)
                return null
            }

            val name = host.toByteArray(Charsets.US_ASCII)
            if (name.size > 255) {
                closeQuietly(upstream)
                return null
            }
            val request = ByteArray(5 + name.size + 2)
            request[0] = SOCKS_VERSION.toByte()
            request[1] = CMD_CONNECT.toByte()
            request[2] = 0
            request[3] = ATYP_DOMAIN.toByte()
            request[4] = name.size.toByte()
            System.arraycopy(name, 0, request, 5, name.size)
            request[5 + name.size] = ((port shr 8) and 0xFF).toByte()
            request[6 + name.size] = (port and 0xFF).toByte()
            out.write(request)
            out.flush()

            val head = readExactly(4)
            if (head[0].toInt() != SOCKS_VERSION) {
                closeQuietly(upstream)
                return null
            }
            val reply = head[1].toInt() and 0xFF
            when (head[3].toInt() and 0xFF) {
                ATYP_IPV4 -> readExactly(4)
                ATYP_IPV6 -> readExactly(16)
                ATYP_DOMAIN -> readExactly((readExactly(1)[0].toInt() and 0xFF))
            }
            readExactly(2) // bound port
            if (reply != REP_SUCCESS) {
                if (reply > 0) reportRefusal(host, port)
                closeQuietly(upstream)
                return null
            }
            return upstream
        } catch (e: Exception) {
            closeQuietly(upstream)
            return null
        }
    }

    /**
     * Answers one DNS query over DNS-over-HTTPS (RFC 8484) through Psiphon.
     *
     * The query and the answer are the SAME wire-format DNS message the device
     * sent and expects, so nothing has to be re-encoded and the transaction id
     * matches by construction.
     *
     * TLS is verified properly: the system trust store plus an explicit hostname
     * check. `startHandshake()` validates the chain but does NOT check that the
     * certificate belongs to the host, so without the second check anyone holding
     * a certificate for any domain could answer the device's DNS - and DNS
     * answers steer every connection that follows.
     */
    private fun answerDnsOverHttps(
        relay: DatagramSocket,
        client: InetSocketAddress,
        request: UdpRequest,
    ) {
        val raw = openPsiphonRawStream(DOH_HOST, DOH_PORT) ?: return
        val answer = try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(raw, DOH_HOST, DOH_PORT, true) as SSLSocket
            tls.soTimeout = DNS_TIMEOUT_MS
            tls.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(DOH_HOST, tls.session)) {
                throw SSLPeerUnverifiedException("$DOH_HOST certificate mismatch (possible MitM)")
            }
            val head = (
                "POST $DOH_PATH HTTP/1.1\r\n" +
                    "Host: $DOH_HOST\r\n" +
                    "Accept: application/dns-message\r\n" +
                    "Content-Type: application/dns-message\r\n" +
                    "Content-Length: ${request.payload.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            val out = tls.getOutputStream()
            out.write(head)
            out.write(request.payload)
            out.flush()
            readDohBody(tls.getInputStream())
        } catch (e: Exception) {
            // One unanswered query. Every resolver on earth drops a packet now
            // and then and every client retries, so this is a stall at worst -
            // never a failure mode of the mode itself.
            null
        } finally {
            closeQuietly(raw)
        }
        if (answer == null || answer.isEmpty()) return

        val reply = ByteArray(request.header.size + answer.size)
        System.arraycopy(request.header, 0, reply, 0, request.header.size)
        System.arraycopy(answer, 0, reply, request.header.size, answer.size)
        rxBytes.addAndGet(answer.size.toLong())
        try {
            relay.send(DatagramPacket(reply, reply.size, client.address, client.port))
        } catch (e: Exception) {
            // Association torn down between the query and the answer.
        }
    }

    /**
     * Reads one HTTP/1.1 response and returns its body.
     *
     * Deliberately minimal: the only server this ever talks to is a DoH resolver
     * answering a POST with a small binary body. `Content-Length` is honoured when
     * present; otherwise the body is whatever arrives before the server closes,
     * which is what `Connection: close` asks for. Chunked encoding is not accepted
     * rather than half-parsed - a resolver that used it would simply fall back to
     * the next query.
     */
    private fun readDohBody(input: InputStream): ByteArray? {
        val header = StringBuilder()
        var consecutive = 0
        while (consecutive < 2 && header.length < 8192) {
            val b = input.read()
            if (b < 0) return null
            val c = b.toChar()
            if (c == '\n') consecutive++ else if (c != '\r') consecutive = 0
            header.append(c)
        }
        val head = header.toString()
        val status = head.lineSequence().firstOrNull().orEmpty()
        if (!status.contains(" 200")) return null
        if (head.contains("Transfer-Encoding: chunked", ignoreCase = true)) return null
        val declared = Regex("(?i)Content-Length:\\s*(\\d+)").find(head)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (declared != null) {
            if (declared <= 0 || declared > DNS_BUFFER) return null
            val body = ByteArray(declared)
            var read = 0
            while (read < declared) {
                val n = input.read(body, read, declared - read)
                if (n < 0) return null
                read += n
            }
            return body
        }
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (buffer.size() <= DNS_BUFFER) {
            val n = input.read(chunk)
            if (n < 0) break
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray().takeIf { it.isNotEmpty() && it.size <= DNS_BUFFER }
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }
}
