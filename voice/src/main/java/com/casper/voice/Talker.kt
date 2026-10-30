package com.casper.voice

/**
 * The speaking half as the engine needs it. [SpeechSynth] is the real one;
 * tests use a fake so the engine can be checked without a speaker.
 */
interface Talker {
    val speaking: Boolean

    /** [onStart] runs once, when the first audio is about to play. */
    suspend fun speak(text: String, onStart: () -> Unit = {}): SpeechSynth.Result

    /** Cuts the voice off at once. */
    fun stop()

    /** Quieter while it is not yet clear whether the user is interrupting. */
    fun duck(on: Boolean)
}
