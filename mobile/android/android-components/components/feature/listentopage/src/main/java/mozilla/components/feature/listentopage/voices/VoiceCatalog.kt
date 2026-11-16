/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.voices

/** The voices a speech engine offers, and the one it reads in. */
interface VoiceCatalog {
    /** The offline voices for [languageTag], one per region and best first, or none when it has no offline voice. */
    suspend fun voicesFor(languageTag: String): List<Voice>

    /** Reads everything from here on in [voice], which has to be one [voicesFor] returned. */
    suspend fun use(voice: Voice)
}
