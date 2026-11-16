/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.listentopage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.action.ListenToPageAction
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.listentopage.ListenState
import mozilla.components.feature.listentopage.ListenStore
import mozilla.components.feature.listentopage.isPlayingOrBuffering
import mozilla.components.lib.state.helpers.AbstractBinding

/**
 * Binding between the [ListenStore] and the [BrowserStore]: it marks the tab being read out loud in the [BrowserStore].
 *
 * @param listenStore [ListenStore] used to listen for changes to [ListenState].
 * @param browserStore [BrowserStore] to update with the tab ID that is being read out loud.
 * @param mainDispatcher The [CoroutineDispatcher] on which the state observation and updates will occur. Defaults to
 *   [Dispatchers.Main].
 */
class ListenToPageBinding(
    listenStore: ListenStore,
    private val browserStore: BrowserStore,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AbstractBinding<ListenState>(listenStore, mainDispatcher) {
    override suspend fun onState(flow: Flow<ListenState>) {
        var previousTabId: String? = null

        flow
            .map {
                ListenTabStatus(
                    tabId = it.tabId,
                    showMediaIcon = it.playbackState.isPlayingOrBuffering && it.error == null,
                )
            }
            .distinctUntilChanged()
            .collect { (tabId, showMediaIcon) ->
                previousTabId
                    ?.takeIf { it != tabId }
                    ?.let {
                        browserStore.dispatch(ListenToPageAction.ListenToPageDeactivated(it))
                    }
                if (tabId != null) {
                    browserStore.dispatch(
                        if (showMediaIcon) {
                            ListenToPageAction.ListenToPageActivated(tabId)
                        } else {
                            ListenToPageAction.ListenToPageDeactivated(tabId)
                        }
                    )
                }
                previousTabId = tabId
            }
    }
}

/**
 * The page this tab shows: in reader mode the article the reader view renders, rather than the reader view's own URL.
 * It is the URL a session is asked to read.
 */
val TabSessionState.listenUrl: String
    get() = readerState.activeUrl ?: content.url

private data class ListenTabStatus(val tabId: String?, val showMediaIcon: Boolean)
