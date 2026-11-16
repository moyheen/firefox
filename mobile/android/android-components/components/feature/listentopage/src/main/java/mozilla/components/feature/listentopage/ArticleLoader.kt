/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage

import mozilla.components.feature.listentopage.content.ContentExtractor
import mozilla.components.feature.listentopage.narration.Article
import mozilla.components.feature.listentopage.playback.ArticleDisplayData

/** Thrown when the page gave back no text to read out. */
internal class NoUsableTextException : Exception("The page has no text to read out")

/**
 * Extracts the article a session reads out.
 *
 * @param contentExtractor Provides the article text and language for a tab.
 * @param describeArticle What the notification and the lock screen say about the article in a tab. Read when the
 *   article is ready.
 */
internal class ArticleLoader(
    private val contentExtractor: ContentExtractor,
    private val describeArticle: (tabId: String) -> ArticleDisplayData,
) {
    /** The article in [tabId], or a failure when the page could not be read or has no text in it. */
    suspend fun load(tabId: String): Result<Article> =
        contentExtractor.getContent(tabId).mapCatching { content ->
            if (content.text.isBlank()) throw NoUsableTextException()

            Article(
                tabId = tabId,
                text = content.text,
                languageTag = content.languageTag,
                languageSource = content.languageSource,
                displayData = describeArticle(tabId),
            )
        }
}
