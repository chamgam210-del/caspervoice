package com.casper.voice.stt

import com.casper.voice.SpeechToText
import com.casper.voice.TokenSource
import com.casper.voice.Transcript
import com.casper.voice.TranscriptionSettings
import com.casper.voice.tts.SpeechException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Deepgram's live transcription (`wss://api.deepgram.com/v1/listen`), one
 * connection per utterance, the same way [OpenAiTranscription] works.
 *
 * Audio goes up as raw 16 kHz PCM16, the microphone's own rate. When the
 * utterance ends, `CloseStream` asks Deepgram to finish; it sends the last
 * words and closes, and the words it marked final are the text.
 * [TranscriptionSettings.prompt] is sent as key terms (comma separated),
 * which Nova-3 uses to spell names right.
 */
class DeepgramTranscription(
    private val settings: TranscriptionSettings,
    private val keys: TokenSource,
    private val http: OkHttpClient = shared
) : SpeechToText {

    override fun transcribe(audio: Flow<ShortArray>): Flow<Transcript> = callbackFlow {
        val key = settings.apiKey ?: keys.token()
            ?: throw SpeechException("no API key for transcription (deepgram)")
        val request = Request.Builder()
            .url(url(settings))
            .header("Authorization", "Token $key")
            .build()

        val open = CompletableDeferred<Unit>()
        val finals = StringBuilder()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val r = parse(text) ?: return
                if (r.final) {
                    if (r.text.isNotBlank()) finals.append(if (finals.isEmpty()) "" else " ").append(r.text)
                    trySend(Transcript(finals.toString(), final = false))
                } else if (r.text.isNotBlank()) {
                    trySend(Transcript(listOf(finals.toString(), r.text).filter { it.isNotBlank() }.joinToString(" "), final = false))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                trySend(Transcript(finals.toString().trim(), final = true))
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(Transcript(finals.toString().trim(), final = true))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open.completeExceptionally(t)
                close(SpeechException(t.message ?: "transcription connection failed", response?.code))
            }
        })

        val pump = launch {
            open.await()
            audio.collect { socket.send(bytes(it).toByteString()) }
            socket.send(CLOSE_STREAM)
            delay(OpenAiTranscription.RESULT_TIMEOUT_MS)
            close(SpeechException("transcription timed out"))
        }
        awaitClose {
            pump.cancel()
            socket.cancel()
        }
    }

    data class Result(val text: String, val final: Boolean)

    companion object {
        const val URL = "wss://api.deepgram.com/v1"
        const val MODEL = "nova-3"
        const val CLOSE_STREAM = """{"type":"CloseStream"}"""

        fun url(settings: TranscriptionSettings): String {
            val base = settings.baseUrlOr(URL).trimEnd('/')
            // HttpUrl only parses http(s); the scheme is put back afterwards.
            val http = base.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
            val b = "$http/listen".toHttpUrl().newBuilder()
                .addQueryParameter("model", settings.modelOr(MODEL))
                .addQueryParameter("encoding", "linear16")
                .addQueryParameter("sample_rate", "16000")
                .addQueryParameter("channels", "1")
                .addQueryParameter("interim_results", "true")
                .addQueryParameter("punctuate", "true")
                .addQueryParameter("smart_format", "true")
            settings.language?.let { b.addQueryParameter("language", it) }
            settings.prompt?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach {
                b.addQueryParameter("keyterm", it)
            }
            val built = b.build().toString()
            return if (base.startsWith("ws")) built.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") else built
        }

        /** A `Results` message; null for metadata and anything else. */
        fun parse(message: String): Result? {
            val o = runCatching { JSONObject(message) }.getOrNull() ?: return null
            if (o.optString("type") != "Results") return null
            val alt = o.optJSONObject("channel")?.optJSONArray("alternatives")?.optJSONObject(0) ?: return null
            return Result(alt.optString("transcript").trim(), o.optBoolean("is_final"))
        }

        fun bytes(samples: ShortArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                out[2 * i] = (samples[i].toInt() and 0xff).toByte()
                out[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
            }
            return out
        }

        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
