/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import mozilla.components.feature.listentopage.facts.emitEngineBoundFact
import mozilla.components.feature.listentopage.synthesis.SpeechSynthesizer

/**
 * The speech engine of one session: bound on first use, because binding it is IPC, and closed when the session ends.
 *
 * @param provider Builds the engine. [SpeechSynthesizer.close] is terminal, so each session builds its own.
 * @param scope Where the engine is bound. It outlives the session, so that an engine still binding when the session
 *   ends can be closed once it is bound.
 * @param ioDispatcher The dispatcher the engine is bound and closed on.
 */
internal class SpeechEngine(
    provider: () -> SpeechSynthesizer,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val binding =
        scope.async(start = CoroutineStart.LAZY) {
            withContext(ioDispatcher) { provider() }.also { emitEngineBoundFact(it.enginePackageName) }
        }

    /** The engine, bound now if it is not yet. */
    suspend fun synthesizer(): SpeechSynthesizer = binding.await()

    /** Closes the engine if it was ever bound, waiting for a binding still in progress. */
    suspend fun close() {
        if (!binding.isActive && !binding.isCompleted) {
            binding.cancel()
            return
        }
        withContext(ioDispatcher) { runCatching { binding.await() }.getOrNull()?.close() }
    }
}
