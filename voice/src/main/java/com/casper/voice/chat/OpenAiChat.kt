package com.casper.voice.chat

import com.casper.voice.Responder
import com.casper.voice.TokenSource
import com.casper.voice.tts.SpeechException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The language model behind [OpenAiChat]. Like the other settings, it can change between turns. */
data class ChatSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-6-luna",
    val instructions: String = DEFAULT_INSTRUCTIONS,
    /** Earlier turns kept, user and assistant lines together. */
    val historyTurns: Int = 20,
    val apiKey: String? = null
) {
    override fun toString(): String =
        "ChatSettings(baseUrl=$baseUrl, model=$model, historyTurns=$historyTurns, apiKey=${if (apiKey == null) "none" else "set"})"

    companion object {
        const val DEFAULT_INSTRUCTIONS =
            "You are a helpful voice assistant. Everything you write is spoken aloud, so answer in short, " +
                "natural sentences with no markdown, lists or links."
    }
}

/**
 * A [Responder] backed by any server with OpenAI's streaming
 * `/chat/completions` API: OpenAI, Groq, OpenRouter, Together, or a local
 * Ollama or llama.cpp server. It keeps the conversation, and when the user
 * cuts a reply off only the part they heard is remembered.
 */
class OpenAiChat(
    private val settings: suspend () -> ChatSettings,
    private val keys: TokenSource,
    private val http: OkHttpClient = shared
) : Responder {
    private val history = ArrayList<JSONObject>()
    private var replying: StringBuilder? = null

    override fun reply(heard: String): Flow<String> = flow {
        val s = settings()
        val key = s.apiKey ?: keys.token()
        if (key.isNullOrBlank() && s.baseUrl.startsWith("https://api.openai.com")) throw SpeechException("no API key for chat")
        synchronized(history) { history += message("user", heard) }
        val request = Request.Builder()
            .url("${s.baseUrl.trimEnd('/')}/chat/completions")
            .apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }
            .post(body(s).toRequestBody(JSON))
            .build()
        val said = StringBuilder()
        replying = said
        val call = http.newCall(request)
        // A blocking read does not see cancellation, so a barge-in cancels the call itself.
        val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw SpeechException("chat failed (${response.code}) ${response.body.string().take(300)}".trim(), response.code)
            }
            val source = response.body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val piece = delta(line) ?: continue
                said.append(piece)
                emit(piece)
            }
        }
        cancel?.dispose()
    }.onCompletion {
        // A cut-off reply was already recorded by cutOff.
        val said = replying ?: return@onCompletion
        replying = null
        if (said.isNotBlank()) remember(said.toString())
    }.flowOn(Dispatchers.IO)

    override fun cutOff(spoken: String) {
        replying = null
        remember(spoken)
    }

    /** Forgets the conversation. */
    fun clear() = synchronized(history) { history.clear() }

    private fun remember(assistant: String) = synchronized(history) {
        if (assistant.isNotBlank()) history += message("assistant", assistant.trim())
    }

    private fun body(s: ChatSettings): String {
        val messages = JSONArray().put(message("system", s.instructions))
        synchronized(history) {
            while (history.size > s.historyTurns * 2) history.removeAt(0)
            history.forEach { messages.put(it) }
        }
        return JSONObject()
            .put("model", s.model)
            .put("stream", true)
            .put("messages", messages)
            .toString()
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun message(role: String, content: String): JSONObject = JSONObject().put("role", role).put("content", content)

        /** The text in one server-sent-events line, or null for anything else. */
        fun delta(line: String): String? {
            if (!line.startsWith("data:")) return null
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]" || data.isEmpty()) return null
            val o = runCatching { JSONObject(data) }.getOrNull() ?: return null
            val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: return null
            val delta = choice.optJSONObject("delta") ?: return null
            if (delta.isNull("content")) return null
            return delta.optString("content").takeIf { it.isNotEmpty() }
        }

        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
