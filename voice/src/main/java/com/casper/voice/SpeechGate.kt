package com.casper.voice

/**
 * Turns per-frame speech scores into the moments that matter: the user
 * started, the user stopped, and the user is talking over the bot.
 *
 * Fed one frame at a time with whether the bot is talking, so the stricter
 * [TurnTaking.holding] rules apply exactly while its voice can leak back in.
 */
class SpeechGate(
    private val rules: TurnTaking = TurnTaking.DEFAULT,
    private val frameMs: Int = 32,
    /** Speech has to last this long to count, so a click or a cough does not. */
    private val startMs: Int = START_MS
) {
    enum class Signal {
        /** A first hint of speech over the bot: soften the bot while it is decided. */
        MaybeSpeech,
        Started,
        Stopped,
        BargeIn
    }

    var speaking = false
        private set

    companion object {
        /** Speech has to last this long to count; the engine keeps this much audio from before it. */
        const val START_MS = 200
    }

    private var aboveMs = 0
    private var belowMs = 0
    private var speakingMs = 0
    private var hinted = false
    private var overBot = false
    private var bargedIn = false

    fun feed(probability: Float, botSpeaking: Boolean): Signal? {
        val rule = rules.detection(botSpeaking)
        val above = probability >= rule.threshold
        if (!speaking) {
            if (!above) {
                aboveMs = 0
                hinted = false
                return null
            }
            aboveMs += frameMs
            if (aboveMs >= startMs) {
                speaking = true
                speakingMs = 0
                belowMs = 0
                overBot = botSpeaking
                bargedIn = false
                return Signal.Started
            }
            if (botSpeaking && !hinted) {
                hinted = true
                return Signal.MaybeSpeech
            }
            return null
        }

        speakingMs += frameMs
        belowMs = if (above) 0 else belowMs + frameMs
        if (belowMs >= rule.silenceMs) {
            reset()
            return Signal.Stopped
        }
        if (overBot && !bargedIn && speakingMs >= rules.bargeHoldMs &&
            rules.isBargeIn(userStillSpeaking = true, botStillSpeaking = botSpeaking)
        ) {
            bargedIn = true
            return Signal.BargeIn
        }
        return null
    }

    fun reset() {
        speaking = false
        aboveMs = 0
        belowMs = 0
        speakingMs = 0
        hinted = false
        overBot = false
        bargedIn = false
    }
}
