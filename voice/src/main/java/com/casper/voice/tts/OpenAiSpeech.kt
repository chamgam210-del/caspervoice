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
 * `POST {baseUrl}/audio/speech`, streamed as raw 24 kHz 16-bit mono PCM.
 * Works with OpenAI and with any server that copies its API.
 */
class OpenAiSpeech(
    private val settings: SpeechSettings,
    private val keys: TokenSource
) : TextToSpeech {
    override val sampleRate: Int = SAMPLE_RATE

    override fun synthesize(text: String): Flow<ShortArray> = flow {
        val key = keys.token()
        if (key.isNullOrBlank() && settings.baseUrl.startsWith("https://api.openai.com")) {
            throw SpeechException("no API key for ${settings.provider}")
        }
        val conn = URL("${settings.baseUrl}/audio/speech").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (!key.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(requestBody(settings, text).toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw SpeechException("speech request failed ($code) ${detail.take(300)}".trim(), code)
            }
            val pcm = PcmReader()
            val buffer = ByteArray(CHUNK_BYTES)
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
        private const val CHUNK_BYTES = 4_800
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        /** `instructions` only goes to models that read it; others reject unknown fields. */
        fun requestBody(settings: SpeechSettings, text: String): String {
            val o = JSONObject()
                .put("model", settings.model)
                .put("voice", settings.voice)
                .put("input", text)
                .put("response_format", "pcm")
            if (!settings.instructions.isNullOrBlank() && settings.model.startsWith("gpt-4o")) {
                o.put("instructions", settings.instructions)
            }
            settings.speed?.let { o.put("speed", it) }
            return o.toString()
        }
    }
}

/** Raw little-endian PCM16 arrives in arbitrary byte counts; a sample split across two reads is held over. */
class PcmReader {
    private var carry = -1

    fun feed(bytes: ByteArray, count: Int): ShortArray {
        var offset = 0
        var total = count
        val first = if (carry >= 0 && count > 0) {
            val s = ((bytes[0].toInt() shl 8) or carry).toShort()
            carry = -1
            offset = 1
            total -= 1
            s
        } else null
        val whole = total / 2
        if (total % 2 == 1) carry = bytes[offset + whole * 2].toInt() and 0xFF
        val extra = if (first != null) 1 else 0
        val out = ShortArray(whole + extra)
        if (first != null) out[0] = first
        for (i in 0 until whole) {
            val lo = bytes[offset + i * 2].toInt() and 0xFF
            val hi = bytes[offset + i * 2 + 1].toInt()
            out[extra + i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }
}
