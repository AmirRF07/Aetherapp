package studio.cluvex.aether.model

/** The external stack a backend needs, independent of which one is chained. */
enum class ExternalKind { PSIPHON }

/**
 * Which network stack carries the session.
 *
 * Both values are CHAINED-or-plain: either the Aether/WARP engine alone, or the
 * engine brought up first as a local SOCKS5 proxy with Psiphon dialling out
 * THROUGH it. The public exit IP then belongs to Psiphon, while the only leg a
 * local censor can observe is Aether's obfuscated transport.
 *
 * ## Why the single-hop PSIPHON mode is gone (1.2.7)
 *
 * It could not connect. Psiphon has to reach its own infrastructure before it
 * can carry anything, and on the networks this app exists for that first hop is
 * exactly what gets blocked - so the single-hop mode sat on "Connecting" and
 * timed out while the chained mode came up in seconds through Aether's anti-DPI
 * handshake. A dead entry in a picker is worse than none: every user who tried
 * it first concluded the app was broken. The chained mode covers the same ground
 * and actually works, so that is what ships.
 *
 * ## Why Tor is gone entirely (also 1.2.7)
 *
 * Both the single-hop `TOR` and the chained `AETHER_TOR` were dropped, and with
 * them the whole Tor runtime: the packaged `libtor.so` executable, its pinned
 * build script, its DNS-aware SOCKS front and the two local ports it owned. Tor
 * carries TCP streams only, so every datagram the device sent had to be answered
 * out of Tor's `DNSPort` and everything else dropped; on top of that a full Tor
 * bootstrap *through* the Aether handshake took long enough that users read it as
 * a hang. The app ships ONE external transport now, Psiphon, which carries real
 * UDP and comes up in seconds.
 *
 * Removing enum values is safe for stored profiles because both persistence
 * paths ([studio.cluvex.aether.core.ProfileCodec] and
 * [studio.cluvex.aether.data.ProfileStore]) store the NAME, and both route an
 * unknown name through [fromStoredName], which MIGRATES the retired values
 * instead of leaving the picker pointing at a backend that no longer exists.
 *
 * Ordering note: new values are APPENDED, for the same reason.
 */
enum class TransportBackend {
    AETHER,
    AETHER_PSIPHON;

    /** Which external transport this mode needs, or null for plain Aether. */
    val externalKind: ExternalKind?
        get() = when (this) {
            AETHER -> null
            AETHER_PSIPHON -> ExternalKind.PSIPHON
        }

    /** True when the bundled Aether/WARP engine has to run for this mode. Always. */
    val usesAetherEngine: Boolean
        get() = true

    /** True when Psiphon has to run for this mode. */
    val usesExternal: Boolean
        get() = externalKind != null

    /**
     * True for the two-stage mode where the external transport rides Aether.
     *
     * Identical to [usesExternal] now that the single-hop mode is gone, and kept
     * as its own name because the call sites read about *chaining* (which port
     * each stage binds, which stage the supervisor watches) rather than about
     * which transport is involved.
     */
    val isChained: Boolean
        get() = usesExternal

    /**
     * Human-readable pipeline, used in the log and in the info row so a user
     * reading a diagnostics dump can tell which hop failed.
     */
    val pipelineLabel: String
        get() = when (this) {
            AETHER -> "Aether"
            AETHER_PSIPHON -> "Aether \u2192 Psiphon"
        }

    companion object {
        /**
         * Decodes a persisted backend name, migrating the values retired in
         * 1.2.7.
         *
         * A saved `PSIPHON` profile becomes its chained equivalent rather than
         * falling back to [AETHER]: the user asked for a Psiphon exit, and the
         * chained mode is the one that delivers it.
         *
         * The two Tor names are a different case. Tor is not in the app any more
         * and there is nothing equivalent to migrate them onto - silently
         * routing a profile that asked for a Tor exit through Psiphon instead
         * would swap one trust model for another without telling anyone. They
         * resolve to plain [AETHER], the safe default; a user who wants a
         * third-party exit can pick the chained Psiphon mode deliberately.
         *
         * Anything genuinely unrecognised also falls back to [AETHER].
         */
        fun fromStoredName(raw: String?): TransportBackend? {
            val name = raw?.trim()?.uppercase() ?: return null
            return when (name) {
                "PSIPHON" -> AETHER_PSIPHON
                // Retired together with the whole Tor runtime.
                "TOR", "AETHER_TOR" -> AETHER
                else -> entries.firstOrNull { it.name == name }
            }
        }
    }
}
