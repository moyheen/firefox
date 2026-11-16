/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.playback

import kotlin.time.Duration

/**
 * What the playback is doing, as the player reports it.
 *
 * @property phase What the player is doing.
 * @property chunkIndex Which chunk of the article the player is on.
 * @property chunkLength How long that chunk was measured to last, or `null` while it has not been.
 * @property position How far into the article the playback has got.
 * @property speed How fast the article is being read out.
 */
data class PlaybackStatus(
    val phase: PlaybackPhase = PlaybackPhase.Idle,
    val chunkIndex: Int = 0,
    val chunkLength: Duration? = null,
    val position: Duration = Duration.ZERO,
    val speed: PlaybackSpeed = PlaybackSpeed.Default,
)

/** Something that happened to the playback that no command asked for. */
sealed interface PlaybackEvent {
    /**
     * The notification or the lock screen asked to move to [position] into the article.
     *
     * @property position Where in the article, against the length the notification was given.
     */
    data class SeekRequested(val position: Duration) : PlaybackEvent

    /**
     * The playback ended without being released, such as when the app was removed from the recent apps or the
     * notification was dismissed.
     */
    data object SessionEnded : PlaybackEvent
}
