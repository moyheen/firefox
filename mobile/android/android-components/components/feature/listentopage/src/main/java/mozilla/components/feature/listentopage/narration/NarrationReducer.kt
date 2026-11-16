/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import mozilla.components.feature.listentopage.narration.NarrationPhase.Active
import mozilla.components.feature.listentopage.playback.PlayableItem
import mozilla.components.feature.listentopage.playback.PlaybackPhase
import mozilla.components.feature.listentopage.playback.PlaybackStatus
import mozilla.components.feature.listentopage.progress.ArticleMeasurements
import mozilla.components.feature.listentopage.progress.ArticleProgress
import mozilla.components.feature.listentopage.progress.ArticleTime
import mozilla.components.feature.listentopage.progress.offsetInMeasuredChunk

/** The least audio a seek can leave in its chunk without the next one made first. */
private const val MIN_AUDIO_AFTER_SEEK_MS = 2_000L

/**
 * The narration after an input: what it now knows, and what it asks to be done, in order.
 *
 * @property model What the narrator now knows.
 * @property effects What it asks to be done.
 */
internal data class Next(val model: NarrationModel, val effects: List<NarrationEffect> = emptyList())

/**
 * Reduces [input] and [model] into the next model and the effects it asks for. It decides everything the narrator does,
 * and does none of it: it neither waits nor calls anything outside itself.
 */
internal fun reduceNarration(model: NarrationModel, input: NarrationInput): Next {
    val step = Step(model)
    when (input) {
        is NarrationCommand.StartSession -> {
            step.phase = Active(input.tabId)
            step.progress = ArticleProgress()
        }
        NarrationCommand.EndSession -> {
            step.phase = NarrationPhase.Idle
            step.progress = ArticleProgress()
        }
        NarrationCommand.WaitForOpening -> step.emit(NarrationEvent.Waiting)
        is NarrationCommand.Seek -> step.seek(input.fraction)
        is NarrationCommand.TogglePlayPause -> step.togglePlayPause(input.phase)
        is NarrationCommand.SetSpeed -> step.effect(PlayerEffect.SetSpeed(input.speed))
        NarrationCommand.Pause -> step.effect(PlayerEffect.Pause)
        else -> step.session?.let { step.reduceInSession(input) }
    }
    return step.next()
}

/** Reduces [input], which only means something while a session is running. */
private fun Step.reduceInSession(input: NarrationInput) {
    when (input) {
        is NarrationCommand.Read -> update { copy(article = input.article) }
        is NarrationCommand.VoiceChosen -> update { copy(voice = input.voice) }
        NarrationCommand.Start -> active.article?.let { synthesizeAndPlay(it, textFraction = null) }
        is NarrationCommand.ChangeVoice -> if (active.changesVoice(input.voice)) changeVoice(input)
        is NarrationCommand.Skip -> skip(input.offset)
        is NarrationInput.PlayerReported -> report(input.report)
        is NarrationInput.NotificationSeek ->
            progress.fractionAt(input.position.inWholeMilliseconds)?.let {
                emit(NarrationEvent.SeekFromNotification(it))
            }
        NarrationInput.PlayerSessionEnded -> emit(NarrationEvent.EndedOutsideApp)
        is NarrationInput.ReadingStarted -> update { copy(reading = ReadingModel(input.readingId)) }
        is NarrationInput.ChunkMeasured ->
            updateReading(input.readingId) { copy(measurements = measurements.measured(input.index, input.length)) }
        is NarrationInput.OpeningMade -> openingMade(input)
        is NarrationInput.TextChunkMade -> if (holds(input.claimId)) placeAtText(input.textFraction)
        NarrationInput.StoppedForNewVoice -> emit(NarrationEvent.Waiting)
        is NarrationInput.VoiceSet -> active.article?.let { synthesizeAndPlay(it, input.textFraction) }
        is NarrationInput.MadeFound -> if (active.reading?.id == input.readingId) enqueue(input.index, input.file)
        is NarrationInput.SeekedWithin -> seekedWithin(input)
        is NarrationInput.RestartChunkMade -> restartChunkMade(input)
        is NarrationInput.NextChunkMade -> claimOf<ClaimedWork.Restart>(input.claimId)?.let { finishRestart(it) }
        is NarrationInput.WorkFailed -> workFailed(input)
        is NarrationCommand.StartSession,
        NarrationCommand.EndSession,
        NarrationCommand.WaitForOpening,
        is NarrationCommand.Seek,
        is NarrationCommand.TogglePlayPause,
        NarrationCommand.Pause,
        is NarrationCommand.SetSpeed -> Unit
    }
}

/** Reads what [report] means for the article, and answers it. */
private fun Step.report(report: PlaybackStatus) {
    update { copy(playlist = playlist.received(report)) }
    val read = active.playlist.lastReadReport ?: return
    active.reading?.let { reading ->
        val timeline = reading.measurements.timeline(active.playlist.frozenLengths)
        val isFinished = read.phase == PlaybackPhase.Ended && reading.measurements.isLastChunk(read.chunkIndex)
        progress =
            progress.next(
                ArticleTime(read.position.inWholeMilliseconds),
                timeline,
                isFinished,
                active.position.afterSeek,
            )
        update { copy(position = position.copy(timeline = timeline, afterSeek = false)) }
    }

    sendShownLength()
    when {
        read.phase == PlaybackPhase.Failed -> recoverOrReportFailure()
        read.phase == PlaybackPhase.Ended -> refillOrEnd()

        // A chunk the player was not on before is one it has moved on to, having had it ready.
        read.phase == PlaybackPhase.Playing && read.chunkIndex != active.playlist.playingChunkIndex -> {
            update { copy(playlist = playlist.playing(read.chunkIndex)) }
            emit(NarrationEvent.Started(read))
            active.reading?.let { effect(SessionEffect.WorkAhead(it.id, playingChunkIndex = null)) }
        }

        else -> emit(NarrationEvent.Observed(read))
    }
}

/** Synthesizes the opening of [article], plays it, and works ahead, or carries on from [textFraction] of its text. */
private fun Step.synthesizeAndPlay(article: Article, textFraction: Double?) {
    update { copy(playlist = Playlist(), recovery = RecoveryAttempts()) }
    val claim = claim(Priority.Restart, ClaimedWork.Opening) ?: return
    effect(SessionEffect.StartReading(newId(), claim.id, article, textFraction))
}

private fun Step.openingMade(input: NarrationInput.OpeningMade) {
    if (!holds(input.claimId)) return
    updateReading(input.readingId) {
        copy(
            measurements =
                measurements.copy(
                    chunkCharacters = input.chunkCharacters,
                    secondsPerCharacter = input.secondsPerCharacter,
                )
        )
    }
    val reading = active.reading ?: return
    val article = active.article ?: return

    if (input.textFraction == null) {
        val item = reading.measurements.itemOf(0, input.opening, Duration.ZERO)
        update { copy(position = position.copy(shownLengthSent = Duration.ZERO)) }
        effect(PlayerEffect.StartArticle(item, article.displayData, article.tabId))
        update { copy(playlist = playlist.queued(0, item.length), claim = null) }
        // Runs ahead of the opening while it plays, so the chunk after it is queued behind it rather than started when
        // the opening ends. A chunk that arrives after the opening has finished cannot be joined onto it.
        effect(SessionEffect.WorkAhead(reading.id, playingChunkIndex = 0))
    } else {
        val textChunk = reading.measurements.chunkAtText(input.textFraction)
        if (textChunk == null) {
            placeAtText(input.textFraction)
        } else {
            effect(SessionEffect.MakeTextChunk(reading.id, input.claimId, textChunk, input.textFraction))
        }
    }
}

/** Places the article at [textFraction] of its text, where a new voice carries on, and restarts there. */
private fun Step.placeAtText(textFraction: Double) {
    val reading = active.reading ?: return
    val (placed, timeline) = reading.measurements.progressAtText(textFraction)
    progress = placed
    update { copy(position = position.copy(timeline = timeline), claim = null) }

    sendShownLength()
    if (progress.durationMs > 0 && progress.positionMs >= progress.durationMs) return finishArticle()

    val target = timeline.toChunkPosition(ArticleTime(progress.positionMs)) ?: return
    val claim = claim(Priority.Seek, ClaimedWork.Restart(target, timeline)) ?: return
    startSeek(timeline.toArticleTime(target), target.chunkIndex)

    // Always made and restarted on, since the player still holds the chunks of the voice before.
    restartAt(claim, ClaimedWork.Restart(target, timeline), discardFirst = false)
}

/**
 * Reads the article out again in [ChangeVoice.voice], from the same place in the text. The audio already made is in the
 * voice before, so it is thrown away.
 */
private fun Step.changeVoice(input: NarrationCommand.ChangeVoice) {
    val textFraction =
        active.reading
            ?.measurements
            ?.textFractionAt(ArticleTime(progress.positionMs), active.position.timeline)
            ?.takeIf { progress.durationMs > 0 }

    // The voice before stops at once, rather than reading on until the new one is ready.
    if (textFraction != null) effect(SessionEffect.StopForNewVoice(progress.positionMs.milliseconds))

    update { copy(voice = input.voice, reading = null, playlist = playlist.cleared()) }
    effect(SessionEffect.CancelSynthesis)
    effect(SessionEffect.ChangeVoice(input.voice, textFraction))
}

/** Seeks to [fraction] of the article, where the reader let go of the bar. */
private fun Step.seek(fraction: Float) {
    val before = progress
    progress = before.seekedTo(fraction)
    if (session == null) {
        // There is nothing to seek in, so the progress goes back to where it was.
        revertProgress(before)
    } else {
        seekToArticlePosition(ArticleTime(progress.positionMs), revertTo = before)
    }
}

/** Moves playback [offset] through the article from where it is, kept inside the article. */
private fun Step.skip(offset: Duration) {
    seekToArticlePosition(
        ArticleTime((progress.positionMs + offset.inWholeMilliseconds).coerceIn(0, progress.durationMs))
    )
}

private fun Step.togglePlayPause(phase: PlaybackPhase) {
    when (phase) {
        PlaybackPhase.Playing,
        PlaybackPhase.Buffering -> effect(PlayerEffect.Pause)

        PlaybackPhase.Paused,
        PlaybackPhase.Failed -> effect(PlayerEffect.Resume)

        PlaybackPhase.Ended -> {
            emit(NarrationEvent.Waiting)
            if (session != null) seekToArticlePosition(ArticleTime.ZERO)
            effect(PlayerEffect.Resume)
        }

        // The opening is still being synthesized, and plays on its own once it is ready.
        PlaybackPhase.Idle -> Unit
    }
}

/** Seeks to [target]. A seek to the end of the article finishes it, since there is nothing left to play. */
private fun Step.seekToArticlePosition(target: ArticleTime, revertTo: ArticleProgress? = null) {
    if (progress.durationMs in 1..target.ms) {
        finishArticle()
        return
    }

    // Placed by the lengths the last report was placed by, so that it is where the bar showed it.
    val timeline = active.position.timeline
    val reading = active.reading
    val chunkPosition = timeline.toChunkPosition(target)?.takeIf { reading != null }
    val claim = chunkPosition?.let { claim(Priority.Seek, ClaimedWork.Seek(it, revertTo)) }
    if (reading == null || chunkPosition == null || claim == null) {
        revertTo?.let(::revertProgress)
        return
    }

    val targetTime = timeline.toArticleTime(chunkPosition)
    startSeek(targetTime, chunkPosition.chunkIndex)
    update { copy(position = position.copy(afterSeek = true)) }
    effect(SessionEffect.SeekWithin(claim.id, reading.id, chunkPosition.chunkIndex, targetTime.duration))
}

/** The seek within the playlist was made, or has to be made by a restart. */
private fun Step.seekedWithin(input: NarrationInput.SeekedWithin) {
    val seek = claimOf<ClaimedWork.Seek>(input.claimId) ?: return
    if (input.succeeded) {
        update { copy(claim = null) }
    } else {
        val restart = ClaimedWork.Restart(seek.target, active.position.timeline, seek = true, revertTo = seek.revertTo)
        restartAt(active.claim ?: return, restart, discardFirst = false)
    }
}

/** Records that playback is moving to [target], inside chunk [chunkIndex]. */
private fun Step.startSeek(target: ArticleTime, chunkIndex: Int) {
    val wasFinished = active.playlist.isFinished
    update { copy(playlist = playlist.seeking(target.ms, chunkIndex)) }
    // The player stays paused where a seek finished the article, so a seek away from there is a paused one.
    if (wasFinished) emit(NarrationEvent.SeekLeftFinish)
}

private fun Step.revertProgress(before: ArticleProgress) {
    progress = before.correctLength()
}

/** Finishes the article where a seek moved playback to its end: the audio stops, and nothing more is made for it. */
private fun Step.finishArticle() {
    // Takes the claim from a seek still waiting on its chunk, and stops making that chunk, so that it does not restart
    // playback after this.
    if (active.claim.let { it != null && it.priority <= Priority.Seek }) {
        update { copy(claim = null) }
        effect(SessionEffect.CancelSynthesis)
    }
    update { copy(playlist = playlist.finished()) }
    effect(PlayerEffect.FinishAt(progress.durationMs.milliseconds))
    emit(NarrationEvent.Ended)
}

/** Makes the chunk the player failed on again, or reports the failure if the chunk cannot be made again. */
private fun Step.recoverOrReportFailure() {
    val target = active.position.timeline.toChunkPosition(ArticleTime(progress.positionMs))
    if (active.reading == null || target == null) return emit(NarrationEvent.PlaybackFailed)

    val restart = ClaimedWork.Restart(target, active.position.timeline)
    val claim = claim(Priority.Recovery, restart) ?: return
    val next = active.recovery.next(target.chunkIndex)
    if (next == null) {
        update { copy(claim = null) }
        emit(NarrationEvent.PlaybackFailed)
    } else {
        update { copy(recovery = next) }
        restartAt(claim, restart, discardFirst = true)
    }
}

/** The player ran out of audio: synthesis is behind, or the article is over. */
private fun Step.refillOrEnd() {
    val reading = active.reading ?: return
    val missing = active.playlist.nextToQueue
    if (missing >= reading.measurements.chunkCount) {
        emit(NarrationEvent.Ended)
    } else {
        emit(NarrationEvent.Waiting)
        effect(SessionEffect.Refill(reading.id, missing))
    }
}

/**
 * Drops the work in flight, makes the chunk that holds [restart]'s target and starts the playlist again there. A seek,
 * a recovery and a voice change all restart here: they only differ in how they pick the target.
 */
private fun Step.restartAt(claim: Claim, restart: ClaimedWork.Restart, discardFirst: Boolean) {
    val reading = active.reading ?: return
    // The audio stops and the session reports the target at once, rather than playing on until the chunk is made.
    effect(PlayerEffect.StopAndBufferAt(restart.placedBy.toArticleTime(restart.target).duration))
    emit(NarrationEvent.Waiting)

    update { copy(playlist = playlist.cleared(), claim = claim.copy(work = restart)) }
    effect(SessionEffect.CancelSynthesis)
    effect(SessionEffect.MakeRestartChunk(claim.id, reading.id, restart.target.chunkIndex, discardFirst))
}

/**
 * The chunk a restart needs is made: playback starts in it, once the chunk after it is made too when little of it is
 * left to play from there, so that the player does not run out of audio before that one is ready.
 */
private fun Step.restartChunkMade(input: NarrationInput.RestartChunkMade) {
    val restart = claimOf<ClaimedWork.Restart>(input.claimId) ?: return
    val reading = active.reading?.takeIf { it.id == input.readingId } ?: return
    if (input.file == null) return failRestart(restart)

    val index = restart.target.chunkIndex
    val measuredMs = reading.measurements.measuredLengths[index]?.inWholeMilliseconds
    val estimatedMs = restart.placedBy.lengthOf(index).inWholeMilliseconds
    val offsetMs = offsetInMeasuredChunk(restart.target.offset.ms, measuredMs, estimatedMs)
    val made = restart.copy(file = input.file, offsetMs = offsetMs)

    if (measuredMs != null && measuredMs - offsetMs < MIN_AUDIO_AFTER_SEEK_MS) {
        update { copy(claim = claim?.copy(work = made)) }
        effect(SessionEffect.MakeNextChunk(input.claimId, reading.id, index + 1))
    } else {
        finishRestart(made)
    }
}

/** Starts the player's playlist again in the chunk [restart] made. */
private fun Step.finishRestart(restart: ClaimedWork.Restart) {
    val reading = active.reading ?: return
    val file = restart.file ?: return
    val index = restart.target.chunkIndex
    val chunkStart = restart.placedBy.startOf(index)
    val startMs = chunkStart.ms + restart.offsetMs
    val item = reading.measurements.itemOf(index, file, chunkStart.duration)
    val frozen = (0 until index).associateWith(restart.placedBy::lengthOf) + (index to item.length)

    effect(PlayerEffect.Restart(item, startMs.milliseconds))
    update { copy(playlist = playlist.restarted(index, frozen, startMs), claim = null) }
    effect(SessionEffect.WorkAhead(reading.id, index))
}

/** A restart could not be made. A seek is given up, and its progress put back. */
private fun Step.failRestart(restart: ClaimedWork.Restart) {
    if (restart.seek) {
        update { copy(playlist = playlist.seekAbandoned()) }
        restart.revertTo?.let {
            revertProgress(it)
            // The session buffers at the target, and would go on reporting it over the reverted progress.
            effect(PlayerEffect.StopAndBufferAt(it.positionMs.milliseconds))
        }
    }
    update { copy(claim = null) }
}

/** Work failed. Claimed work the session has since given up on fails unseen. */
private fun Step.workFailed(input: NarrationInput.WorkFailed) {
    if (input.claimId != null) {
        if (!holds(input.claimId)) return
        val restart = active.claim?.work as? ClaimedWork.Restart
        if (restart != null) failRestart(restart) else update { copy(claim = null) }
    }
    emit(
        when (input.failure) {
            SynthesisFailure.NothingToRead -> NarrationEvent.NothingToRead
            // The engine only reaches for the network when it has no offline voice for the language, so a network
            // failure during synthesis means the same thing to the user as an empty voice list.
            SynthesisFailure.NoOfflineVoice -> NarrationEvent.NoOfflineVoice
            SynthesisFailure.Failed -> NarrationEvent.SynthesisFailed
        }
    )
}

/** Hands the player chunk [index], placed after what it holds, with its length frozen as it stands now. */
private fun Step.enqueue(index: Int, file: File) {
    val measurements = active.reading?.measurements ?: return
    val frozen = active.playlist.frozenLengths
    val item = measurements.itemOf(index, file, measurements.timeline(frozen).startOf(index).duration)
    effect(PlayerEffect.Enqueue(item))
    update { copy(playlist = playlist.queued(index, item.length)) }
}

/** Gives the notification the length the sheet's bar is drawn against, when it has changed. */
private fun Step.sendShownLength() {
    val shown = progress.shownDuration
    if (shown == active.position.shownLengthSent) return
    update { copy(position = position.copy(shownLengthSent = shown)) }
    effect(PlayerEffect.SetArticleDuration(shown))
}

/**
 * One reduction in progress: the model it is making, and the effects it has asked for so far. It lives only for one
 * call of [reduceNarration], so nothing outside it ever sees it change.
 */
private class Step(model: NarrationModel) {
    var phase: NarrationPhase = model.phase
    var progress: ArticleProgress = model.progress
    var lastId: Int = model.lastId
    private val effects = mutableListOf<NarrationEffect>()

    val session: Active?
        get() = phase as? Active

    /** The running session. Only read where a session is known to be running. */
    val active: Active
        get() = phase as Active

    fun update(change: Active.() -> Active) {
        session?.let { phase = it.change() }
    }

    fun updateReading(readingId: Int, change: ReadingModel.() -> ReadingModel) {
        update { if (reading?.id == readingId) copy(reading = reading.change()) else this }
    }

    fun emit(event: NarrationEvent) {
        effects += NarrationEffect.Emit(event)
        if (event == NarrationEvent.Ended) progress = progress.atEnd()
    }

    fun effect(effect: NarrationEffect) {
        effects += effect
    }

    fun newId(): Int {
        lastId += 1
        return lastId
    }

    /** Claims the right to move playback for [work], or `null` when work that matters more holds it. */
    fun claim(priority: Priority, work: ClaimedWork): Claim? {
        val held = active.claim
        if (held != null && priority < held.priority) return null
        return Claim(newId(), priority, work).also { update { copy(claim = it) } }
    }

    fun holds(claimId: Int): Boolean = session?.claim?.id == claimId

    inline fun <reified W : ClaimedWork> claimOf(claimId: Int): W? =
        session?.claim?.takeIf { it.id == claimId }?.work as? W

    fun next() = Next(NarrationModel(phase, progress, lastId), effects)
}

/** Chunk [index], made into [file], to be placed at [startsAt]. */
private fun ArticleMeasurements.itemOf(index: Int, file: File, startsAt: Duration) =
    PlayableItem(index, file, startsAt, lengthOf(index), isMeasured = index in measuredLengths)
