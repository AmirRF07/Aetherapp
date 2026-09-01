package studio.cluvex.aether.transport

import android.net.VpnService
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ExternalKind

interface ExternalTransport {
    /** Brings the transport up and returns the local SOCKS5 port it listens on. */
    suspend fun start(): Int
    fun isAlive(): Boolean
    fun stop()
}

object ExternalTransportFactory {
    /**
     * Builds the transport for [profile].
     *
     * There is exactly ONE external transport left: Psiphon, always chained. The
     * single-hop backends and the whole Tor runtime both went in 1.2.7
     * (see [studio.cluvex.aether.model.TransportBackend]), so the wiring is fixed:
     *
     * ```
     *   stage 1  Aether engine      -> SOCKS5 127.0.0.1:1819
     *   stage 2  Psiphon            -> SOCKS5 127.0.0.1:1827, dialling via 1819
     *   front    PsiphonSocksFront  -> SOCKS5 127.0.0.1:1825   <- tun2socks talks here
     * ```
     *
     * The transport returns the FRONT's port, never its own: the front is the
     * only listener in this app that answers SOCKS5 `UDP ASSOCIATE`, which is the
     * command hev-socks5-tunnel needs for every DNS query the device makes.
     * Handing tun2socks the raw transport port is precisely the bug that left
     * `Aether -> Psiphon` connected and unable to open a single site.
     */
    fun create(service: VpnService, profile: ConnectionProfile): ExternalTransport {
        val upstream = "socks5://${TunnelConfig.SOCKS_HOST}:${TunnelConfig.SOCKS_PORT}"

        return when (profile.backend.externalKind) {
            ExternalKind.PSIPHON -> PsiphonTransport(
                service = service,
                region = profile.exitRegion,
                upstreamProxy = upstream,
                localSocksPort = TunnelConfig.PSIPHON_SOCKS_PORT,
                frontPort = TunnelConfig.CHAIN_SOCKS_PORT,
            )
            null -> error("${profile.backend} is not an external transport")
        }
    }
}
