/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mozilla.components.feature.listentopage.content.Content
import mozilla.components.feature.listentopage.fakes.FakeAudioFileCache
import mozilla.components.feature.listentopage.fakes.FakePlaybackController
import mozilla.components.feature.listentopage.fakes.FakeSpeechSynthesizer
import mozilla.components.feature.listentopage.playback.ArticleDisplayData
import mozilla.components.feature.listentopage.settings.ListenEnabledState
import mozilla.components.feature.listentopage.settings.ListenSettings
import mozilla.components.lib.state.Middleware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ListenFeatureTest {

    private val playback = FakePlaybackController()
    private val synthesizer = FakeSpeechSynthesizer()
    private val cache = FakeAudioFileCache()
    private val settings = ListenSettings.inMemory(enabled = ListenEnabledState.Enabled)
    private var extractions = 0

    @Test
    fun `test that closing ends the session and lets go of the player, the engine and the audio`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val feature = featureIn(scope)
        feature.store.dispatch(ListenAction.User.Session.ListenRequested("tab", "https://example.org"))
        advanceUntilIdle()

        feature.close()
        advanceUntilIdle()

        assertTrue("the playback was kept", playback.released)
        assertTrue("the engine was kept", synthesizer.closed)
        assertTrue("the audio was kept", cache.cleared)
        assertEquals(ListenState(), feature.store.state)
        scope.cancel()
    }

    @Test
    fun `test that a closed feature starts no more sessions`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val feature = featureIn(scope)
        feature.close()
        advanceUntilIdle()

        feature.store.dispatch(ListenAction.User.Session.ListenRequested("tab", "https://example.org"))
        advanceUntilIdle()

        assertEquals(0, extractions)
        scope.cancel()
    }

    @Test
    fun `test that turning the feature off stops the session`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val actions = mutableListOf<ListenAction>()
        val feature = featureIn(scope, recordInto = actions)
        feature.store.dispatch(ListenAction.User.Session.ListenRequested("tab", "https://example.org"))
        advanceUntilIdle()

        settings.setEnabled(false)
        advanceUntilIdle()

        assertEquals(
            ListenAction.User.Session.StopRequested(ListenAction.User.Session.StopSource.FeatureDisabled),
            actions.filterIsInstance<ListenAction.User.Session.StopRequested>().single(),
        )
        scope.cancel()
    }

    private fun TestScope.featureIn(scope: CoroutineScope, recordInto: MutableList<ListenAction>? = null) =
        createListenFeature(
            contentExtractor = {
                extractions += 1
                Result.success(Content(text = "Article text.", languageTag = "en-US"))
            },
            describeArticle = { ArticleDisplayData() },
            synthesizerProvider = { synthesizer },
            audioCache = cache,
            playbackController = playback,
            settings = settings,
            scope = scope,
            middlewareBefore = listOfNotNull(recordInto?.let(::recording)),
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun recording(into: MutableList<ListenAction>): Middleware<ListenState, ListenAction> = { _, next, action ->
        into.add(action)
        next(action)
    }
}
