package com.casper.voice.stt

import com.casper.voice.SpeechToText
import com.casper.voice.TokenSource
import com.casper.voice.TranscriptionSettings
import com.casper.voice.tts.SpeechException
import java.util.concurrent.ConcurrentHashMap

/**
 * The transcription providers by name, the same way [com.casper.voice.tts.SpeechProviders]
 * holds the voices. Adding one is a class implementing [SpeechToText] and one
 * [register] call.
 *
 * Built in:
 * - `openai`: OpenAI's streaming transcription session, with partial text.
 * - `openai-compatible`: any server with OpenAI's file-upload
 *   `/audio/transcriptions` API (set `baseUrl` and `model`), such as a local
 *   whisper.cpp or faster-whisper server.
 * - `groq`: the same, with Groq's address and Whisper model filled in.
 * - `deepgram`: Deepgram's live transcription, Nova-3 by default, with partial text.
 */
object TranscriptionProviders {
    private val makers = ConcurrentHashMap<String, (TranscriptionSettings, TokenSource) -> SpeechToText>().apply {
        put("openai") { settings, keys -> OpenAiTranscription(settings, keys) }
        put("openai-compatible") { settings, keys -> HttpTranscription(settings, keys) }
        put("groq") { settings, keys ->
            HttpTranscription(settings, keys, defaultBaseUrl = HttpTranscription.GROQ_URL, defaultModel = HttpTranscription.GROQ_MODEL)
        }
        put("deepgram") { settings, keys -> DeepgramTranscription(settings, keys) }
    }

    fun register(name: String, maker: (TranscriptionSettings, TokenSource) -> SpeechToText) {
        makers[name.lowercase()] = maker
    }

    val names: Set<String> get() = makers.keys

    fun create(settings: TranscriptionSettings, keys: TokenSource): SpeechToText {
        val maker = makers[settings.provider.lowercase()]
            ?: throw SpeechException("unknown transcription provider '${settings.provider}' (have: ${names.sorted().joinToString()})")
        return maker(settings, keys)
    }
}
