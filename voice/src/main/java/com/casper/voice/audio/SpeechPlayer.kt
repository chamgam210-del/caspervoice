package com.casper.voice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Plays speech as a voice call does, so the phone's echo canceller treats it
 * as the far end of a call and takes it back out of the microphone.
 */
class SpeechPlayer(val sampleRate: Int) {
    @Volatile private var track: AudioTrack? = null

    val playing: Boolean get() = track != null

    /**
     * Returns once everything written has been heard, or [stop] was called.
     * True when it all played; false when it was cut off. Cutting off also
     * cancels [chunks], so a download in flight stops with it.
     */
    suspend fun play(chunks: Flow<ShortArray>): Boolean = withContext(Dispatchers.IO) {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBytes, sampleRate / 5 * 2))
            .build()
        track = t
        var written = 0
        var completed = false
        try {
            t.play()
            chunks.takeWhile { track === t }.collect { chunk ->
                var off = 0
                while (off < chunk.size && track === t) {
                    val n = t.write(chunk, off, min(WRITE_SAMPLES, chunk.size - off))
                    if (n <= 0) return@collect
                    off += n
                    written += n
                }
            }
            while (track === t && t.playbackHeadPosition < written) delay(DRAIN_POLL_MS)
            completed = track === t
        } finally {
            runCatching { t.stop() }
            t.release()
            if (track === t) track = null
        }
        completed
    }

    /** How much has actually been heard so far, in milliseconds. */
    fun playedMs(): Long = track?.let { it.playbackHeadPosition * 1_000L / sampleRate } ?: 0L

    /** Cuts off whatever is playing, at once. */
    fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.pause() }
        runCatching { t.flush() }
    }

    /** Quieter while it is not yet clear whether the user is interrupting. */
    fun duck(on: Boolean) {
        track?.let { runCatching { it.setVolume(if (on) DUCKED else 1f) } }
    }

    private companion object {
        const val WRITE_SAMPLES = 1_024
        const val DRAIN_POLL_MS = 20L
        const val DUCKED = 0.3f
    }
}
