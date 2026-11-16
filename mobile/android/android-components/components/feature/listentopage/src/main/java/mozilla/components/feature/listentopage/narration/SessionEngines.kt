/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import mozilla.components.feature.listentopage.synthesis.SpeechSynthesizer
import mozilla.components.feature.listentopage.voices.Voice
import mozilla.components.feature.listentopage.voices.VoiceCatalog

/**
 * The speech engine of the session running, shared by the narration, which reads with it, and the voice selection,
 * which asks it for voices. Each session opens its own, because closing an engine is terminal.
 *
 * @param provider Builds an engine.
 * @param scope Where an engine is bound. It outlives the session, so that an engine still binding when the session ends
 *   can be closed once it is bound.
 * @param ioDispatcher The dispatcher an engine is bound and closed on.
 */
internal class SessionEngines(
    private val provider: () -> SpeechSynthesizer,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** The engine of the session running, or `null` when no session is. Read from the IO dispatcher too. */
    @Volatile
    var current: SpeechEngine? = null
        private set

    /** Opens the engine of a new session. */
    fun open(): SpeechEngine = SpeechEngine(provider, scope, ioDispatcher).also { current = it }

    /** Takes the engine away from the session that is ending, and returns it to be closed. */
    fun detach(): SpeechEngine? = current.also { current = null }
}

/** The voices of the engine of the session running. */
internal class EngineVoiceCatalog(private val engines: SessionEngines) : VoiceCatalog {
    override suspend fun voicesFor(languageTag: String): List<Voice> = synthesizer().loadAvailableVoices(languageTag)

    override suspend fun use(voice: Voice) = synthesizer().setVoice(voice)

    private suspend fun synthesizer(): SpeechSynthesizer =
        engines.current?.synthesizer() ?: throw CancellationException("No session is running")
}
