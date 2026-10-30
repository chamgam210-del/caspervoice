package com.casper.voice.tts

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.UtteranceProgressListener
import com.casper.voice.SpeechSettings
import com.casper.voice.TextToSpeech
import com.casper.voice.audio.Resampler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * The phone's own text-to-speech engine: free, offline, no key.
 *
 * The audio is taken as the engine makes it, through
 * [UtteranceProgressListener.onAudioAvailable], and played by the library's
 * player like any other voice, so ducking, barge-in and the echo canceller
 * work the same. Engines pick their own sample rate, so it is converted to
 * [SAMPLE_RATE].
 *
 * [SpeechSettings.voice] is a system voice name (see
 * `android.speech.tts.TextToSpeech.getVoices`) or a language tag such as
 * "en-GB"; anything else keeps the engine's default. [SpeechSettings.model]
 * can name an engine package such as "com.google.android.tts".
 */
class SystemSpeech(
    private val context: Context,
    private val settings: SpeechSettings
) : TextToSpeech {
    override val sampleRate: Int = SAMPLE_RATE

    override fun synthesize(text: String): Flow<ShortArray> = callbackFlow {
        val engine = engine(context, settings.model.takeIf { it != SpeechSettings.OPENAI_MODEL && it.contains('.') })
        val id = UUID.randomUUID().toString()
        val file = File.createTempFile("speech", ".wav", context.cacheDir)
        var resampler: Resampler? = null
        val bytes = PcmReader()
        sinks[id] = object : Sink {
            override fun begin(rate: Int, format: Int) {
                if (format != AudioFormat.ENCODING_PCM_16BIT) {
                    close(SpeechException("system voice gave audio format $format, not 16-bit"))
                    return
                }
                resampler = Resampler(rate, SAMPLE_RATE)
            }

            override fun audio(chunk: ByteArray) {
                val r = resampler ?: return
                val out = r.feed(bytes.feed(chunk, chunk.size))
                if (out.isNotEmpty()) trySend(out)
            }

            override fun done() { close() }
            override fun error(code: Int) { close(SpeechException("system voice failed ($code)")) }
        }
        apply(engine, settings)
        val queued = engine.synthesizeToFile(text, null, file, id)
        if (queued != AndroidTts.SUCCESS) close(SpeechException("system voice refused the line"))
        awaitClose {
            sinks.remove(id)
            file.delete()
        }
    }

    private interface Sink {
        fun begin(rate: Int, format: Int)
        fun audio(chunk: ByteArray)
        fun done()
        fun error(code: Int)
    }

    companion object {
        const val SAMPLE_RATE = 24_000

        private val sinks = ConcurrentHashMap<String, Sink>()
        private val lock = Mutex()
        @Volatile private var shared: AndroidTts? = null
        @Volatile private var sharedPackage: String? = null

        private suspend fun engine(context: Context, pkg: String?): AndroidTts = lock.withLock {
            shared?.takeIf { sharedPackage == pkg }?.let { return@withLock it }
            shared?.shutdown()
            val ready = CompletableDeferred<Int>()
            val tts = withContext(Dispatchers.Main) {
                if (pkg == null) AndroidTts(context.applicationContext) { ready.complete(it) }
                else AndroidTts(context.applicationContext, { ready.complete(it) }, pkg)
            }
            if (ready.await() != AndroidTts.SUCCESS) {
                tts.shutdown()
                throw SpeechException("the phone's text-to-speech engine did not start")
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    sinks[utteranceId]?.begin(sampleRateInHz, audioFormat)
                }
                override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                    sinks[utteranceId]?.audio(audio)
                }
                override fun onDone(utteranceId: String) { sinks[utteranceId]?.done() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) { sinks[utteranceId]?.error(-1) }
                override fun onError(utteranceId: String, errorCode: Int) { sinks[utteranceId]?.error(errorCode) }
                override fun onStop(utteranceId: String, interrupted: Boolean) { sinks[utteranceId]?.done() }
            })
            shared = tts
            sharedPackage = pkg
            tts
        }

        private fun apply(tts: AndroidTts, settings: SpeechSettings) {
            val voice = settings.voice
            val named = runCatching { tts.voices?.firstOrNull { it.name == voice } }.getOrNull()
            when {
                named != null -> tts.voice = named
                voice != SpeechSettings.OPENAI_VOICE && voice.matches(Regex("[A-Za-z]{2,3}([-_][A-Za-z0-9]+)*")) ->
                    tts.language = Locale.forLanguageTag(voice.replace('_', '-'))
            }
            tts.setSpeechRate((settings.speed ?: 1.0).toFloat())
        }

        /** Frees the engine; the next line starts it again. */
        fun shutdown() {
            shared?.shutdown()
            shared = null
            sharedPackage = null
        }
    }
}
