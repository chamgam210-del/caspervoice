package com.casper.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.max

/**
 * The microphone as a voice call hears it: 16 kHz mono PCM16, in frames of
 * [frameSamples].
 *
 * VOICE_COMMUNICATION is the source that switches on the phone's own echo
 * canceller. The canceller and noise suppressor are also attached to this
 * recorder's session, which is where Android applies them; attached to a
 * player's session they do nothing.
 */
class MicCapture(
    val sampleRate: Int = 16_000,
    val frameSamples: Int = 512,
    private val echoCanceller: Boolean = true,
    private val noiseSuppressor: Boolean = true
) {
    data class Opened(
        val sessionId: Int,
        /** Null when the phone has no such effect to switch. */
        val echoCanceller: Boolean?,
        val noiseSuppressor: Boolean?
    )

    /** Cold: the microphone opens when collected and is released when collection stops. */
    @SuppressLint("MissingPermission")
    fun frames(onOpened: (Opened) -> Unit = {}): Flow<ShortArray> = flow {
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        check(minBytes > 0) { "no microphone buffer size ($minBytes)" }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            CHANNEL,
            ENCODING,
            max(minBytes, frameSamples * BYTES_PER_SAMPLE * BUFFERED_FRAMES)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("microphone unavailable (permission, or another app holds it)")
        }
        val session = record.audioSessionId
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(session) }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(session) }.getOrNull() else null
        try {
            record.startRecording()
            onOpened(Opened(session, switch(aec, echoCanceller), switch(ns, noiseSuppressor)))
            while (currentCoroutineContext().isActive) {
                val frame = ShortArray(frameSamples)
                var filled = 0
                while (filled < frameSamples) {
                    val n = record.read(frame, filled, frameSamples - filled)
                    if (n < 0) error("microphone read failed ($n)")
                    filled += n
                }
                emit(frame)
            }
        } finally {
            aec?.let { runCatching { it.release() } }
            ns?.let { runCatching { it.release() } }
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    private fun switch(effect: AudioEffect?, on: Boolean): Boolean? {
        effect ?: return null
        runCatching { effect.enabled = on }
        return runCatching { effect.enabled }.getOrNull()
    }

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2
        const val BUFFERED_FRAMES = 4
    }
}
