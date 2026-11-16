/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage

import mozilla.components.feature.listentopage.playback.PlaybackPhase

/**
 * Reduces the given [action] and current [state] into a new [ListenState].
 *
 * What the session reports changes the state. What someone did changes only the state the player has to show at once.
 * The article progress is the narration's: only [NarrationChanged] changes it, so that the store and the narration
 * cannot disagree about it.
 *
 * @param state The current [ListenState].
 * @param action The [ListenAction] to process.
 * @return The resulting [ListenState] after applying the action.
 */
fun listenReducer(state: ListenState, action: ListenAction): ListenState =
    when (action) {
        is ListenAction.User -> reduceUser(state, action)
        is ListenAction.System -> reduceSystem(state, action)
    }

private fun reduceUser(state: ListenState, action: ListenAction.User): ListenState =
    when (action) {
        is ListenAction.User.Session.ListenRequested -> ListenState(tabId = action.tabId, url = action.url)

        is ListenAction.User.Session.StopRequested -> ListenState()

        is ListenAction.User.Voices.VoiceSelected ->
            state.copy(voiceState = state.voiceState.copy(selectedVoice = action.voice))

        is ListenAction.User.Controls.PlaybackSpeedSelected ->
            state.copy(playbackState = state.playbackState.copy(speed = action.playbackSpeed))

        is ListenAction.User.ModeChanged -> state.copy(mode = action.mode)

        ListenAction.User.ErrorDismissed -> state.copy(error = null)

        is ListenAction.User.Controls.SeekRequested,
        ListenAction.User.Voices.VoicesClicked,
        ListenAction.User.Voices.VoicesDismissed,
        ListenAction.User.Controls.PlayPauseClicked,
        ListenAction.User.Controls.RewindClicked,
        ListenAction.User.Controls.ForwardClicked,
        ListenAction.User.Controls.PlaybackSpeedClicked,
        ListenAction.User.Controls.PlaybackSpeedDismissed -> state
    }

private fun reduceSystem(state: ListenState, action: ListenAction.System): ListenState =
    when (action) {
        is ListenAction.System.Content -> reduceContent(state, action)
        is ListenAction.System.Voices -> reduceVoices(state, action)
        is ListenAction.System.Playback -> reducePlayback(state, action)
        ListenAction.System.Synthesis.SynthesisFailed -> state.copy(error = ListenError.SynthesisFailed)
        FeatureClosed -> ListenState()
        is NarrationChanged -> state.copy(articleProgress = action.narration.progress)
        SeekLeftFinish ->
            if (state.playbackState.phase == PlaybackPhase.Ended) {
                state.copy(playbackState = state.playbackState.copy(phase = PlaybackPhase.Paused))
            } else {
                state
            }
    }

private fun reduceContent(state: ListenState, action: ListenAction.System.Content): ListenState =
    when (action) {
        is ListenAction.System.Content.ContentReady ->
            state.copy(
                title = action.title,
                site = action.site,
                languageTag = action.languageTag,
                voiceState = VoiceState(),
            )

        ListenAction.System.Content.ContentUnavailable -> state.copy(error = ListenError.ContentUnavailable)
    }

private fun reduceVoices(state: ListenState, action: ListenAction.System.Voices): ListenState =
    when (action) {
        is ListenAction.System.Voices.AvailableVoicesLoaded ->
            state.copy(
                voiceState =
                    state.voiceState.copy(
                        availableVoices = action.voices,
                        selectedVoice = action.selectedVoice,
                        loadState = VoiceLoadState.Loaded,
                    )
            )

        // The empty list is the answer, not the absence of one: the engine has been asked and has nothing offline for
        // this language.
        ListenAction.System.Voices.NoOfflineVoicesAvailable ->
            state.copy(
                voiceState = VoiceState(loadState = VoiceLoadState.Loaded),
                error = ListenError.NoOfflineVoice,
            )

        is ListenAction.System.Voices.FallbackVoiceChosen ->
            state.copy(voiceState = state.voiceState.copy(selectedVoice = action.voice))
    }

private fun reducePlayback(state: ListenState, action: ListenAction.System.Playback): ListenState =
    when (action) {
        is ListenAction.System.Playback.StateChangeObserved -> state.copy(playbackState = action.playbackState)

        is ListenAction.System.Playback.PlaybackStarted ->
            state.copy(
                playbackState =
                    state.playbackState.copy(
                        phase = PlaybackPhase.Playing,
                        chunk = action.chunk,
                        positionMs = action.positionMs,
                    )
            )

        // A wait does not change a player that was paused.
        ListenAction.System.Playback.PlaybackWaiting ->
            if (state.playbackState.phase == PlaybackPhase.Paused) {
                state
            } else {
                state.copy(playbackState = state.playbackState.copy(phase = PlaybackPhase.Buffering))
            }

        ListenAction.System.Playback.PlaybackEnded ->
            state.copy(playbackState = state.playbackState.copy(phase = PlaybackPhase.Ended))

        ListenAction.System.Playback.PlaybackFailed ->
            state.copy(
                playbackState = state.playbackState.copy(phase = PlaybackPhase.Failed),
                error = ListenError.PlaybackFailed,
            )
    }
