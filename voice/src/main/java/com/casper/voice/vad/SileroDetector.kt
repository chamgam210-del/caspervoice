package com.casper.voice.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.casper.voice.SpeechDetector
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 at 16 kHz.
 *
 * Each 512-sample frame goes in behind the last 64 samples of the one before.
 * The model was trained that way; without that context its scores collapse
 * towards zero on real speech.
 */
class SileroDetector(model: ByteArray) : SpeechDetector, AutoCloseable {
    override val frameSamples: Int = FRAME

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(model)
    private var state = freshState()
    private val context = FloatArray(CONTEXT)

    override fun speechProbability(frame: ShortArray): Float {
        require(frame.size == FRAME) { "Silero needs $FRAME samples, got ${frame.size}" }
        val input = FloatArray(CONTEXT + FRAME)
        context.copyInto(input)
        for (i in frame.indices) input[CONTEXT + i] = frame[i] / 32768f
        input.copyInto(context, startIndex = input.size - CONTEXT)

        val audio = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong()))
        val rate = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf())
        val st = OnnxTensor.createTensor(env, state)
        try {
            session.run(mapOf("input" to audio, "sr" to rate, "state" to st)).use { out ->
                @Suppress("UNCHECKED_CAST")
                state = out[1].value as Array<Array<FloatArray>>
                return probabilityOf(out[0].value)
            }
        } finally {
            audio.close()
            rate.close()
            st.close()
        }
    }

    override fun reset() {
        state = freshState()
        context.fill(0f)
    }

    override fun close() {
        session.close()
    }

    private fun probabilityOf(value: Any?): Float = when (value) {
        is Array<*> -> probabilityOf(value[0])
        is FloatArray -> value[0]
        is Number -> value.toFloat()
        else -> 0f
    }

    private fun freshState() = Array(2) { Array(1) { FloatArray(128) } }

    companion object {
        /** Where the library keeps the model (Silero VAD v5, MIT licence). */
        const val ASSET = "vad/silero_vad.onnx"

        private const val FRAME = 512
        private const val CONTEXT = 64
        private const val SAMPLE_RATE = 16_000L

        /** The model bundled with the library. */
        fun fromAssets(context: Context): SileroDetector =
            SileroDetector(context.assets.open(ASSET).use { it.readBytes() })
    }
}
