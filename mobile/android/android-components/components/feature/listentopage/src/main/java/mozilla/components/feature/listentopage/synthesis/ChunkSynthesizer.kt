/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.synthesis

import android.os.SystemClock
import java.io.File
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.feature.listentopage.facts.ListenToPageFacts
import mozilla.components.feature.listentopage.facts.emitChunkSynthesizedFact
import mozilla.components.feature.listentopage.synthesis.audio.readWavDurationOrNull

/**
 * One chunk of the article, made.
 *
 * @property index Which chunk of the article it is.
 * @property file Where its audio is.
 * @property length How long its audio lasts, or `null` when it could not be measured.
 */
internal data class ChunkAudio(val index: Int, val file: File, val length: Duration?)

/**
 * Makes one chunk at a time: synthesizes it, measures the audio at once, and reports it.
 *
 * @param synthesizer The engine.
 * @param workDispatcher The dispatcher for the work that must not run on the thread a caller dispatched on.
 * @param measureAudio How long the audio in a file lasts, or `null` when it cannot be read.
 * @param elapsedMs Reads a monotonic clock, so that how long a request took can be measured under a test's virtual time
 *   as well as a device's real one.
 */
internal class ChunkSynthesizer(
    private val synthesizer: SpeechSynthesizer,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val measureAudio: suspend (File) -> Duration? = { readWavDurationOrNull(it) },
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) {
    /**
     * Makes chunk [index], which holds [text], and measures what came back.
     *
     * @param keep Takes the file as soon as the engine has written it, before it is measured, so that the file is kept
     *   track of, and deleted later, even when the work is cancelled while it is measured.
     * @return The chunk, with how long the engine took to make it.
     * @throws SpeechSynthesisException if the engine cannot make it.
     */
    suspend fun make(index: Int, text: String, keep: suspend (File) -> Unit): Pair<ChunkAudio, Long> {
        val startedAt = elapsedMs()
        val file = synthesizer.synthesizeToFile(text)
        val synthesisMs = elapsedMs() - startedAt
        keep(file)
        val length = measure(file)
        emitChunkSynthesizedFact(
            ListenToPageFacts.ChunkSynthesis(
                index = index,
                length = text.length,
                synthesisMs = synthesisMs,
                audioMs = length?.inWholeMilliseconds,
                fileKilobytes = kilobytesOf(file),
            )
        )

        return ChunkAudio(index, file, length) to synthesisMs
    }

    /**
     * How long the audio in [file] lasts.
     *
     * A file the system reclaimed, and one an interrupted request left behind, both measure as no audio at all. That is
     * not a length, so it has to read the same as a file whose header could not be understood.
     */
    private suspend fun measure(file: File): Duration? =
        withContext(workDispatcher) { measureAudio(file) }?.takeIf { it > Duration.ZERO }

    /** How many kilobytes on disk are taken by [file], or `null` if the file is empty or gone. */
    private suspend fun kilobytesOf(file: File): Long? =
        withContext(workDispatcher) { file.length() }.takeIf { it > 0 }?.let { (it / BYTES_PER_KILOBYTE).roundToLong() }

    private companion object {
        const val BYTES_PER_KILOBYTE = 1024.0
    }
}
