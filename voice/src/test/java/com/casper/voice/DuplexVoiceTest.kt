package com.casper.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuplexVoiceTest {

    private val loud = ShortArray(512) { 1000 }
    private val quiet = ShortArray(512)

    /** Reads loudness off the frame itself and advances the clock a frame at a time. */
    private class Detector(val clock: () -> Unit) : SpeechDetector {
        override val frameSamples = 512
        override fun speechProbability(frame: ShortArray): Float {
            clock()
            return if (frame[0].toInt() != 0) 0.95f else 0.05f
        }
    }

    private class Ears(val answers: MutableList<() -> String>) : SpeechToText {
        val heard = mutableListOf<List<ShortArray>>()
        var calls = 0
        override fun transcribe(audio: Flow<ShortArray>): Flow<Transcript> = flow {
            calls++
            val frames = mutableListOf<ShortArray>()
            audio.collect { frames += it }
            heard += frames
            val text = answers.removeAt(0)()
            emit(Transcript(text.take(3), final = false))
            emit(Transcript(text, final = true))
        }
    }

    private class Mouth : Talker {
        @Volatile override var speaking = false
        val ducks = mutableListOf<Boolean>()
        var stopped = false
        val said = mutableListOf<String>()
        private var end = CompletableDeferred<Boolean>()

        override suspend fun speak(text: String, onStart: () -> Unit): SpeechSynth.Result {
            said += text
            end = CompletableDeferred()
            speaking = true
            onStart()
            val finished = end.await()
            speaking = false
            val s = SpeechSettings()
            // Cut in the second sentence: the first was heard whole.
            val sentences = text.split(". ")
            return if (finished) SpeechSynth.Result.Finished(s, 100, 1_000)
            else SpeechSynth.Result.Interrupted(
                s, 100, 500,
                heard = sentences.first() + ". " + sentences.getOrElse(1) { "" }.substringBefore(' '),
                unheard = sentences.drop(1).joinToString(". "),
                heardBeforeCut = sentences.first() + "."
            )
        }

        override fun stop() {
            stopped = true
            end.complete(false)
        }

        fun finish() = end.complete(true)
        override fun duck(on: Boolean) { ducks += on }
    }

    private class Rig(scope: TestScope, answers: List<() -> String>, rules: TurnTaking = TurnTaking.DEFAULT) {
        var now = 0L
        val mic = Channel<ShortArray>(Channel.UNLIMITED)
        val ears = Ears(answers.toMutableList())
        val mouth = Mouth()
        val events = mutableListOf<VoiceEvent>()
        val logs = mutableListOf<String>()
        val engine = DuplexVoice(
            scope = scope.backgroundScope,
            microphone = { mic.receiveAsFlow() },
            detector = Detector { now += 32 },
            ears = { ears },
            voice = mouth,
            rules = rules,
            clock = { now },
            log = { logs += it }
        )

        init {
            engine.events.onEach { events += it }.launchIn(scope.backgroundScope)
        }
    }

    private suspend fun TestScope.rig(vararg answers: () -> String): Rig {
        val r = Rig(this, answers.toList())
        // Let the collector subscribe before anything is emitted.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { }
        r.mic.trySend(quiet)
        assertTrue(r.engine.start())
        return r
    }

    /** The test scheduler does not run work resumed from a channel until time moves. */
    private suspend fun TestScope.settle() {
        advanceUntilIdle()
        delay(50)
        advanceUntilIdle()
    }

    private fun Rig.feed(frame: ShortArray, frames: Int) = repeat(frames) { mic.trySend(frame) }

    @Test
    fun aSpokenQuestionIsHeardOnceWithTheStartOfItKept() = runTest {
        val r = rig({ "what's the weather like" })
        r.feed(quiet, 20)
        r.feed(loud, 20)
        r.feed(quiet, 60)
        settle()

        assertEquals(1, r.ears.calls)
        assertTrue(r.events.contains(VoiceEvent.UserStarted))
        assertTrue(r.events.contains(VoiceEvent.UserStopped))
        assertEquals(listOf(VoiceEvent.Heard("what's the weather like")), r.events.filterIsInstance<VoiceEvent.Heard>())
        assertTrue(r.events.any { it is VoiceEvent.Partial })
        // 20 loud frames, the pre-roll before them, and the silence after.
        val sent = r.ears.heard.single()
        assertTrue("pre-roll kept, got ${sent.size}", sent.size >= 20 + 15)
        assertTrue(sent.first().all { it == 0.toShort() })
        assertFalse(r.engine.userSpeaking)
    }

    @Test
    fun silenceAndNoiseThatNeverLastsSendNothing() = runTest {
        val r = rig()
        r.feed(quiet, 100)
        r.feed(loud, 3)
        r.feed(quiet, 100)
        settle()
        assertEquals(0, r.ears.calls)
        assertTrue(r.events.none { it is VoiceEvent.Heard })
    }

    @Test
    fun aCoughOverTheBotOnlyLowersItsVolumeForAMoment() = runTest {
        val r = rig()
        r.mouth.speaking = true
        r.feed(loud, 4)
        r.feed(quiet, 40)
        settle()

        assertFalse("the bot was not stopped", r.mouth.stopped)
        assertEquals(listOf(true, false), r.mouth.ducks.take(2))
        assertEquals(0, r.ears.calls)
        assertEquals(1, r.engine.stats.falseInterruptions)
        assertTrue(r.events.none { it is VoiceEvent.BargeIn || it is VoiceEvent.Heard })
    }

    @Test
    fun talkingOverTheBotStopsItAndIsHeard() = runTest {
        val r = rig({ "actually, what about Friday" })
        val said = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { r.engine.speak("Tomorrow will be sunny.") }
        r.feed(loud, 40)
        r.feed(quiet, 40)
        settle()

        assertTrue(r.mouth.stopped)
        assertTrue(r.events.contains(VoiceEvent.BargeIn))
        assertEquals(1, r.engine.stats.bargeIns)
        assertEquals(listOf(VoiceEvent.Heard("actually, what about Friday")), r.events.filterIsInstance<VoiceEvent.Heard>())
        said.cancel()
    }

    @Test
    fun aBargeInThatWasNothingResumesTheLineFromTheCutSentence() = runTest {
        val r = rig({ "" })
        var result: SpeechSynth.Result? = null
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            result = r.engine.speak("Tomorrow will be sunny. Then rain comes in the evening.")
        }
        r.feed(loud, 40)
        r.feed(quiet, 60)
        settle()

        assertTrue(r.mouth.stopped)
        assertEquals(listOf("Tomorrow will be sunny. Then rain comes in the evening.", "Then rain comes in the evening."), r.mouth.said)
        assertTrue(r.events.contains(VoiceEvent.Resumed("Then rain comes in the evening.")))
        r.mouth.finish()
        settle()
        assertTrue(result is SpeechSynth.Result.Finished)
        assertTrue(r.events.none { it is VoiceEvent.Heard })
    }

    @Test
    fun aRealBargeInDoesNotResumeAndReportsWhatWasHeard() = runTest {
        val r = rig({ "stop, what about Friday" })
        var result: SpeechSynth.Result? = null
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            result = r.engine.speak("Tomorrow will be sunny. Then rain comes in the evening.")
        }
        r.feed(loud, 40)
        r.feed(quiet, 60)
        settle()

        assertEquals(1, r.mouth.said.size)
        val cut = result as SpeechSynth.Result.Interrupted
        assertEquals("Tomorrow will be sunny. Then", cut.heard)
        assertTrue(r.events.contains(VoiceEvent.Interrupted("Tomorrow will be sunny. Then rain comes in the evening.", "Tomorrow will be sunny. Then")))
        assertTrue(r.events.none { it is VoiceEvent.Resumed })
    }

    @Test
    fun resumingCanBeSwitchedOff() = runTest {
        val r = Rig(this, listOf({ "" }), TurnTaking.DEFAULT.copy(resumeAfterFalseBargeIn = false))
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { }
        r.mic.trySend(quiet)
        assertTrue(r.engine.start())
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            r.engine.speak("Tomorrow will be sunny. Then rain comes in the evening.")
        }
        r.feed(loud, 40)
        r.feed(quiet, 60)
        settle()
        assertEquals(1, r.mouth.said.size)
    }

    @Test
    fun aLineThatMustPlayThroughIsNotStoppedButTheUserIsStillHeard() = runTest {
        val r = rig({ "wait" })
        val said = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            r.engine.speak("Please confirm the payment.", interruptible = false)
        }
        r.feed(loud, 40)
        r.feed(quiet, 40)
        settle()
        assertFalse(r.mouth.stopped)
        assertTrue(r.events.contains(VoiceEvent.BargeIn))
        said.cancel()
    }

    @Test
    fun itsOwnVoiceComingBackIsNotTreatedAsTheUser() = runTest {
        val r = rig({ "it will be sunny tomorrow afternoon in Oshawa" })
        val said = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            r.engine.speak("It will be sunny tomorrow afternoon in Oshawa, with a high of fifteen.")
        }
        r.feed(loud, 40)
        r.feed(quiet, 40)
        settle()
        assertEquals(1, r.engine.stats.echoesDropped)
        assertTrue(r.events.none { it is VoiceEvent.Heard })
        said.cancel()
    }

    @Test
    fun aFailedTranscriptionIsTriedOnceMoreWithTheSameAudio() = runTest {
        val r = rig({ error("connection reset") }, { "turn on the lights" })
        r.feed(loud, 20)
        r.feed(quiet, 60)
        settle()
        assertEquals(2, r.ears.calls)
        assertEquals(listOf(VoiceEvent.Heard("turn on the lights")), r.events.filterIsInstance<VoiceEvent.Heard>())
    }

    @Test
    fun twoFailuresAreReported() = runTest {
        val r = rig({ error("a") }, { error("b") })
        r.feed(loud, 20)
        r.feed(quiet, 60)
        settle()
        assertTrue(r.events.any { it is VoiceEvent.Failed })
        assertTrue(r.events.none { it is VoiceEvent.Heard })
    }

    @Test
    fun sayingALineTellsWhenItStartsAndStops() = runTest {
        val r = rig()
        assertTrue(r.engine.say(Utterance("Hello.")))
        settle()
        assertTrue(r.events.contains(VoiceEvent.BotStarted))
        assertTrue(r.engine.botSpeaking)
        r.mouth.finish()
        settle()
        assertTrue(r.events.contains(VoiceEvent.BotStopped))
        assertFalse(r.engine.botSpeaking)
    }

    @Test
    fun stoppingEndsTheCallAndTheMicrophone() = runTest {
        val r = rig()
        r.engine.stop()
        assertEquals(VoiceState.Idle, r.engine.state.value)
        assertFalse(r.engine.say(Utterance("Hello.")))
    }
}
