/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.synthesis

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Thrown when the article holds nothing that can be read out loud. */
internal class NothingToReadException : Exception("The article has no words to read out")

/**
 * Splits an article into the chunks the engine reads: the opening first, and the rest sized once the opening has been
 * read aloud and timed.
 *
 * @param chunker Splits the text on sentence boundaries.
 * @param maxInputLength The engine's own limit, which caps every chunk.
 */
internal class ArticleChunker(private val chunker: TextChunker, private val maxInputLength: Int) {

    /**
     * Splits the opening off [text].
     *
     * @throws NothingToReadException if [text] holds no words.
     */
    fun splitOpening(text: String, languageTag: String): Pair<String, SplitArticle> {
        val split = chunker.splitFirstSentence(text, languageTag, maxInputLength)
        val opening = split.firstChunks.firstOrNull() ?: throw NothingToReadException()
        return opening to split
    }

    /**
     * The article after its opening, in chunks of [CHUNK_TARGET], growing up to it from what this device managed while
     * the opening played.
     */
    fun chunksAfterOpening(
        split: SplitArticle,
        languageTag: String,
        rate: SpeechRate?,
        openingLength: Duration?,
        synthesisMs: Long,
    ): List<String> {
        val rest =
            split.firstChunks.drop(1).flatMap { chunksOf(it, languageTag, rate) } +
                chunksOf(split.remainingText, languageTag, rate)

        val firstTarget = firstChunkTarget(openingLength, synthesisMs)
        if (rate == null || firstTarget == null || rest.isEmpty()) {
            return rest
        }

        return growingChunksOf(rest.first(), languageTag, rate, firstTarget) + rest.drop(1)
    }

    /** The chunks of [text] at [rate], within whatever the engine accepts in one request. */
    private fun chunksOf(text: String, languageTag: String, rate: SpeechRate?) =
        if (rate == null) {
            chunker.chunk(text, maxInputLength, languageTag)
        } else {
            chunker.chunkByDuration(text, languageTag, rate, maxInputLength)
        }

    /** [chunk] cut into pieces that grow from [firstTarget] towards [CHUNK_TARGET], doubling each time. */
    private fun growingChunksOf(
        chunk: String,
        languageTag: String,
        rate: SpeechRate,
        firstTarget: Duration,
    ): List<String> {
        val growing = mutableListOf<String>()
        var rest = chunk
        var target = firstTarget

        while (rest.isNotEmpty() && target < CHUNK_TARGET) {
            val piece = chunker.chunkByDuration(rest, languageTag, rate, maxInputLength, target).firstOrNull() ?: break
            val remainder = rest.removePrefix(piece).trimStart()

            // A piece holding all that is left, or one the chunker did not take off the front, stops the growing. What
            // is left then goes out whole rather than being cut again, so that no part of the article is dropped.
            if (remainder.isEmpty() || remainder.length == rest.length) break

            growing += piece
            rest = remainder
            target *= 2
        }

        return if (rest.isEmpty()) growing else growing + rest
    }

    /**
     * How long the first chunk after the opening should be, or `null` to let it have the full [CHUNK_TARGET].
     *
     * It is the audio this device produced while the opening played, so the chunk after the opening is one the device
     * has shown it can make in time. Every chunk after it grows from here.
     */
    private fun firstChunkTarget(playedFor: Duration?, synthesisTookMs: Long): Duration? {
        if (playedFor == null || synthesisTookMs <= 0) {
            return null
        }

        val producedPerMs = playedFor.inWholeMilliseconds.toDouble() / synthesisTookMs

        return (playedFor * producedPerMs).coerceAtLeast(MIN_FIRST_TARGET).takeIf { it < CHUNK_TARGET }
    }

    private companion object {
        // A floor under the first chunk, so that an engine which measured badly does not start on a chunk of a word or
        // two and spend more of the article growing than reading.
        val MIN_FIRST_TARGET = 5.seconds
    }
}
