/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.progress

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import mozilla.components.feature.listentopage.synthesis.SpeechRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Measurements of an article of [chunkChars] characters a chunk, of which [measured] have been made and measured. */
private fun measurements(
    chunkChars: List<Int>,
    measured: Map<Int, Duration> = emptyMap(),
    rate: SpeechRate? = SpeechRate(charsPerSecond = 10.0),
) =
    ArticleMeasurements(
        chunkCharacters = chunkChars,
        measuredLengths = measured,
        secondsPerCharacter = rate?.secondsPerCharacter,
    )

private val SpeechRate.secondsPerCharacter: Double
    get() = 1 / charsPerSecond

private fun timelineOf(vararg lengthsMs: Long) = ChunkTimeline(lengthsMs.map { it.milliseconds })

class ArticleMeasurementsTest {

    @Test
    fun `test that the text position counts the characters before the position and the fraction of its chunk read`() {
        val measurements = ArticleMeasurements(chunkCharacters = listOf(100, 300, 600))

        // Halfway through the second chunk, which lasts twice as long per character as the first.
        val textPosition = measurements.textFractionAt(ArticleTime(20_000), timelineOf(10_000, 20_000, 30_000))

        assertEquals((100 + 150) / 1_000.0, textPosition!!, 0.0001)
    }

    @Test
    fun `test that a text position is in the chunk that holds its character, up to the end of the text`() {
        val measurements = ArticleMeasurements(chunkCharacters = listOf(100, 300, 600))

        assertEquals(0, measurements.chunkAtText(0.0))
        assertEquals(1, measurements.chunkAtText(0.1))
        assertEquals(1, measurements.chunkAtText(0.25))
        assertEquals(2, measurements.chunkAtText(0.4))
        assertEquals(2, measurements.chunkAtText(1.0))
    }

    @Test
    fun `test that a text position and the progress at it lead back to each other`() {
        val measurements =
            ArticleMeasurements(
                chunkCharacters = listOf(100, 300, 600),
                measuredLengths = mapOf(0 to 10.seconds, 1 to 60.seconds),
                secondsPerCharacter = 0.1,
            )

        val (progress, timeline) = measurements.progressAtText(0.7)
        val textPosition = measurements.textFractionAt(ArticleTime(progress.positionMs), timeline)

        assertEquals(0.7, textPosition!!, 0.0001)
    }

    @Test
    fun `test that the progress at a text position is placed in the chunk that holds it, by its measured length`() {
        // The second chunk was measured at twice its estimate, which the progress is placed by.
        val measurements =
            ArticleMeasurements(
                chunkCharacters = listOf(100, 300, 600),
                measuredLengths = mapOf(0 to 10.seconds, 1 to 60.seconds),
                secondsPerCharacter = 0.1,
            )

        val (progress, timeline) = measurements.progressAtText(0.25)

        assertEquals(10_000L + 30_000L, progress.positionMs)
        assertEquals(timeline.duration.inWholeMilliseconds, progress.durationMs)
        assertEquals(60.seconds, timeline.lengthOf(1))
        assertEquals(progress.positionMs.toFloat() / progress.durationMs, progress.barPosition!!, 0f)
    }

    @Test
    fun `test that an article that has not been split has no text position`() {
        assertNull(ArticleMeasurements().textFractionAt(ArticleTime.ZERO, ChunkTimeline()))
        assertNull(ArticleMeasurements().chunkAtText(0.5))
    }

    @Test
    fun `test that the lengths are measured where they can be and estimated where they cannot`() {
        val measurements = measurements(chunkChars = listOf(100, 200, 300), measured = mapOf(1 to 5.seconds))

        // The one that has been made counts for what it measured, and the forty characters a second it measured at
        // sizes the others, rather than the ten the opening was given.
        assertEquals(listOf(2_500L, 5_000L, 7_500L), measurements.lengthsMs())
    }

    @Test
    fun `test that one measured chunk gives the rest of the article a length without an opening rate`() {
        val measurements = measurements(chunkChars = listOf(100, 200), measured = mapOf(0 to 10.seconds), rate = null)

        // The chunk that was made implies ten characters a second, which is enough to size the one that was not.
        assertEquals(listOf(10_000L, 20_000L), measurements.lengthsMs())
    }

    @Test
    fun `test that a chunk far past the measured ones is estimated from the nearest of them, not as nothing`() {
        // Only the start has been made, as after a seek far ahead of the window.
        val lengths =
            measurements(chunkChars = List(20) { 100 }, measured = mapOf(0 to 10.seconds, 1 to 10.seconds), rate = null)
                .lengthsMs()

        assertEquals(10_000L, lengths[19])
    }

    @Test
    fun `test that a chunk that has not been measured is estimated from the measured chunks nearest it`() {
        // Ten seconds a hundred characters at the start, and twenty at the end, as references at the end of an article
        // are read out more slowly than the text before them.
        val lengths =
            measurements(
                    chunkChars = List(10) { 100 },
                    measured = mapOf(0 to 10.seconds, 1 to 10.seconds, 8 to 20.seconds, 9 to 20.seconds),
                    rate = null,
                )
                .lengthsMs()

        // Weighted by how near each measured chunk is: a third chunk mostly like the start, an eighth like the end.
        assertEquals(11_711.0, lengths[2].toDouble(), 1.0)
        assertEquals(18_289.0, lengths[7].toDouble(), 1.0)
    }

    @Test
    fun `test that a slow part measured at one end does not stretch the estimate of the other`() {
        val chunkChars = List(10) { 100 }
        val start = mapOf(0 to 10.seconds, 1 to 10.seconds)

        val before = measurements(chunkChars, measured = start, rate = null).lengthsMs()
        val after =
            measurements(chunkChars, measured = start + mapOf(8 to 20.seconds, 9 to 20.seconds), rate = null)
                .lengthsMs()

        assertEquals(10_000L, before[2])
        // Averaged over the whole article, it would have gone to fifteen seconds.
        assertTrue("the start was stretched to ${after[2]}", after[2] < 12_000)
    }

    @Test
    fun `test that a short opening counts for little in the estimate of the chunk after it`() {
        // A site's name read out slowly, then a full chunk read out at the rate of the rest of the article.
        val lengths =
            measurements(
                    chunkChars = listOf(15, 500, 500),
                    measured = mapOf(0 to 2_500.milliseconds, 2 to 30.seconds),
                    rate = null,
                )
                .lengthsMs()

        // Counting the opening as much as the full chunk would have made the second chunk about fifty-seven seconds.
        assertEquals(31_553.0, lengths[1].toDouble(), 1.0)
    }

    @Test
    fun `test that splitting the article again sizes its chunks at the new speech rate`() {
        val split = measurements(listOf(100, 200), rate = SpeechRate(charsPerSecond = 10.0))
        assertEquals(listOf(10_000L, 20_000L), split.lengthsMs())

        val resplit = split.copy(chunkCharacters = listOf(100, 200, 300), secondsPerCharacter = 1 / 20.0)

        assertEquals(listOf(5_000L, 10_000L, 15_000L), resplit.lengthsMs())
    }

    @Test
    fun `test that a new voice is estimated from its own measurements`() {
        val oldVoice = measurements(listOf(100, 200, 300), rate = SpeechRate(10.0)).measured(0, 20.seconds)
        assertEquals(listOf(20_000L, 40_000L, 60_000L), oldVoice.lengthsMs())

        // What the narrator starts again from when the voice changes.
        val newVoice = measurements(listOf(100, 200, 300), rate = SpeechRate(20.0))
        assertEquals(listOf(5_000L, 10_000L, 15_000L), newVoice.lengthsMs())

        assertEquals(listOf(4_000L, 8_000L, 12_000L), newVoice.measured(0, 4.seconds).lengthsMs())
    }

    @Test
    fun `test that measuring a chunk updates the estimates of the chunks that are not measured`() {
        val split = measurements(listOf(100, 200, 300), rate = SpeechRate(10.0))
        assertEquals(listOf(10_000L, 20_000L, 30_000L), split.lengthsMs())

        // The second chunk read twice as fast as the opening, so the chunks around it are estimated at its speed.
        assertEquals(listOf(5_000L, 10_000L, 15_000L), split.measured(1, 10.seconds).lengthsMs())
    }

    @Test
    fun `test that frozen lengths are kept whatever was measured since`() {
        val measurements = measurements(listOf(100, 200), rate = SpeechRate(10.0)).measured(0, 20.seconds)

        val timeline = measurements.timeline(frozenLengths = mapOf(0 to 12.seconds))

        assertEquals(listOf(12.seconds, 40.seconds), timeline.chunkLengths)
    }

    private fun ArticleMeasurements.lengthsMs() = timeline().chunkLengths.map { it.inWholeMilliseconds }
}
