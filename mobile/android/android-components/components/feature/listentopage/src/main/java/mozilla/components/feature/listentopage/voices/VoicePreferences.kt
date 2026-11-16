/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.voices

/**
 * The voice the user picked for each article language, kept for each engine, because a voice's id only means something
 * to the engine that offers it.
 */
interface VoicePreferences {
    /**
     * @param engine The engine the voice belongs to.
     * @param languageTag The language of the article.
     * @return The id of the voice saved for [languageTag] in [engine], or `null` when there is none.
     */
    suspend fun getSelectedVoiceId(engine: String, languageTag: String): String?

    /**
     * Persists the voice the user picked for one article language in one engine.
     *
     * @param engine The engine the voice belongs to.
     * @param languageTag The language of the article.
     * @param voiceId The id of the picked voice.
     */
    suspend fun setSelectedVoiceId(engine: String, languageTag: String, voiceId: String)
}
