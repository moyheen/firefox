/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.progress

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import mozilla.components.feature.listentopage.synthesis.AUDIO_WINDOW_RADIUS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ten characters a second, so a hundred characters is ten seconds of speech and the arithmetic below is checkable by
// eye. All of it is made up: what a device really reads at is measured at runtime.
private const val SECONDS_PER_CHARACTER = 0.1

/** The timeline of an article of [chunkChars] characters a chunk, of which [measured] have been made and measured. */
private fun timeline(
    chunkChars: List<Int>,
    measured: Map<Int, Duration> = emptyMap(),
    secondsPerCharacter: Double? = SECONDS_PER_CHARACTER,
    frozenLengths: Map<Int, Duration> = emptyMap(),
) = ArticleMeasurements(chunkChars, measured, secondsPerCharacter).timeline(frozenLengths)

/** The progress after a report at [positionMs] against [timeline]. */
private fun ArticleProgress.after(
    positionMs: Long,
    timeline: ChunkTimeline,
    isFinished: Boolean = false,
    afterSeek: Boolean = false,
) = next(ArticleTime(positionMs), timeline, isFinished, afterSeek)

/** Where the bar is drawn after a report at [positionMs] in an article of [durationMs]. */
private fun ArticleProgress.barAt(positionMs: Long, durationMs: Long, afterSeek: Boolean = false): Float =
    after(positionMs, ChunkTimeline(listOf(durationMs.milliseconds)), afterSeek = afterSeek).fraction

class ArticleProgressTest {

    // Halfway through forty seconds of article.
    private val halfway = ArticleProgress(positionMs = 20_000, durationMs = 40_000, barPosition = 0.5f)

    @Test
    fun `test that the bar reads zero before the opening has been measured`() {
        // Before the opening returns there is no article to divide up, and after it fails to measure there is no rate
        // to size one at. Neither is a fraction of anything, and neither may read as full or as not a number.
        val unsplit = ArticleProgress().after(0, timeline(chunkChars = emptyList(), secondsPerCharacter = null))
        val unmeasured = ArticleProgress().after(3_000, timeline(listOf(100, 200), secondsPerCharacter = null))

        assertEquals(ArticleProgress(), unsplit)
        assertEquals(ArticleProgress(), unmeasured)
        assertEquals(0f, unsplit.fraction, 0f)
        assertFalse(unmeasured.fraction.isNaN())
    }

    @Test
    fun `test that a chunk not yet synthesized is estimated from the measured rate`() {
        val progress = ArticleProgress().after(5_000, timeline(listOf(100, 200)))

        assertEquals(5_000, progress.positionMs)
        assertEquals(30_000, progress.durationMs)
    }

    @Test
    fun `test that a chunk with audio but no measured length is sized from its characters`() {
        val progress = ArticleProgress().after(1_000, timeline(listOf(100, 200), measured = mapOf(0 to 8.seconds)))

        assertEquals(24_000, progress.durationMs)
    }

    @Test
    fun `test that the bar reaches exactly one at the end of the article`() {
        val timeline =
            timeline(listOf(100, 200, 300), measured = mapOf(0 to 12.seconds, 1 to 19.seconds, 2 to 31.seconds))

        // Short of the end, as the last position the player sampled before the chunk ended always is.
        val progress =
            ArticleProgress(positionMs = 31_000, durationMs = 62_000).after(61_999, timeline, isFinished = true)

        assertEquals(62_000, progress.positionMs)
        assertEquals(62_000, progress.durationMs)
        assertEquals(1f, progress.fraction, 0f)
    }

    @Test
    fun `test that the bar stays short of full until the last chunk has ended`() {
        val timeline = timeline(List(3) { 100 }, measured = (0..2).associateWith { 10.seconds })

        val sampled = ArticleProgress().after(30_000, timeline)
        val ended = sampled.after(30_000, timeline, isFinished = true)

        assertEquals(30_000, sampled.positionMs)
        assertTrue("the bar is at ${sampled.fraction}", sampled.fraction < 1f)
        assertEquals(1f, ended.fraction, 0f)
    }

    @Test
    fun `test that replacing an estimate with a real length never moves the bar backwards`() {
        val chunkChars = List(6) { 100 }
        val real = listOf(18.seconds, 6.seconds, 14.seconds, 9.seconds, 21.seconds, 5.seconds)
        val measured = mutableMapOf<Int, Duration>()
        var progress = ArticleProgress()

        for (chunk in chunkChars.indices) {
            for (made in chunk..minOf(chunk + AUDIO_WINDOW_RADIUS, chunkChars.lastIndex)) {
                measured[made] = real[made]
            }
            val timeline = timeline(chunkChars, measured.toMap())
            val startMs = real.take(chunk).sumOf { it.inWholeMilliseconds }

            for (positionMs in 0..real[chunk].inWholeMilliseconds step 1_000) {
                val next = progress.after(startMs + positionMs, timeline)

                assertTrue(
                    "chunk $chunk at ${positionMs}ms went back from ${progress.fraction} to ${next.fraction}",
                    next.fraction >= progress.fraction,
                )
                progress = next
            }

            progress =
                progress.after(
                    startMs + real[chunk].inWholeMilliseconds,
                    timeline,
                    isFinished = chunk == chunkChars.lastIndex,
                )
        }

        assertEquals(1f, progress.fraction, 0f)
    }

    @Test
    fun `test that a real length shorter than its estimate does not pin the bar at full`() {
        val timeline = timeline(List(4) { 100 }, measured = (0..3).associateWith { 2_500.milliseconds })
        val halfway = ArticleProgress(positionMs = 20_000, durationMs = 40_000)

        val progress = halfway.after(6_000, timeline)

        assertTrue("the bar is at ${progress.fraction}", progress.fraction < 1f)
        assertTrue("the position is inside the article", progress.positionMs <= progress.durationMs)
    }

    @Test
    fun `test that a seek moves the bar without stretching or shrinking the article`() {
        val timeline = timeline(List(4) { 100 })
        val playing = ArticleProgress(positionMs = 20_000, durationMs = 30_000)

        assertEquals(40_000, playing.after(5_000, timeline, afterSeek = true).durationMs)
        assertEquals(40_000, playing.after(35_000, timeline, afterSeek = true).durationMs)
    }

    @Test
    fun `test that an article of one chunk is a bar of its own`() {
        val timeline = timeline(listOf(100), measured = mapOf(0 to 10.seconds))

        assertEquals(0.5f, ArticleProgress().after(5_000, timeline).fraction, 0f)
        assertEquals(1f, ArticleProgress().after(0, timeline, isFinished = true).fraction, 0f)
    }

    @Test
    fun `test that the chunks before the playlist keep the lengths they had when it started`() {
        // The first chunk was estimated at ten seconds when the playlist started at the second. Measuring the second
        // at five seconds halves the rate the first would now be estimated at, which must not move the second.
        val timeline =
            timeline(
                chunkChars = listOf(100, 100, 100),
                measured = mapOf(1 to 5.seconds),
                secondsPerCharacter = null,
                frozenLengths = mapOf(0 to 10.seconds),
            )

        assertEquals(listOf(10.seconds, 5.seconds, 5.seconds), timeline.chunkLengths)
        assertEquals(20_000L, ArticleProgress().after(12_000, timeline).durationMs)
    }

    @Test
    fun `test that the bar is drawn at the position over the length`() {
        assertEquals(0.25f, ArticleProgress().barAt(positionMs = 10_000, durationMs = 40_000), 0f)
    }

    @Test
    fun `test that the bar is empty while the article has no length`() {
        assertEquals(0f, ArticleProgress().barAt(positionMs = 10_000, durationMs = 0), 0f)
    }

    @Test
    fun `test that the bar slows down rather than going back or stopping when the article is estimated longer`() {
        val next = halfway.barAt(positionMs = 21_000, durationMs = 50_000)

        assertTrue("the bar went back or stopped at $next", next > halfway.fraction)
        assertTrue("the bar did not slow down", next < halfway.fraction + 1_000f / 50_000)
    }

    @Test
    fun `test that a bar slowed down for a longer estimate catches up with the position`() {
        var progress = halfway
        for (positionMs in 21_000L..40_000L step 1_000) {
            progress = progress.after(positionMs, ChunkTimeline(listOf(50.seconds)))
        }

        assertEquals(40_000f / 50_000, progress.fraction, 0.0001f)
    }

    @Test
    fun `test that the bar speeds up rather than jumping on when the article is estimated shorter`() {
        val next = halfway.barAt(positionMs = 21_000, durationMs = 30_000)

        assertTrue("the bar jumped on to $next", next < 21_000f / 30_000)
        assertTrue("the bar did not speed up", next > halfway.fraction + 1_000f / 30_000)
    }

    @Test
    fun `test that a bar behind a shorter estimate catches up with the position and then keeps up with it`() {
        var progress = halfway
        for (positionMs in 21_000L..30_000L step 1_000) {
            progress = progress.after(positionMs, ChunkTimeline(listOf(36.seconds)))
            assertTrue("the bar passed the position at $positionMs", progress.fraction <= positionMs / 36_000f)
        }

        assertEquals(30_000f / 36_000, progress.fraction, 0.0001f)
    }

    @Test
    fun `test that a bar where the position puts it keeps up with it`() {
        assertEquals(21_000f / 40_000, halfway.barAt(positionMs = 21_000, durationMs = 40_000), 0f)
    }

    @Test
    fun `test that a seek moves the bar to where playback is against the estimated length`() {
        assertEquals(5_000f / 50_000, halfway.barAt(positionMs = 5_000, durationMs = 50_000, afterSeek = true), 0f)
        assertEquals(35_000f / 50_000, halfway.barAt(positionMs = 35_000, durationMs = 50_000, afterSeek = true), 0f)
    }

    @Test
    fun `test that playback moving back moves the bar back even without a seek`() {
        assertEquals(5_000f / 50_000, halfway.barAt(positionMs = 5_000, durationMs = 50_000), 0f)
    }

    @Test
    fun `test that a seek near the end after the estimate grew leaves the bar room for the audio after it`() {
        val timeline = ChunkTimeline(listOf(50.seconds))
        var progress = halfway.after(45_000, timeline, afterSeek = true)
        assertEquals(45_000f / 50_000, progress.fraction, 0f)

        for (positionMs in 46_000L..49_000L step 1_000) {
            val next = progress.after(positionMs, timeline)
            assertTrue("the bar went back at $positionMs", next.fraction >= progress.fraction)
            assertTrue("the bar went back past the position at $positionMs", next.fraction >= positionMs / 50_000f)
            assertTrue("the bar filled with audio left at $positionMs", next.fraction < 1f)
            progress = next
        }
    }

    @Test
    fun `test that the notification is given the length that puts its bar where the sheet's is`() {
        val slowed = ArticleProgress(positionMs = 21_000, durationMs = 50_000, barPosition = 0.5f)
        val caughtUp = ArticleProgress(positionMs = 21_000, durationMs = 50_000, barPosition = 21_000f / 50_000)

        assertEquals(42.seconds, slowed.shownDuration)
        assertEquals(50.seconds, caughtUp.shownDuration)
    }

    @Test
    fun `test that the notification is given a longer length while the sheet's bar catches up with a shorter estimate`() {
        val behind = ArticleProgress(positionMs = 21_000, durationMs = 30_000, barPosition = 0.6f)

        assertEquals(35.seconds, behind.shownDuration)
    }

    @Test
    fun `test that a position on the notification's bar is placed against the length it was given`() {
        val slowed = ArticleProgress(positionMs = 21_000, durationMs = 50_000, barPosition = 0.5f)

        assertEquals(0.25f, slowed.fractionAt(10_500)!!, 0f)
        assertEquals(null, ArticleProgress().fractionAt(10_500))
    }

    @Test
    fun `test that a seek moves the elapsed time to that share of the article`() {
        val seeked = ArticleProgress(positionMs = 102_000, durationMs = 600_000).seekedTo(0.25f)

        assertEquals(150_000, seeked.positionMs)
        assertEquals(0.25f, seeked.fraction, 0f)
    }

    @Test
    fun `test that a seek lands against the estimated length, so the bar is back in step with it`() {
        // A bar held back at a third while the estimate grows is drawn against 300s rather than the 600s estimated.
        val held = ArticleProgress(positionMs = 100_000, durationMs = 600_000, barPosition = 1 / 3f)

        val seeked = held.seekedTo(0.5f)

        assertEquals(300_000, seeked.positionMs)
        assertEquals(600.seconds, seeked.shownDuration)
        assertEquals(600_000, held.seekedTo(1f).positionMs)
    }

    @Test
    fun `test that a seek past either end of the article stops at that end`() {
        val playing = ArticleProgress(positionMs = 102_000, durationMs = 600_000)

        assertEquals(600_000, playing.seekedTo(1.5f).positionMs)
        assertEquals(0, playing.seekedTo(-0.5f).positionMs)
    }

    @Test
    fun `test that a seek in an article of no length stays at the start`() {
        assertEquals(ArticleProgress(), ArticleProgress().seekedTo(0.5f))
    }

    @Test
    fun `test that the end of the article fills the bar`() {
        val ended = ArticleProgress(positionMs = 590_000, durationMs = 600_000).atEnd()

        assertEquals(600_000, ended.positionMs)
        assertEquals(1f, ended.fraction, 0f)
    }

    // A real duration shorter than the estimate it replaced shortens the whole article, and a position left outside it
    // would hold the bar at full while there is still audio to play.
    @Test
    fun `test that a position outside a shortened article is pulled back inside it`() {
        assertEquals(
            ArticleProgress(positionMs = 60_000, durationMs = 60_000),
            ArticleProgress(positionMs = 90_000, durationMs = 60_000).correctLength(),
        )
    }

    @Test
    fun `test that a jump forward further than playback goes between reports moves the bar`() {
        // Thirty seconds on, as a seek from another app's controller moves it, with no seek of the narration's.
        assertEquals(50_000f / 60_000, halfway.barAt(positionMs = 50_000, durationMs = 60_000), 0f)
    }
}
