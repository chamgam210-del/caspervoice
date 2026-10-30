package com.casper.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTakingTest {
    private val rules = TurnTaking.DEFAULT

    @Test
    fun theBotTalkingMakesDetectionStricterAndStopsAutoInterrupt() {
        val listening = rules.detection(botSpeaking = false)
        val holding = rules.detection(botSpeaking = true)
        assertTrue(holding.threshold > listening.threshold)
        assertTrue(listening.interrupt)
        assertFalse(holding.interrupt)
    }

    @Test
    fun theDefaultsAreTheOnesTunedOnThePhone() {
        assertEquals(TurnTaking.Detection(0.72, 500, 1_500, true), rules.listening)
        assertEquals(TurnTaking.Detection(0.88, 300, 700, false), rules.holding)
        assertEquals(750L, rules.bargeHoldMs)
    }

    @Test
    fun onlySpeechStillGoingOverTheBotIsABargeIn() {
        assertTrue(rules.isBargeIn(userStillSpeaking = true, botStillSpeaking = true))
        assertFalse(rules.isBargeIn(userStillSpeaking = false, botStillSpeaking = true))
        assertFalse(rules.isBargeIn(userStillSpeaking = true, botStillSpeaking = false))
    }
}
