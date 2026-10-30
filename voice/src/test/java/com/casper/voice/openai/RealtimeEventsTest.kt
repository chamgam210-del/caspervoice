package com.casper.voice.openai

import com.casper.voice.TurnTaking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeEventsTest {

    @Test
    fun turnDetectionWritesMillisecondsAsIntegers() {
        val json = RealtimeEvents.turnDetection(TurnTaking.DEFAULT.holding, "gpt-4o-mini-transcribe", "en")
        assertEquals(
            """{"type":"session.update","session":{"type":"realtime","audio":{"input":{"transcription":{"model":"gpt-4o-mini-transcribe","language":"en"},"turn_detection":{"type":"server_vad","threshold":0.88,"prefix_padding_ms":300,"silence_duration_ms":700,"interrupt_response":false,"create_response":false}}}}}""",
            json
        )
    }

    @Test
    fun listeningDetectionIsTheOneSentAfterConnect() {
        val json = RealtimeEvents.turnDetection(TurnTaking.DEFAULT.listening, "gpt-4o-mini-transcribe", "en")
        val vad = JSONObject(json).getJSONObject("session").getJSONObject("audio")
            .getJSONObject("input").getJSONObject("turn_detection")
        assertEquals(0.72, vad.getDouble("threshold"), 0.0)
        assertEquals(500, vad.getInt("prefix_padding_ms"))
        assertEquals(1_500, vad.getInt("silence_duration_ms"))
        assertTrue(vad.getBoolean("interrupt_response"))
        assertFalse(vad.getBoolean("create_response"))
    }

    @Test
    fun anOutOfBandResponseSeesOnlyItsInstructions() {
        val json = JSONObject(RealtimeEvents.response("Say exactly: Hi.", tools = false, history = false, spokenLanguage = "English"))
        assertEquals("response.create", json.getString("type"))
        val response = json.getJSONObject("response")
        assertEquals("none", response.getString("tool_choice"))
        assertEquals("none", response.getString("conversation"))
        assertEquals(0, response.getJSONArray("input").length())
        assertEquals("Speak English only.\nSay exactly: Hi.", response.getString("instructions"))
    }

    @Test
    fun aResponseWithHistoryAndToolsIsLeftToTheSession() {
        val response = JSONObject(RealtimeEvents.response("Call them.", tools = true, history = true, spokenLanguage = "English"))
            .getJSONObject("response")
        assertFalse(response.has("tool_choice"))
        assertFalse(response.has("conversation"))
        assertEquals("Call them.", response.getString("instructions"))
    }

    @Test
    fun whatWasSaidOutOfBandIsKeptAsTheAssistants() {
        val json = JSONObject(RealtimeEvents.assistantSaid("  It's sunny.  "))
        assertEquals("conversation.item.create", json.getString("type"))
        val item = json.getJSONObject("item")
        assertEquals("assistant", item.getString("role"))
        val part = item.getJSONArray("content").getJSONObject(0)
        assertEquals("output_text", part.getString("type"))
        assertEquals("It's sunny.", part.getString("text"))
    }
}
