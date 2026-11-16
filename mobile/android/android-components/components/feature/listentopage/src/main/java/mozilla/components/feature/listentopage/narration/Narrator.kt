/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import mozilla.components.feature.listentopage.narration.NarrationPhase.Active
import mozilla.components.feature.listentopage.playback.PlaybackController
import mozilla.components.feature.listentopage.playback.PlaybackEvent
import mozilla.components.feature.listentopage.playback.PlaybackPhase
import mozilla.components.feature.listentopage.progress.ArticleProgress
import mozilla.components.feature.listentopage.progress.ChunkTimeline
import mozilla.components.feature.listentopage.synthesis.TextChunker
import mozilla.components.feature.listentopage.synthesis.audio.AudioFileCache
import mozilla.components.feature.listentopage.voices.Voice

/**
 * Reads an article out. It is a loop: each input goes through [reduceNarration], which decides what the narration now
 * holds and what has to be done, and the effects it asks for are carried out here and by the session's
 * [NarrationEffectRunner], whose results come back as inputs. It gives out [state] and [events] rather than writing to
 * the store.
 *
 * It lives as long as the store. It runs on one thread, which is what lets the loop take one input at a time.
 *
 * @param engines Opens each session's speech engine, which the voice selection asks for voices too.
 * @param audioCache Holds the audio files. It is emptied when the session stops.
 * @param playback Plays the audio files. It is used instead of the player directly, because only playback commanded
 *   through the media session keeps the audio alive in the background and shows the notification.
 * @param scope The [CoroutineScope] the narration runs in. It has to dispatch on one thread.
 * @param ioDispatcher The dispatcher for the work that must not run on the narrator's thread.
 * @param chunker Splits article text into the chunks.
 */
internal class Narrator(
    private val engines: SessionEngines,
    private val audioCache: AudioFileCache,
    private val playback: PlaybackController,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val chunker: TextChunker = TextChunker.android(),
) {
    private val _state = MutableStateFlow(NarrationState())
    val state: StateFlow<NarrationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<NarrationEvent>(extraBufferCapacity = Int.MAX_VALUE)
    val events: Flow<NarrationEvent> = _events.asSharedFlow()

    /** What the narration holds. Only [step] changes it. */
    @VisibleForTesting
    internal var model = NarrationModel()
        private set

    private var resources: SessionResources? = null

    // The end of every session so far, chained, so that a new session waits for the one before it to let go.
    private var teardown: Job? = null

    // Inputs given while one is being reduced, which wait for it.
    private val pending = ArrayDeque<NarrationInput>()
    private var isReducing = false

    /** Asks the narration to do [command]. */
    fun send(command: NarrationCommand) {
        when (command) {
            is NarrationCommand.StartSession -> startSession(command.tabId)
            NarrationCommand.EndSession -> endSession()
            else -> dispatch(command)
        }
    }

    /** Whether [voice] would change what the session reads in, which it does not for the voice reading. */
    fun changesVoice(voice: Voice): Boolean = (model.phase as? Active)?.changesVoice(voice) == true

    /** Ends the session, and returns once it has let go of the player, the engine and the audio. */
    suspend fun shutDown() {
        endSession()
        teardown?.join()
    }

    /** Runs [work] for as long as the session lasts. */
    fun inSession(work: suspend () -> Unit): Job = resources?.scope?.launch { work() } ?: Job().apply { complete() }

    /** Starts a session reading [tabId], which watches the player until it ends. */
    private fun startSession(tabId: String) {
        endSession()
        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        val engine = engines.open()
        // What the session's own work hands back, which is dropped once the session has ended.
        val dispatchWhileRunning: (NarrationInput) -> Unit = { input ->
            if (resources?.scope === sessionScope) dispatch(input)
        }
        val runner =
            NarrationEffectRunner(
                sessionScope,
                teardown,
                engine,
                playback,
                audioCache,
                chunker,
                ioDispatcher,
                session = { model.phase as? Active },
                dispatch = dispatchWhileRunning,
            )
        resources = SessionResources(sessionScope, runner)
        dispatch(NarrationCommand.StartSession(tabId))

        // Since playback can be controlled through the notification, or by other audio, what it does cannot be worked
        // out from our own commands. The player is only watched once the session before has let go of it, and from the
        // first thing it does after that, so that nothing it reported for that session is read as this one's.
        val previousTeardown = teardown
        sessionScope.launch {
            previousTeardown?.join()
            launch {
                playback.status
                    .dropWhile { it.phase == PlaybackPhase.Idle }
                    .collect { dispatchWhileRunning(NarrationInput.PlayerReported(it)) }
            }
            playback.events.collect { event ->
                dispatchWhileRunning(
                    when (event) {
                        is PlaybackEvent.SeekRequested -> NarrationInput.NotificationSeek(event.position)
                        PlaybackEvent.SessionEnded -> NarrationInput.PlayerSessionEnded
                    }
                )
            }
        }
    }

    /**
     * Ends the session: cancels everything it has in flight, waits for the engine request in flight, gives up the
     * playback, closes the engine and deletes the audio, in that order. This also takes the notification away.
     */
    private fun endSession() {
        val ending = resources
        resources = null
        val endingEngine = engines.detach()
        // Cancelled at once, so that nothing the session runs reaches the next one. The teardown below still waits for
        // it to stop, which is when the engine request in flight has finished.
        val endingJob = ending?.scope?.coroutineContext?.job?.also { it.cancel() }
        dispatch(NarrationCommand.EndSession)

        val previous = teardown
        teardown = scope.launch {
            previous?.join()
            endingJob?.join()

            playback.release()

            // The engine is an IPC binding into the text to speech app, which stays alive for the rest of this
            // app's life unless it is shut down. Closing is terminal, which is why the next session builds its own.
            endingEngine?.close()

            // Nothing else deletes the audio, so without this a session leaves its files behind for as long as the
            // system keeps the cache directory. Only safe once the playback above has given up the files.
            audioCache.clear()
        }
    }

    /** Puts the session at [progress], placed by [timeline], as if the player had reported it. */
    @VisibleForTesting
    internal fun placeAt(progress: ArticleProgress, timeline: ChunkTimeline) {
        val session = model.phase as? Active
        model =
            model.copy(
                phase = session?.copy(position = session.position.copy(timeline = timeline)) ?: model.phase,
                progress = progress,
            )
        publish()
    }

    /** Reduces [input], or queues it behind the input being reduced, so that inputs are taken one at a time. */
    private fun dispatch(input: NarrationInput) {
        pending.addLast(input)
        if (isReducing) return

        isReducing = true
        try {
            while (pending.isNotEmpty()) step(pending.removeFirst())
        } finally {
            isReducing = false
        }
    }

    private fun step(input: NarrationInput) {
        val next = reduceNarration(model, input)
        model = next.model
        publish()
        next.effects.forEach(::execute)
    }

    private fun publish() {
        _state.value = NarrationState(progress = model.progress)
    }

    private fun execute(effect: NarrationEffect) {
        when (effect) {
            is NarrationEffect.Emit -> _events.tryEmit(effect.event)
            is PlayerEffect -> command(effect)
            is SessionEffect -> resources?.runner?.run(effect)
        }
    }

    /**
     * Sends [effect] to the player, in the order the commands were given. A command once the session has ended is
     * dropped: sent then, it would start the playback, and its notification, again after the session let go of it.
     */
    private fun command(effect: PlayerEffect) {
        val sessionScope = resources?.scope ?: return
        sessionScope.launch {
            when (effect) {
                is PlayerEffect.StartArticle -> playback.startArticle(effect.item, effect.displayData, effect.tabId)
                is PlayerEffect.Enqueue -> playback.enqueue(effect.item)
                is PlayerEffect.Restart -> playback.restartAt(effect.item, effect.at)
                is PlayerEffect.StopAndBufferAt -> playback.stopAndBufferAt(effect.at)
                is PlayerEffect.FinishAt -> playback.finishAt(effect.at)
                is PlayerEffect.SetArticleDuration -> playback.setArticleDuration(effect.length)
                PlayerEffect.Pause -> playback.pause()
                PlayerEffect.Resume -> playback.resume()
                is PlayerEffect.SetSpeed -> playback.setSpeed(effect.speed)
            }
        }
    }

    /** What a running session holds outside the model: the coroutines it runs and what carries out its work. */
    private class SessionResources(val scope: CoroutineScope, val runner: NarrationEffectRunner)
}
