package com.casper.voice.tts

import android.content.Context
import com.casper.voice.SpeechSettings
import com.casper.voice.TextToSpeech
import com.casper.voice.TokenSource
import java.util.concurrent.ConcurrentHashMap

/** A provider failing in a way worth reporting: no key, a refusal, a dropped connection. */
class SpeechException(message: String, val status: Int? = null) : Exception(message)

/**
 * The speech providers by name. Adding one is a class implementing
 * [TextToSpeech] and one [register] call; nothing else in the library or the
 * host changes.
 *
 * Built in:
 * - `openai`: OpenAI's `/audio/speech`, or any server copying it (set `baseUrl`),
 *   such as a local Kokoro or Piper server.
 * - `elevenlabs`: ElevenLabs streaming speech; `voice` is a voice ID.
 * - `system`: the phone's own engine, free and offline, once [installSystemVoice]
 *   has run ([com.casper.voice.VoiceAgent] does it).
 */
object SpeechProviders {
    private val makers = ConcurrentHashMap<String, (SpeechSettings, TokenSource) -> TextToSpeech>().apply {
        put("openai") { settings, keys -> OpenAiSpeech(settings, keys) }
        put("openai-compatible") { settings, keys -> OpenAiSpeech(settings, keys) }
        put("elevenlabs") { settings, keys -> ElevenLabsSpeech(settings, keys) }
    }

    /** Adds `system`, the phone's own text-to-speech engine. */
    fun installSystemVoice(context: Context) {
        val app = context.applicationContext
        register("system") { settings, _ -> SystemSpeech(app, settings) }
    }

    fun register(name: String, maker: (SpeechSettings, TokenSource) -> TextToSpeech) {
        makers[name.lowercase()] = maker
    }

    val names: Set<String> get() = makers.keys

    fun create(settings: SpeechSettings, keys: TokenSource): TextToSpeech {
        val maker = makers[settings.provider.lowercase()]
            ?: throw SpeechException("unknown speech provider '${settings.provider}' (have: ${names.sorted().joinToString()})")
        return maker(settings, keys)
    }
}
