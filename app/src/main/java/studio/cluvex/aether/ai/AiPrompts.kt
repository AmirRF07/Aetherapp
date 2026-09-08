package studio.cluvex.aether.ai

import org.json.JSONArray
import org.json.JSONObject

/** What the log analysis came back with. */
data class AiAdvice(
    /** What the operator's inspection appears to be doing, in the user's language. */
    val dpi: String,
    /** "high" | "medium" | "low" - how much the log actually supports the reading. */
    val confidence: String,
    /** One short paragraph the user reads first. */
    val summary: String,
    /** Proposed setting changes, already filtered to [AiPatch.WRITABLE] keys. */
    val changes: List<AiChange>,
)

/**
 * Every prompt the app sends, and the parser for what comes back.
 *
 * ## Two rules that shape all of it
 *
 * **1. This is a settings optimiser, not an evasion adviser.** The feature reads
 * the app's own connection log and tunes the app's own options. So the system
 * instructions say that explicitly and bound the model to the options listed in
 * [AiPatch.WRITABLE] - a model asked to "get around DPI" free-form will happily
 * produce confident, wrong, unactionable advice about other people's
 * infrastructure. Asked "which of these 29 knobs best fits the failures in this
 * log", it produces something the app can actually apply and the user can
 * actually judge.
 *
 * **2. Answer in the user's language, ask in ours.** The topic descriptions in
 * [AiTopic] and the setting keys in [AiPatch] are English, because they are
 * technical ground truth and translating them would blur it. The instruction to
 * reply in Persian is separate, so a Persian user gets a Persian answer about an
 * accurately described setting rather than an English answer or an accurate-
 * sounding Persian answer about the wrong thing.
 */
object AiPrompts {

    /** The fenced block the model uses to request setting changes. */
    const val APPLY_FENCE = "aether-apply"

    private fun languageRule(persian: Boolean): String =
        if (persian) {
            "Answer in Persian (فارسی), in a natural, friendly, technically precise " +
                "register. Keep setting names, protocol names, numbers and units in " +
                "Latin script (MTU, MASQUE, WireGuard, 1280) because that is how they " +
                "appear in the app's own UI."
        } else {
            "Answer in English, plainly and precisely."
        }

    private val PRODUCT = """
        You are the built-in assistant of Aether, an Android censorship-circumvention
        VPN app used mainly in Iran. Facts about the app you must not contradict:
        - Two network backends: "Aether" (one hop through a bundled Aether/WARP engine)
          and "Aether -> Psiphon" (the engine runs first as a local SOCKS5 proxy and
          Psiphon dials out through it, so the exit IP is Psiphon's).
        - Transports: MASQUE (QUIC or HTTP/2), WireGuard, and WARP*2 ("gool", WARP in
          WARP), plus a Smart mode that tries strategies and keeps what works.
        - Anti-DPI options it really has: Amnezia-style obfuscation profiles ("noize"),
          TLS ClientHello fragmentation with size and delay ranges, Encrypted Client
          Hello, MTU control, endpoint scanning strategies, and TLS group selection.
        - You are talking to the user THROUGH that tunnel: this conversation only
          works while the app is connected in the chained "Aether -> Psiphon" mode.
    """.trimIndent()

    private val SCOPE = """
        Your job is to OPTIMISE THIS APP'S OWN SETTINGS for the network the user is
        on. Diagnose what the app's log shows and map it onto the app's options. Do
        not speculate about, or give instructions for, attacking or defeating any
        third party's infrastructure - you have no information about it and the app
        has no controls for it. If the log does not support a conclusion, say so
        instead of inventing one.
    """.trimIndent()

    /** System instruction for the "what is this setting" sheet. */
    fun explain(persian: Boolean, topic: AiTopic, currentValue: String?): String = """
        $PRODUCT

        ${languageRule(persian)}

        The user tapped the AI icon next to one specific option and wants to
        understand it. Explain, in three short labelled parts and nothing else:
        1. What it is.
        2. What it is for - what problem it solves.
        3. How to use it - when to change it, what to set it to, and what the
           trade-off is. If the sensible answer is "leave it alone", say that.

        Be concrete and short: about 120 words in total. No preamble, no
        pleasantries, no markdown headings above level three, no bullet nesting.

        THE OPTION (this description is authoritative - do not contradict it and do
        not guess beyond it):
        name: ${topic.label}
        behaviour: ${topic.detail}
        ${if (currentValue != null) "the user's current value: $currentValue" else ""}
    """.trimIndent()

    /**
     * System instruction for the DPI / log analysis.
     *
     * @param strict added on a re-ask after an answer that could not be parsed.
     *   Kept out of the first attempt on purpose: a wall of "DO NOT" makes shorter,
     *   more timid answers, and the first attempt is the one that succeeds almost
     *   every time now that the request runs in JSON mode with a real token budget.
     */
    fun advisor(persian: Boolean, profileSnapshot: String, strict: Boolean = false): String = """
        $PRODUCT

        $SCOPE

        ${languageRule(persian)}

        You will be given a REDACTED excerpt of the app's own connection log (public
        IP addresses are masked to a /16 and credentials are removed) plus the user's
        current settings. Work out what the operator's traffic inspection appears to
        be doing to THIS connection - for example: blocking UDP or QUIC, resetting on
        the TLS ClientHello, blocking specific edge IP ranges, throttling after a
        volume, breaking long-lived connections, or nothing unusual at all - and
        propose the settings that fit that evidence.

        Reply with ONE JSON object and nothing else. No prose, no code fence:
        {
          "dpi": "one or two sentences: what the inspection appears to be doing",
          "confidence": "high" | "medium" | "low",
          "summary": "one short paragraph for the user, plain language",
          "changes": [
            { "key": "<setting key>", "value": "<new value>", "why": "one short sentence" }
          ]
        }

        Rules for "changes":
        - Only these keys, with these accepted values:
        ${AiPatch.WRITABLE.entries.joinToString("\n        ") { "  ${it.key}: ${it.value}" }}
        - Propose at most 5 changes, and only ones the log actually justifies. An
          empty "changes" array is the correct answer when the connection looks
          healthy - say so in "summary" and do not invent work.
        - Never repeat a value the user already has.
        - "dpi", "summary" and every "why" must be in the user's language;
          "key" and "value" must be exactly as listed above.

        THE USER'S CURRENT SETTINGS:
        $profileSnapshot
        ${if (strict) STRICT_JSON else ""}
    """.trimIndent()

    /**
     * The extra pressure applied only on a re-ask.
     *
     * "Shorter" is the operative instruction, not "please comply": the previous
     * attempt failed because the answer did not fit, so asking for the same answer
     * more firmly would fail the same way.
     */
    private val STRICT_JSON = """

        THIS IS A SECOND ATTEMPT. The previous answer could not be used. Output the
        JSON object and NOTHING else - no code fence, no leading sentence, no
        trailing note. Keep every string SHORT: "dpi" at most two sentences,
        "summary" at most three, each "why" one clause. Emit at most 3 changes. A
        complete short answer is required; a long truncated one is worthless.
    """.trimIndent()

    /** System instruction for the in-app chat. */
    fun chat(persian: Boolean, profileSnapshot: String, model: String): String = """
        $PRODUCT

        $SCOPE

        ${languageRule(persian)}

        You are answering inside the app's own chat screen. You are $model. Be
        useful and brief: a few sentences or a short list, no filler, no
        self-description unless asked. You may answer general questions too, not
        only questions about the app.

        THE USER CAN ASK YOU TO CHANGE SETTINGS. When - and only when - the user
        asks for a change, append at the very END of your reply one fenced block:

        ```$APPLY_FENCE
        {"changes":[{"key":"mtu","value":"1280","why":"short reason"}]}
        ```

        - Put your normal answer above the block; the app strips the block out and
          turns it into a button the user has to press. Never claim you already
          changed something - you are proposing it.
        - Only these keys and values are possible:
        ${AiPatch.WRITABLE.entries.joinToString("\n        ") { "  ${it.key}: ${it.value}" }}
        - Anything else - the network backend, the upstream proxy, routing rules,
          which apps are tunnelled, pinning a manual endpoint, organization
          credentials - you CANNOT change, by design, because those decide which
          traffic is protected and where it goes. If asked, explain where to change
          it by hand instead: Settings, then the relevant page.
        - Tunnel settings are handed to the engine when it starts, so an applied
          change takes effect on the NEXT connect. Say so when it matters.

        THE USER'S CURRENT SETTINGS:
        $profileSnapshot
    """.trimIndent()

    /**
     * Builds the user turn that carries the log for [advisor].
     *
     * The instruction stays in English even for a Persian user: it is addressed to
     * the model, not to the user, and the language the ANSWER comes back in is set
     * once, in the system instruction.
     */
    fun advisorRequest(logDigest: String): String = buildString {
        append("Here is the log of my current session. Analyse it and answer with the JSON object.")
        append("\n\n--- BEGIN LOG (redacted) ---\n")
        append(logDigest)
        append("\n--- END LOG ---")
    }

    // ---- parsing ---------------------------------------------------------

    /**
     * Parses the advisor's JSON answer.
     *
     * Tolerant on purpose. Models wrap JSON in fences, prepend "Here you go:", and
     * occasionally emit a trailing comma; a strict parse would turn a perfectly
     * usable answer into "something went wrong", so the outermost balanced object
     * is extracted first and only then parsed. If that fails there is genuinely
     * nothing to use, and null is returned so the caller can show the raw text.
     */
    fun parseAdvice(raw: String): AiAdvice? {
        val json = extractJsonObject(raw) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val changes = parseChanges(obj.optJSONArray("changes"))
        val summary = obj.optString("summary").trim()
        val dpi = obj.optString("dpi").trim()
        if (summary.isEmpty() && dpi.isEmpty() && changes.isEmpty()) return null
        return AiAdvice(
            dpi = dpi,
            confidence = obj.optString("confidence").trim().lowercase(),
            summary = summary,
            changes = changes,
        )
    }

    /**
     * Splits a chat reply into the prose the user sees and the changes it proposes.
     *
     * The block is REMOVED from the visible text: leaving raw JSON in a chat bubble
     * is how an assistant announces that it is a script. The user sees a sentence
     * and a button.
     */
    fun splitChatReply(raw: String): Pair<String, List<AiChange>> {
        val fenceStart = raw.indexOf("```$APPLY_FENCE")
        if (fenceStart < 0) return raw.trim() to emptyList()
        val bodyStart = raw.indexOf('\n', fenceStart)
        if (bodyStart < 0) return raw.trim() to emptyList()
        val fenceEnd = raw.indexOf("```", bodyStart)
        val block = if (fenceEnd < 0) raw.substring(bodyStart) else raw.substring(bodyStart, fenceEnd)
        val visible = (
            raw.substring(0, fenceStart) +
                if (fenceEnd < 0) "" else raw.substring(fenceEnd + 3)
            ).trim()
        val json = extractJsonObject(block) ?: return visible to emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return visible to emptyList()
        return visible to parseChanges(obj.optJSONArray("changes"))
    }

    private fun parseChanges(array: JSONArray?): List<AiChange> {
        if (array == null) return emptyList()
        val out = mutableListOf<AiChange>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val key = item.optString("key").trim()
            if (key.isEmpty()) continue
            // Numbers and booleans arrive unquoted about half the time; optString
            // coerces both, which is why the whole pipeline speaks text.
            val value = item.optString("value").trim()
            out += AiChange(key = key, value = value, why = item.optString("why").trim())
        }
        return out
    }

    /** Returns the first balanced `{...}` in [raw], or null. */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
