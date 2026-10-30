package com.casper.voice.audio

/**
 * Linear-interpolation resampling of a PCM16 stream that arrives in pieces.
 * The position between samples carries over from one piece to the next, so
 * the seams do not click.
 */
class Resampler(private val from: Int, private val to: Int) {
    private val step = from.toDouble() / to
    private var pos = 0.0
    private var last: Short? = null

    fun feed(input: ShortArray): ShortArray {
        if (from == to || input.isEmpty()) return input
        // Index -1 is the last sample of the previous piece.
        val prev = last
        val out = ArrayList<Short>((input.size * to / from) + 2)
        fun at(i: Int): Int = if (i < 0) (prev?.toInt() ?: input[0].toInt()) else input[i].toInt()
        while (true) {
            val a = kotlin.math.floor(pos).toInt()
            if (a + 1 > input.lastIndex) break
            val f = pos - a
            out += (at(a) * (1 - f) + at(a + 1) * f).toInt().toShort()
            pos += step
        }
        last = input.last()
        // In the next piece, this piece's last sample is index -1.
        pos -= input.size
        return out.toShortArray()
    }
}
