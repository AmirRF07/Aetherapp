package studio.cluvex.aether.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Its own DataStore, deliberately not [ProfileStore]'s.
 *
 * The connection profile is handed to the engine on every connect and is wiped
 * by "reset all settings". The AI configuration is neither: it is an account
 * credential plus three preferences that have nothing to do with the tunnel, and
 * losing the key because somebody reset their MTU would be infuriating. Separate
 * file, separate lifetime - the same reasoning [OnboardingStore] uses.
 */
private val Context.aiDataStore by preferencesDataStore(name = "aether_ai")

/**
 * Everything the AI layer needs to know about how the user has configured it.
 *
 * [Immutable] for the same reason [studio.cluvex.aether.model.ConnectionProfile]
 * is: without it the Compose compiler infers the class as unstable because of the
 * `List` member, and then it cannot skip a single composable that takes it - so
 * one keystroke in the API-key field would recompose every AI row on the page.
 */
@Immutable
data class AiSettings(
    /** The user's Gemini API key. Read back from [SecretStore], never from prefs. */
    val apiKey: String = "",
    /** Bare model id the user picked, e.g. `gemini-2.0-flash`. Blank = not chosen. */
    val model: String = "",
    /**
     * Model ids the last successful discovery found for THIS key.
     *
     * Cached because discovery needs the tunnel: without a cache the model picker
     * would be empty every time the user opened it while disconnected, which
     * reads as "the app lost my settings" rather than "we cannot reach Google
     * right now".
     */
    val discoveredModels: List<String> = emptyList(),
    /** Analyse the log and propose tuning on every connect. */
    val autoOptimize: Boolean = true,
    /**
     * Write a proposal straight into the profile instead of waiting for a tap.
     *
     * Defaults OFF on purpose. The proposal comes from a remote model, and a
     * setting that changes itself behind the user's back is indistinguishable
     * from a bug when the next connect behaves differently. Opt in explicitly.
     */
    val autoApply: Boolean = false,
    /** Show the AI icon next to individual options. */
    val showHints: Boolean = true,
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    /** The model to actually call: the chosen one, or the first discovered one. */
    val effectiveModel: String
        get() = model.ifBlank { discoveredModels.firstOrNull().orEmpty() }
}

/** Persists [AiSettings]; the key itself goes to the Keystore-sealed vault. */
class GeminiStore(private val context: Context) {

    private object Keys {
        val model = stringPreferencesKey("model")
        val models = stringPreferencesKey("models")
        val autoOptimize = booleanPreferencesKey("autoOptimize")
        val autoApply = booleanPreferencesKey("autoApply")
        val showHints = booleanPreferencesKey("showHints")
    }

    private val secrets = SecretStore(context)

    val settings: Flow<AiSettings> = context.aiDataStore.data.map { prefs ->
        AiSettings(
            apiKey = secrets.read(SecretStore.GEMINI_KEY),
            model = prefs[Keys.model] ?: "",
            discoveredModels = prefs[Keys.models]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            autoOptimize = prefs[Keys.autoOptimize] ?: true,
            autoApply = prefs[Keys.autoApply] ?: false,
            showHints = prefs[Keys.showHints] ?: true,
        )
    }

    /**
     * Stores the key sealed with a hardware-backed AES-GCM key.
     *
     * Trimmed first: an API key pasted from a web page almost always arrives with
     * a trailing newline or a stray space, and a key with whitespace in it fails
     * authentication with a 400 that says nothing useful about why.
     */
    suspend fun saveKey(key: String) {
        secrets.write(SecretStore.GEMINI_KEY, key.trim())
        // Touch the store so the settings flow re-emits: the key lives outside
        // DataStore, so nothing else would tell a collector it changed.
        context.aiDataStore.edit { it[Keys.model] = it[Keys.model] ?: "" }
    }

    suspend fun saveModel(model: String) {
        context.aiDataStore.edit { it[Keys.model] = model }
    }

    suspend fun saveDiscovered(models: List<String>) {
        context.aiDataStore.edit { it[Keys.models] = models.joinToString(",") }
    }

    suspend fun saveAutoOptimize(enabled: Boolean) {
        context.aiDataStore.edit { it[Keys.autoOptimize] = enabled }
    }

    suspend fun saveAutoApply(enabled: Boolean) {
        context.aiDataStore.edit { it[Keys.autoApply] = enabled }
    }

    suspend fun saveShowHints(enabled: Boolean) {
        context.aiDataStore.edit { it[Keys.showHints] = enabled }
    }

    /** Forgets the key, the model choice and the cached model list. */
    suspend fun forget() {
        secrets.write(SecretStore.GEMINI_KEY, "")
        context.aiDataStore.edit { prefs ->
            prefs[Keys.model] = ""
            prefs[Keys.models] = ""
        }
    }
}
