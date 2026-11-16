/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.progress

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun timelineOf(vararg lengthsMs: Long) = ChunkTimeline(lengthsMs.map { it.milliseconds })

private fun position(chunkIndex: Int, offsetMs: Long) = ChunkPosition(chunkIndex, ChunkOffset(offsetMs))

class ChunkTimelineTest {

    private val lengths = timelineOf(10_000, 20_000, 30_000)

    @Test
    fun `test that a position in the article resolves to the chunk holding it`() {
        assertEquals(position(0, 0), lengths.toChunkPosition(ArticleTime(0)))
        assertEquals(position(0, 9_999), lengths.toChunkPosition(ArticleTime(9_999)))
        assertEquals(position(1, 0), lengths.toChunkPosition(ArticleTime(10_000)))
        assertEquals(position(1, 19_999), lengths.toChunkPosition(ArticleTime(29_999)))
        assertEquals(position(2, 0), lengths.toChunkPosition(ArticleTime(30_000)))
    }

    @Test
    fun `test that a chunk position resolves back to the position in the article it came from`() {
        assertEquals(ArticleTime(0), lengths.toArticleTime(position(0, 0)))
        assertEquals(ArticleTime(25_000), lengths.toArticleTime(position(1, 15_000)))
        for (positionMs in listOf(0L, 9_999L, 10_000L, 45_000L)) {
            val time = ArticleTime(positionMs)
            assertEquals(time, lengths.toArticleTime(checkNotNull(lengths.toChunkPosition(time))))
        }
    }

    @Test
    fun `test that each chunk starts where the ones before it end`() {
        assertEquals(ArticleTime(0), lengths.startOf(0))
        assertEquals(ArticleTime(10_000), lengths.startOf(1))
        assertEquals(ArticleTime(30_000), lengths.startOf(2))
        assertEquals(60.seconds, lengths.duration)
    }

    @Test
    fun `test that a position past the end of the article lands at the end of its last chunk`() {
        assertEquals(position(1, 20_000), timelineOf(10_000, 20_000).toChunkPosition(ArticleTime(1_000_000)))
    }

    @Test
    fun `test that a position before the article lands at its start`() {
        assertEquals(position(0, 0), timelineOf(10_000, 20_000).toChunkPosition(ArticleTime(-5_000)))
    }

    @Test
    fun `test that an article with no lengths has nowhere to resolve a position to`() {
        assertTrue(ChunkTimeline().isEmpty)
        assertNull(ChunkTimeline().toChunkPosition(ArticleTime(1_000)))
    }

    // The lengths a reader was shown are the ones their position has to be read against, so resolving has to work off
    // a timeline that can be held on to.
    @Test
    fun `test that a held timeline resolves the same after the article has moved on`() {
        val shown = ArticleMeasurements(listOf(100, 200, 300), secondsPerCharacter = 0.1).timeline()
        val moved =
            ArticleMeasurements(listOf(100, 200, 300), mapOf(1 to 1.seconds), secondsPerCharacter = 0.1).timeline()

        assertEquals(position(1, 15_000), shown.toChunkPosition(ArticleTime(25_000)))
        assertEquals(position(2, 1_500), moved.toChunkPosition(ArticleTime(25_000)))
    }

    @Test
    fun `test that an article nothing can be sized from has no lengths at all`() {
        assertTrue(ArticleMeasurements(listOf(100, 200), secondsPerCharacter = null).timeline().isEmpty)
    }
}
