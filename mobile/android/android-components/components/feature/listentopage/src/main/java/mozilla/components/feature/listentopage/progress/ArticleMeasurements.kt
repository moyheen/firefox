/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.progress

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * What the article's length is estimated from, filled in as synthesis finds it out. The only place that estimates the
 * length of a chunk that has not been measured.
 *
 * @property chunkCharacters How many characters each chunk holds, in reading order, or empty until the article is
 *   split.
 * @property measuredLengths How long each chunk that has been made lasts, by chunk.
 * @property secondsPerCharacter How long one character took to read in the opening, or `null` when it could not be
 *   measured.
 */
internal data class ArticleMeasurements(
    val chunkCharacters: List<Int> = emptyList(),
    val measuredLengths: Map<Int, Duration> = emptyMap(),
    val secondsPerCharacter: Double? = null,
) {
    /** How many chunks the article came to, or `0` before it has been split. */
    val chunkCount: Int = chunkCharacters.size

    /** Whether chunk at [index] is the last one of the article. */
    fun isLastChunk(index: Int): Boolean = index == chunkCount - 1

    /** These measurements with chunk [index] measured at [length]. */
    fun measured(index: Int, length: Duration) = copy(measuredLengths = measuredLengths + (index to length))

    // Where each chunk starts in the article's text, in characters, with the end of the text last.
    private val chunkBoundaries = chunkCharacters.runningFold(0, Int::plus)

    private val totalCharacters: Int = chunkBoundaries.last()

    // Computed once per instance and replaced on a split or chunk measurement.
    private val estimatedLengthsMs: List<Long?> by lazy {
        (0 until chunkCount).map { index ->
            calculateChunkReadingSpeedFromNearbyChunks(index)?.let {
                (chunkCharacters[index] * it * MS_PER_SECOND).toLong()
            }
        }
    }

    /**
     * How long chunk [index] lasts: as it was frozen in [frozenLengths], or as it was measured, or as it is estimated,
     * or zero when there is nothing to estimate it from.
     *
     * A chunk that has been made but not measured is sized exactly like one that does not exist yet: having audio does
     * not mean having a length.
     */
    fun lengthOf(index: Int, frozenLengths: Map<Int, Duration> = emptyMap()): Duration =
        frozenLengths[index]
            ?: measuredLengths[index]
            ?: estimatedLengthsMs.getOrNull(index)?.milliseconds
            ?: Duration.ZERO

    /**
     * How long each chunk lasts, with the chunks in [frozenLengths] kept at the length they were frozen at. They are
     * mostly estimates, and letting them change would move every position after them. Empty if nothing has been sized.
     */
    fun timeline(frozenLengths: Map<Int, Duration> = emptyMap()): ChunkTimeline {
        lastTimeline
            ?.takeIf { it.first == frozenLengths }
            ?.let {
                return it.second
            }

        val lengths = (0 until chunkCount).map { lengthOf(it, frozenLengths) }
        val timeline = if (lengths.sumOf { it.inWholeMilliseconds } > 0) ChunkTimeline(lengths) else ChunkTimeline()
        lastTimeline = frozenLengths to timeline
        return timeline
    }

    // The timeline last built, and the frozen lengths it was built with, since the narration asks for it again on every
    // player report. Not part of the measurements, so it is outside the constructor and left out of equality.
    private var lastTimeline: Pair<Map<Int, Duration>, ChunkTimeline>? = null

    /** How far through the article's text [position] is, between 0 and 1, or `null` before the article is split. */
    fun textFractionAt(position: ArticleTime, timeline: ChunkTimeline): Double? {
        val chunkPosition = timeline.toChunkPosition(position)
        if (totalCharacters <= 0 || chunkPosition == null || chunkPosition.chunkIndex >= chunkCount) return null

        val chunkMs = timeline.lengthOf(chunkPosition.chunkIndex).inWholeMilliseconds
        val fractionOfChunkRead = if (chunkMs > 0) chunkPosition.offset.ms.toDouble() / chunkMs else 0.0
        val characters =
            chunkBoundaries[chunkPosition.chunkIndex] + fractionOfChunkRead * chunkCharacters[chunkPosition.chunkIndex]
        return (characters / totalCharacters).coerceIn(0.0, 1.0)
    }

    /**
     * The index of the chunk that holds [textFraction], between 0 and 1 of the article's characters, or `null` before
     * the article is split.
     */
    fun chunkAtText(textFraction: Double): Int? {
        if (totalCharacters <= 0) return null

        val targetCharacters = textFraction.coerceIn(0.0, 1.0) * totalCharacters
        return chunkBoundaries.indexOfLast { it <= targetCharacters }.coerceAtMost(chunkCount - 1)
    }

    /**
     * Where [textFraction], between 0 and 1 of the article's characters, falls in [timeline]: the chunk that holds it,
     * and the same fraction of that chunk's length as of its characters.
     */
    fun positionAtText(textFraction: Double, timeline: ChunkTimeline): ChunkPosition? {
        val chunkIndex = chunkAtText(textFraction) ?: return null
        if (timeline.chunkLengths.size != chunkCount) return null

        val charactersIntoChunk = textFraction.coerceIn(0.0, 1.0) * totalCharacters - chunkBoundaries[chunkIndex]
        val characters = chunkCharacters[chunkIndex]
        val fractionOfChunkRead = if (characters > 0) (charactersIntoChunk / characters).coerceIn(0.0, 1.0) else 0.0
        val offsetMs = (fractionOfChunkRead * timeline.lengthOf(chunkIndex).inWholeMilliseconds).roundToLong()
        return ChunkPosition(chunkIndex, ChunkOffset(offsetMs))
    }

    /**
     * The progress at [textFraction], between 0 and 1 of the article's characters, by the lengths these measurements
     * give its chunks, along with those lengths. It is placed in the chunk that holds that text, so that it is where
     * playback restarts.
     */
    fun progressAtText(textFraction: Double): Pair<ArticleProgress, ChunkTimeline> {
        val timeline = timeline()
        val durationMs = timeline.duration.inWholeMilliseconds
        val positionMs =
            positionAtText(textFraction, timeline)?.let { timeline.toArticleTime(it).ms }
                ?: (durationMs * textFraction).roundToLong()
        val progress =
            ArticleProgress(
                positionMs = positionMs,
                durationMs = durationMs,
                barPosition = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
            )
        return progress to timeline
    }

    /**
     * How long one character takes to read near chunk [index], from the [NEAREST_MEASURED_CHUNKS] measured chunks on
     * each side, weighted by size and closeness. Parts of a page can be read at different speeds (such as the body, a
     * list or the references) and neighbouring chunks are usually the same part. Falls back to the opening's speed, or
     * `null`, when nothing is measured.
     */
    private fun calculateChunkReadingSpeedFromNearbyChunks(index: Int): Double? {
        var weighted = 0.0
        var weights = 0.0
        // Looks both ways from the chunk, so a chunk past the measured ones still takes the speed of the nearest.
        for (side in listOf(index - 1 downTo 0, index + 1 until chunkCount)) {
            side
                .asSequence()
                .mapNotNull { other -> measuredLengths[other]?.let { other to it } }
                .filter { (other, _) -> chunkCharacters[other] > 0 }
                .take(NEAREST_MEASURED_CHUNKS)
                .forEach { (other, measured) ->
                    // A bigger chunk is a more reliable measurement, and a nearer one more likely the same part of the
                    // page, so each counts by its size divided by how far away it is.
                    val size = chunkCharacters[other]
                    val weight = size.toDouble() / abs(index - other)
                    weighted += weight * measured.toDouble(DurationUnit.SECONDS) / size
                    weights += weight
                }
        }

        // The weighted average of the seconds per character, or the opening's speed while nothing is measured.
        return if (weights > 0) weighted / weights else secondsPerCharacter
    }
}

/** How many measured chunks on each side an unmeasured chunk's speed is estimated from. */
private const val NEAREST_MEASURED_CHUNKS = 2

private const val MS_PER_SECOND = 1_000
