/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.listentopage

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.ReaderAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ReaderState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.listentopage.ListenAction
import mozilla.components.feature.listentopage.ListenAction.User.Session.StopSource
import mozilla.components.feature.listentopage.ListenState
import mozilla.components.feature.listentopage.ListenStore
import mozilla.components.feature.listentopage.listenReducer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.listentopage.listenUrl

private const val TAB_ID = "tab-1"
private const val OTHER_TAB_ID = "tab-2"
private const val URL = "https://example.org/article"
private const val OTHER_URL = "https://example.org/other-article"
private const val READER_URL = "moz-extension://readerview/readerview.html?url=$URL&id=reader-1"

@RunWith(AndroidJUnit4::class)
class ListenTabWatcherMiddlewareTest {

    private val testDispatcher = StandardTestDispatcher()

    private val actions = mutableListOf<ListenAction>()

    private lateinit var watcher: ListenTabWatcherMiddleware

    private lateinit var listenStore: ListenStore

    @Test
    fun `WHEN the tab being listened to closes THEN the session stops`() {
        val browserStore = browserStoreWithOpenTabs()
        listen(browserStore, TAB_ID)

        browserStore.dispatch(TabListAction.RemoveTabAction(TAB_ID))
        idle()

        assertEquals(listOf(requested(TAB_ID), stopped(StopSource.TabClosed)), sessionActions())
    }

    @Test
    fun `WHEN another tab closes THEN the session goes on`() {
        val browserStore = browserStoreWithOpenTabs()
        listen(browserStore, TAB_ID)

        browserStore.dispatch(TabListAction.RemoveTabAction(OTHER_TAB_ID))
        idle()

        assertEquals(listOf(requested(TAB_ID)), sessionActions())
    }

    @Test
    fun `WHEN the tab being listened to leaves reader mode THEN the session stops`() {
        val browserStore = browserStoreWithOpenTabs()
        listen(browserStore, TAB_ID)

        browserStore.dispatch(ReaderAction.UpdateReaderActiveAction(TAB_ID, false))
        idle()

        assertEquals(listOf(requested(TAB_ID), stopped(StopSource.ReaderModeClosed)), sessionActions())
    }

    @Test
    fun `WHEN another tab leaves reader mode THEN the session goes on`() {
        val browserStore = browserStoreWithOpenTabs()
        listen(browserStore, TAB_ID)

        browserStore.dispatch(ReaderAction.UpdateReaderActiveAction(OTHER_TAB_ID, false))
        idle()

        assertEquals(listOf(requested(TAB_ID)), sessionActions())
    }

    @Test
    fun `WHEN the tab being listened to moves on to another page THEN the session stops`() {
        val browserStore = browserStoreWithOpenTabs()
        listen(browserStore, TAB_ID)

        browserStore.dispatch(ContentAction.UpdateUrlAction(TAB_ID, OTHER_URL))
        idle()

        assertEquals(listOf(requested(TAB_ID), stopped(StopSource.TabClosed)), sessionActions())
    }

    @Test
    fun `WHEN the tab moves on to another page before the watch has started THEN the session still stops`() {
        val browserStore = browserStoreWithOpenTabs()
        start(browserStore)

        listenStore.dispatch(requested(TAB_ID))
        browserStore.dispatch(ContentAction.UpdateUrlAction(TAB_ID, OTHER_URL))
        idle()

        assertEquals(listOf(requested(TAB_ID), stopped(StopSource.TabClosed)), sessionActions())
    }

    @Test
    fun `WHEN the tab goes from the reader view back to the article THEN the session goes on`() {
        val browserStore =
            BrowserStore(
                BrowserState(
                    tabs =
                        listOf(
                            createTab(
                                url = READER_URL,
                                id = TAB_ID,
                                readerState = ReaderState(active = true, activeUrl = URL),
                            )
                        ),
                    selectedTabId = TAB_ID,
                )
            )
        listen(browserStore, TAB_ID)

        browserStore.dispatch(ReaderAction.ClearReaderActiveUrlAction(TAB_ID))
        browserStore.dispatch(ContentAction.UpdateUrlAction(TAB_ID, URL))
        idle()

        assertEquals(listOf(requested(TAB_ID)), sessionActions())
    }

    @Test
    fun `WHEN another tab moves on to another page THEN the session goes on`() {
        val browserStore = browserStoreWithOpenTabs()
        listen(browserStore, TAB_ID)

        browserStore.dispatch(ContentAction.UpdateUrlAction(OTHER_TAB_ID, OTHER_URL))
        idle()

        assertEquals(listOf(requested(TAB_ID)), sessionActions())
    }

    @Test
    fun `WHEN the tab an earlier session listened to closes THEN the current session goes on`() {
        val browserStore = browserStoreWithOpenTabs()
        listen(browserStore, TAB_ID)
        listenStore.dispatch(requested(OTHER_TAB_ID))
        idle()

        browserStore.dispatch(TabListAction.RemoveTabAction(TAB_ID))
        idle()

        assertEquals(listOf(requested(TAB_ID), requested(OTHER_TAB_ID)), sessionActions())
    }

    @Test
    fun `WHEN the session is stopped THEN closing its tab stops nothing more`() {
        val browserStore = browserStoreWithOpenTabs()
        listen(browserStore, TAB_ID)

        listenStore.dispatch(stopped(StopSource.CloseButton))
        browserStore.dispatch(TabListAction.RemoveTabAction(TAB_ID))
        idle()

        assertEquals(listOf(requested(TAB_ID), stopped(StopSource.CloseButton)), sessionActions())
    }

    @Test
    fun `WHEN the watcher is stopped THEN closing the tab no longer stops the session`() {
        val browserStore = browserStoreWithOpenTabs()
        listen(browserStore, TAB_ID)

        watcher.stop()
        browserStore.dispatch(TabListAction.RemoveTabAction(TAB_ID))
        idle()

        assertEquals(listOf(requested(TAB_ID)), sessionActions())
    }

    private fun start(browserStore: BrowserStore) {
        watcher = ListenTabWatcherMiddleware(browserStore, TestScope(testDispatcher))
        listenStore =
            ListenStore(
                initialState = ListenState(),
                reducer = ::listenReducer,
                middleware =
                    listOf(
                        { _, next, action ->
                            actions.add(action)
                            next(action)
                        },
                        watcher,
                    ),
            )
    }

    /** Starts the watcher, and a session on [tabId] asking for the URL Fenix asks for. */
    private fun listen(browserStore: BrowserStore, tabId: String) {
        start(browserStore)
        val tab = checkNotNull(browserStore.state.tabs.firstOrNull { it.id == tabId })
        listenStore.dispatch(ListenAction.User.Session.ListenRequested(tabId, tab.listenUrl))
        idle()
    }

    private fun requested(tabId: String) = ListenAction.User.Session.ListenRequested(tabId, URL)

    private fun stopped(source: StopSource) = ListenAction.User.Session.StopRequested(source)

    private fun idle() = testDispatcher.scheduler.advanceUntilIdle()

    private fun sessionActions() = actions.filterIsInstance<ListenAction.User.Session>()

    /** A browser with both tabs open and in reader mode, and [TAB_ID] selected. */
    private fun browserStoreWithOpenTabs() =
        BrowserStore(
            BrowserState(
                tabs =
                    listOf(
                        createTab(url = URL, id = TAB_ID, readerState = ReaderState(active = true)),
                        createTab(url = URL, id = OTHER_TAB_ID, readerState = ReaderState(active = true)),
                    ),
                selectedTabId = TAB_ID,
            )
        )
}
