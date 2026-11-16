/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.playback

/**
 * @property durationMs How long the article is shown to last, or `0` while nothing has been measured.
 * @property bufferingAtMs Where in the article a seek is waiting for its chunk to be made, or `null` when none is.
 */
internal data class ArticleTimeline(
    val durationMs: Long = 0,
    val bufferingAtMs: Long? = null,
)
