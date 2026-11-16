/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import java.io.File
import kotlin.time.Duration
import mozilla.components.feature.listentopage.playback.ArticleDisplayData
import mozilla.components.feature.listentopage.playback.PlayableItem
import mozilla.components.feature.listentopage.playback.PlaybackPhase
import mozilla.components.feature.listentopage.playback.PlaybackSpeed
import mozilla.components.feature.listentopage.playback.PlaybackStatus
import mozilla.components.feature.listentopage.progress.ArticleMeasurements
import mozilla.components.feature.listentopage.progress.ArticleProgress
import mozilla.components.feature.listentopage.progress.ChunkPosition
import mozilla.components.feature.listentopage.progress.ChunkTimeline
import mozilla.components.feature.listentopage.voices.Voice

/**
 * Everything the narrator knows. Only [reduceNarration] makes a new one.
 *
 * @property phase Whether a session is running, and what it holds.
 * @property progress How far through the article playback has got.
 * @property lastId The last id given to a reading or a claim. It carries on across sessions, so that whatever a session
 *   before this one reports can never be taken for this session's own.
 */
internal data class NarrationModel(
    val phase: NarrationPhase = NarrationPhase.Idle,
    val progress: ArticleProgress = ArticleProgress(),
    val lastId: Int = 0,
)

/** Whether a session is running. */
internal sealed interface NarrationPhase {
    /** No session is running. */
    data object Idle : NarrationPhase

    /**
     * A session is running.
     *
     * @property tabId The tab it reads.
     * @property article The article, or `null` while it is being extracted.
     * @property voice The voice it reads in, or `null` before one is chosen.
     * @property reading What synthesis has made of the article, or `null` before it starts, and while a new voice is
     *   set.
     * @property playlist What the player has been handed.
     * @property position Where playback is placed in the article.
     * @property recovery How often a chunk the player failed on has been made again.
     * @property claim The work allowed to move playback, or `null` when none is.
     */
    data class Active(
        val tabId: String,
        val article: Article? = null,
        val voice: Voice? = null,
        val reading: ReadingModel? = null,
        val playlist: Playlist = Playlist(),
        val position: PositionModel = PositionModel(),
        val recovery: RecoveryAttempts = RecoveryAttempts(),
        val claim: Claim? = null,
    ) : NarrationPhase {
        /** Whether [voice] would change what the session reads in: it is not the voice reading, and there is one. */
        fun changesVoice(voice: Voice): Boolean = voice != this.voice && article != null
    }
}

/**
 * One reading of the article. A new voice reads it again, so it starts a new reading with a new [id], and whatever the
 * reading before it reports afterwards is ignored.
 *
 * @property id Which reading it is.
 * @property measurements How the article was split, and how long its chunks are found to last.
 */
internal data class ReadingModel(val id: Int, val measurements: ArticleMeasurements = ArticleMeasurements())

/**
 * Where playback is placed in the article.
 *
 * @property timeline The lengths the last report was placed by. A seek is placed by them too, so that it lands where it
 *   was shown.
 * @property afterSeek Whether the next report read is the first after a seek, which moves the bar rather than smoothing
 *   it.
 * @property shownLengthSent The length the notification was last given.
 */
internal data class PositionModel(
    val timeline: ChunkTimeline = ChunkTimeline(),
    val afterSeek: Boolean = false,
    val shownLengthSent: Duration = Duration.ZERO,
)

/** How much a piece of work matters, which decides what it may replace. */
internal enum class Priority {
    Recovery,
    Seek,
    Restart,
}

/**
 * The right to move playback, held by one piece of work at a time. A result that names a claim no longer held comes
 * from work something more recent replaced, and is ignored.
 *
 * @property id Which claim it is.
 * @property priority What it may replace: a claim of the same or a lower priority.
 * @property work What the work holding it is doing.
 */
internal data class Claim(val id: Int, val priority: Priority, val work: ClaimedWork)

/** What the work holding a [Claim] is doing. */
internal sealed interface ClaimedWork {
    /** Making the opening of the article. */
    data object Opening : ClaimedWork

    /**
     * Moving playback to [target] within what the player holds.
     *
     * @property revertTo The progress to put back if the seek cannot be made, or `null` to put none back.
     */
    data class Seek(val target: ChunkPosition, val revertTo: ArticleProgress?) : ClaimedWork

    /**
     * Making the chunk that holds [target] and starting the player's playlist again there.
     *
     * @property placedBy The lengths the target was placed by when the restart was asked for.
     * @property seek Whether a seek asked for it, which is given up, and its progress put back, if it fails.
     * @property revertTo The progress a seek puts back if it fails, or `null` to put none back.
     * @property file The made chunk, once it has been made.
     * @property offsetMs How far into the made chunk playback starts, once the chunk has been made.
     */
    data class Restart(
        val target: ChunkPosition,
        val placedBy: ChunkTimeline,
        val seek: Boolean = false,
        val revertTo: ArticleProgress? = null,
        val file: File? = null,
        val offsetMs: Long = 0,
    ) : ClaimedWork
}

/** Why synthesis failed. */
internal enum class SynthesisFailure {
    NothingToRead,
    NoOfflineVoice,
    Failed,
}

/**
 * What can happen to the narration: a [NarrationCommand] from outside, something the player did, or the result of an
 * effect. Only commands can be sent from outside the narrator.
 */
internal sealed interface NarrationInput {
    data class PlayerReported(val report: PlaybackStatus) : NarrationInput

    data class NotificationSeek(val position: Duration) : NarrationInput

    data object PlayerSessionEnded : NarrationInput

    data class ReadingStarted(val readingId: Int) : NarrationInput

    data class ChunkMeasured(val readingId: Int, val index: Int, val length: Duration) : NarrationInput

    data class OpeningMade(
        val readingId: Int,
        val claimId: Int,
        val chunkCharacters: List<Int>,
        val secondsPerCharacter: Double?,
        val opening: File,
        val textFraction: Double?,
    ) : NarrationInput

    data class TextChunkMade(val readingId: Int, val claimId: Int, val textFraction: Double) : NarrationInput

    data class VoiceSet(val textFraction: Double?) : NarrationInput

    data object StoppedForNewVoice : NarrationInput

    data class MadeFound(val readingId: Int, val index: Int, val file: File) : NarrationInput

    data class SeekedWithin(val claimId: Int, val succeeded: Boolean) : NarrationInput

    data class RestartChunkMade(val claimId: Int, val readingId: Int, val file: File?) : NarrationInput

    data class NextChunkMade(val claimId: Int) : NarrationInput

    data class WorkFailed(val failure: SynthesisFailure, val claimId: Int?) : NarrationInput
}

/** What the session asks of the narration, through [Narrator.send]. */
internal sealed interface NarrationCommand : NarrationInput {
    /** Starts a session reading [tabId], ending the one before it. */
    data class StartSession(val tabId: String) : NarrationCommand

    /** Ends the session, and lets go of everything it holds. */
    data object EndSession : NarrationCommand

    /** Takes [article] as the article the session reads out. */
    data class Read(val article: Article) : NarrationCommand

    /** The session picked [voice] for itself, and set it on the engine. */
    data class VoiceChosen(val voice: Voice) : NarrationCommand

    /** Shows the session as waiting for its opening, from the article being ready until the opening plays. */
    data object WaitForOpening : NarrationCommand

    /** Synthesizes the opening of the article, plays it, and works ahead on what follows it. */
    data object Start : NarrationCommand

    /** Reads the article out again in [voice], from the same place in the text. Ignored for the voice reading. */
    data class ChangeVoice(val voice: Voice) : NarrationCommand

    /** Seeks to [fraction] of the article, which is where the reader let go of the bar. */
    data class Seek(val fraction: Float) : NarrationCommand

    /** Moves playback [offset] through the article from where it is, kept inside the article. */
    data class Skip(val offset: Duration) : NarrationCommand

    /** Pauses or resumes an article whose playback is in [phase], or starts it again once it has ended. */
    data class TogglePlayPause(val phase: PlaybackPhase) : NarrationCommand

    /** Pauses the article. */
    data object Pause : NarrationCommand

    /** Reads the rest of the article out at [speed]. */
    data class SetSpeed(val speed: PlaybackSpeed) : NarrationCommand
}

/** What the narration asks to be done. */
internal sealed interface NarrationEffect {
    /** Gives out [event], which becomes an action. */
    data class Emit(val event: NarrationEvent) : NarrationEffect
}

/** Commands to the player. Each is sent in the order it was asked for, and nothing comes back from it. */
internal sealed interface PlayerEffect : NarrationEffect {
    data class StartArticle(val item: PlayableItem, val displayData: ArticleDisplayData, val tabId: String) :
        PlayerEffect

    data class Enqueue(val item: PlayableItem) : PlayerEffect

    data class Restart(val item: PlayableItem, val at: Duration) : PlayerEffect

    data class StopAndBufferAt(val at: Duration) : PlayerEffect

    data class FinishAt(val at: Duration) : PlayerEffect

    data class SetArticleDuration(val length: Duration) : PlayerEffect

    data object Pause : PlayerEffect

    data object Resume : PlayerEffect

    data class SetSpeed(val speed: PlaybackSpeed) : PlayerEffect
}

/** Work on the session's synthesis and audio, whose results come back as inputs. */
internal sealed interface SessionEffect : NarrationEffect {
    /** Cancels the synthesis in flight. Synthesis asked for afterwards waits for it to stop. */
    data object CancelSynthesis : SessionEffect

    data class StartReading(val readingId: Int, val claimId: Int, val article: Article, val textFraction: Double?) :
        SessionEffect

    data class MakeTextChunk(val readingId: Int, val claimId: Int, val index: Int, val textFraction: Double) :
        SessionEffect

    data class ChangeVoice(val voice: Voice, val textFraction: Double?) : SessionEffect

    /** Stops the voice before at [at], and answers once the player has been told, so that the wait follows the stop. */
    data class StopForNewVoice(val at: Duration) : SessionEffect

    data class MakeRestartChunk(val claimId: Int, val readingId: Int, val index: Int, val discardFirst: Boolean) :
        SessionEffect

    data class MakeNextChunk(val claimId: Int, val readingId: Int, val index: Int) : SessionEffect

    data class Refill(val readingId: Int, val index: Int) : SessionEffect

    /** Fills the window around [playingChunkIndex], or around the chunk the player is on when `null`. */
    data class WorkAhead(val readingId: Int, val playingChunkIndex: Int?) : SessionEffect

    /** Moves playback within what the player holds, if the chunk's audio is still on disk. */
    data class SeekWithin(val claimId: Int, val readingId: Int, val chunkIndex: Int, val at: Duration) : SessionEffect
}
