package com.casper.voice

import com.casper.voice.audio.MicCapture
import com.casper.voice.audio.SpeechPlayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * How much of the bot's own voice the microphone still hears.
 *
 * Plays speech through the speaker while listening, the way a call would, and
 * scores every frame. Every [SpeechGate.Signal.Started] or
 * [SpeechGate.Signal.BargeIn] while only the bot is talking is the bot
 * interrupting itself. Run it with nobody talking.
 */
class EchoTest(
    private val detector: SpeechDetector,
    private val rules: TurnTaking = TurnTaking.DEFAULT
) {
    data class Phase(
        val name: String,
        val echoCanceller: Boolean?,
        val noiseSuppressor: Boolean?,
        val frames: Int,
        val maxProbability: Float,
        /** Share of frames that would count as speech with the listening threshold. */
        val overListening: Float,
        /** Share of frames that would count as speech with the stricter threshold used while the bot talks. */
        val overHolding: Float,
        val falseStarts: Int,
        val falseBargeIns: Int,
        val meanRms: Float,
        val maxRms: Float,
        /** Signals with their time from the first frame, then one line per second of the loudest score and level. */
        val timeline: List<String> = emptyList()
    ) {
        fun summary(): String =
            "$name: aec=${echoCanceller ?: "n/a"} ns=${noiseSuppressor ?: "n/a"} frames=$frames " +
                "maxP=${"%.3f".format(maxProbability)} " +
                "overListening=${pct(overListening)} overHolding=${pct(overHolding)} " +
                "falseStarts=$falseStarts falseBargeIns=$falseBargeIns " +
                "rms mean=${"%.4f".format(meanRms)} max=${"%.4f".format(maxRms)}"

        private fun pct(f: Float) = "${"%.1f".format(f * 100)}%"
    }

    /** [speech] is 16-bit mono PCM at [speechRate]. */
    suspend fun run(speech: ShortArray, speechRate: Int, quietMs: Int = 3_000): List<Phase> = listOf(
        listen("quiet room", MicCapture(echoCanceller = true), botSpeaking = false, play = null, quietMs = quietMs),
        listen("speaking, echo canceller on", MicCapture(echoCanceller = true), botSpeaking = true, play = speech to speechRate),
        listen("speaking, echo canceller off", MicCapture(echoCanceller = false), botSpeaking = true, play = speech to speechRate)
    )

    /**
     * One playback with the bot "speaking" the whole time. With nobody talking,
     * any Started or BargeIn is an echo. With someone talking over it, they
     * are the expected result, and the timeline says when.
     */
    suspend fun speaking(name: String, speech: ShortArray, speechRate: Int, echoCanceller: Boolean = true): Phase =
        listen(name, MicCapture(echoCanceller = echoCanceller), botSpeaking = true, play = speech to speechRate)

    private suspend fun listen(
        name: String,
        mic: MicCapture,
        botSpeaking: Boolean,
        play: Pair<ShortArray, Int>?,
        quietMs: Int = 0
    ): Phase = coroutineScope {
        detector.reset()
        val gate = SpeechGate(rules, frameMs = mic.frameSamples * 1_000 / mic.sampleRate)
        var opened: MicCapture.Opened? = null
        var frames = 0
        var maxP = 0f
        var overListening = 0
        var overHolding = 0
        var starts = 0
        var barges = 0
        var rmsSum = 0.0
        var maxRms = 0f
        val quietFrames = quietMs * mic.sampleRate / 1_000 / mic.frameSamples
        val frameMs = mic.frameSamples * 1_000 / mic.sampleRate
        val signals = mutableListOf<String>()
        val seconds = mutableListOf<String>()
        var secMaxP = 0f
        var secMaxRms = 0f

        val done = AtomicBoolean(false)
        if (play != null) {
            launch {
                try {
                    SpeechPlayer(play.second).play(flowOf(play.first))
                } finally {
                    done.set(true)
                }
            }
        }

        mic.frames { opened = it }
            .takeWhile { if (play == null) frames < quietFrames else !done.get() }
            .collect { frame ->
                frames++
                val p = detector.speechProbability(frame)
                val rms = rmsOf(frame)
                if (p > maxP) maxP = p
                if (p >= rules.listening.threshold) overListening++
                if (p >= rules.holding.threshold) overHolding++
                rmsSum += rms
                if (rms > maxRms) maxRms = rms
                val at = (frames - 1) * frameMs
                if (p > secMaxP) secMaxP = p
                if (rms > secMaxRms) secMaxRms = rms
                if (frames * frameMs % 1_000 < frameMs) {
                    seconds += "s${at / 1_000} maxP=${"%.2f".format(secMaxP)} maxRms=${"%.3f".format(secMaxRms)}"
                    secMaxP = 0f
                    secMaxRms = 0f
                }
                val signal = gate.feed(p, botSpeaking)
                if (signal != null) signals += "${at}ms $signal p=${"%.2f".format(p)}"
                when (signal) {
                    SpeechGate.Signal.Started -> starts++
                    SpeechGate.Signal.BargeIn -> barges++
                    else -> Unit
                }
            }

        val n = frames.coerceAtLeast(1)
        Phase(
            name = name,
            echoCanceller = opened?.echoCanceller,
            noiseSuppressor = opened?.noiseSuppressor,
            frames = frames,
            maxProbability = maxP,
            overListening = overListening.toFloat() / n,
            overHolding = overHolding.toFloat() / n,
            falseStarts = starts,
            falseBargeIns = barges,
            meanRms = (rmsSum / n).toFloat(),
            maxRms = maxRms,
            timeline = listOf("signals: ${signals.joinToString("; ").ifEmpty { "none" }}") + seconds
        )
    }

    private fun rmsOf(frame: ShortArray): Float {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return (sqrt(sum / frame.size) / 32768.0).toFloat()
    }
}
