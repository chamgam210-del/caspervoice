package com.casper.voice.stt

import com.casper.voice.TranscriptionSettings
import com.casper.voice.stt.TranscriptionEvents.Incoming
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionEventsTest {

    @Test
    fun theUrlFollowsTheBaseUrl() {
        assertEquals("wss://api.openai.com/v1/realtime?intent=transcription", TranscriptionEvents.url("https://api.openai.com/v1"))
        assertEquals("ws://10.0.0.5:8000/v1/realtime?intent=transcription", TranscriptionEvents.url("http://10.0.0.5:8000/v1/"))
    }

    @Test
    fun theSessionTakesTheModelAndLanguageAndLeavesTurnsToTheLibrary() {
        val json = JSONObject(TranscriptionEvents.sessionUpdate(TranscriptionSettings(language = "en", prompt = "Casper, Oshawa")))
        assertEquals("session.update", json.getString("type"))
        val session = json.getJSONObject("session")
        assertEquals("transcription", session.getString("type"))
        val input = session.getJSONObject("audio").getJSONObject("input")
        assertEquals(24_000, input.getJSONObject("format").getInt("rate"))
        assertTrue("turn detection must be off", input.isNull("turn_detection"))
        val t = input.getJSONObject("transcription")
        assertEquals("gpt-transcribe", t.getString("model"))
        assertEquals("en", t.getString("language"))
        assertEquals("Casper, Oshawa", t.getString("prompt"))
    }

    @Test
    fun noLanguageMeansNoLanguageField() {
        val json = JSONObject(TranscriptionEvents.sessionUpdate(TranscriptionSettings()))
        val t = json.getJSONObject("session").getJSONObject("audio").getJSONObject("input").getJSONObject("transcription")
        assertTrue(!t.has("language") && !t.has("prompt"))
    }

    @Test
    fun sixteenKilohertzBecomesTwentyFour() {
        val up = TranscriptionEvents.upsample(ShortArray(512) { 1000 })
        assertEquals(768, up.size)
        assertTrue(up.all { it == 1000.toShort() })
        val ramp = TranscriptionEvents.upsample(shortArrayOf(0, 300, 600))
        assertEquals(listOf<Short>(0, 200, 400, 600), ramp.toList())
    }

    @Test
    fun anAppendCarriesTheAudioAsBase64() {
        val json = JSONObject(TranscriptionEvents.append(ShortArray(2) { 1 }))
        assertEquals("input_audio_buffer.append", json.getString("type"))
        assertTrue(json.getString("audio").isNotEmpty())
    }

    @Test
    fun incomingMessagesAreTold() {
        assertEquals(
            Incoming.Delta(" the"),
            TranscriptionEvents.parse("""{"type":"conversation.item.input_audio_transcription.delta","delta":" the"}""")
        )
        assertEquals(
            Incoming.Completed("What's the weather?"),
            TranscriptionEvents.parse("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"What's the weather?"}""")
        )
        assertEquals(Incoming.Error("bad key"), TranscriptionEvents.parse("""{"type":"error","error":{"message":"bad key"}}"""))
        assertEquals(Incoming.Other, TranscriptionEvents.parse("""{"type":"session.created"}"""))
        assertEquals(Incoming.Other, TranscriptionEvents.parse("not json"))
    }

    @Test
    fun settingsKeepDefaultsForWhatIsMissing() {
        val s = TranscriptionSettings.parse("""{"model":"gpt-4o-mini-transcribe","language":"en","unknown":1}""")
        assertEquals("gpt-4o-mini-transcribe", s.model)
        assertEquals("en", s.language)
        assertEquals("openai", s.provider)
        assertEquals("https://api.openai.com/v1", s.baseUrl)
    }
}
