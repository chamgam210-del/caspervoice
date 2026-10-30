package com.casper.voice

import android.content.Context
import com.casper.voice.audio.AudioRoute
import com.casper.voice.audio.AudioRouting
import com.casper.voice.audio.MicCapture
import com.casper.voice.stt.TranscriptionProviders
import com.casper.voice.tts.SpeechProviders
import com.casper.voice.vad.SileroDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** The key for a provider by its name ("openai", "deepgram", ...), or null when there is none. */
fun interface KeySource {
    suspend fun key(provider: String): String?
}

/**
 * The whole voice in one object: microphone with echo cancelling, speech
 * detection, turn-taking, barge-in, transcription and speech, set up from a
 * few settings.
 *
 * ```
 * val agent = VoiceAgent.create(context) {
 *     keys = KeySource { provider -> myKeys[provider] }
 *     hearing = TranscriptionSettings(provider = "deepgram")
 *     speaking = SpeechSettings(provider = "system")
 *     responder = OpenAiChat({ ChatSettings() }, TokenSource { myKeys["openai"] })
 * }
 * agent.start()
 * ```
 *
 * Without a [Builder.responder] it only listens and speaks: collect
 * [events] for [VoiceEvent.Heard] and answer with [say] or [speak].
 * Needs the RECORD_AUDIO permission granted before [start], and, to keep
 * listening in the background, a foreground service of type microphone in
 * the host app.
 */
class VoiceAgent private constructor(
    private val engine: DuplexVoice,
    private val scope: CoroutineScope,
    private val ownsScope: Boolean,
    private val routing: AudioRouting?,
    private val detector: AutoCloseable?,
    private val responder: Responder?,
    private val log: (String) -> Unit
) : VoiceEngine by engine, AutoCloseable {

    class Builder internal constructor(private val context: Context) {
        /** Keys for providers whose settings carry none. The library never stores them. */
        var keys: KeySource = KeySource { null }

        /** The ears. Null switches hearing off. Use [hearingFrom] to change them while running. */
        var hearing: TranscriptionSettings? = TranscriptionSettings()

        /** The voice. Null switches speaking off. Use [speakingFrom] to change it while running. */
        var speaking: SpeechSettings? = SpeechSettings()

        internal var hearingSource: (suspend () -> TranscriptionSettings?)? = null
        internal var speakingSource: (suspend () -> SpeechSettings?)? = null

        /** Read before every utterance, so a model, language or key can change mid-conversation. */
        fun hearingFrom(source: suspend () -> TranscriptionSettings?) { hearingSource = source }

        /** Read before every line. */
        fun speakingFrom(source: suspend () -> SpeechSettings?) { speakingSource = source }

        /** Answers what the user said. Leave null when the host decides every word. */
        var responder: Responder? = null

        var rules: TurnTaking = TurnTaking.DEFAULT

        /** Null leaves the phone's audio mode and routing to the host. */
        var route: AudioRoute? = AudioRoute.Auto

        var echoCanceller: Boolean = true
        var noiseSuppressor: Boolean = true

        /** Another speech detector instead of the bundled Silero model. */
        var detector: SpeechDetector? = null

        /** Another source of 16 kHz mono PCM16 frames instead of the microphone. */
        var microphone: (() -> Flow<ShortArray>)? = null

        /** Where the agent runs. Defaults to its own, closed with [close]. */
        var scope: CoroutineScope? = null

        var log: (String) -> Unit = {}

        /** Seconds of audio sent to be transcribed, for counting the cost. */
        var onTranscribed: (audioMs: Long) -> Unit = {}

        /** Every utterance with its audio, for tuning. */
        var onUtterance: (DuplexVoice.Capture) -> Unit = {}

        internal fun build(): VoiceAgent {
            SpeechProviders.installSystemVoice(context)
            val ownsScope = scope == null
            val runScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val silero = if (detector == null) SileroDetector.fromAssets(context) else null
            val fixedHearing = hearing
            val fixedSpeaking = speaking
            val hearing: suspend () -> TranscriptionSettings? = hearingSource ?: { fixedHearing }
            val speaking: suspend () -> SpeechSettings? = speakingSource ?: { fixedSpeaking }
            val keys = keys
            val voice = SpeechSynth(
                settings = speaking,
                keyFor = { s -> keys.key(s.provider) },
                log = log
            )
            val mic = microphone ?: { MicCapture(echoCanceller = echoCanceller, noiseSuppressor = noiseSuppressor).frames() }
            val engine = DuplexVoice(
                scope = runScope,
                microphone = mic,
                detector = detector ?: silero!!,
                ears = {
                    hearing()?.let { s -> TranscriptionProviders.create(s, TokenSource { s.apiKey ?: keys.key(s.provider) }) }
                },
                voice = voice,
                rules = rules,
                log = log,
                onTranscribed = onTranscribed,
                onUtterance = onUtterance
            )
            return VoiceAgent(
                engine = engine,
                scope = runScope,
                ownsScope = ownsScope,
                routing = route?.let { AudioRouting(context, it) },
                detector = silero,
                responder = responder,
                log = log
            )
        }
    }

    private var listening: Job? = null
    private var replying: Job? = null
    private var routed = false

    /** What happened since [start]: turns heard, barge-ins, echoes dropped, timings. */
    val stats: DuplexVoice.Stats get() = engine.stats

    override suspend fun start(): Boolean {
        route(true)
        if (!engine.start()) {
            route(false)
            return false
        }
        if (responder != null) {
            listening = scope.launch {
                engine.events.collect { if (it is VoiceEvent.Heard) answer(it.text) }
            }
        }
        return true
    }

    override suspend fun stop() {
        listening?.cancel()
        replying?.cancel()
        listening = null
        replying = null
        engine.stop()
        route(false)
    }

    private fun route(on: Boolean) {
        val r = routing ?: return
        if (on == routed) return
        routed = on
        if (on) r.start() else r.stop()
    }

    /** Says a line and returns how it went, including how much was heard if it was cut off. */
    suspend fun speak(text: String, interruptible: Boolean = true): SpeechSynth.Result = engine.speak(text, interruptible)

    /** A new user turn replaces whatever reply was still being written or spoken. */
    private fun answer(heard: String) {
        val r = responder ?: return
        replying?.cancel()
        replying = scope.launch {
            val sentences = SentenceBuffer()
            val spoken = StringBuilder()
            var cut = false
            try {
                r.reply(heard).collect { piece ->
                    for (s in sentences.add(piece)) {
                        if (!say(s, spoken)) { cut = true; throw CutOff() }
                    }
                }
                val rest = sentences.flush()
                if (rest.isNotBlank() && !say(rest, spoken)) cut = true
            } catch (_: CutOff) {
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("reply failed: ${e.message}")
            }
            if (cut) r.cutOff(spoken.toString().trim())
        }
    }

    /** False when the user cut this sentence off. */
    private suspend fun say(sentence: String, spoken: StringBuilder): Boolean {
        return when (val result = engine.speak(sentence)) {
            is SpeechSynth.Result.Interrupted -> {
                spoken.append(' ').append(result.heard)
                false
            }
            else -> {
                spoken.append(' ').append(sentence)
                true
            }
        }
    }

    private class CutOff : Exception()

    /** Frees everything. Call [stop] first to end a live conversation cleanly. */
    override fun close() {
        listening?.cancel()
        replying?.cancel()
        engine.stopTalking()
        route(false)
        if (ownsScope) scope.cancel()
        detector?.close()
    }

    companion object {
        fun create(context: Context, configure: Builder.() -> Unit = {}): VoiceAgent =
            Builder(context.applicationContext).apply(configure).build()
    }
}
