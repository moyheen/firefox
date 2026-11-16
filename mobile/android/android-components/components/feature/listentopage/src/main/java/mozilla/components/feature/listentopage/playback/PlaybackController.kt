/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mozilla.components.feature.listentopage.R
import mozilla.components.support.ktx.android.content.appName

/**
 * What the playback notification and the lock screen say is being read out.
 *
 * @property title The article title, or `null` when the page has none, which [withAppNameIfUntitled] then fills in.
 * @property site Where the article is from.
 * @property isPrivate Whether the article is from a private tab, which [hiddenIfPrivate] then hides.
 */
data class ArticleDisplayData(val title: String? = null, val site: String? = null, val isPrivate: Boolean = false)

/** [privateTitle] and [privateSite] in place of the title and site of an article from a private tab. */
internal fun ArticleDisplayData.hiddenIfPrivate(privateTitle: String, privateSite: String): ArticleDisplayData =
    if (isPrivate) copy(title = privateTitle, site = privateSite) else this

/**
 * [appName] as the title where the page gave none.
 *
 * Media3 passes a null title straight to the notification, which leaves its title line empty. The name is put on here
 * rather than where the article is described, so that the store keeps saying the page itself had no title.
 */
internal fun ArticleDisplayData.withAppNameIfUntitled(appName: String): ArticleDisplayData =
    if (title != null) this else copy(title = appName)

/** How often the position is sampled while playing. It is only published once it reaches a new second. */
internal const val POSITION_SAMPLE_INTERVAL_MS = 500L

/** Commands the playback of the synthesized audio. */
interface PlaybackController {
    /**
     * What the playback is doing, as the player reports it.
     *
     * It holds a default [PlaybackStatus] while no playback is connected.
     */
    val status: StateFlow<PlaybackStatus>

    /** What happened to the playback that no command asked for. */
    val events: Flow<PlaybackEvent>
        get() = emptyFlow()

    /** Tells the notification how long the article is shown to last. Does nothing while no playback is connected. */
    suspend fun setArticleDuration(duration: Duration)

    /**
     * Starts reading an article out from [item], its opening, replacing anything already playing, as
     * [articleDisplayData] describes it.
     *
     * @param tabId The tab the article is from, which tapping the notification opens.
     */
    suspend fun startArticle(item: PlayableItem, articleDisplayData: ArticleDisplayData, tabId: String)

    /** Adds [item] to the end of what is queued, to be read out once the rest has been. */
    suspend fun enqueue(item: PlayableItem)

    /** Pauses playback, keeping the position. */
    suspend fun pause()

    /** Resumes playback from the position it was paused at. */
    suspend fun resume()

    /**
     * Moves playback to [position] into the article, inside [chunkIndex].
     *
     * @return Whether the player holds the chunk at [chunkIndex]. When it does not, nothing moved, and playback has to
     *   be restarted there.
     */
    suspend fun seekTo(chunkIndex: Int, position: Duration): Boolean

    /** Drops what is queued and starts again on [item] at [position] into the article. */
    suspend fun restartAt(item: PlayableItem, position: Duration)

    /**
     * Stops the audio and reports buffering at [position] into the article until [restartAt] starts the playlist there,
     * for a seek whose chunk has to be made first.
     */
    suspend fun stopAndBufferAt(position: Duration)

    /**
     * Stops the audio where a seek finished the article, and shows [position], its end, until playback is moved or
     * started again. A play from outside the app, such as the notification, then starts the article again from the top.
     */
    suspend fun finishAt(position: Duration)

    /** Reads the rest of the article out at [speed]. */
    suspend fun setSpeed(speed: PlaybackSpeed)

    /** Gives up the playback, which takes the notification away. A later call starts it again. */
    suspend fun release()
}

/**
 * A [PlaybackController] that drives [ListenMediaSessionService] through a [MediaController].
 *
 * This is the client half of the media session, and the only place in the module that holds a [MediaController]. It
 * exists so that a caller can drive playback without naming a media3 type, which a caller in Fenix cannot resolve.
 *
 * A [MediaController] may only be used on the main thread, so every method here moves to it. The connection is made on
 * first use and reused afterwards.
 *
 * @param context Used to connect to the session.
 * @param scope The [CoroutineScope] the connection is held in.
 * @param sessionToken Finds the session to connect to.
 * @param sessionActivity Is lambda to construct an intent to open the tab on notification click.
 */
class ListenPlaybackController
internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sessionToken: suspend () -> SessionToken,
    private val sessionActivity: (tabId: String) -> PendingIntent? = { null },
) : PlaybackController {

    /**
     * Connects to [ListenMediaSessionService].
     *
     * @param context Used to connect to [ListenMediaSessionService].
     * @param scope The [CoroutineScope] the connection is held in.
     * @param sessionActivity Is lambda to construct an intent to open the tab on notification click.
     * @param ioDispatcher The dispatcher the service lookup runs on.
     */
    constructor(
        context: Context,
        scope: CoroutineScope,
        sessionActivity: (tabId: String) -> PendingIntent? = { null },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        context = context,
        scope = scope,
        sessionToken = {
            // The SessionToken constructor asks the package manager to resolve the service, which is a blocking IPC.
            withContext(ioDispatcher) {
                SessionToken(context, ComponentName(context, ListenMediaSessionService::class.java))
            }
        },
        sessionActivity = sessionActivity,
    )

    // Main thread only, like the controller it produces.
    private var connection: Deferred<MediaController>? = null

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _events =
        MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    override suspend fun setArticleDuration(duration: Duration) {
        withContext(Dispatchers.Main) {
            // Never connects: a report that arrives after the playback was released must not start it again.
            val connected = connection ?: return@withContext
            val controller = runCatching { connected.await() }.getOrNull() ?: return@withContext
            controller.sendArticleDuration(duration)
        }
    }

    // Main thread only, like the controller it samples.
    private var positionJob: Job? = null

    // What the article now playing says it is, kept so that every chunk of it is queued saying the same thing.
    private var displayData: ArticleDisplayData? = null

    private val appName: String by lazy { context.appName }

    private val privateTitle: String by lazy {
        context.getString(R.string.mozac_feature_listentopage_private_tab_title)
    }

    private val privateSite: String by lazy {
        context.getString(R.string.mozac_feature_listentopage_private_tab_site)
    }

    private val playerListener =
        object : Player.Listener {
            // onEvents rather than the individual callbacks: it runs once per batch of changes, where the separate
            // callbacks would each publish a snapshot of the same batch.
            override fun onEvents(player: Player, events: Player.Events) {
                _status.value = player.toPlaybackStatus()
                samplePosition(player)
            }
        }

    override suspend fun startArticle(item: PlayableItem, articleDisplayData: ArticleDisplayData, tabId: String) =
        onController {
            // Published before the command, so that a report the previous session left behind cannot be read as this
            // session's in the time it takes the player to report for itself.
            _status.value =
                PlaybackStatus(
                    phase = PlaybackPhase.Buffering,
                    speed = PlaybackSpeed.nearest(it.playbackParameters.speed),
                )

            val shownDisplayData =
                articleDisplayData.hiddenIfPrivate(privateTitle, privateSite).withAppNameIfUntitled(appName)
            displayData = shownDisplayData

            it.sendCustomCommand(
                SessionCommand(COMMAND_SET_SESSION_ACTIVITY, Bundle.EMPTY),
                sessionActivity(tabId).toSessionActivityArgs(),
            )

            // A new article starts at its top, with no length until one is worked out.
            it.sendArticleDuration(Duration.ZERO)
            it.setMediaItem(item.toMediaItem(shownDisplayData))
            it.prepare()
            it.play()
        }

    override suspend fun enqueue(item: PlayableItem) = onController {
        val displayData = this.displayData ?: return@onController
        it.addMediaItem(item.toMediaItem(displayData))

        if (it.playbackState == Player.STATE_ENDED) {
            it.seekTo(it.mediaItemCount - 1, it.currentPosition)
        }
    }

    override suspend fun pause() = onController { it.pause() }

    override suspend fun resume() = onController {
        it.prepare()
        it.play()
    }

    override suspend fun seekTo(chunkIndex: Int, position: Duration): Boolean {
        var hasChunk = false
        onController {
            val item =
                (0 until it.mediaItemCount).firstOrNull { index -> it.getMediaItemAt(index).chunkIndex() == chunkIndex }
            if (item != null) {
                it.seekTo(item, position.inWholeMilliseconds)
                hasChunk = true
            }
        }
        return hasChunk
    }

    override suspend fun restartAt(item: PlayableItem, position: Duration) = onController {
        val displayData = this.displayData ?: return@onController

        // The position goes in with the item rather than as a seek afterwards, which would let the player start at
        // the top of the chunk and be moved off it a moment later.
        it.setMediaItem(item.toMediaItem(displayData), position.inWholeMilliseconds)

        // Prepared but not played: a player that was paused stays paused.
        it.prepare()
    }

    override suspend fun finishAt(position: Duration) = onController {
        it.sendCustomCommand(
            SessionCommand(COMMAND_FINISH_AT, Bundle.EMPTY),
            Bundle().apply { putLong(KEY_POSITION_MS, position.inWholeMilliseconds) },
        )
    }

    // The session stops the player itself once it has the position to buffer at.
    override suspend fun stopAndBufferAt(position: Duration) = onController {
        it.sendCustomCommand(
            SessionCommand(COMMAND_STOP_AND_BUFFER_AT, Bundle.EMPTY),
            Bundle().apply { putLong(KEY_POSITION_MS, position.inWholeMilliseconds) },
        )
    }

    override suspend fun setSpeed(speed: PlaybackSpeed) = onController { it.setPlaybackSpeed(speed.multiplier) }

    override suspend fun release() {
        withContext(Dispatchers.Main) {
            val released = connection ?: return@withContext
            connection = null
            forgetStatus()

            // A connection that failed has no controller to release.
            val controller = runCatching { released.await() }.getOrNull() ?: return@withContext

            controller.stop()
            controller.clearMediaItems()
            controller.release()
        }
    }

    /**
     * Keeps [status] a second fresh while the audio plays, and stops sampling when it does not.
     *
     * The player reports the events but pushes no position, so the position has to be read. Only whole-second changes
     * are published.
     */
    private fun samplePosition(player: Player) {
        if (!player.isPlaying) {
            positionJob?.cancel()
            positionJob = null
            return
        }

        if (positionJob?.isActive == true) return

        positionJob =
            scope.launch(Dispatchers.Main) {
                while (true) {
                    // We sample faster than is required for publishing to ensure that data is up to date.
                    delay(POSITION_SAMPLE_INTERVAL_MS.milliseconds)

                    val sampled = player.currentPosition
                    _status.update {
                        if (it.position.inWholeMilliseconds / MS_PER_SECOND == sampled / MS_PER_SECOND) {
                            it
                        } else {
                            it.copy(position = sampled.milliseconds)
                        }
                    }
                }
            }
    }

    /** Drops what the last connection was doing, so that nobody is left watching a session that is gone. */
    private fun forgetStatus() {
        positionJob?.cancel()
        positionJob = null
        displayData = null
        _status.value = PlaybackStatus()
    }

    private suspend fun onController(command: (MediaController) -> Unit) =
        withContext(Dispatchers.Main) {
            command(connection?.await() ?: newConnection().await())
        }

    /**
     * Starts connecting, and keeps the attempt so that later commands reuse it.
     *
     * A failed attempt drops itself rather than being cached, because otherwise the first failure is replayed to every
     * later command and the session can never recover without restarting the process.
     */
    private fun newConnection(): Deferred<MediaController> =
        scope
            .async(Dispatchers.Main) { connect() }
            .also { connecting ->
                connection = connecting
                connecting.invokeOnCompletion { failure ->
                    if (failure != null && connection === connecting) {
                        connection = null
                    }
                }
            }

    private suspend fun connect(): MediaController {
        val token = sessionToken()
        val pending =
            MediaController.Builder(context, token)
                .setListener(
                    object : MediaController.Listener {
                        // The session goes away on its own, so a kept controller can go stale: media3 stops the
                        // service once the article ends with nobody commanding it. Commands sent to a disconnected
                        // controller are dropped without a word, so the next request has to build a new one.
                        override fun onDisconnected(controller: MediaController) {
                            // A connection still kept here means the session ended without being asked to.
                            val isConnected = connection != null
                            connection = null
                            forgetStatus()
                            if (isConnected) _events.tryEmit(PlaybackEvent.SessionEnded)
                        }

                        override fun onCustomCommand(
                            controller: MediaController,
                            command: SessionCommand,
                            args: Bundle,
                        ): ListenableFuture<SessionResult> {
                            if (command.customAction == COMMAND_SEEK_ARTICLE) {
                                _events.tryEmit(PlaybackEvent.SeekRequested(args.getLong(KEY_POSITION_MS).milliseconds))
                            }
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                    }
                )
                .buildAsync()

        val controller = suspendCancellableCoroutine { continuation ->
            // The listener only runs once the future is done, so `get` returns rather than waits. Whichever
            // terminal state it reports, success, failure or cancellation, belongs to the coroutine that is waiting
            // for it.
            pending.addListener(
                { continuation.resumeWith(runCatching { pending.get() }) },
                ContextCompat.getMainExecutor(context),
            )

            continuation.invokeOnCancellation { MediaController.releaseFuture(pending) }
        }

        controller.addListener(playerListener)
        _status.value = controller.toPlaybackStatus()

        return controller
    }

    private companion object {
        const val MS_PER_SECOND = 1000L
    }
}

private fun Player.toPlaybackStatus() =
    PlaybackStatus(
        phase = toPlaybackPhase(),
        chunkIndex = currentMediaItem?.chunkIndex() ?: currentMediaItemIndex,
        chunkLength = currentMediaItem?.measuredLength(),
        position = currentPosition.milliseconds,
        speed = PlaybackSpeed.nearest(playbackParameters.speed),
    )

/** Tells the session how long the article is shown to last. */
private fun MediaController.sendArticleDuration(duration: Duration) {
    sendCustomCommand(
        SessionCommand(COMMAND_SET_ARTICLE_DURATION, Bundle.EMPTY),
        Bundle().apply { putLong(KEY_DURATION_MS, duration.inWholeMilliseconds) },
    )
}

/** The phase the player is in. */
internal fun Player.toPlaybackPhase() =
    when {
        playerError != null -> PlaybackPhase.Failed
        playbackState == Player.STATE_IDLE -> PlaybackPhase.Idle
        playbackState == Player.STATE_ENDED -> PlaybackPhase.Ended
        playbackState == Player.STATE_BUFFERING && playWhenReady -> PlaybackPhase.Buffering
        isPlaying -> PlaybackPhase.Playing
        else -> PlaybackPhase.Paused
    }
