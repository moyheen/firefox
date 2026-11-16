/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import mozilla.components.feature.listentopage.playback.PlaybackStatus
import mozilla.components.feature.listentopage.progress.ArticleProgress

/**
 * The narration as the store sees it.
 *
 * @property progress How far through the article playback has got.
 */
internal data class NarrationState(val progress: ArticleProgress = ArticleProgress())

/** One-time events of the narration, which become the public actions in the order they happen. */
internal sealed interface NarrationEvent {
    /** Becomes `StateChangeObserved`. */
    data class Observed(val status: PlaybackStatus) : NarrationEvent

    /** Becomes `PlaybackStarted`. */
    data class Started(val status: PlaybackStatus) : NarrationEvent

    /** Becomes `PlaybackWaiting`. */
    data object Waiting : NarrationEvent

    /** Becomes `PlaybackEnded`. */
    data object Ended : NarrationEvent

    /** Becomes `PlaybackFailed`. */
    data object PlaybackFailed : NarrationEvent

    /** Becomes `SynthesisFailed`. */
    data object SynthesisFailed : NarrationEvent

    /** Becomes `ContentUnavailable`. */
    data object NothingToRead : NarrationEvent

    /** Becomes `NoOfflineVoicesAvailable`. */
    data object NoOfflineVoice : NarrationEvent

    /** Becomes `SeekLeftFinish`. */
    data object SeekLeftFinish : NarrationEvent

    /** Becomes `SeekRequested`, from the notification or the lock screen. */
    data class SeekFromNotification(val fraction: Float) : NarrationEvent

    /** Becomes `StopRequested`, because the media session ended outside the app. */
    data object EndedOutsideApp : NarrationEvent
}
