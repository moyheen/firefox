/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.synthesis

/**
 * How many chunks either side of the one playing keep their audio.
 *
 * The chunks ahead are the lookahead of the synthesis queue as well: what the queue works on next and what the cache
 * keeps are the same window, so neither has to guess at the other's depth.
 */
internal const val AUDIO_WINDOW_RADIUS = 2

/**
 * The chunks whose audio is worth having on disk while [currentChunk] plays, being [currentChunk] itself and up to
 * [AUDIO_WINDOW_RADIUS] chunks either side of it.
 *
 * The window shrinks at the ends of the article rather than sliding along to keep its size. Otherwise, we would want to
 * delete already synthesized chunks as the index moved forwards.
 *
 * @param currentChunk Index of the chunk being played.
 * @param chunkCount How many chunks the article has.
 * @return The indices of the current window.
 */
internal fun audioWindow(currentChunk: Int, chunkCount: Int): IntRange {
    require(chunkCount >= 0) { "chunkCount cannot be negative, was $chunkCount" }
    if (chunkCount == 0) return IntRange.EMPTY

    require(currentChunk in 0 until chunkCount) {
        "currentChunk must be a chunk of the article, was $currentChunk of $chunkCount"
    }

    val first = maxOf(0, currentChunk - AUDIO_WINDOW_RADIUS)
    val last = minOf(chunkCount - 1, currentChunk + AUDIO_WINDOW_RADIUS)

    return first..last
}

/**
 * The chunks whose audio is kept while [playingChunkIndex] plays: its window, every chunk up to [lastQueuedChunkIndex],
 * which the player already holds, and the chunk a seek is moving into, [seekingToChunkIndex].
 */
internal fun keptChunks(
    playingChunkIndex: Int,
    chunkCount: Int,
    lastQueuedChunkIndex: Int?,
    seekingToChunkIndex: Int?,
): IntRange {
    val window = audioWindow(playingChunkIndex, chunkCount)
    val keepFrom = if (seekingToChunkIndex == null) window.first else minOf(window.first, seekingToChunkIndex)
    val keepTo = maxOf(window.last, lastQueuedChunkIndex ?: window.last, seekingToChunkIndex ?: window.last)
    return keepFrom..keepTo
}

/** The order the window around [playingChunkIndex] is made in: the chunks ahead first, then the ones passed. */
internal fun makeOrder(playingChunkIndex: Int, chunkCount: Int): List<Int> {
    val window = audioWindow(playingChunkIndex, chunkCount)
    return (playingChunkIndex..window.last) + (playingChunkIndex - 1 downTo window.first)
}
