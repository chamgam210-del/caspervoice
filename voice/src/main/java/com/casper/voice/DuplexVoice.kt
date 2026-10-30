package com.casper.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A voice conversation, end to end: it listens through [microphone], tells
 * speech from noise, sends each utterance to [ears], and says what the host
 * gives it through [voice]. It never decides what to say.
 *
 * What it takes care of so the host does not have to:
 * - **Turns.** An utterance starts when speech has lasted long enough to count
 *   and ends after the silence in [rules]. The half second before it started is
 *   kept, so the first word is not clipped.
 * - **Talking over the bot.** While the bot speaks, detection is stricter. The
 *   first hint of speech lowers the bot's volume, speech that keeps going for
 *   the hold time stops it, and a cough or a clipped sound gives the volume
 *   back and is never transcribed.
 * - **Its own echo.** A transcript made of what the bot had just said is dropped.
 * - **A failed transcription** is retried once with the same audio.
 *
 * Everything is injected, so the pieces can be swapped and the whole thing
 * tested without a microphone.
 */
class DuplexVoice(
    private val scope: CoroutineScope,
    private val microphone: () -> Flow<ShortArray>,
    private val detector: SpeechDetector,
    /** The transcriber for the next utterance, or null when hearing is not configured. */
    private val ears: suspend () -> SpeechToText?,
    private val voice: Talker,
    private val rules: TurnTaking = TurnTaking.DEFAULT,
    private val frameMs: Int = 32,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** Called with the milliseconds of audio each time a transcription finished, so the host can count the cost. */
    private val onTranscribed: (audioMs: Long) -> Unit = {},
    /** Every utterance the engine kept, with the audio, for tuning. See [Capture]. */
    private val onUtterance: (Capture) -> Unit = {}
) : VoiceEngine {

    /** What happened since [start], for tuning. */
    data class Stats(
        val heard: Int = 0,
        val bargeIns: Int = 0,
        /** Sounds over the bot that were not a real interruption. */
        val falseInterruptions: Int = 0,
        /** Transcripts dropped because they were the bot's own words. */
        val echoesDropped: Int = 0,
        /** From asking for a line to the first sound, for the last line. */
        val firstSoundMs: Long = -1,
        /** From the end of the user's speech to the text, for the last utterance. */
        val transcribeMs: Long = -1
    )

    /**
     * One utterance as the engine saw it. [text] is null when it was never sent to be
     * transcribed (a sound over the bot that ended before the hold time), which is
     * worth listening to when something the user said seems to be missing.
     */
    class Capture(
        val frames: List<ShortArray>,
        val overBot: Boolean,
        val barged: Boolean,
        val text: String?,
        val transcribeMs: Long
    ) {
        val audioMs: Long get() = frames.size.toLong() * 32
    }

    private val _state = MutableStateFlow(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VoiceEvent> = _events

    @Volatile override var userSpeaking = false
        private set

    override val botSpeaking: Boolean get() = voice.speaking

    @Volatile var stats = Stats()
        private set

    private val gate = SpeechGate(rules, frameMs)
    private val preRoll = ArrayDeque<ShortArray>()
    private val preRollFrames = (SpeechGate.START_MS + rules.listening.prefixMs) / frameMs + 1

    private var run: CoroutineScope? = null
    private var turn: Turn? = null
    private var ducked = false
    private var duckedAt = 0L

    @Volatile private var interruptible = true
    @Volatile private var lastBotText = ""
    @Volatile private var botStoppedAt = 0L
    /** Set when a barge-in stopped the line being spoken. */
    @Volatile private var lineVerdict: CompletableDeferred<Boolean>? = null

    private inner class Turn(val overBot: Boolean) {
        val startedAt = clock()
        val frames = ArrayList<ShortArray>()
        val audio = Channel<ShortArray>(Channel.UNLIMITED)
        val ended = CompletableDeferred<Unit>()
        var endedAt = 0L
        var streaming = false
        var barged = false
        /** Completed with whether a barge-in was real speech, once the transcript says. */
        var verdict: CompletableDeferred<Boolean>? = null

        fun settle(real: Boolean) {
            verdict?.complete(real)
        }

        fun add(frame: ShortArray) {
            frames += frame
            if (streaming) audio.trySend(frame)
        }

        /** Starts sending what has been heard so far, and everything after it. */
        fun stream() {
            if (streaming) return
            streaming = true
            frames.forEach { audio.trySend(it) }
        }
    }

    override suspend fun start(): Boolean {
        if (_state.value != VoiceState.Idle) return false
        _state.value = VoiceState.Connecting
        detector.reset()
        gate.reset()
        preRoll.clear()
        turn = null
        ducked = false
        val job = SupervisorJob(scope.coroutineContext[Job])
        val mine = CoroutineScope(scope.coroutineContext + job)
        run = mine

        val ready = CompletableDeferred<Boolean>()
        mine.launch {
            try {
                microphone().collect { frame ->
                    if (!ready.isCompleted) {
                        ready.complete(true)
                        _state.value = VoiceState.Live
                    }
                    onFrame(frame, mine)
                }
                ready.complete(false)
                micEnded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ready.complete(false)
                log("microphone failed: ${e.message}")
                _events.tryEmit(VoiceEvent.Failed(e.message ?: e.javaClass.simpleName))
                micEnded()
            }
        }
        val ok = withTimeoutOrNull(START_TIMEOUT_MS) { ready.await() } ?: false
        if (!ok) {
            mine.cancel()
            run = null
            _state.value = VoiceState.Idle
        }
        return ok
    }

    /** The microphone went away under a live call, so there is no call. */
    private fun micEnded() {
        if (_state.value == VoiceState.Live) {
            userSpeaking = false
            _state.value = VoiceState.Idle
        }
    }

    override suspend fun stop() {
        if (_state.value == VoiceState.Idle) return
        _state.value = VoiceState.Closing
        run?.cancel()
        run = null
        turn?.audio?.cancel()
        turn?.settle(real = true)
        lineVerdict?.complete(true)
        turn = null
        userSpeaking = false
        voice.stop()
        _state.value = VoiceState.Idle
    }

    override suspend fun say(utterance: Utterance): Boolean {
        if (_state.value != VoiceState.Live) return false
        scope.launch { speak(utterance.text, utterance.interruptible) }
        return true
    }

    /**
     * Says a line and returns how it went, for a host that has to wait for
     * the end, count the seconds, or know how much was heard when it was cut off.
     *
     * When the user cuts it off, this waits for what they said before
     * returning. If it was nothing (see [TurnTaking.resumeAfterFalseBargeIn])
     * the rest of the line is said and the result is for the whole line.
     */
    suspend fun speak(text: String, interruptible: Boolean = true, onStart: () -> Unit = {}): SpeechSynth.Result {
        var line = text
        var before = ""
        var resumes = 0
        while (true) {
            lineVerdict = null
            val result = speakOnce(line, interruptible, if (resumes == 0) onStart else ({}))
            if (result !is SpeechSynth.Result.Interrupted) return result
            val verdict = lineVerdict
            lineVerdict = null
            val heard = join(before, result.heard)
            val whole = result.copy(heard = heard, heardBeforeCut = join(before, result.heardBeforeCut))
            _events.tryEmit(VoiceEvent.Interrupted(text, heard))
            if (verdict == null || !rules.resumeAfterFalseBargeIn || result.unheard.isBlank() || resumes >= MAX_RESUMES) return whole
            val real = withTimeoutOrNull(VERDICT_TIMEOUT_MS) { verdict.await() } ?: true
            if (real) return whole
            log("the barge-in was nothing; saying the rest again")
            _events.tryEmit(VoiceEvent.Resumed(result.unheard))
            before = whole.heardBeforeCut
            line = result.unheard
            resumes++
        }
    }

    private fun join(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")

    private suspend fun speakOnce(text: String, interruptible: Boolean, onStart: () -> Unit): SpeechSynth.Result {
        this.interruptible = interruptible
        lastBotText = text
        val asked = clock()
        var started = false
        val result = voice.speak(text) {
            started = true
            stats = stats.copy(firstSoundMs = clock() - asked)
            _events.tryEmit(VoiceEvent.BotStarted)
            onStart()
        }
        if (started) {
            botStoppedAt = clock()
            _events.tryEmit(VoiceEvent.BotStopped)
        }
        ducked = false
        voice.duck(false)
        return result
    }

    override fun stopTalking() = voice.stop()

    private fun onFrame(frame: ShortArray, mine: CoroutineScope) {
        val bot = voice.speaking
        val probability = detector.speechProbability(frame)
        preRoll.addLast(frame)
        while (preRoll.size > preRollFrames) preRoll.removeFirst()

        when (gate.feed(probability, bot)) {
            SpeechGate.Signal.MaybeSpeech -> {
                if (!ducked) {
                    ducked = true
                    duckedAt = clock()
                    voice.duck(true)
                }
            }
            SpeechGate.Signal.Started -> begin(bot, mine)
            SpeechGate.Signal.BargeIn -> barge(mine)
            SpeechGate.Signal.Stopped -> {
                turn?.add(frame)
                finish()
            }
            null -> turn?.add(frame)
        }

        // A hint that never became speech was a click or a cough.
        if (ducked && !gate.speaking && clock() - duckedAt > DUCK_RELEASE_MS) {
            ducked = false
            voice.duck(false)
            stats = stats.copy(falseInterruptions = stats.falseInterruptions + 1)
        }
    }

    private fun begin(overBot: Boolean, mine: CoroutineScope) {
        val t = Turn(overBot)
        turn = t
        preRoll.forEach { t.frames += it }
        userSpeaking = true
        _events.tryEmit(VoiceEvent.UserStarted)
        // Over the bot it may be a cough or the bot's own echo: wait to see if it lasts.
        if (!overBot) transcribe(t, mine)
    }

    private fun barge(mine: CoroutineScope) {
        val t = turn ?: return
        t.barged = true
        stats = stats.copy(bargeIns = stats.bargeIns + 1)
        _events.tryEmit(VoiceEvent.BargeIn)
        if (interruptible && voice.speaking) {
            val v = CompletableDeferred<Boolean>()
            t.verdict = v
            lineVerdict = v
            voice.stop()
        }
        ducked = false
        voice.duck(false)
        transcribe(t, mine)
    }

    private fun finish() {
        val t = turn ?: return
        turn = null
        userSpeaking = false
        preRoll.clear()
        t.endedAt = clock()
        _events.tryEmit(VoiceEvent.UserStopped)
        if (t.overBot && !t.barged) {
            stats = stats.copy(falseInterruptions = stats.falseInterruptions + 1)
            ducked = false
            voice.duck(false)
            t.audio.cancel()
            t.ended.complete(Unit)
            log("sound over the bot ended before the hold time; not transcribed (${t.frames.size * frameMs}ms)")
            onUtterance(Capture(t.frames.toList(), overBot = true, barged = false, text = null, transcribeMs = -1))
            return
        }
        t.audio.close()
        t.ended.complete(Unit)
    }

    private fun transcribe(t: Turn, mine: CoroutineScope) {
        if (t.streaming) return
        t.stream()
        mine.launch {
            val listener = try {
                ears()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                t.settle(real = true)
                fail("transcription is not available: ${e.message}")
                return@launch
            }
            if (listener == null) {
                t.settle(real = true)
                fail("transcription is not configured")
                return@launch
            }
            var text = attempt(listener, t.audio.receiveAsFlow())
            if (text == null) {
                log("transcription failed; trying once more")
                t.ended.await()
                text = attempt(listener, t.frames.asFlow())
            }
            if (text == null) {
                t.settle(real = true)
                fail("transcription failed twice")
                return@launch
            }
            onTranscribed(t.frames.size.toLong() * frameMs)
            val ended = if (t.endedAt > 0) t.endedAt else clock()
            stats = stats.copy(transcribeMs = clock() - ended)
            t.ended.await()
            log("utterance ${t.frames.size * frameMs}ms${if (t.overBot) ", over the bot" else ""}, text in ${stats.transcribeMs}ms: ${text.take(100)}")
            onUtterance(Capture(t.frames.toList(), t.overBot, t.barged, text, stats.transcribeMs))
            if (text.isBlank()) {
                t.settle(real = false)
                return@launch
            }
            if (isEcho(text, t)) {
                stats = stats.copy(echoesDropped = stats.echoesDropped + 1)
                log("dropped the bot's own words: ${text.take(80)}")
                t.settle(real = false)
                return@launch
            }
            t.settle(real = true)
            stats = stats.copy(heard = stats.heard + 1)
            _events.emit(VoiceEvent.Heard(text))
        }
    }

    /** The finished text, "" when nothing was said, null when it failed. */
    private suspend fun attempt(listener: SpeechToText, audio: Flow<ShortArray>): String? {
        var final: String? = null
        try {
            listener.transcribe(audio).collect {
                if (it.final) final = it.text else _events.tryEmit(VoiceEvent.Partial(it.text))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("transcription error: ${e.message}")
            return null
        }
        return final ?: ""
    }

    private fun isEcho(text: String, t: Turn): Boolean {
        val recent = voice.speaking || t.startedAt - botStoppedAt < ECHO_TAIL_MS
        return (t.overBot || recent) && EchoFilter.isEcho(text, lastBotText)
    }

    private fun fail(reason: String) {
        log(reason)
        _events.tryEmit(VoiceEvent.Failed(reason))
    }

    private companion object {
        const val START_TIMEOUT_MS = 3_000L
        const val DUCK_RELEASE_MS = 400L
        const val ECHO_TAIL_MS = 2_000L
        const val VERDICT_TIMEOUT_MS = 15_000L
        const val MAX_RESUMES = 2
    }
}
