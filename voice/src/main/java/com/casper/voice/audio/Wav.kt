package com.casper.voice.audio

import java.io.ByteArrayOutputStream

/** 16-bit mono PCM in a WAV file, so a clip can be played or sent to another model. */
object Wav {
    fun bytes(frames: List<ShortArray>, sampleRate: Int = 16_000): ByteArray {
        val samples = frames.sumOf { it.size }
        val data = samples * 2
        val out = ByteArrayOutputStream(44 + data)
        fun int(v: Int) = repeat(4) { out.write((v shr (8 * it)) and 0xff) }
        fun short(v: Int) = repeat(2) { out.write((v shr (8 * it)) and 0xff) }
        out.write("RIFF".toByteArray())
        int(36 + data)
        out.write("WAVEfmt ".toByteArray())
        int(16)
        short(1) // PCM
        short(1) // mono
        int(sampleRate)
        int(sampleRate * 2)
        short(2)
        short(16)
        out.write("data".toByteArray())
        int(data)
        for (frame in frames) for (s in frame) short(s.toInt())
        return out.toByteArray()
    }
}
