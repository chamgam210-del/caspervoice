package com.casper.voice

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A live voice: it hears the user, turns what they said into text, and says
 * what the host app gives it. It never decides what to say. That belongs to
 * the host, which reads [VoiceEvent.Heard] and answers with [say].
 */
interface VoiceEngine {
    val state: StateFlow<VoiceState>
    val events: SharedFlow<VoiceEvent>

    val userSpeaking: Boolean
    val botSpeaking: Boolean

    suspend fun start(): Boolean
    suspend fun stop()

    /** True when the engine took the line; [VoiceEvent.BotStarted] follows once it plays. */
    suspend fun say(utterance: Utterance): Boolean

    /** Stops whatever is playing now. Lines not yet given to [say] are the host's to drop. */
    fun stopTalking()
}

enum class VoiceState { Idle, Connecting, Live, Closing }

data class Utterance(
    val text: String,
    /** False for lines that must play through, such as a confirmation the user has to hear. */
    val interruptible: Boolean = true
)

sealed interface VoiceEvent {
    data object UserStarted : VoiceEvent
    data object UserStopped : VoiceEvent

    /** A guess at what is being said, for showing on screen. Never act on it. */
    data class Partial(val text: String) : VoiceEvent

    /** A finished utterance. This is the one to act on. */
    data class Heard(val text: String) : VoiceEvent

    data object BotStarted : VoiceEvent
    data object BotStopped : VoiceEvent

    /** The user talked over the bot long enough for [TurnTaking.bargeHoldMs] to count it. */
    data object BargeIn : VoiceEvent

    /**
     * A line was cut off by the user. [heard] is the part that got out, which
     * is what belongs in the conversation history.
     */
    data class Interrupted(val text: String, val heard: String) : VoiceEvent

    /** The barge-in was nothing after all, so the cut line is being said again from [from]. */
    data class Resumed(val from: String) : VoiceEvent

    data class Failed(val reason: String) : VoiceEvent
}
