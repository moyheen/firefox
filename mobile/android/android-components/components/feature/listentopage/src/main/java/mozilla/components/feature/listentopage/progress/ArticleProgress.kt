/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.progress

/**
 * How far through the whole article playback has got, worked out rather than reported.
 *
 * @property positionMs How far into the article, across every chunk before the one playing.
 * @property durationMs How long the whole article lasts: measured where chunks have been made, estimated where they
 *   have not, so it moves as the article is synthesized.
 * @property chunkDurationsMs How long each chunk lasts, in reading order. These lengths change as additional chunks are
 *   synthesized, so this should match what was last reported to the player.
 * @property barPosition Where the progress bar is drawn, or `null` to draw it at [positionMs] of [durationMs]. It can
 *   be ahead of that while the article has just been estimated longer, so that the bar does not go back.
 */
data class ArticleProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val chunkDurationsMs: List<Long> = emptyList(),
    val barPosition: Float? = null,
) {
    /** How far along the progress bar is, between 0 and 1. */
    val fraction: Float
        get() = barPosition ?: if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
