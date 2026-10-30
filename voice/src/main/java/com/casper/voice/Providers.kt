package com.casper.voice

import kotlinx.coroutines.flow.Flow

/**
 * The pieces an engine is built from. Each one can be swapped without the
 * host noticing: a cloud transcriber for an on-device one, one voice for
 * another.
 */

/** A short-lived credential. Long-lived keys stay on the host's server. */
fun interface TokenSource {
    suspend fun token(): String?
}

/** Speech in, text out. [audio] is 16 kHz mono PCM16. */
interface SpeechToText {
    fun transcribe(audio: Flow<ShortArray>): Flow<Transcript>
}

data class Transcript(val text: String, val final: Boolean)

/** Text in, speech out, as 16-bit mono PCM chunks at [sampleRate]. */
interface TextToSpeech {
    val sampleRate: Int
    fun synthesize(text: String): Flow<ShortArray>
}

/** The probability, 0 to 1, that a frame of audio is speech. */
interface SpeechDetector {
    val frameSamples: Int
    fun speechProbability(frame: ShortArray): Float

    /** Forgets earlier audio, so a new stream starts clean. */
    fun reset() {}
}
