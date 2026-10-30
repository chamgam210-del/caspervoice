package com.casper.voice.openai

import com.casper.voice.TurnTaking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The OpenAI Realtime client events, written out exactly.
 *
 * OpenAI rejects a session.update whose *_ms fields are decimals (300.0), and
 * that left turn detection half-applied, so these are built as strings rather
 * than through a client library's value encoding.
 */
object RealtimeEvents {
    const val CANCEL_RESPONSE = """{"type":"response.cancel"}"""

    /** Over WebRTC, audio already sent keeps playing until this clears it. */
    const val CLEAR_OUTPUT_AUDIO = """{"type":"output_audio_buffer.clear"}"""

    /**
     * Replies are never created automatically: the host decides who answers.
     * The transcription language goes with every update, because unpinned,
     * room noise comes back as words in another language.
     */
    fun turnDetection(detection: TurnTaking.Detection, transcribeModel: String, language: String): String =
        """{"type":"session.update","session":{"type":"realtime","audio":{"input":{"transcription":{"model":"$transcribeModel","language":"$language"},"turn_detection":{"type":"server_vad","threshold":${detection.threshold},"prefix_padding_ms":${detection.prefixMs},"silence_duration_ms":${detection.silenceMs},"interrupt_response":${detection.interrupt},"create_response":false}}}}}"""

    /**
     * One reply with these instructions. Without [history] it is out of band:
     * the model sees nothing but the instructions, which replace the
     * session's, so the language rule has to be repeated.
     */
    fun response(instructions: String, tools: Boolean, history: Boolean, spokenLanguage: String): String {
        val response = JSONObject()
        if (!tools) response.put("tool_choice", "none")
        if (history) {
            response.put("instructions", instructions)
        } else {
            response.put("instructions", "Speak $spokenLanguage only.\n$instructions")
                .put("conversation", "none")
                .put("input", JSONArray())
        }
        return JSONObject()
            .put("type", "response.create")
            .put("response", response)
            .toString()
    }

    /** Adds a line said out of band to the conversation, as the assistant's. */
    fun assistantSaid(text: String): String {
        val content = JSONArray().put(JSONObject().put("type", "output_text").put("text", text.trim()))
        val item = JSONObject().put("type", "message").put("role", "assistant").put("content", content)
        return JSONObject().put("type", "conversation.item.create").put("item", item).toString()
    }
}
