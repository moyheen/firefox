/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage

import kotlin.time.Duration.Companion.milliseconds
import mozilla.components.feature.listentopage.narration.NarrationCommand
import mozilla.components.feature.listentopage.narration.Narrator
import mozilla.components.feature.listentopage.playback.SEEK_BACK_INCREMENT_MS
import mozilla.components.feature.listentopage.playback.SEEK_FORWARD_INCREMENT_MS
import mozilla.components.feature.listentopage.voices.Voice
import mozilla.components.feature.listentopage.voices.VoiceLoad
import mozilla.components.feature.listentopage.voices.VoiceSelector
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/**
 * [Middleware] that turns what the user does into calls on the session, and what comes of those calls into actions.
 *
 * @param narrator Reads the article out.
 * @param articleLoader Extracts the article.
 * @param voices Picks the voice the article is read in.
 */
internal class ListenMiddleware(
    private val narrator: Narrator,
    private val articleLoader: ArticleLoader,
    private val voices: VoiceSelector,
) : Middleware<ListenState, ListenAction> {

    override fun invoke(
        store: Store<ListenState, ListenAction>,
        next: (ListenAction) -> Unit,
        action: ListenAction,
    ) {
        next(action)

        when (action) {
            is ListenAction.User -> handle(store, action)
            is ListenAction.System.Content.ContentReady -> loadVoicesAndRead(store, action.languageTag)
            is ListenAction.System -> Unit
        }
    }

    private fun handle(store: ListenStore, action: ListenAction.User) {
        when (action) {
            is ListenAction.User.Session.ListenRequested -> {
                voices.cancel()
                narrator.send(NarrationCommand.StartSession(action.tabId))
                loadArticle(store, action.tabId)
            }

            is ListenAction.User.Session.StopRequested -> {
                voices.cancel()
                narrator.send(NarrationCommand.EndSession)
            }

            is ListenAction.User.Voices.VoiceSelected -> changeVoice(store, action.voice)

            ListenAction.User.Voices.VoicesClicked -> refreshVoices(store)

            is ListenAction.User.Controls.SeekRequested -> narrator.send(NarrationCommand.Seek(action.fraction))
            ListenAction.User.Controls.PlayPauseClicked ->
                narrator.send(NarrationCommand.TogglePlayPause(store.state.playbackState.phase))
            ListenAction.User.Controls.RewindClicked ->
                narrator.send(NarrationCommand.Skip(-SEEK_BACK_INCREMENT_MS.milliseconds))
            ListenAction.User.Controls.ForwardClicked ->
                narrator.send(NarrationCommand.Skip(SEEK_FORWARD_INCREMENT_MS.milliseconds))
            is ListenAction.User.Controls.PlaybackSpeedSelected ->
                narrator.send(NarrationCommand.SetSpeed(action.playbackSpeed))

            ListenAction.User.Voices.VoicesDismissed,
            ListenAction.User.Controls.PlaybackSpeedClicked,
            ListenAction.User.Controls.PlaybackSpeedDismissed,
            is ListenAction.User.ModeChanged,
            ListenAction.User.ErrorDismissed -> Unit
        }
    }

    private fun loadArticle(store: ListenStore, tabId: String) {
        narrator.inSession {
            val article = articleLoader.load(tabId)

            // A session for another tab was started, or the session was stopped, while the article was extracted.
            if (store.state.tabId != tabId) return@inSession

            article
                .onSuccess {
                    narrator.send(NarrationCommand.Read(it))
                    store.dispatch(
                        ListenAction.System.Content.ContentReady(
                            languageTag = it.languageTag,
                            languageSource = it.languageSource,
                            title = it.displayData.title,
                            site = it.displayData.site,
                        )
                    )
                }
                .onFailure { store.dispatch(ListenAction.System.Content.ContentUnavailable) }
        }
    }

    /**
     * Loads the voices of the article language, and then reads the article out. A session reads as soon as it starts,
     * so the reader hears the article from a single tap.
     *
     * The load and the synthesis are one job rather than two because the engine reads with the voice it was last given:
     * starting the synthesis alongside the load reads the opening of the article in whichever voice the engine happens
     * to default to. A language with no offline voice is not read out at all.
     */
    private fun loadVoicesAndRead(store: ListenStore, languageTag: String) {
        narrator.send(NarrationCommand.WaitForOpening)
        voices.loadThen(
            languageTag = languageTag,
            onLoaded = { load ->
                if (load is VoiceLoad.Loaded) narrator.send(NarrationCommand.VoiceChosen(load.selected))
                store.dispatch(
                    when (load) {
                        is VoiceLoad.Loaded ->
                            ListenAction.System.Voices.AvailableVoicesLoaded(load.voices, load.selected)
                        VoiceLoad.NoOfflineVoice -> ListenAction.System.Voices.NoOfflineVoicesAvailable
                        VoiceLoad.Failed -> ListenAction.System.Synthesis.SynthesisFailed
                    }
                )
            },
            andThen = { narrator.send(NarrationCommand.Start) },
        )
    }

    /**
     * Refreshes the voices that are loaded for the player. Will select a fallback voice if the selected voice is no
     * longer available, or the user has not made a selection. Will pause playback and show an error if no voices are
     * available.
     */
    private fun refreshVoices(store: ListenStore) {
        val languageTag = store.state.languageTag ?: return
        // abandon a refresh before the page has loaded the first time
        if (store.state.voiceState.loadState != VoiceLoadState.Loaded) return

        voices.refresh(languageTag) { refreshed ->
            val selected = store.state.voiceState.selectedVoice
            when {
                refreshed.isEmpty() -> {
                    if (store.state.playbackState.isPlayingOrBuffering) narrator.send(NarrationCommand.Pause)
                    store.dispatch(ListenAction.System.Voices.NoOfflineVoicesAvailable)
                }

                selected != null && selected in refreshed ->
                    store.dispatch(ListenAction.System.Voices.AvailableVoicesLoaded(refreshed, selected))

                else -> {
                    val fallback = voices.savedVoiceFor(refreshed, languageTag)
                    store.dispatch(ListenAction.System.Voices.AvailableVoicesLoaded(refreshed, selected ?: fallback))
                    store.dispatch(ListenAction.System.Voices.FallbackVoiceChosen(fallback))
                    changeVoice(store, fallback)
                }
            }
        }
    }

    /** Reads on in [voice], and saves it as the voice of the article language. */
    private fun changeVoice(store: ListenStore, voice: Voice) {
        // Picking the voice already reading asks for nothing, so the audio is left alone.
        val changes = narrator.changesVoice(voice)
        voices.choose(voice, store.state.languageTag, replacesLoad = changes)
        narrator.send(NarrationCommand.ChangeVoice(voice))
    }
}
