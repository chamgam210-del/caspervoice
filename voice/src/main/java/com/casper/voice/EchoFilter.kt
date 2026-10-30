package com.casper.voice

/**
 * Tells the bot's own voice, picked up by the microphone and transcribed,
 * from something the user said.
 *
 * The phone's echo canceller removes most of it. What slips through can
 * still be transcribed, and acted on as if the user had said it, which is
 * how an assistant ends up answering itself. A transcript made of words the
 * bot had just spoken is dropped.
 */
object EchoFilter {
    fun isEcho(heard: String, botSaid: String): Boolean {
        val said = words(heard)
        if (said.isEmpty()) return false
        val bot = words(botSaid)
        if (bot.isEmpty()) return false
        val botSet = bot.toSet()
        val inBot = said.count { it in botSet }
        if (said.size >= MIN_WORDS && inBot.toDouble() / said.size >= SHARE) return true
        // A short run that is exactly a stretch of what the bot said.
        return said.size >= MIN_RUN && containsRun(bot, said)
    }

    private fun containsRun(bot: List<String>, run: List<String>): Boolean {
        if (run.size > bot.size) return false
        for (start in 0..bot.size - run.size) {
            if (run.indices.all { bot[start + it] == run[it] }) return true
        }
        return false
    }

    private fun words(text: String): List<String> =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").split(' ').filter { it.isNotBlank() }

    private const val MIN_WORDS = 4
    private const val MIN_RUN = 3
    private const val SHARE = 0.8
}
