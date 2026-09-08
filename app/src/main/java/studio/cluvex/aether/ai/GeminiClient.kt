package studio.cluvex.aether.ai

import org.json.JSONArray
import org.json.JSONObject
import studio.cluvex.aether.core.DiagnosticsLog

/** One Gemini model, as the user's own key is allowed to see it. */
data class GeminiModel(
    /** Bare id, e.g. `gemini-2.0-flash` (the `models/` prefix is stripped). */
    val id: String,
    val displayName: String,
    val description: String,
    val inputTokenLimit: Int,
    val outputTokenLimit: Int,
    /** True when this model can answer `generateContent`, i.e. can be chatted to. */
    val chatCapable: Boolean,
)

/** One turn of a conversation. */
data class GeminiTurn(val fromUser: Boolean, val text: String)

/**
 * Result of an API call: either a value, or a message that is safe to show a user.
 *
 * A sealed result rather than exceptions because every single failure here has to
 * end up as a sentence on screen. Throwing would push that translation into six
 * different call sites and guarantee that one of them shows a raw
 * `SSLPeerUnverifiedException` to somebody who just wanted to know what MTU means.
 */
sealed interface AiResult<out T> {
    data class Ok<T>(val value: T) : AiResult<T>

    /**
     * @param kind a machine-readable cause so the UI can offer the right fix
     *   (re-enter the key, connect the tunnel, pick another model) instead of one
     *   generic "something went wrong".
     */
    data class Err(
        val message: String,
        val kind: AiErrorKind,
        /** Server-suggested wait, when the failure carried one. Drives the backoff. */
        val retryAfterSeconds: Double? = null,
    ) : AiResult<Nothing>
}

/** What went wrong, at the granularity the UI actually reacts to. */
enum class AiErrorKind {
    /** The key was rejected (401/403, or Google's "API key not valid"). */
    BAD_KEY,

    /** Quota or rate limit (429). */
    RATE_LIMIT,

    /** The model id is unknown to this key (404). */
    NO_SUCH_MODEL,

    /** The request never reached Google: proxy refused, TLS failed, timeout. */
    TRANSPORT,

    /**
     * Google was reached and failed on its own side (5xx).
     *
     * Split out from [TRANSPORT] because the two need opposite words. A 500
     * `Internal error encountered` was being reported to the user as "could not
     * reach Google through the tunnel", which sent people to re-check a tunnel
     * that was working perfectly - the request got all the way to Google and
     * back. It is also the one class of failure that a plain retry fixes, which
     * is why pressing "try again" always worked and nothing else did.
     */
    SERVER_ERROR,

    /** Google answered, with something this client could not use. */
    PROTOCOL,

    /**
     * The answer was cut off mid-generation (`finishReason: MAX_TOKENS`).
     *
     * Its own kind because it is the failure behind a half-written chat reply and
     * behind an advisor answer that will not parse as JSON: the text is not
     * corrupt, it is incomplete, and the fix is a bigger output budget rather
     * than a different model or a different key.
     */
    TRUNCATED,

    /** Google refused to answer on safety grounds. */
    BLOCKED,
}

/**
 * Thin, dependency-free client for the Gemini REST API.
 *
 * JSON is built and parsed with `org.json`, which is part of the Android platform
 * - so the AI features add exactly ZERO third-party dependencies to an app whose
 * users have every reason to care about what is inside its APK.
 *
 * Every call is blocking and MUST run off the main thread; the callers in
 * [AiSession] all use `Dispatchers.IO`.
 */
object GeminiClient {

    private const val API = "/v1beta"

    /**
     * Lists the models THIS key may use.
     *
     * The whole point of the feature: a free key, a billing-enabled key and a key
     * from a region where a given model has not launched all see different lists,
     * so a hard-coded picker would offer models that 404 for half the users. We
     * ask the key what it can do.
     */
    fun listModels(apiKey: String, socksPort: Int): AiResult<List<GeminiModel>> {
        val collected = mutableListOf<GeminiModel>()
        var pageToken: String? = null
        var page = 0
        do {
            val suffix = if (pageToken.isNullOrBlank()) "" else "&pageToken=$pageToken"
            val response = call("GET", "$API/models?pageSize=200$suffix", socksPort, apiKey, null)
            val body = when (response) {
                is AiResult.Err -> return response
                is AiResult.Ok -> response.value
            }
            val json = parseObject(body) ?: return AiResult.Err(
                "Google's reply was not valid JSON.",
                AiErrorKind.PROTOCOL,
            )
            val models = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until models.length()) {
                val item = models.optJSONObject(i) ?: continue
                collected += item.toGeminiModel()
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            page++
            // Bound the walk: a runaway nextPageToken must not turn "open the
            // model picker" into an unbounded loop over a metered connection.
        } while (!pageToken.isNullOrBlank() && page < MAX_MODEL_PAGES)

        // The allow-list is applied HERE, not in the picker: an id that never
        // enters the app cannot be selected, cached, or sent. See [AiModelPolicy].
        val offered = AiModelPolicy.filter(collected.filter { it.chatCapable })
        DiagnosticsLog.i(
            "ai",
            "models: ${collected.size} returned by the key, ${offered.size} offered after the allow-list",
        )
        return AiResult.Ok(offered)
    }

    /**
     * Sends a conversation and returns the model's answer as plain text.
     *
     * @param system the system instruction; carries the app's persona, the
     *   language to answer in and the machine-readable contract for settings
     *   changes (see [AiPrompts]).
     * @param history previous turns, oldest first. Sent in full because the REST
     *   API is stateless - there is no server-side conversation to append to.
     */
    fun generate(
        apiKey: String,
        socksPort: Int,
        model: String,
        system: String,
        history: List<GeminiTurn>,
        temperature: Double = 0.4,
        maxOutputTokens: Int = 2048,
        /**
         * Ask for `application/json` instead of prose.
         *
         * Used by the advisor, whose answer is parsed as JSON and turned into
         * configuration. With this set the model is not able to wrap the object in
         * a code fence or introduce it with a sentence, which is most of what the
         * tolerant extractor in [AiPrompts] was written to survive.
         */
        jsonOutput: Boolean = false,
    ): AiResult<String> {
        if (model.isBlank()) {
            return AiResult.Err("No Gemini model selected.", AiErrorKind.NO_SUCH_MODEL)
        }
        if (!AiModelPolicy.isAllowed(model)) {
            // Belt and braces on the allow-list: a model id can also arrive from a
            // preference file written by an older build, and that path does not go
            // through discovery.
            return AiResult.Err(
                "Model \"$model\" is not one of the models this app supports.",
                AiErrorKind.NO_SUCH_MODEL,
            )
        }
        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
            )
            put(
                "contents",
                JSONArray().apply {
                    history.forEach { turn ->
                        put(
                            JSONObject()
                                .put("role", if (turn.fromUser) "user" else "model")
                                .put(
                                    "parts",
                                    JSONArray().put(JSONObject().put("text", turn.text)),
                                ),
                        )
                    }
                },
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", temperature)
                    .put("maxOutputTokens", maxOutputTokens)
                    .apply {
                        if (jsonOutput) put("responseMimeType", "application/json")
                        // ------------------------------------------------------
                        // ROOT CAUSE of the truncated / unparsable answers.
                        //
                        // maxOutputTokens is a budget for EVERYTHING the model
                        // emits, and on a thinking-capable model the reasoning
                        // tokens are spent out of it FIRST. The advisor asked for
                        // 1400 tokens with a log digest in the prompt, the model
                        // spent the lot thinking, and the visible answer came back
                        // empty or cut off mid-object - which is exactly the
                        // `200 OK` + "advisor answer could not be parsed as JSON"
                        // pair in the field log, and the half-written chat replies.
                        //
                        // thinkingBudget = 0 turns reasoning off, so the whole
                        // budget goes to the answer. That is the right trade for
                        // this app: every prompt here is short-horizon (explain one
                        // setting, map a log onto a fixed list of knobs) and none
                        // of them benefit from a reasoning pass that costs the
                        // answer. It also removes an unbounded, unbillable source
                        // of latency from a request already travelling through two
                        // tunnels.
                        //
                        // Sent unconditionally: models that do not support thinking
                        // ignore an unknown generationConfig member rather than
                        // rejecting the request.
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    },
            )
        }

        val response = call(
            "POST",
            "$API/models/${model.removePrefix("models/")}:generateContent",
            socksPort,
            apiKey,
            payload.toString(),
        )
        val body = when (response) {
            is AiResult.Err -> return response
            is AiResult.Ok -> response.value
        }
        val json = parseObject(body)
            ?: return AiResult.Err("Google's reply was not valid JSON.", AiErrorKind.PROTOCOL)

        // Refused before generation: there is no candidate to read, and the
        // reason lives somewhere else entirely.
        json.optJSONObject("promptFeedback")?.optString("blockReason")
            ?.takeIf { it.isNotBlank() && it != "BLOCK_REASON_UNSPECIFIED" }
            ?.let { return AiResult.Err(it, AiErrorKind.BLOCKED) }

        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return AiResult.Err("The model returned no answer.", AiErrorKind.BLOCKED)
        }
        val candidate = candidates.optJSONObject(0)
            ?: return AiResult.Err("The model returned no answer.", AiErrorKind.PROTOCOL)
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    // Reasoning parts are marked `thought: true` and are NOT the
                    // answer. Concatenating them produced replies that opened with
                    // the model talking to itself, and JSON that had prose in
                    // front of it. thinkingBudget=0 should mean there are none;
                    // this is the guard for the models that ignore it.
                    if (part.optBoolean("thought", false)) continue
                    append(part.optString("text"))
                }
            }
        }
        val finish = candidate.optString("finishReason")
        if (text.isBlank()) {
            return AiResult.Err(
                finish.ifBlank { "The model returned an empty answer." },
                when (finish) {
                    "SAFETY", "PROHIBITED_CONTENT" -> AiErrorKind.BLOCKED
                    "MAX_TOKENS" -> AiErrorKind.TRUNCATED
                    else -> AiErrorKind.PROTOCOL
                },
            )
        }
        // Answered, but cut off. Reported rather than returned as a success: a
        // half-sentence in a chat bubble and a half-object in the advisor are both
        // failures, and the caller can retry with a bigger budget because it now
        // knows which failure this is. The partial text rides along so the caller
        // can still show it if it decides to.
        if (finish == "MAX_TOKENS") {
            DiagnosticsLog.w("ai", "answer hit MAX_TOKENS after ${text.length} chars")
            return AiResult.Err(text, AiErrorKind.TRUNCATED)
        }
        return AiResult.Ok(text)
    }

    // ---- plumbing --------------------------------------------------------

    /**
     * One HTTP exchange, with every failure already translated.
     *
     * Note what is NOT logged: the key, the path's query string and the request
     * body. Only the method, the model-bearing part of the path and the status
     * code go to the diagnostics log, because that log is exportable and users
     * paste it into public bug reports.
     */
    private fun call(
        method: String,
        path: String,
        socksPort: Int,
        apiKey: String,
        jsonBody: String?,
    ): AiResult<String> {
        if (apiKey.isBlank()) {
            return AiResult.Err("No API key stored.", AiErrorKind.BAD_KEY)
        }
        var attempt = 0
        var last: AiResult.Err = AiResult.Err("No attempt was made.", AiErrorKind.TRANSPORT)
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val outcome = attemptCall(method, path, socksPort, apiKey, jsonBody)
            if (outcome is AiResult.Ok) return outcome
            val error = outcome as AiResult.Err
            last = error
            if (attempt >= MAX_ATTEMPTS || !error.kind.worthRetrying) break
            val wait = backoffMillis(attempt, error.retryAfterSeconds)
            DiagnosticsLog.i(
                "ai",
                "${error.kind} on attempt $attempt/$MAX_ATTEMPTS; retrying in ${wait}ms",
            )
            // Blocking sleep is correct here: every caller of this function is
            // already on Dispatchers.IO inside a cancellable coroutine (see
            // AiSession), and the socket reads either side of it block for far
            // longer. Sleeping keeps the whole retry policy in one readable place
            // instead of splitting it across suspend boundaries.
            try {
                Thread.sleep(wait)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return last
    }

    /** One HTTP exchange, with every failure already classified. */
    private fun attemptCall(
        method: String,
        path: String,
        socksPort: Int,
        apiKey: String,
        jsonBody: String?,
    ): AiResult<String> {
        val response = runCatching {
            GeminiHttp.request(method, path, socksPort, apiKey, jsonBody)
        }.getOrElse { failure ->
            DiagnosticsLog.w(
                "ai",
                "request failed via 127.0.0.1:$socksPort: ${failure.javaClass.simpleName}: " +
                    failure.message,
            )
            return AiResult.Err(
                failure.message ?: failure.javaClass.simpleName,
                AiErrorKind.TRANSPORT,
            )
        }
        DiagnosticsLog.i("ai", "$method ${path.substringBefore('?')} -> ${response.code}")
        if (response.ok) return AiResult.Ok(response.body)
        if (response.code == 0) {
            return AiResult.Err(
                "No response through the tunnel.",
                AiErrorKind.TRANSPORT,
            )
        }
        val error = parseObject(response.body)?.optJSONObject("error")
        val apiMessage = error?.optString("message")?.takeIf { it.isNotBlank() }
        val kind = when {
            response.code == 400 && apiMessage?.contains("API key", true) == true -> AiErrorKind.BAD_KEY
            response.code == 401 || response.code == 403 -> AiErrorKind.BAD_KEY
            response.code == 429 -> AiErrorKind.RATE_LIMIT
            response.code == 404 -> AiErrorKind.NO_SUCH_MODEL
            // 5xx is Google failing, NOT the tunnel failing. See AiErrorKind.SERVER_ERROR.
            response.code >= 500 -> AiErrorKind.SERVER_ERROR
            else -> AiErrorKind.PROTOCOL
        }
        return AiResult.Err(
            message = apiMessage ?: "HTTP ${response.code}",
            kind = kind,
            retryAfterSeconds = response.retryAfterSeconds ?: retryDelayFromError(error),
        )
    }

    /**
     * Reads Google's own `RetryInfo.retryDelay` out of an error payload.
     *
     * A 429 body carries `details[].retryDelay: "2.379075806s"`, i.e. the server
     * telling us exactly how long to wait. The field log shows five 429s inside
     * ten seconds because nobody read it. Honouring it is both faster than a fixed
     * backoff and the difference between waiting once and being rate-limited
     * harder for hammering.
     */
    private fun retryDelayFromError(error: JSONObject?): Double? {
        val details = error?.optJSONArray("details") ?: return null
        for (i in 0 until details.length()) {
            val raw = details.optJSONObject(i)?.optString("retryDelay")?.trim().orEmpty()
            if (raw.isEmpty()) continue
            val seconds = raw.removeSuffix("s").toDoubleOrNull() ?: continue
            if (seconds >= 0) return seconds
        }
        return null
    }

    /**
     * How long to wait before attempt [attempt] + 1.
     *
     * The server's own figure wins when it sent one; otherwise exponential with a
     * ceiling. The ceiling matters more than the curve: this runs while a user is
     * looking at a spinner, so a retry policy that is allowed to wait a minute is
     * a policy that looks like a hang.
     */
    private fun backoffMillis(attempt: Int, retryAfterSeconds: Double?): Long {
        val suggested = retryAfterSeconds?.let { (it * 1000).toLong() } ?: 0L
        val exponential = BASE_BACKOFF_MS shl (attempt - 1)
        return maxOf(suggested, exponential).coerceIn(BASE_BACKOFF_MS, MAX_BACKOFF_MS)
    }

    /**
     * Which failures a second attempt can plausibly fix.
     *
     * A rejected key, an unknown model and a safety refusal are deterministic:
     * retrying them wastes the user's time and quota to arrive at the same answer.
     * A 5xx, a rate limit and a dropped socket are not.
     */
    private val AiErrorKind.worthRetrying: Boolean
        get() = this == AiErrorKind.SERVER_ERROR ||
            this == AiErrorKind.RATE_LIMIT ||
            this == AiErrorKind.TRANSPORT

    private const val MAX_ATTEMPTS = 3
    private const val BASE_BACKOFF_MS = 700L
    private const val MAX_BACKOFF_MS = 6_000L

    private fun parseObject(body: String): JSONObject? =
        runCatching { JSONObject(body) }.getOrNull()

    private fun JSONObject.toGeminiModel(): GeminiModel {
        val methods = optJSONArray("supportedGenerationMethods")
        val supports = buildList {
            if (methods != null) for (i in 0 until methods.length()) add(methods.optString(i))
        }
        val raw = optString("name")
        return GeminiModel(
            id = raw.removePrefix("models/"),
            displayName = optString("displayName").ifBlank { raw.removePrefix("models/") },
            description = optString("description"),
            inputTokenLimit = optInt("inputTokenLimit", 0),
            outputTokenLimit = optInt("outputTokenLimit", 0),
            chatCapable = supports.contains("generateContent"),
        )
    }

    private const val MAX_MODEL_PAGES = 5
}
