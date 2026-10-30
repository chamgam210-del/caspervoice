package com.casper.voice

import com.casper.voice.SpeechGate.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {

    private fun SpeechGate.run(ms: Int, probability: Float, botSpeaking: Boolean): List<Signal> =
        (0 until ms / 32).mapNotNull { feed(probability, botSpeaking) }

    @Test
    fun aCoughOverTheBotOnlySoftensIt() {
        val gate = SpeechGate()
        val signals = gate.run(96, 0.95f, botSpeaking = true) + gate.run(500, 0.05f, botSpeaking = true)
        assertEquals(listOf(Signal.MaybeSpeech), signals)
        assertFalse(gate.speaking)
    }

    @Test
    fun talkingOverTheBotBargesInAfterTheHold() {
        val gate = SpeechGate()
        val first = gate.run(224, 0.95f, botSpeaking = true)
        assertEquals(listOf(Signal.MaybeSpeech, Signal.Started), first)
        assertTrue(gate.run(704, 0.95f, botSpeaking = true).isEmpty())
        assertEquals(listOf(Signal.BargeIn), gate.run(64, 0.95f, botSpeaking = true))
        assertTrue(gate.run(1_000, 0.95f, botSpeaking = true).isEmpty())
    }

    @Test
    fun echoBelowTheHoldingThresholdIsIgnoredButTheSameLevelCountsWhenListening() {
        val holding = SpeechGate()
        assertTrue(holding.run(2_000, 0.8f, botSpeaking = true).isEmpty())
        val listening = SpeechGate()
        assertEquals(listOf(Signal.Started), listening.run(2_000, 0.8f, botSpeaking = false))
    }

    @Test
    fun aTurnEndsAfterTheListeningSilence() {
        val gate = SpeechGate()
        gate.run(640, 0.9f, botSpeaking = false)
        assertTrue(gate.run(1_440, 0.1f, botSpeaking = false).isEmpty())
        assertEquals(listOf(Signal.Stopped), gate.run(96, 0.1f, botSpeaking = false))
    }

    @Test
    fun speechThatStartedBeforeTheBotIsNotABargeIn() {
        val gate = SpeechGate()
        gate.run(640, 0.95f, botSpeaking = false)
        assertTrue(gate.run(2_000, 0.95f, botSpeaking = true).isEmpty())
    }
}
