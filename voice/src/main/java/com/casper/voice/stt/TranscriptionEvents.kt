package com.casper.voice.stt

import com.casper.voice.TranscriptionSettings
import org.json.JSONObject
import java.util.Base64

/**
 * The messages of OpenAI's streaming transcription session, built and read
 * in one place so they can be tested without a network.
 *
 * Audio goes up as 24 kHz PCM16, the rate that session takes. The library's
 * microphone is 16 kHz, so [upsample] converts it.
 */
object TranscriptionEvents {
    const val SAMPLE_RATE = 24_000
    const val COMMIT = """{"type":"input_audio_buffer.commit"}"""

    fun url(baseUrl: String): String {
        val ws = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> baseUrl
        }
        return "${ws.trimEnd('/')}/realtime?intent=transcription"
    }

    /** Turn detection is null on purpose: the library decides when the user has finished. */
    fun sessionUpdate(settings: TranscriptionSettings): String {
        val transcription = JSONObject().put("model", settings.model)
        settings.language?.let { transcription.put("language", it) }
        settings.prompt?.let { transcription.put("prompt", it) }
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
            .put("transcription", transcription)
            .put("turn_detection", JSONObject.NULL)
        return JSONObject()
            .put("type", "session.update")
            .put("session", JSONObject().put("type", "transcription").put("audio", JSONObject().put("input", input)))
            .toString()
    }

    fun append(samples16k: ShortArray): String {
        val up = upsample(samples16k)
        val bytes = ByteArray(up.size * 2)
        for (i in up.indices) {
            bytes[2 * i] = (up[i].toInt() and 0xff).toByte()
            bytes[2 * i + 1] = (up[i].toInt() shr 8).toByte()
        }
        return JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.getEncoder().encodeToString(bytes))
            .toString()
    }

    /** 16 kHz to 24 kHz by linear interpolation: three samples out for every two in. */
    fun upsample(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input
        val out = ShortArray(input.size * 3 / 2)
        for (i in out.indices) {
            val pos = i * 2.0 / 3.0
            val a = pos.toInt().coerceAtMost(input.lastIndex)
            val b = (a + 1).coerceAtMost(input.lastIndex)
            val f = pos - a
            out[i] = (input[a] * (1 - f) + input[b] * f).toInt().toShort()
        }
        return out
    }

    sealed interface Incoming {
        data class Delta(val text: String) : Incoming
        data class Completed(val text: String) : Incoming
        data class Error(val message: String) : Incoming
        data object Other : Incoming
    }

    fun parse(message: String): Incoming {
        val o = runCatching { JSONObject(message) }.getOrNull() ?: return Incoming.Other
        return when (o.optString("type")) {
            "conversation.item.input_audio_transcription.delta" -> Incoming.Delta(o.optString("delta"))
            "conversation.item.input_audio_transcription.completed" -> Incoming.Completed(o.optString("transcript"))
            "conversation.item.input_audio_transcription.failed" ->
                Incoming.Error(o.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "transcription failed" })
            "error" -> Incoming.Error(o.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "error" })
            else -> Incoming.Other
        }
    }
}
