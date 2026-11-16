/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import mozilla.components.feature.listentopage.content.ContentExtractor
import mozilla.components.feature.listentopage.narration.EngineVoiceCatalog
import mozilla.components.feature.listentopage.narration.Narrator
import mozilla.components.feature.listentopage.narration.SessionEngines
import mozilla.components.feature.listentopage.playback.ArticleDisplayData
import mozilla.components.feature.listentopage.playback.PlaybackController
import mozilla.components.feature.listentopage.settings.ListenSettings
import mozilla.components.feature.listentopage.synthesis.SpeechSynthesizer
import mozilla.components.feature.listentopage.synthesis.TextChunker
import mozilla.components.feature.listentopage.synthesis.audio.AudioFileCache
import mozilla.components.feature.listentopage.voices.VoiceSelector
import mozilla.components.lib.state.Middleware

/**
 * The feature that reads pages out loud: its [store], and everything that runs for it, until [close].
 *
 * @property store The store the player sheet and the embedding app use.
 */
class ListenFeature
internal constructor(
    val store: ListenStore,
    private val narrator: Narrator,
    private val lifetime: CoroutineScope,
) {
    /**
     * Ends the session running, waits for it to let go of the player, the engine and the audio, empties the store, and
     * then stops everything the feature runs. The store does nothing afterwards.
     */
    suspend fun close() {
        narrator.shutDown()
        store.dispatch(FeatureClosed)
        lifetime.cancel()
    }
}

/**
 * Builds the [ListenFeature] that reads pages out loud, and starts it.
 *
 * @param contentExtractor Provides the article text and language for a tab.
 * @param describeArticle What the playback notification and the lock screen say about the article in a tab.
 * @param synthesizerProvider Builds the speech engine. See [Narrator].
 * @param audioCache Holds the audio files. It is emptied when a session stops.
 * @param playbackController Plays the audio files.
 * @param settings The feature's own preferences: the voice picked for each article language, and whether the feature is
 *   turned on. A session stops when the feature is turned off.
 * @param scope The [CoroutineScope] the feature runs in until [ListenFeature.close]. It has to dispatch on one thread.
 * @param middlewareBefore Middleware that sees each action before the session acts on it.
 * @param middlewareAfter Middleware that sees each action after the session has acted on it.
 * @param ioDispatcher The dispatcher for the work that must not run on the thread the store dispatched on.
 * @param chunker Splits article text into the chunks.
 */
@Suppress("LongParameterList")
fun createListenFeature(
    contentExtractor: ContentExtractor,
    describeArticle: (tabId: String) -> ArticleDisplayData,
    synthesizerProvider: () -> SpeechSynthesizer,
    audioCache: AudioFileCache,
    playbackController: PlaybackController,
    settings: ListenSettings,
    scope: CoroutineScope,
    middlewareBefore: List<Middleware<ListenState, ListenAction>> = emptyList(),
    middlewareAfter: List<Middleware<ListenState, ListenAction>> = emptyList(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    chunker: TextChunker = TextChunker.android(),
): ListenFeature {
    val lifetime = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
    val engines = SessionEngines(synthesizerProvider, lifetime, ioDispatcher)
    val narrator = Narrator(engines, audioCache, playbackController, lifetime, ioDispatcher, chunker)
    val middleware =
        ListenMiddleware(
            narrator = narrator,
            articleLoader = ArticleLoader(contentExtractor, describeArticle),
            voices = VoiceSelector(EngineVoiceCatalog(engines), settings, lifetime, ioDispatcher),
        )
    val store =
        ListenStore(
            initialState = ListenState(),
            reducer = ::listenReducer,
            middleware = middlewareBefore + middleware + middlewareAfter,
        )
    NarrationBinding(store, narrator, lifetime).start()
    ListenEnabledBinding(store, settings, lifetime).start()
    return ListenFeature(store, narrator, lifetime)
}
