/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.feature.listentopage.narration.NarrationPhase.Active
import mozilla.components.feature.listentopage.playback.PlaybackController
import mozilla.components.feature.listentopage.synthesis.NoOfflineVoiceAvailableException
import mozilla.components.feature.listentopage.synthesis.NothingToReadException
import mozilla.components.feature.listentopage.synthesis.SynthesisQueue
import mozilla.components.feature.listentopage.synthesis.TextChunker
import mozilla.components.feature.listentopage.synthesis.audio.AudioFileCache
import mozilla.components.feature.listentopage.synthesis.audio.ChunkFiles
import mozilla.components.support.base.log.logger.Logger

/**
 * Carries out the [SessionEffect]s of one session, and hands what comes of them back as inputs. It decides nothing:
 * what to make, and what to do with what was made, is [reduceNarration]'s.
 *
 * @param scope Everything the session runs, cancelled when it ends.
 * @param after What has to finish before the session's first synthesis: the end of the session before it.
 * @param engine The session's speech engine.
 * @param playback The player.
 * @param audioCache Where the audio is kept.
 * @param chunker Splits article text into the chunks.
 * @param ioDispatcher The dispatcher for the work that must not run on the narrator's thread.
 * @param session What the session holds now, read when a piece of work asks which chunk comes next.
 * @param dispatch Hands an input back to the narrator.
 */
@Suppress("LongParameterList")
internal class NarrationEffectRunner(
    private val scope: CoroutineScope,
    after: Job?,
    private val engine: SpeechEngine,
    private val playback: PlaybackController,
    private val audioCache: AudioFileCache,
    private val chunker: TextChunker,
    private val ioDispatcher: CoroutineDispatcher,
    private val session: () -> Active?,
    private val dispatch: (NarrationInput) -> Unit,
) {
    private val logger = Logger("NarrationEffectRunner")

    private val worker = NarrationWorker(scope, after)

    // Telling the player to stop for a new voice, which the old voice's audio is only deleted after.
    private var stoppingForNewVoice: Job? = null

    // The queue of the current reading, and which reading it is.
    private var readingId: Int? = null
    private var queue: SynthesisQueue? = null

    fun run(effect: SessionEffect) {
        when (effect) {
            SessionEffect.CancelSynthesis -> worker.cancelAll()
            is SessionEffect.StartReading -> synthesizing(effect.claimId) { startReading(effect) }
            is SessionEffect.MakeTextChunk ->
                synthesizing(effect.claimId) {
                    queueOf(effect.readingId)?.audioFor(effect.index)
                    dispatch(NarrationInput.TextChunkMade(effect.readingId, effect.claimId, effect.textFraction))
                }
            is SessionEffect.ChangeVoice -> synthesizing(claimId = null) { changeVoice(effect) }
            is SessionEffect.MakeRestartChunk -> synthesizing(effect.claimId) { makeRestartChunk(effect) }
            is SessionEffect.MakeNextChunk ->
                synthesizing(effect.claimId) {
                    queueOf(effect.readingId)?.makeNext(effect.index)
                    dispatch(NarrationInput.NextChunkMade(effect.claimId))
                }
            is SessionEffect.Refill -> synthesizing(claimId = null) { refill(effect) }
            is SessionEffect.WorkAhead ->
                synthesizing(claimId = null) { workAhead(effect.readingId, effect.playingChunkIndex) }
            is SessionEffect.SeekWithin -> scope.launch { seekWithin(effect) }
            is SessionEffect.StopForNewVoice -> stoppingForNewVoice = scope.launch {
                    playback.stopAndBufferAt(effect.at)
                    dispatch(NarrationInput.StoppedForNewVoice)
                }
        }
    }

    private suspend fun startReading(effect: SessionEffect.StartReading) {
        val synthesizer = engine.synthesizer()
        val made =
            SynthesisQueue(synthesizer, ChunkFiles(audioCache), chunker, ioDispatcher) { index, length ->
                dispatch(NarrationInput.ChunkMeasured(effect.readingId, index, length))
            }
        readingId = effect.readingId
        queue = made
        dispatch(NarrationInput.ReadingStarted(effect.readingId))

        val opening = made.startReading(effect.article.text, effect.article.languageTag)
        dispatch(
            NarrationInput.OpeningMade(
                readingId = effect.readingId,
                claimId = effect.claimId,
                chunkCharacters = (0 until made.chunkCount).map(made::charactersIn),
                secondsPerCharacter = made.speechRate?.let { 1 / it.charsPerSecond },
                opening = opening,
                textFraction = effect.textFraction,
            )
        )
    }

    /**
     * Throws away the audio of the voice before, and sets [SessionEffect.ChangeVoice.voice] on the engine. It runs once
     * the request in flight has finished, so that the request cannot write its audio in the voice before after that
     * audio has been thrown away, or be handed the new voice.
     */
    private suspend fun changeVoice(effect: SessionEffect.ChangeVoice) {
        // The player could otherwise move on to a file deleted here before it is told to stop.
        stoppingForNewVoice?.join()
        queue?.clear()
        queue = null
        readingId = null
        withContext(ioDispatcher) { engine.synthesizer().setVoice(effect.voice) }
        dispatch(NarrationInput.VoiceSet(effect.textFraction))
    }

    private suspend fun makeRestartChunk(effect: SessionEffect.MakeRestartChunk) {
        val queue = queueOf(effect.readingId) ?: return
        if (effect.discardFirst) queue.discard(effect.index)
        dispatch(NarrationInput.RestartChunkMade(effect.claimId, effect.readingId, queue.audioFor(effect.index)))
    }

    private suspend fun refill(effect: SessionEffect.Refill) {
        val queue = queueOf(effect.readingId) ?: return
        // A repeated report of playback ending finds the chunk queued already.
        if ((session()?.playlist?.lastQueuedChunkIndex ?: -1) >= effect.index) return

        val file = queue.audioFor(effect.index) ?: return
        dispatch(NarrationInput.MadeFound(effect.readingId, effect.index, file))
        workAhead(effect.readingId, effect.index)
    }

    /**
     * Fills the window around [playingChunkIndex], or around the chunk the player is on when `null`, handing back each
     * chunk as soon as it is made so that the player does not run out while the rest of the window is made.
     */
    private suspend fun workAhead(readingId: Int, playingChunkIndex: Int?) {
        val queue = queueOf(readingId) ?: return
        val playing = playingChunkIndex ?: session()?.playlist?.playingChunkIndex ?: return

        handBackWhatIsMade(readingId, queue)
        val playlist = session()?.playlist ?: return
        queue.makeAround(playing, playlist.lastQueuedChunkIndex, playlist.seekingToChunkIndex) {
            handBackWhatIsMade(readingId, queue)
        }
    }

    /** Hands back every made chunk after what the player has, in order, up to the first that has not been made. */
    private suspend fun handBackWhatIsMade(readingId: Int, queue: SynthesisQueue) {
        var next = session()?.takeIf { it.reading?.id == readingId }?.playlist?.nextToQueue ?: return
        while (next < queue.chunkCount) {
            val file = queue.fileFor(next) ?: return
            dispatch(NarrationInput.MadeFound(readingId, next, file))
            next = session()?.playlist?.nextToQueue?.takeIf { it > next } ?: return
        }
    }

    private suspend fun seekWithin(effect: SessionEffect.SeekWithin) {
        val succeeded =
            queueOf(effect.readingId)?.fileFor(effect.chunkIndex) != null &&
                playback.seekTo(effect.chunkIndex, effect.at)
        dispatch(NarrationInput.SeekedWithin(effect.claimId, succeeded))
    }

    private fun queueOf(readingId: Int): SynthesisQueue? = queue.takeIf { this.readingId == readingId }

    /** Runs [work] on the worker, and hands back whatever it fails with, naming the claim it was done for. */
    private fun synthesizing(claimId: Int?, work: suspend () -> Unit) {
        worker.submit {
            val failure =
                try {
                    work()
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: NothingToReadException) {
                    SynthesisFailure.NothingToRead
                } catch (_: NoOfflineVoiceAvailableException) {
                    SynthesisFailure.NoOfflineVoice
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    logger.error("Could not read the article out loud", e)
                    SynthesisFailure.Failed
                }
            failure?.let { dispatch(NarrationInput.WorkFailed(it, claimId)) }
        }
    }
}
