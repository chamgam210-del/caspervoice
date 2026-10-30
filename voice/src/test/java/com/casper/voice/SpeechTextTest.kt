package com.casper.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    @Test
    fun markdownNeverReachesTheVoice() {
        assertEquals("The cinema has three showings tonight.", SpeechText.clean("**The cinema** has _three_ showings tonight."))
        assertEquals("Prices Here are two options", SpeechText.clean("## Prices\n- Here are two options").replace("\n", " "))
        assertEquals("See the site for details", SpeechText.clean("See [the site](https://example.com/x) for details"))
        assertFalse(SpeechText.clean("Use `foo` now ``` code ``` ok").contains("`"))
    }

    @Test
    fun snakeCaseAndPlainUnderscoresSurvive() {
        assertEquals("the max_value is 5", SpeechText.clean("the max_value is 5"))
    }

    @Test
    fun shortTextIsOnePiece() {
        assertEquals(listOf("Sure. It is sunny."), SpeechText.split("Sure. It is sunny."))
    }

    @Test
    fun longTextBreaksAfterSentencesAndStartsWithAShortPiece() {
        val sentence = "This is a sentence of moderate length that keeps going. "
        val pieces = SpeechText.split(sentence.repeat(60), firstChars = 200, maxChars = 600)
        assertTrue(pieces.size > 3)
        assertTrue("first piece is short: ${pieces[0].length}", pieces[0].length <= 260)
        assertTrue(pieces.drop(1).all { it.length <= 700 })
        assertTrue(pieces.all { it.endsWith(".") })
        assertEquals(sentence.repeat(60).replace(Regex("\\s+"), " ").trim(), pieces.joinToString(" "))
    }

    @Test
    fun aSentenceLongerThanTheLimitIsStillCut() {
        val pieces = SpeechText.split("word ".repeat(500).trim(), firstChars = 100, maxChars = 300)
        assertTrue(pieces.all { it.length <= 300 })
    }

    @Test
    fun whatWasHeardBeforeAnInterruptionEndsOnAWord() {
        val text = "The cinema in Whitby has three showings tonight at six, eight and ten."
        assertEquals("", SpeechText.spokenPart(text, 0))
        assertEquals("The cinema in Whitby", SpeechText.spokenPart(text, 1_500))
        assertEquals(text, SpeechText.spokenPart(text, 60_000))
    }

    @Test
    fun aCutLineResumesFromTheStartOfTheSentenceThatWasCut() {
        val pieces = listOf("It is sunny. Rain comes later tonight.", "Take a coat.")
        // The first piece's audio ran 0-4000 ms; cut 3/4 of the way through it.
        val cut = SpeechText.cut(pieces, listOf(4_000L, 5_000L), 3_000)
        // 3/4 of the way lands inside "later", so the cut backs up to the last whole word.
        assertEquals("It is sunny. Rain comes", cut.heard)
        assertEquals("Rain comes later tonight. Take a coat.", cut.unheard)
        assertEquals("It is sunny.", cut.before)
    }

    @Test
    fun aCutInAPieceStillArrivingFallsBackToTheSpeakingRate() {
        val cut = SpeechText.cut(listOf("Hello there.", "The second piece is long and still arriving."), listOf(1_000L), 1_000 + 1_000)
        assertEquals("Hello there. The second", cut.heard)
        assertEquals("The second piece is long and still arriving.", cut.unheard)
    }

    @Test
    fun aLineHeardToTheEndHasNothingLeft() {
        val cut = SpeechText.cut(listOf("Done."), listOf(800L), 900)
        assertEquals("Done.", cut.heard)
        assertEquals("", cut.unheard)
    }
}
