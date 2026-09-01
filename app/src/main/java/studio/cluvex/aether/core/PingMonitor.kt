package studio.cluvex.aether.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/** Result of a single latency measurement. */
data class PingResult(
    val ms: Long = -1L,
    val running: Boolean = false,
    val error: Boolean = false,
)

/**
 * On-demand TCP latency check, ported from the merged PingRepository and
 * adapted to Aether Mobile's tunnel plumbing ([TunnelConfig]).
 *
 * BATTERY DESIGN: there is deliberately NO periodic polling loop here. A
 * measurement only runs when the user taps the ping badge, or exactly once
 * after a new connection comes up. Each run is a single TCP handshake to
 * Cloudflare's anycast resolver (1.1.1.1:53) with a hard 5 s timeout, so one
 * measurement costs one packet round-trip and never keeps the CPU awake.
 */
object PingMonitor {
    private val _state = MutableStateFlow(PingResult())
    val state: StateFlow<PingResult> = _state.asStateFlow()

    /** Serialises concurrent taps so two probes can never overlap. */
    private val mutex = Mutex()

    /**
     * SOCKS5 port a tunnelled probe goes through.
     *
     * ROOT CAUSE this fixes: the probe was hardcoded to
     * [TunnelConfig.SOCKS_PORT] (1819), which in a CHAINED `Aether -> Psiphon`
     * session is stage 1's own listener. The badge therefore measured the first
     * hop only and said nothing about the path the user's traffic actually takes -
     * exactly the wrong number to be looking at while diagnosing "the ping is over
     * 1000". [AetherVpnService] publishes the finished pipeline's port here.
     */
    @Volatile
    private var tunnelPort: Int = TunnelConfig.SOCKS_PORT

    /** Points tunnelled probes at the port the finished pipeline exposes. */
    fun setTunnelPort(port: Int) {
        if (port in 1..65535) tunnelPort = port
    }

    /** Back to the engine's own listener; called on teardown. */
    fun resetTunnelPort() {
        tunnelPort = TunnelConfig.SOCKS_PORT
    }

    /**
     * Measures TCP handshake latency to 1.1.1.1:53.
     *
     * @param viaTunnel when true the probe socket is opened THROUGH the local
     * SOCKS5 listener of the running engine, so the number reflects the
     * tunnel's real end-to-end latency; when false it connects directly and
     * shows the operator's latency instead.
     */
    suspend fun pingOnce(viaTunnel: Boolean) {
        if (!mutex.tryLock()) return
        try {
            _state.value = PingResult(running = true)
            val ms = withContext(Dispatchers.IO) { measure(viaTunnel) }
            _state.value = if (ms >= 0) PingResult(ms = ms) else PingResult(error = true)
        } finally {
            mutex.unlock()
        }
    }

    private fun measure(viaTunnel: Boolean): Long {
        val start = SystemClock.elapsedRealtime()
        return try {
            val socket = if (viaTunnel) {
                Socket(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress(TunnelConfig.SOCKS_HOST, tunnelPort),
                    ),
                )
            } else {
                Socket()
            }
            socket.use { s ->
                s.connect(InetSocketAddress("1.1.1.1", 53), 5000)
            }
            SystemClock.elapsedRealtime() - start
        } catch (e: Exception) {
            DiagnosticsLog.w("ping", "Latency probe failed (viaTunnel=$viaTunnel): ${e.message}")
            -1L
        }
    }
}
