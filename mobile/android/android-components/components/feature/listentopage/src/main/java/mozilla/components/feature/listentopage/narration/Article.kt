/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import mozilla.components.feature.listentopage.content.LanguageSource
import mozilla.components.feature.listentopage.playback.ArticleDisplayData

/**
 * The article of one listening session, the tab it was extracted from, and the language it is being read as.
 *
 * @property tabId The tab the article was extracted from.
 * @property text What is read out.
 * @property languageTag The BCP 47 language it is read as.
 * @property languageSource Where [languageTag] came from.
 * @property displayData What the notification and the lock screen say about it.
 */
internal class Article(
    val tabId: String,
    val text: String,
    val languageTag: String,
    val languageSource: LanguageSource,
    val displayData: ArticleDisplayData,
)
