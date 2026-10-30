package com.casper.voice

import org.json.JSONObject

/**
 * Everything that picks the voice, in one value a host can change at any
 * time. Nothing in the library caches it: the next line spoken uses whatever
 * the host hands over then, so a model, voice or key can change between two
 * sentences.
 *
 * [provider] chooses the implementation (see [SpeechProviders]). [baseUrl]
 * lets "openai" talk to any server that speaks the same API, such as a
 * self-hosted one. [apiKey] is optional: without it the host's key source
 * is asked.
 */
data class SpeechSettings(
    val provider: String = "openai",
    val baseUrl: String = OPENAI_URL,
    val model: String = OPENAI_MODEL,
    val voice: String = OPENAI_VOICE,
    /** How to speak, for models that take direction ("warm, unhurried"). */
    val instructions: String? = null,
    val speed: Double? = null,
    val apiKey: String? = null
) {
    /** The base URL to use, or [default] when the host left it at OpenAI's and the provider lives elsewhere. */
    fun baseUrlOr(default: String): String = if (baseUrl == OPENAI_URL) default else baseUrl

    /** The voice to use, or [default] when the host left it at OpenAI's. */
    fun voiceOr(default: String): String = if (voice == OPENAI_VOICE) default else voice

    /** The model to use, or [default] when the host left it at OpenAI's. */
    fun modelOr(default: String): String = if (model == OPENAI_MODEL) default else model

    /** Never prints the key, so settings can be logged. */
    override fun toString(): String =
        "SpeechSettings(provider=$provider, baseUrl=$baseUrl, model=$model, voice=$voice, speed=$speed, " +
            "instructions=${if (instructions == null) "none" else "set"}, apiKey=${if (apiKey == null) "none" else "set"})"

    companion object {
        const val OPENAI_URL = "https://api.openai.com/v1"
        const val OPENAI_MODEL = "gpt-4o-mini-tts"
        const val OPENAI_VOICE = "cedar"

        /** Missing fields keep their defaults, unknown ones are ignored. */
        fun parse(json: String): SpeechSettings {
            val o = JSONObject(json)
            val d = SpeechSettings()
            fun text(key: String): String? = if (o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotEmpty() }
            return SpeechSettings(
                provider = text("provider")?.lowercase() ?: d.provider,
                baseUrl = text("baseUrl")?.trimEnd('/') ?: d.baseUrl,
                model = text("model") ?: d.model,
                voice = text("voice") ?: d.voice,
                instructions = text("instructions"),
                speed = if (o.isNull("speed") || !o.has("speed")) null else o.optDouble("speed").takeIf { !it.isNaN() },
                apiKey = text("apiKey")
            )
        }
    }
}
