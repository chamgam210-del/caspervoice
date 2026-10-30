package com.casper.voice

/**
 * When the user's speech counts, and when it is allowed to stop the bot.
 *
 * While the bot is talking, its own voice comes back through the microphone
 * and coughs sound like words, so detection is stricter and nothing stops the
 * bot automatically. Speech only interrupts once it has gone on for
 * [bargeHoldMs] while the bot is still talking.
 */
data class TurnTaking(
    val listening: Detection,
    val holding: Detection,
    val bargeHoldMs: Long,
    /**
     * After a barge-in that turns out to be nothing (a blank transcript or the
     * bot's own echo), say the rest of the line again from the sentence that was cut.
     */
    val resumeAfterFalseBargeIn: Boolean = true
) {
    data class Detection(
        val threshold: Double,
        val prefixMs: Int,
        val silenceMs: Int,
        /** Whether speech starting stops the bot straight away. */
        val interrupt: Boolean
    )

    fun detection(botSpeaking: Boolean): Detection = if (botSpeaking) holding else listening

    /** Asked [bargeHoldMs] after the user started talking over the bot. */
    fun isBargeIn(userStillSpeaking: Boolean, botStillSpeaking: Boolean): Boolean =
        userStillSpeaking && botStillSpeaking

    companion object {
        /**
         * Tuned on a phone on speakerphone. A 800 ms silence was cutting people
         * off mid-thought; 1.5 s is the buffer.
         */
        val DEFAULT = TurnTaking(
            listening = Detection(threshold = 0.72, prefixMs = 500, silenceMs = 1_500, interrupt = true),
            holding = Detection(threshold = 0.88, prefixMs = 300, silenceMs = 700, interrupt = false),
            bargeHoldMs = 750L
        )
    }
}
