package com.casper.voice.stt

import com.casper.voice.SpeechToText
import com.casper.voice.TokenSource
import com.casper.voice.Transcript
import com.casper.voice.TranscriptionSettings
import com.casper.voice.tts.SpeechException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Transcribes one utterance over OpenAI's streaming transcription session.
 *
 * One call is one utterance: the connection opens, the audio goes up as it
 * arrives, and when [audio] ends the session is told the speech is over and
 * the text comes back. Words arrive as they are recognised, then the whole
 * text, then the connection closes. It never decides where speech ends; the
 * caller does, by ending [audio].
 */
class OpenAiTranscription(
    private val settings: TranscriptionSettings,
    private val keys: TokenSource,
    private val http: OkHttpClient = shared
) : SpeechToText {

    override fun transcribe(audio: Flow<ShortArray>): Flow<Transcript> = callbackFlow {
        val key = settings.apiKey ?: keys.token()
            ?: throw SpeechException("no API key for transcription (${settings.provider})")
        val request = Request.Builder()
            .url(TranscriptionEvents.url(settings.baseUrl))
            .header("Authorization", "Bearer $key")
            .build()

        val open = CompletableDeferred<Unit>()
        var heard = ""
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(TranscriptionEvents.sessionUpdate(settings))
                open.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val e = TranscriptionEvents.parse(text)) {
                    is TranscriptionEvents.Incoming.Delta -> {
                        heard += e.text
                        trySend(Transcript(heard, final = false))
                    }
                    is TranscriptionEvents.Incoming.Completed -> {
                        trySend(Transcript(e.text.trim(), final = true))
                        close()
                    }
                    is TranscriptionEvents.Incoming.Error -> {
                        // Nothing was said that the model could hear: not a failure.
                        if (e.message.contains("empty", ignoreCase = true)) close()
                        else close(SpeechException(e.message))
                    }
                    TranscriptionEvents.Incoming.Other -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open.completeExceptionally(t)
                close(SpeechException(t.message ?: "transcription connection failed", response?.code))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        })

        val pump = launch {
            open.await()
            audio.collect { socket.send(TranscriptionEvents.append(it)) }
            socket.send(TranscriptionEvents.COMMIT)
            delay(RESULT_TIMEOUT_MS)
            close(SpeechException("transcription timed out"))
        }
        awaitClose {
            pump.cancel()
            socket.cancel()
        }
    }

    companion object {
        /** After the speech has ended, the text is normally back in about a second. */
        const val RESULT_TIMEOUT_MS = 10_000L

        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
