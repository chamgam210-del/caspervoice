package com.casper.voice

/** Turning written text into something a voice should read, and cutting it into requests. */
object SpeechText {

    /** Markdown a voice would read out loud ("asterisk asterisk"), removed. */
    fun clean(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
        .replace(Regex("\\*{1,3}([^*\\n]+)\\*{1,3}"), "$1")
        .replace(Regex("(?<![A-Za-z0-9])_{1,2}([^_\\n]+)_{1,2}(?![A-Za-z0-9])"), "$1")
        .replace("*", "")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\s*\\n\\s*"), "\n")
        .trim()

    /**
     * Pieces of at most [maxChars], broken after sentences. The first piece is
     * kept short so the voice starts sooner; the rest are longer so there are
     * fewer seams.
     */
    fun split(text: String, firstChars: Int = 400, maxChars: Int = 1_200): List<String> {
        val sentences = Regex("[^.!?\\n]+[.!?]*[\"')\\]]*\\s*|\\n").findAll(text).map { it.value }.toList()
        val pieces = mutableListOf<String>()
        var current = StringBuilder()
        for (s in sentences) {
            val limit = if (pieces.isEmpty()) firstChars else maxChars
            if (current.isNotEmpty() && current.length + s.length > limit) {
                pieces += current.toString().trim()
                current = StringBuilder()
            }
            if (s.length > maxChars) {
                s.chunked(maxChars).forEach { part ->
                    if (current.isNotEmpty()) { pieces += current.toString().trim(); current = StringBuilder() }
                    pieces += part.trim()
                }
            } else {
                current.append(s)
            }
        }
        if (current.isNotBlank()) pieces += current.toString().trim()
        return pieces.filter { it.isNotBlank() }
    }

    /**
     * The words of [text] a voice would have got through after [playedMs],
     * ended at a word boundary. Speech runs at roughly [charsPerSecond].
     */
    fun spokenPart(text: String, playedMs: Long, charsPerSecond: Double = 14.0): String {
        val chars = (playedMs / 1_000.0 * charsPerSecond).toInt()
        return text.substring(0, wordCut(text, chars)).trim()
    }

    /** [before] is the whole sentences heard ahead of the one that was cut. */
    data class Cut(val heard: String, val unheard: String, val before: String = heard)

    /**
     * Where a line made of [pieces] was cut off after [playedMs]. [endsMs] is
     * where each piece's audio ended, for the pieces that had fully arrived;
     * within a piece the words are assumed to be spread evenly over its audio.
     * The unheard part starts at the beginning of the sentence that was cut,
     * so a resumed line does not start mid-sentence.
     */
    fun cut(pieces: List<String>, endsMs: List<Long>, playedMs: Long): Cut {
        if (pieces.isEmpty()) return Cut("", "")
        var i = endsMs.indexOfFirst { it > playedMs }
        if (i < 0) i = endsMs.size
        if (i >= pieces.size) return Cut(pieces.joinToString(" "), "")
        val piece = pieces[i]
        val startMs = if (i == 0) 0L else endsMs[i - 1]
        val into = (playedMs - startMs).coerceAtLeast(0)
        val chars = if (i < endsMs.size && endsMs[i] > startMs) {
            (piece.length * into.toDouble() / (endsMs[i] - startMs)).toInt()
        } else {
            (into / 1_000.0 * 14.0).toInt()
        }
        val at = wordCut(piece, chars)
        val sentenceStart = Regex("[^.!?\\n]+[.!?]*[\"')\\]]*\\s*").findAll(piece)
            .map { it.range.first }
            .lastOrNull { it <= at } ?: 0
        val heard = (pieces.subList(0, i) + piece.substring(0, at)).joinToString(" ").trim()
        val unheard = (listOf(piece.substring(sentenceStart)) + pieces.subList(i + 1, pieces.size)).joinToString(" ").trim()
        val before = (pieces.subList(0, i) + piece.substring(0, sentenceStart)).joinToString(" ").trim()
        return Cut(heard, unheard, before)
    }

    private fun wordCut(text: String, chars: Int): Int {
        if (chars >= text.length) return text.length
        if (chars <= 0) return 0
        return text.lastIndexOf(' ', chars).takeIf { it > 0 } ?: chars
    }
}
