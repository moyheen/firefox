/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.listentopage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.listentopage.ListenAction
import mozilla.components.feature.listentopage.ListenAction.User.Session.StopSource
import mozilla.components.feature.listentopage.ListenState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.listentopage.listenUrl

/**
 * [Middleware] that stops each session once the tab it reads closes, moves on to another page, or leaves reader mode.
 *
 * The tab is taken as it is when the session is asked for, before anything else can change it, so that a page the tab
 * moves on to straight away is not taken for the one being read.
 *
 * @param browserStore Where the tab is watched.
 * @param scope Where the tab is watched from, until [stop].
 */
class ListenTabWatcherMiddleware(
    private val browserStore: BrowserStore,
    private val scope: CoroutineScope,
) : Middleware<ListenState, ListenAction> {

    private var watching: Job? = null

    override fun invoke(
        store: Store<ListenState, ListenAction>,
        next: (ListenAction) -> Unit,
        action: ListenAction,
    ) {
        when (action) {
            is ListenAction.User.Session.ListenRequested -> {
                val page = ListenedPage.from(browserStore.state, action.tabId, action.url)
                stop()
                watching = scope.launch { store.dispatch(ListenAction.User.Session.StopRequested(page.awaitLeft())) }
            }
            is ListenAction.User.Session.StopRequested -> stop()
            else -> Unit
        }
        next(action)
    }

    /** Stops watching the tab of the session running. */
    fun stop() {
        watching?.cancel()
        watching = null
    }

    private suspend fun ListenedPage.awaitLeft(): StopSource =
        browserStore.stateFlow.mapNotNull { state -> leftIn(state) }.first()

    /**
     * The page a session reads, as its tab showed it when the session was asked for.
     *
     * @property tabId The tab.
     * @property tabUrl The URL the tab showed.
     * @property pageUrl The page the session reads, which in reader mode is the article rather than the reader view.
     * @property inReaderMode Whether the tab was in reader mode.
     */
    private data class ListenedPage(
        val tabId: String,
        val tabUrl: String?,
        val pageUrl: String,
        val inReaderMode: Boolean,
    ) {
        /** Why the session has to stop in [state], or `null` while the tab still shows this page. */
        fun leftIn(state: BrowserState): StopSource? {
            val tab = state.findTab(tabId)
            return when {
                tab == null -> StopSource.TabClosed
                tab.content.url != tabUrl && tab.listenUrl != pageUrl -> StopSource.TabClosed
                inReaderMode && !tab.readerState.active -> StopSource.ReaderModeClosed
                else -> null
            }
        }

        companion object {
            fun from(state: BrowserState, tabId: String, pageUrl: String): ListenedPage {
                val tab = state.findTab(tabId)
                return ListenedPage(
                    tabId = tabId,
                    tabUrl = tab?.content?.url,
                    pageUrl = pageUrl,
                    inReaderMode = tab?.readerState?.active == true,
                )
            }
        }
    }
}
