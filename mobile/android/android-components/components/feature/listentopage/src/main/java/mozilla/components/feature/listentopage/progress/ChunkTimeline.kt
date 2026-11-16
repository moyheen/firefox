/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.progress

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long each chunk of the article lasts, in reading order. The only place that turns a time into a chunk into a time
 * into the article, and back.
 *
 * @property chunkLengths How long each chunk lasts. Empty while nothing has been sized.
 */
internal data class ChunkTimeline(val chunkLengths: List<Duration> = emptyList()) {

    private val lengthsMs: List<Long> = chunkLengths.map { it.inWholeMilliseconds }

    // Where each chunk starts, with the end of the article last.
    private val startsMs: List<Long> = lengthsMs.runningFold(0L, Long::plus)

    /** How long the whole article lasts. */
    val duration: Duration = startsMs.last().milliseconds

    /** Whether nothing has been sized, in which case no time can be placed in a chunk. */
    val isEmpty: Boolean
        get() = lengthsMs.isEmpty()

    /** How long chunk [chunk] lasts, or zero when the article has no such chunk. */
    fun lengthOf(chunk: Int): Duration = chunkLengths.getOrNull(chunk) ?: Duration.ZERO

    /** Where chunk [chunk] starts in the article. */
    fun startOf(chunk: Int): ArticleTime = ArticleTime(startsMs[chunk.coerceIn(0, lengthsMs.size)])

    /** Where [position] falls in the article. */
    fun toArticleTime(position: ChunkPosition): ArticleTime = startOf(position.chunkIndex) + position.offset

    /**
     * The chunk that holds [time], and how far into it [time] is, or `null` when nothing has been sized. A time past
     * the end is placed at the end of the last chunk.
     */
    fun toChunkPosition(time: ArticleTime): ChunkPosition? {
        if (lengthsMs.isEmpty()) return null

        val target = time.ms.coerceAtLeast(0)
        // The first chunk that ends after the target, or the last chunk when none does.
        var index = 0
        var last = lengthsMs.lastIndex
        while (index < last) {
            val middle = (index + last) ushr 1
            if (startsMs[middle + 1] > target) last = middle else index = middle + 1
        }
        return ChunkPosition(index, ChunkOffset((target - startsMs[index]).coerceIn(0, lengthsMs[index])))
    }
}

/** How far before the end of a chunk a seek into it lands at the latest. */
internal const val CHUNK_END_MARGIN_MS = 500L

/**
 * [offsetMs] into a chunk estimated at [estimatedMs], now that it has been measured at [measuredMs]: kept where it was
 * asked for, so that the article stays at the time it was moved to, unless that is past the end of a chunk that turned
 * out shorter.
 */
internal fun offsetInMeasuredChunk(offsetMs: Long, measuredMs: Long?, estimatedMs: Long? = measuredMs): Long {
    if (measuredMs == null) return offsetMs

    val lastMs = (measuredMs - CHUNK_END_MARGIN_MS).coerceAtLeast(0)
    if (offsetMs <= lastMs) return offsetMs

    // Scales the offset when the chunk turned out shorter than its estimate, otherwise only the end margin applies.
    val scaledOffsetMs = if (estimatedMs != null && estimatedMs > 0) offsetMs * measuredMs / estimatedMs else offsetMs
    return scaledOffsetMs.coerceAtMost(lastMs)
}
