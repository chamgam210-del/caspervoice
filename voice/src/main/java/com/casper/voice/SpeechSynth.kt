package com.casper.voice

import com.casper.voice.audio.SpeechPlayer
import com.casper.voice.tts.SpeechProviders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Says text with whichever speech provider the host's settings name right now.
 *
 * Settings and key are asked for on every call, so the host can swap the
 * model, voice, provider or key between two lines without restarting
 * anything. One line plays at a time; a second waits its turn.
 */
class SpeechSynth(
    /** The settings for the next line, or null when speaking this way is switched off. */
    private val settings: suspend () -> SpeechSettings?,
    /** The key for a provider, used when the settings carry none. */
    private val keyFor: suspend (SpeechSettings) -> String?,
    private val log: (String) -> Unit = {}
) : Talker {
    sealed interface Result {
        /** Nothing was spoken because speaking this way is off. */
        data object Off : Result

        /** Everything was heard. [audioMs] is the length of the speech. */
        data class Finished(val settings: SpeechSettings, val firstAudioMs: Long, val audioMs: Long) : Result

        /**
         * Cut off by [stop] after [playedMs] of it was heard. [heard] is the
         * words that got out, for the conversation history; [unheard] starts
         * at the sentence that was cut, which is what to say if the line resumes.
         */
        data class Interrupted(
            val settings: SpeechSettings,
            val firstAudioMs: Long,
            val playedMs: Long,
            val heard: String = "",
            val unheard: String = "",
            /** The whole sentences heard before the one that was cut. */
            val heardBeforeCut: String = ""
        ) : Result

        /** [started] says whether any audio had played, which decides whether another voice should repeat it. */
        data class Failed(val settings: SpeechSettings, val reason: String, val started: Boolean) : Result
    }

    private val turn = Mutex()
    @Volatile private var player: SpeechPlayer? = null
    @Volatile private var playedAtStop = 0L

    @Volatile override var speaking = false
        private set

    suspend fun enabled(): Boolean = settings() != null

    /** [onStart] runs once, when the first audio is about to play. */
    override suspend fun speak(text: String, onStart: () -> Unit): Result = turn.withLock {
        val s = settings() ?: return@withLock Result.Off
        val clean = SpeechText.clean(text)
        if (clean.isBlank()) return@withLock Result.Finished(s, 0, 0)

        val tts = try {
            SpeechProviders.create(s, TokenSource { s.apiKey ?: keyFor(s) })
        } catch (e: Exception) {
            return@withLock Result.Failed(s, e.message ?: e.javaClass.simpleName, started = false).also { report(it) }
        }

        val began = System.nanoTime()
        var firstAudioMs = -1L
        var samples = 0L
        val p = SpeechPlayer(tts.sampleRate)
        playedAtStop = 0L
        player = p
        speaking = true
        val texts = SpeechText.split(clean)
        val ends = ArrayList<Long>()
        val result: Result = try {
            val audio = pieces(tts, texts, ends).onEach {
                samples += it.size
                if (firstAudioMs < 0) {
                    firstAudioMs = (System.nanoTime() - began) / 1_000_000
                    onStart()
                }
            }
            if (p.play(audio)) {
                Result.Finished(s, firstAudioMs, samples * 1_000 / tts.sampleRate)
            } else {
                val cut = SpeechText.cut(texts, ends.map { it * 1_000 / tts.sampleRate }, playedAtStop)
                Result.Interrupted(s, firstAudioMs, playedAtStop, cut.heard, cut.unheard, cut.before)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failed(s, e.message ?: e.javaClass.simpleName, started = firstAudioMs >= 0)
        } finally {
            speaking = false
            player = null
        }
        report(result)
        result
    }

    private fun report(r: Result) = log("synth $r")

    /**
     * Every piece is requested at once and played in order, so the next is
     * usually ready before the last one ends and there is no gap at the seam.
     */
    private fun pieces(tts: TextToSpeech, texts: List<String>, ends: MutableList<Long>): Flow<ShortArray> = flow {
        coroutineScope {
            val channels = texts.map { piece ->
                Channel<ShortArray>(Channel.UNLIMITED).also { ch ->
                    launch {
                        try {
                            tts.synthesize(piece).collect { ch.send(it) }
                            ch.close()
                        } catch (e: Throwable) {
                            ch.close(e)
                            if (e is CancellationException) throw e
                        }
                    }
                }
            }
            var total = 0L
            for (ch in channels) {
                for (chunk in ch) {
                    total += chunk.size
                    emit(chunk)
                }
                ends += total
            }
        }
    }

    /** Cuts the voice off at once. How much was heard comes back with [speak]'s result. */
    override fun stop() {
        val p = player ?: return
        playedAtStop = p.playedMs()
        p.stop()
    }

    /** Quieter while it is still unclear whether the user is interrupting. */
    override fun duck(on: Boolean) {
        player?.duck(on)
    }
}
