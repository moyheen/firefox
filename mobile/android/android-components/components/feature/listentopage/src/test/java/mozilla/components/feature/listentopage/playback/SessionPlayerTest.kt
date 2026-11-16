/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import mozilla.components.feature.listentopage.fakes.FakeChunkPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SessionPlayerTest {

    @Test
    fun `test that the session is given the position and duration of the whole article`() {
        val player = SessionPlayer(ChunkPlayer()) {}

        player.startPlaylist(Placement(playlistStartMs = 75_000, durationMs = 600_000))

        assertEquals(75_000L + FIRST_CHUNK_MS + 15_000, player.currentPosition)
        assertEquals(600_000L, player.duration)
    }

    @Test
    fun `test that clearing the playlist forgets where it was in the article`() {
        val player = SessionPlayer(FakeChunkPlayer()) {}
        player.startPlaylist(Placement(playlistStartMs = 134_247, durationMs = 600_000))

        player.clearMediaItems()

        assertEquals(ArticleTimeline(), player.articleTimeline)
        assertEquals(0L, player.currentPosition)
    }

    @Test
    fun `test that a seek to a position is handed over as an article seek rather than moving the chunk`() {
        val chunk = ChunkPlayer()
        val articleSeeks = mutableListOf<Long>()
        val player = SessionPlayer(chunk) { articleSeeks.add(it) }

        player.seekTo(90_000)

        assertEquals(listOf(90_000L), articleSeeks)
        assertTrue("the chunk playing was moved", chunk.seeks.isEmpty())
    }

    @Test
    fun `test that skipping back is an article seek that can reach into the chunk before`() {
        val chunk = ChunkPlayer()
        val articleSeeks = mutableListOf<Long>()
        val player = SessionPlayer(chunk) { articleSeeks.add(it) }
        player.startPlaylist(Placement(playlistStartMs = 75_000, durationMs = 600_000))

        player.seekBack()

        assertEquals(listOf(75_000L + FIRST_CHUNK_MS + 15_000 - SEEK_BACK_MS), articleSeeks)
        assertTrue("the chunk playing was moved", chunk.seeks.isEmpty())
    }

    @Test
    fun `test that skipping forward is an article seek that stops at the end of the article`() {
        val chunk = ChunkPlayer()
        val articleSeeks = mutableListOf<Long>()
        val player = SessionPlayer(chunk) { articleSeeks.add(it) }
        player.startPlaylist(Placement(playlistStartMs = 0, durationMs = FIRST_CHUNK_MS + 20_000))

        player.seekForward()

        assertEquals(listOf(FIRST_CHUNK_MS + 20_000), articleSeeks)
        assertTrue("the chunk playing was moved", chunk.seeks.isEmpty())
    }

    @Test
    fun `test that a seek to a position is shown at once until playback is moved there`() {
        val chunk = ChunkPlayer()
        assertEquals(Player.STATE_READY, chunk.playbackState)
        val player = SessionPlayer(chunk) {}
        player.startPlaylist(Placement(playlistStartMs = 75_000))
        val positions = recordSeekPositions(player)

        player.seekTo(200_000)

        assertEquals(200_000L, player.currentPosition)
        assertEquals("the session was not told of the position", listOf(200_000L), positions)

        player.seekTo(1, 75_000L + FIRST_CHUNK_MS + 5_000)

        assertEquals(
            "the seek was still shown once it was made",
            75_000L + FIRST_CHUNK_MS + 15_000,
            player.currentPosition,
        )
    }

    @Test
    fun `test that a seek that is never made stops being shown`() {
        val player = SessionPlayer(ChunkPlayer()) {}

        player.seekTo(200_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PENDING_SEEK_TIMEOUT_MS))

        assertEquals(FIRST_CHUNK_MS + 15_000, player.currentPosition)
    }

    @Test
    fun `test that a seek into an item is taken in article time and moves the chunk`() {
        val chunk = ChunkPlayer()
        val player = SessionPlayer(chunk) {}
        player.startPlaylist(Placement(playlistStartMs = 75_000))

        player.seekTo(1, 75_000L + FIRST_CHUNK_MS + 5_000)

        assertEquals(listOf(1 to 5_000L), chunk.seeks)
    }

    @Test
    fun `test that a new playlist is started at an article position`() {
        val chunk = ChunkPlayer()
        val player = SessionPlayer(chunk) {}

        player.startPlaylist(Placement(playlistStartMs = 75_000), positionMs = 80_000)

        assertEquals(listOf(5_000L), chunk.startPositions)
    }

    @Test
    fun `test that a new playlist given as a list is started at an article position`() {
        val chunk = ChunkPlayer()
        val player = SessionPlayer(chunk) {}

        player.setMediaItems(listOf(playlistFirstItem(Placement(playlistStartMs = 75_000))), 0, 80_000)

        assertEquals(listOf(5_000L), chunk.startPositions)
    }

    @Test
    fun `test that a playlist a controller starts reaches the player at the position into its chunk`() =
        throughSession(Placement(playlistStartMs = 75_000)) { chunk, controller, _ ->
            controller.setMediaItem(chunkItem(startsAtMs = 75_000), 80_000)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(listOf(5_000L), chunk.startPositions)
        }

    @Test
    fun `test that a seek a controller makes into an item reaches the player as a position into its chunk`() =
        throughSession(Placement(playlistStartMs = 75_000)) { chunk, controller, _ ->
            controller.seekTo(1, 75_000L + FIRST_CHUNK_MS + 5_000)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(listOf(1 to 5_000L), chunk.seeks)
        }

    @Test
    fun `test that skipping back through the session is an article seek`() =
        throughSession(Placement(playlistStartMs = 75_000, durationMs = 600_000)) { chunk, controller, seeks ->
            controller.seekBack()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(listOf(75_000L + FIRST_CHUNK_MS + 15_000 - SEEK_BACK_MS), seeks)
            assertTrue("the chunk playing was moved", chunk.seeks.isEmpty())
        }

    /** A playlist's first item, placed in the article at [placement], as the controller hands it to the session. */
    private fun playlistFirstItem(placement: Placement) = chunkItem(startsAtMs = placement.playlistStartMs)

    /** Starts a playlist placed in the article at [placement], as the controller does, at [positionMs] if given. */
    private fun SessionPlayer.startPlaylist(placement: Placement, positionMs: Long? = null) {
        if (placement.durationMs > 0) setArticleDuration(placement.durationMs)
        if (positionMs == null) {
            setMediaItem(playlistFirstItem(placement))
        } else {
            setMediaItem(playlistFirstItem(placement), positionMs)
        }
    }

    /**
     * Runs [test] with a controller connected through a real session to a [SessionPlayer] at [placement], so that what
     * the session hands the player is what is tested rather than what the controller asked for.
     */
    private fun throughSession(
        placement: Placement,
        test: (chunk: ChunkPlayer, controller: MediaController, articleSeeks: List<Long>) -> Unit,
    ) {
        val chunk = ChunkPlayer()
        val articleSeeks = mutableListOf<Long>()
        val player = SessionPlayer(chunk) { articleSeeks.add(it) }
        player.startPlaylist(placement)
        chunk.startPositions.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = MediaSession.Builder(context, player).build()
        val connecting = MediaController.Builder(context, session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        val controller = connecting.get()

        try {
            test(chunk, controller, articleSeeks)
        } finally {
            controller.release()
            session.release()
            // Releasing finishes on the main looper, and a session left behind would keep its id from the next test.
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `test that an article position converts to the position into the item holding it`() {
        val chunk = ChunkPlayer()
        val player = SessionPlayer(chunk) {}
        player.startPlaylist(Placement(playlistStartMs = 75_000))
        chunk.seeks.clear()

        player.seekTo(1, 75_000L + FIRST_CHUNK_MS + 5_000)

        assertEquals(listOf(1 to 5_000L), chunk.seeks)
    }

    @Test
    fun `test that an item the player has not loaded is placed by where it starts`() {
        val player = SessionPlayer(ChunkPlayer(firstChunkLoaded = false)) {}

        player.startPlaylist(Placement(playlistStartMs = 75_000))

        assertEquals(75_000L + FIRST_CHUNK_MS + 15_000, player.currentPosition)
    }

    @Test
    fun `test that a chunk's length is not offered as the length of the article`() {
        val item = chunkItem(startsAtMs = 0)

        assertNull("the notification would show the chunk as the whole article", item.mediaMetadata.durationMs)
        assertEquals(FIRST_CHUNK_MS.milliseconds, item.length())
    }

    @Test
    fun `test that the session is told when the duration of the article changes`() {
        val player = SessionPlayer(ChunkPlayer()) {}
        var reports = 0
        player.listenedToBySession(
            object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    reports += 1
                }
            }
        )

        player.setArticleDuration(600_000)
        player.setArticleDuration(600_000)

        assertEquals("the session was not told exactly once", 1, reports)
    }

    @Test
    fun `test that buffering for a seek stops the player and shows its target as the position and the player as buffering`() {
        val chunk = ChunkPlayer()
        assertEquals(Player.STATE_READY, chunk.playbackState)
        val player = SessionPlayer(chunk) {}
        val states = recordStates(player)

        player.bufferAt(200_000)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the player was not stopped", chunk.stopped)
        assertEquals(200_000L, player.currentPosition)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertTrue("the session was not told of the buffering", states.isNotEmpty())
        assertFalse("the session was told the player stopped", Player.STATE_IDLE in states)
    }

    @Test
    fun `test that preparing the playlist a seek buffers for stops buffering and reports the player's own state`() {
        val chunk = ChunkPlayer()
        assertEquals(Player.STATE_READY, chunk.playbackState)
        val player = SessionPlayer(chunk) {}
        val states = recordStates(player)
        player.bufferAt(200_000)
        shadowOf(Looper.getMainLooper()).idle()
        states.clear()

        player.setMediaItem(chunkItem(startsAtMs = 0))
        player.prepare()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(player.articleTimeline.bufferingAtMs)
        assertEquals(FIRST_CHUNK_MS + 15_000, player.currentPosition)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse("the session was told the player stopped", Player.STATE_IDLE in states)
    }

    @Test
    fun `test that preparing before the playlist a seek buffers for is set keeps buffering`() {
        val chunk = ChunkPlayer()
        val player = SessionPlayer(chunk) {}
        player.bufferAt(200_000)
        shadowOf(Looper.getMainLooper()).idle()

        player.prepare()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the old playlist was prepared again", chunk.stopped)
        assertEquals(200_000L, player.articleTimeline.bufferingAtMs)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
    }

    @Test
    fun `test that finishing the article pauses the player and shows its end as the position`() {
        val chunk = ChunkPlayer()
        val player = SessionPlayer(chunk) {}
        player.startPlaylist(Placement(playlistStartMs = 75_000, durationMs = 600_000))
        player.play()
        shadowOf(Looper.getMainLooper()).idle()

        player.finishAt(600_000)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("the player was not paused", chunk.shouldPlay)
        assertFalse(player.playWhenReady)
        assertEquals(600_000L, player.currentPosition)
    }

    @Test
    fun `test that playing a finished article asks for its top rather than playing on, and plays once it is there`() {
        val chunk = ChunkPlayer()
        val articleSeeks = mutableListOf<Long>()
        val player = SessionPlayer(chunk) { articleSeeks.add(it) }
        player.startPlaylist(Placement(playlistStartMs = 0, durationMs = 600_000))
        player.finishAt(600_000)
        shadowOf(Looper.getMainLooper()).idle()

        player.play()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0L), articleSeeks)
        assertFalse("the audio from before the finish was played on", chunk.shouldPlay)
        assertEquals(600_000L, player.currentPosition)

        player.seekTo(0, 0)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0 to 0L), chunk.seeks)
        assertTrue("the top of the article was not played", chunk.shouldPlay)
        assertEquals(FIRST_CHUNK_MS + 15_000, player.currentPosition)
    }

    @Test
    fun `test that a play on a finished article is shown as playing while its start is loading`() {
        val player = SessionPlayer(ChunkPlayer()) {}
        player.startPlaylist(Placement(playlistStartMs = 0, durationMs = 600_000))
        player.finishAt(600_000)
        shadowOf(Looper.getMainLooper()).idle()

        player.play()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the play asked for was not shown", player.playWhenReady)
    }

    @Test
    fun `test that playing a finished article plays its top once it is made again`() {
        val chunk = ChunkPlayer()
        val player = SessionPlayer(chunk) {}
        player.startPlaylist(Placement(playlistStartMs = 0, durationMs = 600_000))
        player.finishAt(600_000)
        player.play()
        shadowOf(Looper.getMainLooper()).idle()

        player.bufferAt(0)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the player made a sound while the top was made", chunk.stopped)
        assertEquals(0L, player.currentPosition)

        player.setMediaItem(chunkItem(startsAtMs = 0))
        player.prepare()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the top of the article was not played", chunk.shouldPlay)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `test that a new playlist stops showing the end of a finished article`() {
        val player = SessionPlayer(ChunkPlayer()) {}
        player.finishAt(600_000)

        player.startPlaylist(Placement(playlistStartMs = 0, durationMs = 600_000))

        assertEquals(FIRST_CHUNK_MS + 15_000, player.currentPosition)
    }

    @Test
    fun `test that everything else the player reports is passed on unchanged`() {
        val chunk = ChunkPlayer()
        assertEquals(Player.STATE_READY, chunk.playbackState)
        val player = SessionPlayer(chunk) {}
        val states = mutableListOf<Int>()
        var batches = 0
        player.listenedToBySession(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    states.add(playbackState)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    batches += 1
                }
            }
        )

        chunk.stopAndReport()

        assertEquals(listOf(Player.STATE_IDLE), states)
        assertEquals(1, batches)
    }

    @Test
    fun `test that a listener that was removed is told nothing more`() {
        val chunk = ChunkPlayer()
        assertEquals(Player.STATE_READY, chunk.playbackState)
        val player = SessionPlayer(chunk) {}
        val states = mutableListOf<Int>()
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    states.add(playbackState)
                }
            }
        player.addListener(listener)

        player.removeListener(listener)
        chunk.stopAndReport()

        assertTrue(states.isEmpty())
    }

    @Test
    fun `test that a new playlist is started at the position into its first item`() {
        val chunk = ChunkPlayer()
        val player = SessionPlayer(chunk) {}
        player.setArticleDuration(600_000)

        player.setMediaItems(listOf(chunkItem(startsAtMs = 75_000)), 0, 80_000)

        assertEquals(ArticleTimeline(durationMs = 600_000), player.articleTimeline)
        assertEquals("the start was converted before the playlist was placed", listOf(5_000L), chunk.startPositions)
    }

    @Test
    fun `test that each item says where it starts in the article`() {
        val item = chunkItem(startsAtMs = 75_000)

        assertEquals(75_000L.milliseconds, item.startsAt())
    }
}

/**
 * Listens to [this] the way the session does, which reads the player as soon as it has it. A player that has not been
 * read has nothing to report a change from.
 */
private fun Player.listenedToBySession(listener: Player.Listener) {
    addListener(listener)
    playbackState
}

/** Every playback state [player] reports, in order. */
private fun recordStates(player: Player): MutableList<Int> {
    val states = mutableListOf<Int>()
    player.listenedToBySession(
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                states.add(playbackState)
            }
        }
    )
    return states
}

/** Every position [player] reports a seek moved it to, in order. */
private fun recordSeekPositions(player: Player): MutableList<Long> {
    val positions = mutableListOf<Long>()
    player.listenedToBySession(
        object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) positions.add(newPosition.positionMs)
            }
        }
    )
    return positions
}

/**
 * A player 15 seconds into the second of two chunks, which records what it is asked to move to.
 *
 * @param firstChunkLoaded Whether the player knows how long the first chunk is.
 */
@OptIn(UnstableApi::class)
private class ChunkPlayer(private val firstChunkLoaded: Boolean = true) : SimpleBasePlayer(Looper.getMainLooper()) {
    val seeks = mutableListOf<Pair<Int, Long>>()
    val startPositions = mutableListOf<Long>()
    var stopped = false
        private set

    var shouldPlay = false
        private set

    // Where the playlist this player was last given starts in the article. Its own two items follow on from there.
    private var firstStartsAtMs = 0L

    /** Stops the player, as buffering for a seek does, and reports it. */
    fun stopAndReport() {
        stopped = true
        invalidateState()
        shadowOf(Looper.getMainLooper()).idle()
    }

    override fun getState(): State =
        State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("first")
                        .setMediaItem(chunkItem(startsAtMs = firstStartsAtMs))
                        .apply { if (firstChunkLoaded) setDurationUs(FIRST_CHUNK_MS * 1_000) }
                        .build(),
                    MediaItemData.Builder("second")
                        .setMediaItem(
                            chunkItem(startsAtMs = firstStartsAtMs + FIRST_CHUNK_MS, lengthMs = SECOND_CHUNK_MS)
                        )
                        .setDurationUs(SECOND_CHUNK_MS * 1_000)
                        .build(),
                )
            )
            .setCurrentMediaItemIndex(1)
            .setPlaybackState(if (stopped) Player.STATE_IDLE else Player.STATE_READY)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setContentPositionMs(15_000)
            .setPlayWhenReady(shouldPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        shouldPlay = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        stopped = true
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        stopped = false
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        seeks.add(mediaItemIndex to positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        startPositions.add(startPositionMs)
        mediaItems.firstOrNull()?.let { firstStartsAtMs = it.startsAt().inWholeMilliseconds }
        return Futures.immediateVoidFuture()
    }
}

/** A chunk's item, as the controller hands it to the session, starting [startsAtMs] into the article. */
private fun chunkItem(startsAtMs: Long, lengthMs: Long = FIRST_CHUNK_MS) =
    PlayableItem(0, File("chunk"), startsAtMs.milliseconds, lengthMs.milliseconds).toMediaItem(ArticleDisplayData())

/** Where a test's playlist starts in the article, and how long the article is shown to last. */
private data class Placement(val playlistStartMs: Long = 0, val durationMs: Long = 0)

private const val FIRST_CHUNK_MS = 40_000L
private const val SECOND_CHUNK_MS = 30_000L
private const val SEEK_BACK_MS = 10_000L
private const val SEEK_FORWARD_MS = 30_000L
