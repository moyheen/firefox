/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.listentopage

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.base.profiler.Profiler
import mozilla.components.feature.listentopage.content.ContentExtractor
import mozilla.components.feature.listentopage.createListenFeature
import mozilla.components.feature.listentopage.playback.ArticleDisplayData
import mozilla.components.feature.listentopage.playback.ListenPlaybackController
import mozilla.components.feature.listentopage.settings.ListenSettings
import mozilla.components.feature.listentopage.synthesis.SpeechSynthesizer
import mozilla.components.feature.listentopage.synthesis.audio.DirectoryAudioFileCache
import mozilla.components.feature.media.service.AbstractMediaSessionService
import mozilla.components.support.base.facts.register
import mozilla.components.support.base.ids.SharedIdsHelper
import mozilla.components.support.ktx.kotlin.stripCommonSubdomains
import mozilla.components.support.locale.LocaleManager
import org.mozilla.fenix.listentopage.ListenToPageBinding
import org.mozilla.fenix.listentopage.listenUrl

private const val OPEN_TAB_PENDING_INTENT_TAG = "org.mozilla.fenix.listentopage.OPEN_TAB"

internal fun Context.openTabPendingIntent(tabId: String): PendingIntent? {
    val intent =
        packageManager.getLaunchIntentForPackage(packageName)?.apply {
            action = AbstractMediaSessionService.ACTION_SWITCH_TAB
            putExtra(AbstractMediaSessionService.EXTRA_TAB_ID, tabId)
        } ?: return null

    return PendingIntent.getActivity(
        this,
        SharedIdsHelper.getIdForTag(this, OPEN_TAB_PENDING_INTENT_TAG),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Provides access to the components that read a page out loud.
 *
 * @param browserStore Used to find the engine session of the tab an article is extracted from, to describe the article
 *   on the notification, and to stop the session when the tab closes or leaves the article.
 * @param context Used to reach the speech engine, the audio cache and the playback service.
 * @param scope The application scope. The [store] outlives any screen, because the audio keeps playing when the app is
 *   in the background, so its work cannot be tied to a fragment or a view model.
 * @param profiler Profiler to log performance markers for the feature.
 * @param isAvailable Whether remote configuration has made the feature available.
 */
class ListenToPage(
    private val browserStore: BrowserStore,
    private val context: Context,
    private val scope: CoroutineScope,
    private val profiler: Profiler?,
    private val isAvailable: Boolean,
) {

    val settings by lazy { ListenSettings.dataStore(context = context, available = isAvailable, scope = scope) }

    /** Allows the behavior of individual tabs to be overridden for testing purposes. */
    val debugController by lazy { ListenDebugController() }

    // The feature lives as long as the app, because the audio keeps playing when the app is in the background, so the
    // app never closes it.
    private val feature by lazy {
        val performanceMiddleware = profiler?.let { ListenPerformanceMiddleware(profiler = it) }?.also { it.register() }

        createListenFeature(
            contentExtractor = { tabId -> contentProvider.extractorFor(tabId).getContent(tabId) },
            describeArticle = { tabId -> browserStore.state.findTab(tabId).describeArticle() },
            // A provider, not an instance: building one binds the speech engine over IPC, and the session closes
            // it when it stops, so it needs a way to get another.
            synthesizerProvider = { SpeechSynthesizer.android(context = context, audioCache = audioCache) },
            audioCache = audioCache,
            playbackController =
                ListenPlaybackController(
                    context = context,
                    scope = scope,
                    sessionActivity = { tabId -> context.openTabPendingIntent(tabId) },
                ),
            settings = settings,
            scope = scope,
            middlewareBefore = listOfNotNull(performanceMiddleware),
            middlewareAfter =
                listOfNotNull(
                    tabWatcher,
                    ListenToPageTelemetryMiddleware().also { it.register() },
                    thermalStatusMonitor()?.let { ListenThermalMiddleware(monitor = it) },
                ),
        )
    }

    private val tabWatcher by lazy { ListenTabWatcherMiddleware(browserStore = browserStore, scope = scope) }

    val store by lazy {
        feature.store.also { ListenToPageBinding(listenStore = it, browserStore = browserStore).start() }
    }

    private fun thermalStatusMonitor(): ThermalStatusMonitor? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val powerManager = context.getSystemService<PowerManager>() ?: return null
        return PowerManagerThermalStatusMonitor(powerManager, ContextCompat.getMainExecutor(context))
    }

    // One cache for the whole feature: the synthesizer writes the audio into it and the session empties it when the
    // session stops. Two instances would each hold their own directory and neither would clean up after the other.
    private val audioCache by lazy { DirectoryAudioFileCache(context) }

    private val contentProvider by lazy {
        val pageExtractor = FenixListenPageExtractor { tabId ->
            browserStore.state.findTab(tabId)?.engineState?.engineSession
        }

        FenixListenContentExtractorProvider(
            extractor =
                ContentExtractor.fromPage(
                    pageContentExtractor = pageExtractor,
                    pageMetadataExtractor = pageExtractor,
                    fallbackLanguageTag = {
                        (LocaleManager.getCurrentLocale(context) ?: LocaleManager.getSystemDefault()).toLanguageTag()
                    },
                ),
            debugController = debugController,
        )
    }
}

/** What the playback notification and the lock screen say about the article in this tab. */
internal fun TabSessionState?.describeArticle(): ArticleDisplayData =
    ArticleDisplayData(
        title = this?.content?.title?.takeIf { it.isNotBlank() },
        site = this?.listenUrl?.toUri()?.host?.takeIf { it.isNotBlank() }?.stripCommonSubdomains(),
        isPrivate = this?.content?.private == true,
    )
