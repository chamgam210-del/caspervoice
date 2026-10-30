package com.casper.voice.tts

import com.casper.voice.SpeechSettings
import com.casper.voice.TextToSpeech
import com.casper.voice.TokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * `POST {baseUrl}/text-to-speech/{voice}/stream`, as raw 24 kHz PCM16.
 *
 * [SpeechSettings.voice] is an ElevenLabs voice ID. Left at the OpenAI
 * default, a stock ElevenLabs voice is used instead.
 */
class ElevenLabsSpeech(
    private val settings: SpeechSettings,
    private val keys: TokenSource
) : TextToSpeech {
    override val sampleRate: Int = SAMPLE_RATE

    override fun synthesize(text: String): Flow<ShortArray> = flow {
        val key = settings.apiKey ?: keys.token()
        if (key.isNullOrBlank()) throw SpeechException("no API key for elevenlabs")
        val conn = URL(url(settings)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("xi-api-key", key)
            conn.outputStream.use { it.write(requestBody(settings, text).toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw SpeechException("speech request failed ($code) ${detail.take(300)}".trim(), code)
            }
            val pcm = PcmReader()
            val buffer = ByteArray(4_800)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    val samples = pcm.feed(buffer, n)
                    if (samples.isNotEmpty()) emit(samples)
                }
            }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val SAMPLE_RATE = 24_000
        const val URL = "https://api.elevenlabs.io/v1"
        const val MODEL = "eleven_flash_v2_5"
        /** "George", one of the stock voices every account has. */
        const val VOICE = "JBFqnCBsd6RMkjVDRZzb"

        fun url(settings: SpeechSettings): String =
            "${settings.baseUrlOr(URL).trimEnd('/')}/text-to-speech/${settings.voiceOr(VOICE)}/stream?output_format=pcm_24000"

        fun requestBody(settings: SpeechSettings, text: String): String {
            val o = JSONObject()
                .put("text", text)
                .put("model_id", settings.modelOr(MODEL))
            settings.speed?.let { o.put("voice_settings", JSONObject().put("speed", it)) }
            return o.toString()
        }
    }
}
