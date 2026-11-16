/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import mozilla.components.feature.listentopage.ChunkState
import mozilla.components.feature.listentopage.PlaybackState
import mozilla.components.feature.listentopage.content.LanguageSource
import mozilla.components.feature.listentopage.fakes.toStatus
import mozilla.components.feature.listentopage.narration.NarrationPhase.Active
import mozilla.components.feature.listentopage.playback.ArticleDisplayData
import mozilla.components.feature.listentopage.playback.PlaybackPhase
import mozilla.components.feature.listentopage.progress.ArticleMeasurements
import mozilla.components.feature.listentopage.progress.ArticleProgress
import mozilla.components.feature.listentopage.progress.CHUNK_END_MARGIN_MS
import mozilla.components.feature.listentopage.progress.offsetInMeasuredChunk
import mozilla.components.feature.listentopage.voices.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val READING = 1

// Three chunks of ten seconds each, all measured.
private val MEASUREMENTS =
    ArticleMeasurements(
        chunkCharacters = listOf(100, 100, 100),
        measuredLengths = (0..2).associateWith { 10.seconds },
        secondsPerCharacter = 0.1,
    )

private val ARTICLE = Article("tab", "Text.", "en-US", LanguageSource.Page, ArticleDisplayData())

/** A session reading the article, 5 seconds into the second chunk, with every chunk handed to the player. */
private val reading =
    NarrationModel(
        phase =
            Active(
                tabId = "tab",
                article = ARTICLE,
                reading = ReadingModel(READING, MEASUREMENTS),
                playlist = Playlist().queued(0, 10.seconds).queued(1, 10.seconds).queued(2, 10.seconds).playing(1),
                position = PositionModel(timeline = MEASUREMENTS.timeline()),
            ),
        progress = ArticleProgress(positionMs = 15_000, durationMs = 30_000),
        lastId = READING,
    )

class NarrationReducerTest {

    @Test
    fun `test that a seek is first made within what the player holds`() {
        val next = reduceNarration(reading, NarrationCommand.Seek(0.5f))

        val seekWithin = next.effects.filterIsInstance<SessionEffect.SeekWithin>().single()
        assertEquals(1, seekWithin.chunkIndex)
        assertEquals(15.seconds, seekWithin.at)
        assertEquals(Priority.Seek, next.active.claim?.priority)
    }

    @Test
    fun `test that a seek the player made lets go of its claim`() {
        val seeking = reduceNarration(reading, NarrationCommand.Seek(0.5f))
        val claimId = seeking.active.claim!!.id

        val next = reduceNarration(seeking.model, NarrationInput.SeekedWithin(claimId, succeeded = true))

        assertNull(next.active.claim)
        assertTrue(next.effects.isEmpty())
    }

    @Test
    fun `test that a seek the player could not make restarts on its chunk`() {
        val seeking = reduceNarration(reading, NarrationCommand.Seek(0.5f))
        val claimId = seeking.active.claim!!.id

        val next = reduceNarration(seeking.model, NarrationInput.SeekedWithin(claimId, succeeded = false))

        assertEquals(
            listOf(
                PlayerEffect.StopAndBufferAt(15.seconds),
                NarrationEffect.Emit(NarrationEvent.Waiting),
                SessionEffect.CancelSynthesis,
                SessionEffect.MakeRestartChunk(claimId, READING, index = 1, discardFirst = false),
            ),
            next.effects,
        )
    }

    @Test
    fun `test that a made chunk starts the player where the seek placed it`() {
        val restarting = restartingAtChunk(1)

        val next = reduceNarration(restarting.model, restarting.chunkMade(File("/audio/1.wav")))

        val restart = next.effects.filterIsInstance<PlayerEffect.Restart>().single()
        assertEquals(10.seconds, restart.item.startsAt)
        assertEquals(15.seconds, restart.at)
        assertEquals(SessionEffect.WorkAhead(READING, 1), next.effects.last())
        assertNull(next.active.claim)
    }

    @Test
    fun `test that a chunk made for a seek something replaced is not played`() {
        val restarting = restartingAtChunk(1)
        val replaced = reduceNarration(restarting.model, NarrationCommand.Seek(0.1f))

        val next = reduceNarration(replaced.model, restarting.chunkMade(File("/audio/1.wav")))

        assertTrue(next.effects.none { it is PlayerEffect.Restart })
    }

    @Test
    fun `test that little audio left after a seek makes the next chunk first`() {
        // Nine and a half seconds into a ten second chunk, so half a second is left.
        val nearEnd = reading.copy(progress = ArticleProgress(positionMs = 19_500, durationMs = 30_000))
        val seeking = reduceNarration(nearEnd, NarrationCommand.Seek(19_500f / 30_000))
        val claimId = seeking.active.claim!!.id
        val restarting = reduceNarration(seeking.model, NarrationInput.SeekedWithin(claimId, succeeded = false))

        val made = reduceNarration(restarting.model, NarrationInput.RestartChunkMade(claimId, READING, File("/1.wav")))
        assertEquals(listOf(SessionEffect.MakeNextChunk(claimId, READING, index = 2)), made.effects)

        val next = reduceNarration(made.model, NarrationInput.NextChunkMade(claimId))
        assertEquals(1, next.effects.filterIsInstance<PlayerEffect.Restart>().size)
    }

    @Test
    fun `test that a failed restart puts the seek back`() {
        val restarting = restartingAtChunk(1)

        val next = reduceNarration(restarting.model, restarting.chunkMade(file = null))

        assertEquals(reading.progress, next.model.progress)
        assertNull(next.active.claim)
    }

    @Test
    fun `test that a seek during the opening is refused and the progress goes back`() {
        val opening = reduceNarration(reading, NarrationCommand.Start)

        val next = reduceNarration(opening.model, NarrationCommand.Seek(0.9f))

        assertTrue(next.effects.isEmpty())
        assertEquals(reading.progress, next.model.progress)
    }

    @Test
    fun `test that a chunk the player keeps failing on is reported once it has been made again too often`() {
        var model = reading
        val failed = PlaybackState(phase = PlaybackPhase.Failed, chunk = ChunkState(index = 1), positionMs = 15_000)
        repeat(MAX_RECOVERY_ATTEMPTS) {
            model = reduceNarration(model, NarrationInput.PlayerReported(failed.toStatus())).model
            model = (model.phase as Active).let { model.copy(phase = it.copy(claim = null)) }
        }

        val next = reduceNarration(model, NarrationInput.PlayerReported(failed.toStatus()))

        assertTrue(NarrationEffect.Emit(NarrationEvent.PlaybackFailed) in next.effects)
    }

    @Test
    fun `test that the player moving on to a chunk starts it and works ahead`() {
        val playing = PlaybackState(phase = PlaybackPhase.Playing, chunk = ChunkState(index = 2), positionMs = 20_000)

        val next = reduceNarration(reading, NarrationInput.PlayerReported(playing.toStatus()))

        assertTrue(NarrationEffect.Emit(NarrationEvent.Started(playing.toStatus())) in next.effects)
        assertEquals(SessionEffect.WorkAhead(READING, playingChunkIndex = null), next.effects.last())
        assertEquals(2, next.active.playlist.playingChunkIndex)
    }

    @Test
    fun `test that a result from a reading a new voice replaced is ignored`() {
        val changed =
            reduceNarration(reading, NarrationCommand.ChangeVoice(Voice("b", Locale.UK, engine = "com.example.tts")))

        val next = reduceNarration(changed.model, NarrationInput.MadeFound(READING, 0, File("/audio/0.wav")))

        assertTrue(next.effects.isEmpty())
    }

    @Test
    fun `test that a seek to the end finishes the article`() {
        val next = reduceNarration(reading, NarrationCommand.Seek(1f))

        assertEquals(PlayerEffect.FinishAt(30_000.milliseconds), next.effects.first())
        assertTrue(NarrationEffect.Emit(NarrationEvent.Ended) in next.effects)
        assertEquals(1f, next.model.progress.fraction, 0f)
    }

    @Test
    fun `test that a seek to the end stops making the chunk an earlier seek was waiting on`() {
        val restarting = restartingAtChunk(1)

        val next = reduceNarration(restarting.model, NarrationCommand.Seek(1f))

        assertNull(next.active.claim)
        assertTrue(SessionEffect.CancelSynthesis in next.effects)
    }

    @Test
    fun `test that work the session gave up on fails unseen`() {
        val restarting = restartingAtChunk(1)
        val finished = reduceNarration(restarting.model, NarrationCommand.Seek(1f))

        val next =
            reduceNarration(finished.model, NarrationInput.WorkFailed(SynthesisFailure.Failed, restarting.claimId))

        assertTrue(next.effects.isEmpty())
    }

    @Test
    fun `test that work the session still waits on reports its failure`() {
        val restarting = restartingAtChunk(1)

        val next =
            reduceNarration(restarting.model, NarrationInput.WorkFailed(SynthesisFailure.Failed, restarting.claimId))

        assertTrue(NarrationEffect.Emit(NarrationEvent.SynthesisFailed) in next.effects)
    }

    @Test
    fun `test that a position into a chunk is kept once the chunk is measured, short of the end of its audio`() {
        assertEquals(15_000L, offsetInMeasuredChunk(offsetMs = 15_000, measuredMs = 20_000))
        assertEquals(20_000L - CHUNK_END_MARGIN_MS, offsetInMeasuredChunk(offsetMs = 24_000, measuredMs = 20_000))
        assertEquals(24_000L, offsetInMeasuredChunk(offsetMs = 24_000, measuredMs = null))
    }

    @Test
    fun `test that an offset past the end of a chunk that turned out shorter lands at the same share of it`() {
        // Three quarters of a chunk estimated at forty seconds, which measured twenty.
        assertEquals(15_000L, offsetInMeasuredChunk(offsetMs = 30_000, measuredMs = 20_000, estimatedMs = 40_000))
        assertEquals(15_000L, offsetInMeasuredChunk(offsetMs = 15_000, measuredMs = 20_000, estimatedMs = 40_000))
    }

    /** A session restarting on [chunk] for a seek the player could not make within what it holds. */
    private fun restartingAtChunk(chunk: Int): Restarting {
        val fraction = (chunk * 10_000 + 5_000) / 30_000f
        val seeking = reduceNarration(reading, NarrationCommand.Seek(fraction))
        val claimId = seeking.active.claim!!.id
        return Restarting(
            reduceNarration(seeking.model, NarrationInput.SeekedWithin(claimId, succeeded = false)).model,
            claimId,
        )
    }

    private class Restarting(val model: NarrationModel, val claimId: Int) {
        fun chunkMade(file: File?) = NarrationInput.RestartChunkMade(claimId, READING, file)
    }

    private val Next.active: Active
        get() = model.phase as Active
}
