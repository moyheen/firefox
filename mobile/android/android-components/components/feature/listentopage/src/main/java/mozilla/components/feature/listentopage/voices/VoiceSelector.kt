/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.voices

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.support.base.log.logger.Logger

/** What the engine has for the article language. */
sealed interface VoiceLoad {
    /**
     * The engine has offline voices for the language.
     *
     * @property voices The voices, best first.
     * @property selected The one to read with: the one the user saved for the language, or the best.
     */
    data class Loaded(val voices: List<Voice>, val selected: Voice) : VoiceLoad

    /** The engine has no offline voice for the language, so the article cannot be read out. */
    data object NoOfflineVoice : VoiceLoad

    /** The engine failed to say which voices it has, or to take the one picked. */
    data object Failed : VoiceLoad
}

/**
 * Picks the voice an article is read in: loads the voices of its language, saves the user's choice, refreshes the list,
 * and falls back to another voice when the chosen one is gone. It knows nothing of the narration: it is told the
 * engine's voices through [catalog], and the session is told only the voice it picks.
 *
 * It owns its jobs, so that a voice the user picks cancels a load still in flight, which would otherwise finish
 * afterwards and put the engine back on the voice it had chosen for itself.
 *
 * @param catalog The voices of the session's engine.
 * @param preferences The voice the user picked for each article language.
 * @param scope Where the loads run, and where a choice is saved.
 * @param ioDispatcher The dispatcher for the work that must not run on the caller's thread.
 */
internal class VoiceSelector(
    private val catalog: VoiceCatalog,
    private val preferences: VoicePreferences,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val logger = Logger("VoiceSelector")

    private var loadJob: Job? = null
    private var refreshJob: Job? = null

    /**
     * Loads the voices of [languageTag], sets the one it picks on the session's engine, reports them with [onLoaded],
     * and then runs [andThen] unless the language has no offline voice. Each session has its own engine, so the voices
     * are loaded for every session. The load and what follows it are one job, so that a voice picked meanwhile cancels
     * both.
     */
    fun loadThen(languageTag: String, onLoaded: (VoiceLoad) -> Unit, andThen: () -> Unit) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val load = load(languageTag)
            onLoaded(load)
            if (load is VoiceLoad.Loaded) andThen()
        }
    }

    /** Asks the engine again which voices it has for [languageTag], and hands them to [onRefreshed]. */
    fun refresh(languageTag: String, onRefreshed: suspend (List<Voice>) -> Unit) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val voices = asking { catalog.voicesFor(languageTag) } ?: return@launch
            onRefreshed(voices)
        }
    }

    /**
     * Saves [voice] as the user's choice for [languageTag], and cancels a load still in flight when [replacesLoad].
     *
     * Saved even when the voice is the one already reading, because the voice a load picks for itself is put in the
     * store without being saved: the first tap on it is what makes the choice the user's own.
     */
    fun choose(voice: Voice, languageTag: String?, replacesLoad: Boolean) {
        languageTag?.let {
            scope.launch(ioDispatcher) { preferences.setSelectedVoiceId(voice.engine, it.language, voice.id) }
        }
        if (replacesLoad) loadJob?.cancel()
    }

    /** The voice of [voices], all of one engine, that the user saved for [languageTag], or the best of them. */
    suspend fun savedVoiceFor(voices: List<Voice>, languageTag: String): Voice {
        val savedId = preferences.getSelectedVoiceId(voices.first().engine, languageTag.language)
        // The list is presumed sorted by a useful heuristic so `first()` is best
        return voices.firstOrNull { it.id == savedId } ?: voices.first()
    }

    /** Stops every load and refresh in flight, because the session they were for has ended. */
    fun cancel() {
        loadJob?.cancel()
        refreshJob?.cancel()
    }

    private suspend fun load(languageTag: String): VoiceLoad =
        asking {
            val voices = catalog.voicesFor(languageTag)
            if (voices.isEmpty()) {
                VoiceLoad.NoOfflineVoice
            } else {
                val selected = savedVoiceFor(voices, languageTag)
                catalog.use(selected)
                VoiceLoad.Loaded(voices, selected)
            }
        } ?: VoiceLoad.Failed

    /** Runs [request] to the engine off the caller's thread, or returns `null` when the engine fails it. */
    private suspend fun <T : Any> asking(request: suspend () -> T): T? =
        try {
            withContext(ioDispatcher) { request() }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.warn("The engine failed to give its voices", e)
            null
        }
}

/**
 * The language subtag of a BCP 47 tag, or the tag itself when it is too malformed to parse.
 *
 * The voice list ignores the region of the article language, so the saved choice has to ignore it too: a voice picked
 * on an "en-US" article is the one offered again on an "en-GB" one, rather than being forgotten between them.
 */
private val String.language: String
    get() = Locale.forLanguageTag(this).language.ifEmpty { this }
