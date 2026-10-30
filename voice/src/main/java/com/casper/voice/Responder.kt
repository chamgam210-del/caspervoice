package com.casper.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the agent says back. Optional: a host that decides every word itself
 * leaves it out and answers [VoiceEvent.Heard] with [VoiceEngine.say].
 *
 * [reply] streams the answer as text pieces of any size; [VoiceAgent] speaks
 * it a sentence at a time as it arrives, so the voice starts before the whole
 * answer is written.
 */
interface Responder {
    fun reply(heard: String): Flow<String>

    /** The reply was cut off; only [spoken] reached the user. Keep that, not the whole reply, in any history. */
    fun cutOff(spoken: String) {}

    companion object {
        /** A responder from a plain function, for simple or scripted replies. */
        fun of(answer: suspend (String) -> String?): Responder = object : Responder {
            override fun reply(heard: String): Flow<String> = kotlinx.coroutines.flow.flow {
                answer(heard)?.let { emit(it) }
            }
        }

        /** Says back what it heard. Handy to check the ears and voice with no model at all. */
        val ECHO: Responder = object : Responder {
            override fun reply(heard: String): Flow<String> = flowOf(heard)
        }
    }
}

/** Cuts streamed text into sentences so each can be spoken as soon as it is complete. */
class SentenceBuffer(private val minChars: Int = 20) {
    private val pending = StringBuilder()

    /** The sentences [piece] completed. */
    fun add(piece: String): List<String> {
        pending.append(piece)
        val out = ArrayList<String>()
        while (true) {
            val end = boundary() ?: break
            out += pending.substring(0, end).trim()
            pending.delete(0, end)
        }
        return out.filter { it.isNotBlank() }
    }

    /** Whatever is left once the stream ends. */
    fun flush(): String = pending.toString().trim().also { pending.clear() }

    private fun boundary(): Int? {
        for (i in minChars until pending.length) {
            val c = pending[i - 1]
            val next = pending[i]
            if (c == '\n') return i
            if ((c == '.' || c == '!' || c == '?') && next.isWhitespace()) return i
        }
        return null
    }
}
