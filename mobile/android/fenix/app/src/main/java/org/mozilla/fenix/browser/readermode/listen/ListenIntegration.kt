/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.readermode.listen

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature
import org.mozilla.fenix.databinding.FragmentBrowserBinding
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.utils.Settings

/**
 * Observes Reader Mode state and shows the configured [ListenVariant] entry point.
 * Each entry point observes [ListenController.state] so play/pause flips
 * across the toolbar pill, banner, and bottom sheet stay in sync.
 */
class ListenIntegration(
    private val context: Context,
    private val browserStore: BrowserStore,
    private val binding: FragmentBrowserBinding,
    private val settings: Settings,
    private val fragmentManager: FragmentManager,
    private val controller: ListenController,
    private val customTabSessionId: String?,
) : LifecycleAwareFeature {

    private var scope: CoroutineScope? = null
    private var inflatedToolbar: ComposeView? = null
    private var inflatedBanner: ComposeView? = null
    private var playingTabId: String? = null
    private var playingUrl: String? = null

    override fun start() {
        scope = browserStore.flowScoped(dispatcher = Dispatchers.Main) { flow ->
            flow.map { resolveTab(it) }
                .map { tab -> Triple(tab?.readerState?.active == true, tab?.id, tab?.content?.url) }
                .distinctUntilChanged()
                .collect { (readerActive, tabId, url) ->
                    refresh(readerActive, tabId, url)
                }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        hideAll()
    }

    private fun resolveTab(state: BrowserState): TabSessionState? =
        customTabSessionId?.let { state.findTab(it) } ?: state.selectedTab

    private fun refresh(readerActive: Boolean, tabId: String?, url: String?) {
        val playbackContextChanged = tabId != playingTabId || url != playingUrl
        if (playbackContextChanged && controller.state.value.isPlaying) {
            controller.stop()
            playingTabId = null
            playingUrl = null
        }
        if (!readerActive || tabId == null) {
            hideAll()
            return
        }
        when (ListenVariant.fromValue(settings.readerListenVariant)) {
            ListenVariant.OFF -> hideAll()
            ListenVariant.TOOLBAR -> {
                hideBanner()
                showToolbar()
            }
            ListenVariant.BANNER -> {
                hideToolbar()
                showBanner()
            }
        }
    }

    private fun showToolbar() {
        val view = inflatedToolbar ?: (binding.listenToolbarStub.inflate() as ComposeView).also {
            it.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            it.setContent {
                val state by controller.state.collectAsState()
                FirefoxTheme {
                    ListenToolbarPill(
                        isPlaying = state.isPlaying,
                        onTap = ::onEntryPointTapped,
                    )
                }
            }
            inflatedToolbar = it
        }
        view.isVisible = true
    }

    private fun hideToolbar() {
        inflatedToolbar?.isVisible = false
    }

    private fun showBanner() {
        val view = inflatedBanner ?: (binding.listenBannerStub.inflate() as ComposeView).also {
            it.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            it.setContent {
                val state by controller.state.collectAsState()
                FirefoxTheme {
                    ListenBanner(
                        title = currentTab()?.content?.title.orEmpty(),
                        isPlaying = state.isPlaying,
                        onTap = ::onEntryPointTapped,
                    )
                }
            }
            inflatedBanner = it
        }
        view.isVisible = true
    }

    private fun hideBanner() {
        inflatedBanner?.isVisible = false
    }

    private fun hideAll() {
        hideToolbar()
        hideBanner()
    }

    private fun currentTab(): TabSessionState? = resolveTab(browserStore.state)

    /**
     * Tap on the toolbar pill or banner: toggle play/pause, and open the full
     * controls sheet on first tap (no-op if it's already showing).
     */
    private fun onEntryPointTapped() {
        val tab = currentTab() ?: return
        playingTabId = tab.id
        playingUrl = tab.content.url
        controller.togglePlayPause(spokenText(tab))
        if (fragmentManager.findFragmentByTag(ListenSheetFragment.TAG) == null) {
            ListenSheetFragment().show(fragmentManager, ListenSheetFragment.TAG)
        }
    }

    private fun spokenText(tab: TabSessionState): String {
        val title = tab.content.title
        val key = tab.content.url.ifEmpty { title }
        val articleResId = ListenController.demoArticleFor(key)
        val article = context.resources.openRawResource(articleResId).bufferedReader().use { it.readText() }
        return if (title.isNotBlank()) "$title. $article" else article
    }
}
