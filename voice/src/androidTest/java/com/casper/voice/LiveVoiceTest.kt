package com.casper.voice

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.casper.voice.audio.Resampler
import com.casper.voice.chat.ChatSettings
import com.casper.voice.chat.OpenAiChat
import com.casper.voice.tts.SpeechException
import com.casper.voice.tts.SpeechProviders
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The library on a real phone with real models, and no microphone: speech is
 * made with a text-to-speech model, then fed to the agent as if it came from
 * the microphone. It goes through the bundled Silero model, turn-taking and
 * the real transcriber, so this checks the whole hearing path end to end.
 *
 * Keys come from an env.properties packed into the test APK only (see
 * voice/build.gradle.kts). A test whose key is missing is skipped, not failed.
 */
/** Makes sound on the phone's speaker; leave it out with `notAnnotation` while someone is on a call. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class PlaysAudio

@RunWith(AndroidJUnit4::class)
class LiveVoiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().context
    private val keys: Properties by lazy {
        Properties().apply { runCatching { context.assets.open("env.properties").use { load(it) } } }
    }

    private fun key(name: String): String? = keys.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

    private val keySource = KeySource { provider ->
        when (provider) {
            "openai", "openai-compatible" -> key("OPENAI_API_KEY")
            "elevenlabs" -> key("ELEVENLABS_API_KEY")
            "deepgram" -> key("DEEPGRAM_API_KEY")
            "groq" -> key("GROQ_API_KEY")
            else -> null
        }
    }

    private fun needs(name: String) = assumeTrue("no $name in the test keys", key(name) != null)

    // ---- speaking ----

    @Test
    fun openAiVoiceStreamsSpeech() = runBlocking {
        needs("OPENAI_API_KEY")
        val ms = speechMs(SpeechSettings(), "Hello from the voice library.")
        assertTrue("got $ms ms of speech", ms in 800..6_000)
    }

    @Test
    fun theSystemVoiceSpeaksWithNoKey() = runBlocking {
        SpeechProviders.installSystemVoice(context)
        val ms = speechMs(SpeechSettings(provider = "system", voice = "en-US"), "Hello from the phone's own voice.")
        assertTrue("got $ms ms of speech", ms in 800..8_000)
    }

    @Test
    fun elevenLabsStreamsSpeech() = runBlocking {
        needs("ELEVENLABS_API_KEY")
        val ms = try {
            speechMs(SpeechSettings(provider = "elevenlabs"), "Hello from ElevenLabs.")
        } catch (e: SpeechException) {
            assumeTrue("ElevenLabs refused the key (${e.status}): ${e.message?.take(120)}", e.status !in setOf(401, 402, 403))
            throw e
        }
        assertTrue("got $ms ms of speech", ms in 500..6_000)
    }

    // ---- hearing, through Silero and turn-taking ----

    @Test
    fun aQuestionIsHeardWithOpenAiStreaming() = runBlocking {
        needs("OPENAI_API_KEY")
        val heard = hear(TranscriptionSettings(language = "en"), "What's the weather like in Oshawa tomorrow afternoon?")
        assertTrue(heard, heard.lowercase().contains("weather"))
        assertTrue(heard, heard.lowercase().contains("tomorrow"))
    }

    @Test
    fun aQuestionIsHeardThroughTheFileUploadApi() = runBlocking {
        needs("OPENAI_API_KEY")
        // The same API that Groq, whisper.cpp and faster-whisper servers copy.
        val heard = hear(
            TranscriptionSettings(provider = "openai-compatible", model = "gpt-4o-mini-transcribe", language = "en"),
            "Set a timer for ten minutes, please."
        )
        assertTrue(heard, heard.lowercase().contains("timer"))
    }

    @Test
    fun deepgramHearsAQuestion() = runBlocking {
        needs("DEEPGRAM_API_KEY")
        needs("OPENAI_API_KEY")
        val heard = hear(TranscriptionSettings(provider = "deepgram", language = "en"), "Remind me to call my mother at six.")
        assertTrue(heard, heard.lowercase().contains("mother"))
    }

    @Test
    fun groqHearsAQuestion() = runBlocking {
        needs("GROQ_API_KEY")
        needs("OPENAI_API_KEY")
        val heard = hear(TranscriptionSettings(provider = "groq", language = "en"), "Remind me to call my mother at six.")
        assertTrue(heard, heard.lowercase().contains("mother"))
    }

    @Test
    fun silenceAndAQuietHumAreNeverSentToBeTranscribed() = runBlocking {
        val hum = ShortArray(16_000 * 3) { (200 * kotlin.math.sin(it * 2 * Math.PI * 120 / 16_000)).toInt().toShort() }
        val events = CopyOnWriteArrayList<VoiceEvent>()
        val transcribed = CopyOnWriteArrayList<Long>()
        agent(TranscriptionSettings(), ShortArray(16_000) + hum + ShortArray(16_000), events, transcribed).use { a ->
            assertTrue(a.start())
            kotlinx.coroutines.delay(4_000)
            a.stop()
        }
        assertTrue("events: $events", events.none { it is VoiceEvent.UserStarted })
        assertTrue(transcribed.isEmpty())
    }

    // ---- replying ----

    @Test
    fun theChatResponderStreamsAReply() = runBlocking {
        needs("OPENAI_API_KEY")
        val chat = OpenAiChat({ ChatSettings() }, TokenSource { key("OPENAI_API_KEY") })
        val reply = withTimeout(30_000) {
            chat.reply("Reply with exactly one word: banana").fold("") { a, b -> a + b }
        }
        assertTrue(reply, reply.lowercase().contains("banana"))
    }

    /** Hears a question, has the responder answer, and plays the answer on the phone's speaker. */
    @Test
    @PlaysAudio
    fun theWholeLoopHearsAnswersAndSpeaks() = runBlocking {
        needs("OPENAI_API_KEY")
        SpeechProviders.installSystemVoice(context)
        val events = CopyOnWriteArrayList<VoiceEvent>()
        val audio = speech16k(SpeechSettings(), "Please say the word banana.")
        val a = agent(
            TranscriptionSettings(language = "en"), audio, events, CopyOnWriteArrayList(),
            speaking = SpeechSettings(provider = "system", voice = "en-US"),
            responder = Responder.of { heard -> "You said: $heard" }
        )
        a.use {
            assertTrue(a.start())
            withTimeout(40_000) {
                while (events.none { it is VoiceEvent.BotStopped }) kotlinx.coroutines.delay(100)
            }
            a.stop()
        }
        val heard = events.filterIsInstance<VoiceEvent.Heard>().single().text
        assertTrue(heard, heard.lowercase().contains("banana"))
        assertTrue(events.indexOf(VoiceEvent.BotStarted) > events.indexOfFirst { it is VoiceEvent.Heard })
    }

    // ---- helpers ----

    private suspend fun speechMs(settings: SpeechSettings, text: String): Long {
        val tts = SpeechProviders.create(settings, TokenSource { settings.apiKey ?: keySource.key(settings.provider) })
        val started = System.currentTimeMillis()
        var first = -1L
        val samples = tts.synthesize(text).fold(0L) { n, chunk ->
            if (first < 0) first = System.currentTimeMillis() - started
            n + chunk.size
        }
        val ms = samples * 1_000 / tts.sampleRate
        Log.i(TAG, "${settings.provider} ${settings.model}: ${ms}ms of speech, first audio after ${first}ms")
        return ms
    }

    /** Speech for [text] as the microphone would give it: 16 kHz mono PCM16. */
    private suspend fun speech16k(settings: SpeechSettings, text: String): ShortArray {
        val tts = SpeechProviders.create(settings, TokenSource { keySource.key(settings.provider) })
        val r = Resampler(tts.sampleRate, 16_000)
        val out = tts.synthesize(text).toList().map { r.feed(it) }
        return ShortArray(16_000) + out.fold(ShortArray(0)) { a, b -> a + b } + ShortArray(16_000 * 3)
    }

    private suspend fun hear(hearing: TranscriptionSettings, text: String): String {
        val audio = speech16k(SpeechSettings(), text)
        val events = CopyOnWriteArrayList<VoiceEvent>()
        val transcribed = CopyOnWriteArrayList<Long>()
        val a = agent(hearing, audio, events, transcribed)
        val heard = a.use {
            assertTrue(a.start())
            val h = withTimeoutOrNull(30_000) { a.events.first { it is VoiceEvent.Heard || it is VoiceEvent.Failed } }
            val s = a.stats
            Log.i(TAG, "${hearing.provider} ${hearing.model}: '$h' transcribed ${s.transcribeMs}ms after speech ended")
            a.stop()
            h
        }
        assertTrue("nothing heard; events: $events", heard is VoiceEvent.Heard)
        assertEquals("one utterance", 1, transcribed.size)
        assertTrue(events.contains(VoiceEvent.UserStarted))
        return (heard as VoiceEvent.Heard).text
    }

    private fun agent(
        hearing: TranscriptionSettings,
        audio: ShortArray,
        events: MutableList<VoiceEvent>,
        transcribed: MutableList<Long>,
        speaking: SpeechSettings? = null,
        responder: Responder? = null
    ): VoiceAgent {
        val a = VoiceAgent.create(context) {
            keys = keySource
            this.hearing = hearing
            this.speaking = speaking
            this.responder = responder
            route = null
            microphone = { frames(audio) }
            log = { Log.i(TAG, it) }
            onTranscribed = { transcribed += it }
        }
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            a.events.collect { events += it }
        }
        return a
    }

    /** The audio in 512-sample frames, then silence for as long as the agent listens, like an open microphone. */
    private fun frames(audio: ShortArray): Flow<ShortArray> = flow {
        var i = 0
        while (i + FRAME <= audio.size) {
            emit(audio.copyOfRange(i, i + FRAME))
            i += FRAME
            // Real time, so timings and the transcriber's stream behave as they do live.
            if (i % (FRAME * 4) == 0) kotlinx.coroutines.delay(FRAME * 4 * 1_000L / 16_000)
        }
        while (true) {
            emit(ShortArray(FRAME))
            kotlinx.coroutines.delay(FRAME * 1_000L / 16_000)
        }
    }

    private companion object {
        const val TAG = "LiveVoiceTest"
        const val FRAME = 512
    }
}
