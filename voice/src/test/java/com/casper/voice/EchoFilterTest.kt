package com.casper.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoFilterTest {
    private val said = "It will be sunny tomorrow afternoon in Oshawa, with a high of fifteen degrees."

    @Test
    fun theBotsOwnWordsAreEcho() {
        assertTrue(EchoFilter.isEcho("it will be sunny tomorrow afternoon in Oshawa", said))
        assertTrue(EchoFilter.isEcho("sunny tomorrow afternoon", said))
    }

    @Test
    fun somethingElseIsNot() {
        assertFalse(EchoFilter.isEcho("what about the weekend", said))
        assertFalse(EchoFilter.isEcho("stop", said))
        assertFalse(EchoFilter.isEcho("yes", "yes please tell me more"))
    }

    @Test
    fun anEmptyTranscriptOrNothingSaidIsNotEcho() {
        assertFalse(EchoFilter.isEcho("", said))
        assertFalse(EchoFilter.isEcho("sunny tomorrow afternoon", ""))
    }
}
