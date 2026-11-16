/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.listentopage

import mozilla.components.browser.state.state.ReaderState
import mozilla.components.browser.state.state.createTab
import mozilla.components.feature.listentopage.playback.ArticleDisplayData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val URL = "https://example.org/article"
private const val READER_URL = "moz-extension://readerview/readerview.html?url=$URL&id=reader-1"

@RunWith(RobolectricTestRunner::class)
class DescribeArticleTest {

    @Test
    fun `WHEN a tab is described THEN it is named by its title and site`() {
        assertEquals(
            ArticleDisplayData(title = "An article", site = "example.org"),
            createTab(url = URL, title = "An article").describeArticle(),
        )
    }

    @Test
    fun `WHEN a reader view article is described THEN it is named by its own site rather than the reader URL`() {
        val readerTab = createTab(url = READER_URL, readerState = ReaderState(active = true, activeUrl = URL))

        assertEquals("example.org", readerTab.describeArticle().site)
    }

    @Test
    fun `WHEN a URL names no site THEN the article has none`() {
        assertNull(createTab(url = "data:text/html,<p>Article</p>").describeArticle().site)
    }

    @Test
    fun `WHEN a page has no title THEN the article has none`() {
        assertNull(createTab(url = URL, title = "").describeArticle().title)
    }

    @Test
    fun `WHEN an article is from a private tab THEN it is described as private`() {
        assertEquals(
            ArticleDisplayData(title = "An article", site = "example.org", isPrivate = true),
            createTab(url = URL, title = "An article", private = true).describeArticle(),
        )
    }

    @Test
    fun `WHEN the tab is gone THEN the article is described with nothing`() {
        assertEquals(ArticleDisplayData(), null.describeArticle())
    }
}
