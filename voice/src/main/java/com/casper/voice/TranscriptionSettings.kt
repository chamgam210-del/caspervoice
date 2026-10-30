package com.casper.voice

import org.json.JSONObject

/**
 * Everything that picks the ears, in one value a host can change at any
 * time (see [SpeechSettings], which works the same way for the voice).
 *
 * [language] is an ISO code such as "en". Pinning it stops a short or noisy
 * utterance from being guessed as another language. [prompt] can carry names
 * or terms the model should spell right.
 */
data class TranscriptionSettings(
    val provider: String = "openai",
    val baseUrl: String = OPENAI_URL,
    val model: String = OPENAI_MODEL,
    val language: String? = null,
    val prompt: String? = null,
    val apiKey: String? = null
) {
    /** The base URL to use, or [default] when the host left it at OpenAI's and the provider lives elsewhere. */
    fun baseUrlOr(default: String): String = if (baseUrl == OPENAI_URL) default else baseUrl

    /** The model to use, or [default] when the host left it at OpenAI's. */
    fun modelOr(default: String): String = if (model == OPENAI_MODEL) default else model

    /** Never prints the key, so settings can be logged. */
    override fun toString(): String =
        "TranscriptionSettings(provider=$provider, baseUrl=$baseUrl, model=$model, language=$language, " +
            "prompt=${if (prompt == null) "none" else "set"}, apiKey=${if (apiKey == null) "none" else "set"})"

    companion object {
        const val OPENAI_URL = "https://api.openai.com/v1"
        const val OPENAI_MODEL = "gpt-transcribe"

        fun parse(json: String): TranscriptionSettings {
            val o = JSONObject(json)
            val d = TranscriptionSettings()
            fun text(key: String): String? = if (o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotEmpty() }
            return TranscriptionSettings(
                provider = text("provider")?.lowercase() ?: d.provider,
                baseUrl = text("baseUrl")?.trimEnd('/') ?: d.baseUrl,
                model = text("model") ?: d.model,
                language = text("language"),
                prompt = text("prompt"),
                apiKey = text("apiKey")
            )
        }
    }
}
