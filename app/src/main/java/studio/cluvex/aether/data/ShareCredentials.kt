package studio.cluvex.aether.data

import android.content.Context
import studio.cluvex.aether.core.LanGuard
import studio.cluvex.aether.core.ShareBridge

/**
 * The credential other devices use to reach this phone's shared tunnel
 * (audit 1.2.9-r3, F-5).
 *
 * ## Why it is generated, not chosen
 *
 * A user-chosen proxy password on a censorship-circumvention tool would be typed
 * once, be four characters long, and be the same one they use elsewhere. There is
 * nothing for the user to decide here, so there is no field to fill in: the app
 * generates 16 characters from [LanGuard.randomPassword] the first time sharing is
 * used, shows them in the Share card with a copy button, and lets the user rotate
 * them with one tap.
 *
 * ## Where it lives
 *
 * In [SecretStore] - sealed with AES-256-GCM under a non-exportable Android
 * Keystore key - and NOT in the DataStore preferences file, for the same reason
 * the Gemini key is not: the preferences file is plain protobuf, and this
 * credential is what stands between a hostile Wi-Fi and the user's exit IP.
 *
 * It is stable across sessions on purpose. It is typed into another device's
 * system proxy settings, so a value that changed per connect would mean the laptop
 * stops working on every reconnect - and a feature that breaks constantly is a
 * feature users turn the security off to fix.
 */
object ShareCredentials {

    /**
     * The username. Fixed, because a random username adds no entropy an attacker
     * has to guess separately (it travels in the same message as the password) and
     * doubles what the user has to copy across.
     */
    const val USER = "aether"

    /**
     * Loads the credential, generating and persisting one on first use, and hands
     * it to [ShareBridge].
     *
     * Idempotent and cheap (one keystore-backed decrypt), safe to call from
     * anywhere. Call it before any code path that can bind the LAN listeners:
     * the bridge falls back to an EPHEMERAL random password if it is ever asked
     * to expose the LAN without one, which is fail-closed but useless to a user
     * who cannot see it.
     */
    fun ensure(context: Context): String {
        val store = SecretStore(context)
        val existing = store.read(KEY)
        if (existing.isNotBlank()) {
            ShareBridge.setCredentials(USER, existing)
            return existing
        }
        val fresh = LanGuard.randomPassword()
        store.write(KEY, fresh)
        ShareBridge.setCredentials(USER, fresh)
        return fresh
    }

    /**
     * Replaces the credential. Existing remote clients keep their open TCP
     * sessions (the bridge authenticates at connect time), but nothing new can
     * attach with the old password.
     */
    fun rotate(context: Context): String {
        val fresh = LanGuard.randomPassword()
        SecretStore(context).write(KEY, fresh)
        ShareBridge.setCredentials(USER, fresh)
        return fresh
    }

    private const val KEY = "share_proxy_password"
}
