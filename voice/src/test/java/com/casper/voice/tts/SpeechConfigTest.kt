package com.casper.voice.tts

import com.casper.voice.SpeechSettings
import com.casper.voice.TokenSource
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpeechConfigTest {

    @Test
    fun missingFieldsKeepTheirDefaults() {
        assertEquals(SpeechSettings(), SpeechSettings.parse("{}"))
        val s = SpeechSettings.parse("""{"model":"tts-1-hd","voice":"nova","extra":1}""")
        assertEquals("tts-1-hd", s.model)
        assertEquals("nova", s.voice)
        assertEquals("openai", s.provider)
        assertNull(s.apiKey)
    }

    @Test
    fun anotherServerKeyAndSpeedCanBeNamed() {
        val s = SpeechSettings.parse(
            """{"provider":"OpenAI","baseUrl":"http://10.0.0.5:8880/v1/","apiKey":"k-123","speed":1.1,"instructions":"warm"}"""
        )
        assertEquals("openai", s.provider)
        assertEquals("http://10.0.0.5:8880/v1", s.baseUrl)
        assertEquals("k-123", s.apiKey)
        assertEquals(1.1, s.speed!!, 0.0)
        assertEquals("warm", s.instructions)
    }

    @Test
    fun theRequestAsksForRawPcmAndCarriesTheChosenModelAndVoice() {
        val body = JSONObject(OpenAiSpeech.requestBody(SpeechSettings(model = "gpt-4o-mini-tts", voice = "cedar"), "Hello."))
        assertEquals("gpt-4o-mini-tts", body.getString("model"))
        assertEquals("cedar", body.getString("voice"))
        assertEquals("Hello.", body.getString("input"))
        assertEquals("pcm", body.getString("response_format"))
        assertFalse(body.has("instructions"))
        assertFalse(body.has("speed"))
    }

    @Test
    fun instructionsOnlyGoToModelsThatTakeThem() {
        val warm = SpeechSettings(model = "gpt-4o-mini-tts", instructions = "warm")
        assertEquals("warm", JSONObject(OpenAiSpeech.requestBody(warm, "x")).getString("instructions"))
        val old = SpeechSettings(model = "tts-1", instructions = "warm", speed = 1.2)
        val body = JSONObject(OpenAiSpeech.requestBody(old, "x"))
        assertFalse(body.has("instructions"))
        assertEquals(1.2, body.getDouble("speed"), 0.0)
    }

    @Test
    fun aSampleSplitAcrossTwoReadsIsStillOneSample() {
        // 0x0102 = 258, 0xFF80 = -128, 0x0001 = 1, in little-endian bytes.
        val bytes = byteArrayOf(0x02, 0x01, 0x80.toByte(), 0xFF.toByte(), 0x01, 0x00)
        val whole = PcmReader().feed(bytes, bytes.size)
        assertArrayEquals(shortArrayOf(258, -128, 1), whole)

        val reader = PcmReader()
        val a = reader.feed(bytes, 3)
        val b = reader.feed(bytes.copyOfRange(3, 6), 3)
        assertArrayEquals(shortArrayOf(258, -128, 1), a + b)
    }

    @Test
    fun aReadOfOneByteIsHeldUntilItsPartnerArrives() {
        val reader = PcmReader()
        assertEquals(0, reader.feed(byteArrayOf(0x34), 1).size)
        assertArrayEquals(shortArrayOf(0x1234), reader.feed(byteArrayOf(0x12), 1))
    }

    @Test
    fun providersAreLookedUpByNameAndCanBeAdded() {
        assertTrue("openai" in SpeechProviders.names)
        val tts = SpeechProviders.create(SpeechSettings(), TokenSource { "k" })
        assertEquals(24_000, tts.sampleRate)
        try {
            SpeechProviders.create(SpeechSettings(provider = "nope"), TokenSource { "k" })
            fail("an unknown provider should be refused")
        } catch (e: SpeechException) {
            assertTrue(e.message!!.contains("nope"))
        }
        SpeechProviders.register("fake") { _, _ -> tts }
        assertTrue("fake" in SpeechProviders.names)
        assertEquals(tts, SpeechProviders.create(SpeechSettings(provider = "FAKE"), TokenSource { null }))
    }
}
