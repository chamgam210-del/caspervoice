package com.casper.voice.stt

import com.casper.voice.SpeechToText
import com.casper.voice.TokenSource
import com.casper.voice.Transcript
import com.casper.voice.TranscriptionSettings
import com.casper.voice.audio.Wav
import com.casper.voice.tts.SpeechException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `POST {baseUrl}/audio/transcriptions` with the whole utterance as a WAV file.
 *
 * The plain file-upload API that OpenAI, Groq, Together, Fireworks and local
 * servers (whisper.cpp, faster-whisper, Speaches) all accept, so any of them
 * can be the ears. There are no partial transcripts: the text arrives once,
 * after the user has finished.
 */
class HttpTranscription(
    private val settings: TranscriptionSettings,
    private val keys: TokenSource,
    private val defaultBaseUrl: String = TranscriptionSettings.OPENAI_URL,
    private val defaultModel: String = TranscriptionSettings.OPENAI_MODEL,
    private val http: OkHttpClient = shared
) : SpeechToText {

    override fun transcribe(audio: Flow<ShortArray>): Flow<Transcript> = flow {
        val frames = ArrayList<ShortArray>()
        audio.collect { frames += it }
        if (frames.isEmpty()) {
            emit(Transcript("", final = true))
            return@flow
        }
        val key = settings.apiKey ?: keys.token()
        val request = request(Wav.bytes(frames), key)
        val text = http.newCall(request).await().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw SpeechException("transcription failed (${response.code}) ${body.take(300)}".trim(), response.code)
            textOf(body)
        }
        emit(Transcript(text, final = true))
    }.flowOn(Dispatchers.IO)

    internal fun request(wav: ByteArray, key: String?): Request {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV))
            .addFormDataPart("model", settings.modelOr(defaultModel))
            .addFormDataPart("response_format", "json")
        settings.language?.let { form.addFormDataPart("language", it) }
        settings.prompt?.let { form.addFormDataPart("prompt", it) }
        return Request.Builder()
            .url("${settings.baseUrlOr(defaultBaseUrl).trimEnd('/')}/audio/transcriptions")
            .apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }
            .post(form.build())
            .build()
    }

    companion object {
        const val GROQ_URL = "https://api.groq.com/openai/v1"
        const val GROQ_MODEL = "whisper-large-v3-turbo"

        private val WAV = "audio/wav".toMediaType()

        /** `{"text": "..."}` from most servers; a few local ones answer in plain text. */
        fun textOf(body: String): String {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) return trimmed
            return JSONObject(trimmed).optString("text").trim()
        }

        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(SpeechException(e.message ?: "request failed"))
        }
    })
    cont.invokeOnCancellation { cancel() }
}
