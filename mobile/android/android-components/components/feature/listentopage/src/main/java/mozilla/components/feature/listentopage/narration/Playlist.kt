/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import kotlin.time.Duration
import mozilla.components.feature.listentopage.playback.PlaybackPhase
import mozilla.components.feature.listentopage.playback.PlaybackStatus

// Reports this far behind a restart still count, because the player nudges its position back when audio starts.
private const val RESTART_REPORT_TOLERANCE_MS = 1_000L

// Reports this far behind a seek still count, for the same reason.
private const val SEEK_REPORT_BEFORE_MS = 1_000L

// Reports this far past a seek still count, because playback moves on between reports.
private const val SEEK_REPORT_AFTER_MS = 5_000L

// The player can send a report or two from where it was before a seek reaches it, so up to this many reports far from
// the seek's target are ignored. After that the seek is given up, so a seek the player never makes cannot freeze the
// article.
private const val MAX_REPORTS_BEFORE_SEEK = 2

/**
 * A record of which chunks have been handed to the player since its playlist last started, and of the seek or restart
 * its reports have to catch up with.
 *
 * @property lastQueuedChunkIndex The index of the last chunk handed to the player, or `null` when none has been since
 *   the playlist was last cleared.
 * @property playingChunkIndex The index of the chunk the player last reported reading out, or `null`.
 * @property frozenLengths The lengths of the chunks before the playlist and of each chunk handed to the player, kept as
 *   they were when the playlist started or the chunk was handed over. The chunks before the playlist were mostly
 *   skipped, so they are estimates, and the session placed the playlist by them.
 * @property seekingToChunkIndex The index of the chunk a seek is moving playback into, until the player reports from
 *   there, or `null`.
 * @property lastReadReport The last report the player sent, or `null` when it was left over from before the last seek
 *   or restart.
 * @property isFinished Whether a seek moved playback to the end of the article, until playback moves again.
 */
internal data class Playlist(
    val lastQueuedChunkIndex: Int? = null,
    val playingChunkIndex: Int? = null,
    val frozenLengths: Map<Int, Duration> = emptyMap(),
    val seekingToChunkIndex: Int? = null,
    val lastReadReport: PlaybackStatus? = null,
    private val restartedAtMs: Long? = null,
    private val seekingToMs: Long? = null,
    private val reportsBeforeSeek: Int = 0,
    val isFinished: Boolean = false,
) {
    /** The next chunk to hand to the player. */
    val nextToQueue: Int
        get() = (lastQueuedChunkIndex ?: -1) + 1

    /**
     * What was handed to the player no longer counts, because the session is replacing it. A seek in progress is kept,
     * since it is what the new playlist is being made for.
     */
    fun cleared() = copy(playingChunkIndex = null, lastQueuedChunkIndex = null, restartedAtMs = null)

    /** Playback is being moved to [positionMs] into the article, which is inside the chunk at [chunkIndex]. */
    fun seeking(positionMs: Long, chunkIndex: Int) =
        copy(seekingToMs = positionMs, seekingToChunkIndex = chunkIndex, reportsBeforeSeek = 0, isFinished = false)

    /** A seek moved playback to the end of the article, which finished it there. */
    fun finished() = copy(isFinished = true, lastReadReport = null)

    /** The seek was given up, so what the player reports is read again whatever its position. */
    fun seekAbandoned() = copy(seekingToMs = null, seekingToChunkIndex = null)

    /** The chunk at [chunkIndex] was handed to the player behind what was queued, lasting [length]. */
    fun queued(chunkIndex: Int, length: Duration) =
        copy(lastQueuedChunkIndex = chunkIndex, frozenLengths = frozenLengths + (chunkIndex to length))

    /** The player reported reading the chunk at [chunkIndex] out. */
    fun playing(chunkIndex: Int) = copy(playingChunkIndex = chunkIndex)

    /**
     * The player was given a new playlist that starts on the chunk at [chunkIndex], [startMs] into the article. A seek
     * waits on [startMs] from now, since the made chunk can be shorter or longer than estimated, which moves the start.
     *
     * @param frozenLengths The lengths of the chunks before the chunk at [chunkIndex] that the session placed the
     *   playlist by, and of the chunk at [chunkIndex] itself.
     */
    fun restarted(chunkIndex: Int, frozenLengths: Map<Int, Duration>, startMs: Long) =
        copy(
            frozenLengths = frozenLengths,
            playingChunkIndex = chunkIndex,
            lastQueuedChunkIndex = chunkIndex,
            restartedAtMs = startMs,
            seekingToMs = seekingToMs?.let { startMs },
            reportsBeforeSeek = 0,
            isFinished = false,
        )

    /**
     * The playlist after the player sent [report], keeping it as [lastReadReport], or `null` when it is left over from
     * before the last seek or restart. A failure is always kept, since it is the chunk sought or restarted on that
     * failed.
     */
    fun received(report: PlaybackStatus): Playlist {
        if (isFinished) return copy(lastReadReport = null)

        val isFailure = report.phase == PlaybackPhase.Failed

        // A report far from where a seek went was taken before the move. A few are ignored, then the seek is given up.
        val isBeforeSeek = seekingToMs != null && !report.isFromSeekTo(seekingToMs)
        if (isBeforeSeek && !isFailure && reportsBeforeSeek < MAX_REPORTS_BEFORE_SEEK) {
            return copy(reportsBeforeSeek = reportsBeforeSeek + 1, lastReadReport = null)
        }
        val afterSeek = seekAbandoned()

        // A report that is not from a restarted playlist is left over from the playlist it replaced.
        val isLeftOver =
            afterSeek.restartedAtMs != null &&
                !report.isFromRestartAt(afterSeek.restartedAtMs, afterSeek.playingChunkIndex)
        if (isLeftOver && !isFailure) {
            return afterSeek.copy(lastReadReport = null)
        }
        return afterSeek.copy(restartedAtMs = null, lastReadReport = report)
    }
}

/** Whether this report is close enough to where a seek went, at [targetMs], to come from after it. */
private fun PlaybackStatus.isFromSeekTo(targetMs: Long): Boolean =
    position.inWholeMilliseconds in (targetMs - SEEK_REPORT_BEFORE_MS)..(targetMs + SEEK_REPORT_AFTER_MS)

/** Whether this report comes from the playlist restarted at [startMs] into the article, on the chunk at [chunkIndex] */
private fun PlaybackStatus.isFromRestartAt(startMs: Long, chunkIndex: Int?): Boolean {
    val isAtRestart = position.inWholeMilliseconds >= startMs - RESTART_REPORT_TOLERANCE_MS
    val hasEndedRestartedChunk = phase == PlaybackPhase.Ended && this.chunkIndex == chunkIndex
    return isAtRestart && (phase == PlaybackPhase.Playing || phase == PlaybackPhase.Paused || hasEndedRestartedChunk)
}
