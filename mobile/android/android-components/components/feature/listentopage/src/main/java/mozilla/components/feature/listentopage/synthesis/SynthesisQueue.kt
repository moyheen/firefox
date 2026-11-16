/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.synthesis

import android.os.SystemClock
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.feature.listentopage.facts.emitOpeningSynthesisStartedFact
import mozilla.components.feature.listentopage.synthesis.audio.ChunkFiles
import mozilla.components.feature.listentopage.synthesis.audio.readWavDurationOrNull
import mozilla.components.support.base.log.logger.Logger

/**
 * Decides which piece of the article the engine works on next, and keeps the audio of the pieces around the one playing
 * on disk. It keeps no copy of how long each piece lasts: each length is reported through [onMeasured].
 *
 * @param synthesizer The engine. Its input limit caps every request.
 * @param audio Where the audio of each chunk is kept and thrown away.
 * @param chunker Splits the article.
 * @param workDispatcher The dispatcher for the work that must not run on the thread a caller dispatched on.
 * @param measureAudio How long the audio in a file lasts, or `null` when it cannot be read.
 * @param elapsedMs Reads a monotonic clock, so that how long a request took can be measured under a test's virtual time
 *   as well as a device's real one.
 * @param onMeasured Called with each chunk once it has been made and measured.
 */
internal class SynthesisQueue(
    synthesizer: SpeechSynthesizer,
    private val audio: ChunkFiles,
    chunker: TextChunker,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
    measureAudio: suspend (File) -> Duration? = { readWavDurationOrNull(it) },
    elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val onMeasured: (index: Int, length: Duration) -> Unit = { _, _ -> },
) {
    private val logger = Logger("SynthesisQueue")

    private val articleChunker = ArticleChunker(chunker, synthesizer.maxInputLength)

    private val chunkSynthesizer = ChunkSynthesizer(synthesizer, workDispatcher, measureAudio, elapsedMs)

    private var chunks: List<String> = emptyList()

    /** How many pieces the article came to, or `0` until [startReading] has run. */
    val chunkCount: Int
        get() = chunks.size

    /** What the opening measured at, or `null` when it could not be measured. */
    var speechRate: SpeechRate? = null
        private set

    /** The characters in chunk [index], for sizing one that has no measured length. */
    fun charactersIn(index: Int): Int = chunks[index].length

    /**
     * Synthesizes the opening of [text] and returns its audio, then works out how the rest of it divides up.
     *
     * The first request is one sentence, because how fast this voice reads is not known until its audio exists. Once it
     * does, the rest is sized in seconds from that measurement rather than from a table of languages.
     *
     * @throws NothingToReadException if [text] holds no words, in which case there is nothing to play and the session
     *   has no article rather than a failed one.
     * @throws SpeechSynthesisException if the engine cannot make the opening, in which case there is nothing to play
     *   and the session has failed.
     */
    suspend fun startReading(text: String, languageTag: String): File {
        val (opening, split) = withContext(workDispatcher) { articleChunker.splitOpening(text, languageTag) }

        emitOpeningSynthesisStartedFact()
        val (made, synthesisMs) = chunkSynthesizer.make(0, opening) { audio.add(0, it) }
        reportLength(made)

        val rate = made.length?.let { SpeechRate.measuredFrom(opening.length, it) }
        speechRate = rate

        chunks =
            withContext(workDispatcher) {
                listOf(opening) + articleChunker.chunksAfterOpening(split, languageTag, rate, made.length, synthesisMs)
            }

        return made.file
    }

    /**
     * The audio of chunk [index], making it now if the window has not got to it yet or if the system has reclaimed what
     * the window made, or `null` when the article has no such chunk and so has ended.
     *
     * @throws SpeechSynthesisException if the engine cannot make it.
     */
    suspend fun audioFor(index: Int): File? {
        if (index !in chunks.indices) return null

        return audio.fileFor(index) ?: make(index)
    }

    /**
     * Moves the window of chunks kept on disk to sit around [playingChunkIndex], making what it is missing and throwing
     * away what has fallen outside it.
     *
     * @param lastQueuedChunkIndex The index of the last chunk already handed to the player, or `null`. Chunks up to it
     *   are kept even past the window, and deleting what it leaves behind ahead of the player would stop playback when
     *   it reached the missing file.
     * @param seekingToChunkIndex The index of the chunk a seek is moving the player into, or `null`. It is kept even
     *   outside the window, which still sits around where the player last reported it was, so that its audio is there
     *   when the player gets to it.
     * @param onChunkMade Called with each chunk's index as soon as it is made, so that it can be queued without waiting
     *   for the rest.
     * @throws SpeechSynthesisException if the engine cannot make one of them. What was made before it stays, so
     *   playback carries on as far as it can, and the chunk it stopped on is made again by [audioFor] if playback ever
     *   reaches it.
     */
    suspend fun moveWindowTo(
        playingChunkIndex: Int,
        lastQueuedChunkIndex: Int? = null,
        seekingToChunkIndex: Int? = null,
        onChunkMade: suspend (chunkIndex: Int) -> Unit = {},
    ) {
        audio.keepOnly(keptChunks(playingChunkIndex, chunks.size, lastQueuedChunkIndex, seekingToChunkIndex))

        for (index in makeOrder(playingChunkIndex, chunks.size)) {
            if (audio.fileFor(index) != null) continue

            make(index)
            onChunkMade(index)
        }
    }

    /**
     * [moveWindowTo], without reporting what it fails with.
     *
     * The chunk a reader is waiting on is made by [audioFor], which does report a failure. A chunk further ahead than
     * that is made again when playback reaches it, so failing to work ahead is not the session's failure and must not
     * put an error over a player that is still reading the article out.
     */
    suspend fun makeAround(
        playingChunkIndex: Int,
        lastQueuedChunkIndex: Int?,
        seekingToChunkIndex: Int?,
        onChunkMade: suspend (chunkIndex: Int) -> Unit,
    ) {
        try {
            moveWindowTo(playingChunkIndex, lastQueuedChunkIndex, seekingToChunkIndex, onChunkMade)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.warn("Could not work ahead on the article", e)
        }
    }

    /**
     * Makes the chunk at [chunkIndex] ahead of a seek, if the article has one. A chunk that fails is not the seek's
     * failure: it is made again when playback reaches it.
     */
    suspend fun makeNext(chunkIndex: Int) {
        if (chunkIndex >= chunkCount) return

        try {
            audioFor(chunkIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.warn("Could not make the chunk after a seek", e)
        }
    }

    /**
     * The audio of chunk [index], or `null` when it has not been made, has been thrown away, or has been reclaimed by
     * the system. Use [audioFor] if you want the chunk to be remade.
     */
    suspend fun fileFor(index: Int): File? = audio.fileFor(index)

    suspend fun discard(index: Int) = audio.discard(index)

    /** Throws away every chunk's audio and forgets the article. */
    suspend fun clear() {
        chunks = emptyList()
        speechRate = null
        audio.clear()
    }

    /** Makes chunk [index]. The one place a chunk is made after the opening, so neither caller can skip it. */
    private suspend fun make(index: Int): File {
        val (made, _) = chunkSynthesizer.make(index, chunks[index]) { audio.add(index, it) }
        reportLength(made)
        return made.file
    }

    /** Reports how long [made] lasts, when it could be measured. */
    private fun reportLength(made: ChunkAudio) {
        made.length?.let { onMeasured(made.index, it) }
    }
}
