/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.progress

import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How far through the whole article playback has got, worked out rather than reported.
 *
 * @property positionMs How far into the article, across every chunk before the one playing.
 * @property durationMs How long the whole article lasts: measured where chunks have been made, estimated where they
 *   have not, so it moves as the article is synthesized.
 * @property barPosition Where the progress bar is drawn, or `null` to draw it at [positionMs] of [durationMs]. It can
 *   be ahead of that while the article has just been estimated longer, so that the bar does not go back.
 */
data class ArticleProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val barPosition: Float? = null,
) {
    /** How far along the progress bar is, between 0 and 1. */
    val fraction: Float
        get() = barPosition ?: if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * The length the bar is drawn against. Given to the notification, which draws the position divided by the length,
     * so its bar matches the sheet's.
     */
    val shownDuration: Duration
        get() {
            val drawn = fraction
            val shownMs =
                if (drawn > 0f && barPosition != null && positionMs > 0) {
                    (positionMs / drawn).roundToLong()
                } else {
                    durationMs
                }
            return shownMs.milliseconds
        }

    /**
     * The progress for a report at [report]: the position, the estimated length, and where the bar is drawn. The
     * position is [report], except that a small step back keeps the previous position, because the player corrects its
     * position slightly when audio starts.
     *
     * @param report How far into the article the player reports it has got.
     * @param timeline How long each chunk lasts.
     * @param isFinished Whether the last chunk of the article has finished, which is what lands the bar on exactly 1.0.
     * @param afterSeek Whether this is the first report after a seek or a restart, which moves the bar to where
     *   playback now is rather than smoothing towards it.
     */
    internal fun next(
        report: ArticleTime,
        timeline: ChunkTimeline,
        isFinished: Boolean,
        afterSeek: Boolean,
    ): ArticleProgress {
        val articleMs = timeline.duration.inWholeMilliseconds
        if (timeline.isEmpty || articleMs <= 0) return ArticleProgress()

        val positionMs = report.ms
        val shownMs =
            if (positionMs in (this.positionMs - MAX_POSITION_CORRECTION_MS) until this.positionMs) {
                this.positionMs
            } else {
                positionMs
            }

        if (isFinished) {
            val endMs = maxOf(articleMs, shownMs)
            return ArticleProgress(positionMs = endMs, durationMs = endMs, barPosition = 1f).correctLength()
        }

        // Longer than the position until the article has been read to the end, so that a bar with audio left never
        // reads as full.
        val durationMs = maxOf(articleMs, shownMs + 1)

        return ArticleProgress(
                positionMs = shownMs,
                durationMs = durationMs,
                barPosition = nextBarPosition(shownMs, durationMs, afterSeek),
            )
            .correctLength()
    }

    /** How far along the bar the notification's [positionMs] is, or `null` while there is no length to place it in. */
    internal fun fractionAt(positionMs: Long): Float? {
        val shownMs = shownDuration.inWholeMilliseconds
        return if (shownMs > 0) positionMs.toFloat() / shownMs else null
    }

    /** This progress moved to [fraction] of the article, which is where the reader let go of the bar. */
    internal fun seekedTo(fraction: Float): ArticleProgress {
        if (durationMs <= 0) return this

        val clamped = fraction.coerceIn(0f, 1f)
        return copy(positionMs = (durationMs * clamped).roundToLong(), barPosition = clamped)
    }

    /** This progress at the end of the article, with the bar full, however the article got there. */
    internal fun atEnd(): ArticleProgress =
        if (durationMs > 0) copy(positionMs = durationMs, barPosition = 1f) else this

    /** This progress with a length of at least zero, and a position inside it. */
    internal fun correctLength(): ArticleProgress {
        val lengthMs = durationMs.coerceAtLeast(0)
        return copy(positionMs = positionMs.coerceIn(0, lengthMs), durationMs = lengthMs)
    }

    /**
     * Where the bar is drawn (0 to 1) now that playback is at [positionMs] of [durationMs], moving smoothly from this
     * progress when the estimated length changes instead of jumping. A seek, or playback moving back or further than
     * normal playback goes between reports, moves the bar to where playback now is against the estimated length, so the
     * bar and the length are back in step after it.
     */
    private fun nextBarPosition(positionMs: Long, durationMs: Long, afterSeek: Boolean): Float {
        if (durationMs <= 0) return 0f

        val updatedPosition = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

        return when {
            this.durationMs <= 0 -> updatedPosition
            afterSeek || positionMs !in this.positionMs..(this.positionMs + MAX_NORMAL_PLAYBACK_STEP_MS) ->
                updatedPosition
            updatedPosition >= fraction -> speedUpBar(positionMs, durationMs, updatedPosition)
            else -> slowDownBar(positionMs, durationMs)
        }
    }

    /** Moves a bar that is behind [updatedPosition] faster than playback, so it catches up without passing it. */
    private fun speedUpBar(positionMs: Long, durationMs: Long, updatedPosition: Float) =
        (fraction + (positionMs - this.positionMs) * (1 + BAR_PLAYBACK_SPEED_CHANGE) / durationMs).coerceAtMost(
            updatedPosition
        )

    /**
     * Moves a bar that is ahead of playback slower than playback, so playback catches up, without filling it before the
     * end.
     */
    private fun slowDownBar(positionMs: Long, durationMs: Long): Float {
        val stepMs = positionMs - this.positionMs
        val remainingMs = (durationMs - this.positionMs).coerceAtLeast(1)
        val stepFraction = minOf((1 - BAR_PLAYBACK_SPEED_CHANGE) / durationMs, (1f - fraction) / remainingMs)
        return (fraction + stepMs * stepFraction).coerceAtMost(1f)
    }
}

/**
 * The furthest playback can move between two reports and still be normal playback: the fastest speed (2x) read for five
 * position samples of half a second, which allows for samples that come late while the main thread is busy. A bigger
 * step forward is a seek the narration did not ask for, such as one from another app's controller.
 */
private const val MAX_NORMAL_PLAYBACK_STEP_MS = 5_000L

/** The furthest back the player corrects its position when audio starts. A bigger step back is always a seek. */
private const val MAX_POSITION_CORRECTION_MS = 1_000L

/**
 * While the bar catches up with a changed estimate, it moves at 1 plus or minus this times the playback speed: faster
 * when it is behind, slower when it is ahead. A choice of how the bar looks: it catches up within seconds without
 * jumping or stopping.
 */
private const val BAR_PLAYBACK_SPEED_CHANGE = 0.5f
