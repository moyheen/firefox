/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mozilla.components.feature.listentopage.narration.NarrationEvent
import mozilla.components.feature.listentopage.narration.Narrator
import mozilla.components.feature.listentopage.playback.PlaybackStatus

/**
 * The only place that turns the narration into actions: what it reports, and what the user did outside the app through
 * the player, such as a seek from the notification or the media session ending.
 *
 * It collects without dispatching to another thread, so that each action reaches the store as soon as the narration
 * gives it out, in the same order as the narration's own calls, as the session did when it dispatched them itself.
 *
 * @param store The store the actions go to.
 * @param narrator The narration to watch.
 * @param scope Where the binding runs, for as long as the store lives.
 */
internal class NarrationBinding(
    private val store: ListenStore,
    private val narrator: Narrator,
    private val scope: CoroutineScope,
) {
    /** Starts dispatching. */
    fun start(): Job =
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) { narrator.events.collect(::forward) }
            launch(start = CoroutineStart.UNDISPATCHED) {
                narrator.state.collect { store.dispatch(NarrationChanged(it)) }
            }
        }

    /** Dispatches what [event] becomes: a report, or something the user did outside the app. */
    private fun forward(event: NarrationEvent) {
        store.dispatch(event.toAction())
    }
}

private fun NarrationEvent.toAction(): ListenAction =
    when (this) {
        is NarrationEvent.Observed -> ListenAction.System.Playback.StateChangeObserved(status.toPlaybackState())
        is NarrationEvent.Started ->
            status.toPlaybackState().let { ListenAction.System.Playback.PlaybackStarted(it.chunk, it.positionMs) }
        NarrationEvent.Waiting -> ListenAction.System.Playback.PlaybackWaiting
        NarrationEvent.Ended -> ListenAction.System.Playback.PlaybackEnded
        NarrationEvent.PlaybackFailed -> ListenAction.System.Playback.PlaybackFailed
        NarrationEvent.SeekLeftFinish -> SeekLeftFinish
        NarrationEvent.SynthesisFailed -> ListenAction.System.Synthesis.SynthesisFailed
        NarrationEvent.NothingToRead -> ListenAction.System.Content.ContentUnavailable
        NarrationEvent.NoOfflineVoice -> ListenAction.System.Voices.NoOfflineVoicesAvailable
        is NarrationEvent.SeekFromNotification -> ListenAction.User.Controls.SeekRequested(fraction)
        NarrationEvent.EndedOutsideApp ->
            ListenAction.User.Session.StopRequested(ListenAction.User.Session.StopSource.MediaSessionEndedOutsideApp)
    }

/** What the store says about the playback the player reports as this. */
private fun PlaybackStatus.toPlaybackState() =
    PlaybackState(
        phase = phase,
        chunk = ChunkState(index = chunkIndex, durationMs = chunkLength?.inWholeMilliseconds),
        positionMs = position.inWholeMilliseconds,
        speed = speed,
    )
