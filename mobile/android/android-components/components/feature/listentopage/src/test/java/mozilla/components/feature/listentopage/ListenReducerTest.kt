/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage

import java.util.Locale
import mozilla.components.feature.listentopage.narration.NarrationState
import mozilla.components.feature.listentopage.playback.PlaybackPhase
import mozilla.components.feature.listentopage.playback.PlaybackSpeed
import mozilla.components.feature.listentopage.progress.ArticleProgress
import mozilla.components.feature.listentopage.voices.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val TAB_ID = "tab-1"
private const val URL = "https://example.org/article"

/** A session with every field set, so a reset is visible in every one of them. */
private val fullState =
    ListenState(
        tabId = TAB_ID,
        url = URL,
        title = "Match Preview: Wrexham AFC vs Sunderland AFC",
        site = "bbc.co.uk",
        languageTag = "de-DE",
        mode = PlayerMode.Compact,
        error = ListenError.PlaybackFailed,
        voiceState =
            VoiceState(
                availableVoices =
                    listOf(Locale.GERMANY, Locale.forLanguageTag("de-AT"), Locale.forLanguageTag("de-CH")).map {
                        Voice(id = "de-voice-${it.country}", locale = it, engine = "com.example.tts")
                    }
            ),
        // Paused rather than playing, since an error means nothing is shown as playing.
        playbackState =
            PlaybackState(
                phase = PlaybackPhase.Paused,
                chunk = ChunkState(index = 3, durationMs = 30_000),
                positionMs = 12_000,
            ),
        articleProgress = ArticleProgress(positionMs = 102_000, durationMs = 600_000),
    )

class ListenReducerTest {

    @Test
    fun `test that a listen request starts a session on the given tab`() {
        val state = listenReducer(ListenState(), ListenAction.User.Session.ListenRequested(TAB_ID, URL))

        assertEquals(TAB_ID, state.tabId)
    }

    @Test
    fun `test that a listen request drops everything the previous session held`() {
        assertEquals(
            ListenState(tabId = "tab-2", url = URL),
            listenReducer(fullState, ListenAction.User.Session.ListenRequested("tab-2", URL)),
        )
    }

    @Test
    fun `test that a listen request records the article url`() {
        val state = listenReducer(ListenState(), ListenAction.User.Session.ListenRequested(TAB_ID, URL))

        assertEquals(URL, state.url)
    }

    @Test
    fun `test that stopping resets every field`() {
        assertEquals(
            ListenState(),
            listenReducer(fullState, ListenAction.User.Session.StopRequested()),
        )
    }

    @Test
    fun `test that dismissing an error clears it and leaves the session alone`() {
        val state = listenReducer(fullState, ListenAction.User.ErrorDismissed)

        assertNull(state.error)
        assertEquals(fullState.copy(error = null), state)
    }

    @Test
    fun `test that dismissing clears every kind of error`() {
        val errors =
            listOf(
                ListenError.NoOfflineVoice,
                ListenError.ContentUnavailable,
                ListenError.SynthesisFailed,
                ListenError.PlaybackFailed,
            )

        errors.forEach { error ->
            val state = listenReducer(fullState.copy(error = error), ListenAction.User.ErrorDismissed)

            assertNull("$error was not cleared", state.error)
        }
    }

    @Test
    fun `test that changing the mode records it`() {
        val collapsed = listenReducer(ListenState(), ListenAction.User.ModeChanged(PlayerMode.Compact))
        val expanded = listenReducer(collapsed, ListenAction.User.ModeChanged(PlayerMode.Expanded))

        assertEquals(ListenState(mode = PlayerMode.Compact), collapsed)
        assertEquals(ListenState(), expanded)
    }

    @Test
    fun `test that dismissing when there is no error changes nothing`() {
        val noError = fullState.copy(error = null)

        assertEquals(noError, listenReducer(noError, ListenAction.User.ErrorDismissed))
    }

    @Test
    fun `test that selecting a voice records it`() {
        val state =
            listenReducer(
                ListenState(),
                ListenAction.User.Voices.VoiceSelected(
                    Voice(id = "en-us-female", locale = Locale.US, engine = "com.example.tts")
                ),
            )

        assertEquals(
            Voice(id = "en-us-female", locale = Locale.US, engine = "com.example.tts"),
            state.voiceState.selectedVoice,
        )
    }

    @Test
    fun `test that a picked playback speed is recorded`() {
        val state = listenReducer(ListenState(), ListenAction.User.Controls.PlaybackSpeedSelected(PlaybackSpeed.X0_5))

        assertEquals(PlaybackSpeed.X0_5, state.playbackState.speed)
    }

    @Test
    fun `test that loaded voices are recorded`() {
        val voices =
            listOf(
                Voice(id = "en-us-female", locale = Locale.US, engine = "com.example.tts"),
                Voice(id = "en-us-male", locale = Locale.US, engine = "com.example.tts"),
            )

        val state =
            listenReducer(ListenState(), ListenAction.System.Voices.AvailableVoicesLoaded(voices, voices.first()))

        assertEquals(voices, state.voiceState.availableVoices)
    }

    @Test
    fun `test that loading voices again replaces the voices of the previous language`() {
        val german = listOf(Voice("de-de", Locale.GERMANY, engine = "com.example.tts"))
        val loaded =
            listenReducer(ListenState(), ListenAction.System.Voices.AvailableVoicesLoaded(german, german.first()))

        val english = listOf(Voice("en-us", Locale.US, engine = "com.example.tts"))
        val state = listenReducer(loaded, ListenAction.System.Voices.AvailableVoicesLoaded(english, english.first()))

        assertEquals(english, state.voiceState.availableVoices)
    }

    @Test
    fun `test that loading voices records the voice they were resolved for`() {
        val voices =
            listOf(
                Voice(id = "en-us-female", locale = Locale.US, engine = "com.example.tts"),
                Voice(id = "en-us-male", locale = Locale.US, engine = "com.example.tts"),
            )

        val state =
            listenReducer(
                fullState,
                ListenAction.System.Voices.AvailableVoicesLoaded(
                    voices,
                    selectedVoice = Voice(id = "en-us-male", locale = Locale.US, engine = "com.example.tts"),
                ),
            )

        assertEquals(voices, state.voiceState.availableVoices)
        assertEquals(
            Voice(id = "en-us-male", locale = Locale.US, engine = "com.example.tts"),
            state.voiceState.selectedVoice,
        )
    }

    @Test
    fun `test that having no offline voice is reported as an error`() {
        val state = listenReducer(ListenState(), ListenAction.System.Voices.NoOfflineVoicesAvailable)

        assertEquals(ListenError.NoOfflineVoice, state.error)
    }

    @Test
    fun `test that having no offline voice leaves the article alone`() {
        val state = listenReducer(fullState.copy(error = null), ListenAction.System.Voices.NoOfflineVoicesAvailable)

        assertEquals(
            fullState.copy(
                error = ListenError.NoOfflineVoice,
                voiceState = VoiceState(loadState = VoiceLoadState.Loaded),
            ),
            state,
        )
    }

    // An empty list only means the language has no offline voice, so the voices of the language before it cannot be
    // left behind for the popup to offer.
    @Test
    fun `test that having no offline voice empties the voices and marks them loaded`() {
        val state = listenReducer(fullState, ListenAction.System.Voices.NoOfflineVoicesAvailable)

        assertEquals(emptyList<Voice>(), state.voiceState.availableVoices)
        assertNull(state.voiceState.selectedVoice)
        assertEquals(VoiceLoadState.Loaded, state.voiceState.loadState)
    }

    @Test
    fun `test that loaded voices are marked as loaded`() {
        val voices = listOf(Voice(id = "en-us-female", locale = Locale.US, engine = "com.example.tts"))

        val state =
            listenReducer(ListenState(), ListenAction.System.Voices.AvailableVoicesLoaded(voices, voices.first()))

        assertEquals(VoiceLoadState.Loaded, state.voiceState.loadState)
    }

    @Test
    fun `test that a session has no voices by default`() {
        val initial = ListenState()

        assertEquals(emptyList<Voice>(), initial.voiceState.availableVoices)
        assertNull(initial.voiceState.selectedVoice)
        assertEquals(VoiceLoadState.NotLoaded, initial.voiceState.loadState)
    }

    @Test
    fun `test that a failed synthesis becomes an error the session can show`() {
        val state = listenReducer(ListenState(tabId = "tab-1"), ListenAction.System.Synthesis.SynthesisFailed)

        assertEquals(ListenError.SynthesisFailed, state.error)
        assertEquals("tab-1", state.tabId)
    }

    @Test
    fun `test that a failed synthesis can be dismissed`() {
        val failed = listenReducer(ListenState(tabId = "tab-1"), ListenAction.System.Synthesis.SynthesisFailed)

        val dismissed = listenReducer(failed, ListenAction.User.ErrorDismissed)

        assertNull(dismissed.error)
    }

    @Test
    fun `test that a session has no tab, no error and the expanded mode by default`() {
        val initial = ListenState()

        assertNull(initial.tabId)
        assertNull(initial.url)
        assertNull(initial.title)
        assertNull(initial.languageTag)
        assertNull(initial.error)
        assertEquals(PlayerMode.Expanded, initial.mode)
    }

    @Test
    fun `test that a ready article starts its voices over, even in the language of the previous one`() {
        val state =
            listenReducer(
                fullState,
                ListenAction.System.Content.ContentReady(
                    languageTag = "de-DE",
                    title = fullState.title,
                    site = fullState.site,
                ),
            )

        assertEquals(fullState.copy(voiceState = VoiceState()), state)
    }

    @Test
    fun `test that a fallback voice becomes the voice shown as selected`() {
        val fallback = fullState.voiceState.availableVoices.last()

        val state = listenReducer(fullState, ListenAction.System.Voices.FallbackVoiceChosen(fallback))

        assertEquals(fullState.copy(voiceState = fullState.voiceState.copy(selectedVoice = fallback)), state)
    }

    @Test
    fun `test that a ready article records its title and site`() {
        val state =
            listenReducer(
                ListenState(tabId = TAB_ID, url = URL),
                ListenAction.System.Content.ContentReady(
                    languageTag = "en-US",
                    title = "An article",
                    site = "example.org",
                ),
            )

        assertEquals("An article", state.title)
        assertEquals("example.org", state.site)
    }

    @Test
    fun `test that a ready article with no title records none`() {
        val state =
            listenReducer(
                fullState,
                ListenAction.System.Content.ContentReady(languageTag = "de-DE", site = "example.org"),
            )

        assertNull(state.title)
        assertEquals("example.org", state.site)
    }

    @Test
    fun `test that an article in another language drops the voices of the previous one`() {
        val state = listenReducer(fullState, ListenAction.System.Content.ContentReady(languageTag = "fr-FR"))

        assertEquals("fr-FR", state.languageTag)
        assertEquals(VoiceState(), state.voiceState)
    }

    @Test
    fun `test that a session is playing nothing by default`() {
        val initial = ListenState()

        assertEquals(PlaybackPhase.Idle, initial.playbackState.phase)
        assertEquals(0, initial.playbackState.chunk.index)
        assertEquals(0, initial.playbackState.positionMs)
        assertNull(initial.playbackState.chunk.durationMs)
    }

    // The bar has nothing to draw until the opening has been measured, which is a few seconds into the session.
    @Test
    fun `test that a session has got nowhere through its article by default`() {
        val initial = ListenState()

        assertEquals(ArticleProgress(), initial.articleProgress)
        assertEquals(0f, initial.articleProgress.fraction, 0f)
    }

    @Test
    fun `test that what the player reports is recorded`() {
        val reported = PlaybackState(phase = PlaybackPhase.Playing, chunk = ChunkState(index = 2), positionMs = 4_000)

        val state = listenReducer(ListenState(), ListenAction.System.Playback.StateChangeObserved(reported))

        assertEquals(reported, state.playbackState)
    }

    @Test
    fun `test that what the player reports leaves the rest of the session alone`() {
        val reported = PlaybackState(phase = PlaybackPhase.Paused, positionMs = 1_000)

        val state = listenReducer(fullState, ListenAction.System.Playback.StateChangeObserved(reported))

        assertEquals(fullState.copy(playbackState = reported), state)
    }

    @Test
    fun `test that how far through the article playback has got is recorded`() {
        val state = progressedTo(ListenState(), ArticleProgress(30_000, 600_000))

        assertEquals(ArticleProgress(positionMs = 30_000, durationMs = 600_000), state.articleProgress)
        assertEquals(0.05f, state.articleProgress.fraction, 0.0001f)
    }

    @Test
    fun `test that article progress leaves the rest of the session alone`() {
        val state = progressedTo(fullState, ArticleProgress(1_000, 2_000))

        assertEquals(fullState.copy(articleProgress = ArticleProgress(1_000, 2_000)), state)
    }

    @Test
    fun `test that an article of no length reads as no progress rather than as not a number`() {
        val state = progressedTo(fullState, ArticleProgress(0, 0))

        assertEquals(ArticleProgress(), state.articleProgress)
        assertEquals(0f, state.articleProgress.fraction, 0f)
    }

    @Test
    fun `test that a seek leaves the progress to the narration`() {
        val state = listenReducer(fullState, ListenAction.User.Controls.SeekRequested(0.25f))

        assertEquals(fullState, state)
    }

    @Test
    fun `test that the end of the article is recorded, and its progress left to the narration`() {
        val playing = fullState.copy(articleProgress = ArticleProgress(positionMs = 590_000, durationMs = 600_000))

        val state = listenReducer(playing, ListenAction.System.Playback.PlaybackEnded)

        assertEquals(PlaybackPhase.Ended, state.playbackState.phase)
        assertEquals(playing.articleProgress, state.articleProgress)
    }

    @Test
    fun `test that a failed player is reported as an error`() {
        val state = listenReducer(ListenState(), ListenAction.System.Playback.PlaybackFailed)

        assertEquals(ListenError.PlaybackFailed, state.error)
        assertEquals(PlaybackPhase.Failed, state.playbackState.phase)
    }

    @Test
    fun `test that the chunk the player has reached is recorded`() {
        val chunk = ChunkState(index = 4, durationMs = 60_000)

        val state =
            listenReducer(ListenState(), ListenAction.System.Playback.PlaybackStarted(chunk, positionMs = 1_000))

        assertEquals(chunk, state.playbackState.chunk)
        assertEquals(1_000, state.playbackState.positionMs)
        assertEquals(PlaybackPhase.Playing, state.playbackState.phase)
    }

    @Test
    fun `test that waiting on the next chunk is recorded as a wait rather than an end`() {
        val playing =
            fullState.copy(error = null, playbackState = fullState.playbackState.copy(phase = PlaybackPhase.Playing))

        val state = listenReducer(playing, ListenAction.System.Playback.PlaybackWaiting)

        assertEquals(PlaybackPhase.Buffering, state.playbackState.phase)
        assertEquals(playing.playbackState.chunk, state.playbackState.chunk)
    }

    @Test
    fun `test that a paused player stays paused while it waits`() {
        val paused = fullState.copy(playbackState = fullState.playbackState.copy(phase = PlaybackPhase.Paused))

        val state = listenReducer(paused, ListenAction.System.Playback.PlaybackWaiting)

        assertEquals(PlaybackPhase.Paused, state.playbackState.phase)
    }

    @Test
    fun `test that the article being read out is recorded as ended`() {
        val readOut = fullState.copy(error = null)

        val state = listenReducer(readOut, ListenAction.System.Playback.PlaybackEnded)

        assertEquals(PlaybackPhase.Ended, state.playbackState.phase)
        assertNull(state.error)
    }

    @Test
    fun `test that a failed player can be dismissed`() {
        val failed = listenReducer(ListenState(), ListenAction.System.Playback.PlaybackFailed)

        assertNull(listenReducer(failed, ListenAction.User.ErrorDismissed).error)
    }

    // An error the user has not seen yet outlives a report that has nothing to say about it, so that a phase change
    // arriving in between cannot take the dialog away.
    @Test
    fun `test that a player with nothing wrong leaves an existing error alone`() {
        val state =
            listenReducer(
                fullState.copy(error = ListenError.SynthesisFailed),
                ListenAction.System.Playback.StateChangeObserved(PlaybackState(phase = PlaybackPhase.Paused)),
            )

        assertEquals(ListenError.SynthesisFailed, state.error)
    }

    @Test
    fun `test that an error leaves the phase as the player reported it`() {
        val playing = ListenState(playbackState = PlaybackState(phase = PlaybackPhase.Playing))

        val failed = listenReducer(playing, ListenAction.System.Synthesis.SynthesisFailed)

        assertEquals(ListenError.SynthesisFailed, failed.error)
        assertEquals(PlaybackPhase.Playing, failed.playbackState.phase)
    }

    @Test
    fun `test that the narration moves the progress`() {
        val narration = NarrationState(progress = ArticleProgress(positionMs = 4_000, durationMs = 30_000))

        val state = listenReducer(ListenState(tabId = TAB_ID), NarrationChanged(narration))

        assertEquals(narration.progress, state.articleProgress)
    }

    @Test
    fun `test that a seek away from an article a seek finished leaves it paused while it waits`() {
        val finished =
            fullState.copy(error = null, playbackState = fullState.playbackState.copy(phase = PlaybackPhase.Ended))

        val seeking = listenReducer(finished, SeekLeftFinish)
        val waiting = listenReducer(seeking, ListenAction.System.Playback.PlaybackWaiting)

        assertEquals(PlaybackPhase.Paused, seeking.playbackState.phase)
        assertEquals(PlaybackPhase.Paused, waiting.playbackState.phase)
    }

    /** [state] once the narration's progress is [progress]. */
    private fun progressedTo(state: ListenState, progress: ArticleProgress) =
        listenReducer(state, NarrationChanged(NarrationState(progress)))
}
