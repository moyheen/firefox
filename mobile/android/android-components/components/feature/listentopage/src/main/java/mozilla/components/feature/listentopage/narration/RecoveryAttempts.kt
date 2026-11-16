/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

internal const val MAX_RECOVERY_ATTEMPTS = 3

/**
 * How many times the session has made a chunk again after the player failed on it.
 *
 * @property chunkIndex The chunk being made again, or `null` when none has failed.
 * @property made How many times it has been made again.
 */
internal data class RecoveryAttempts(val chunkIndex: Int? = null, val made: Int = 0) {

    /**
     * The attempts once [chunkIndex] is made again, or `null` when it has been made again too many times already. A
     * failure on another chunk starts counting again.
     */
    fun next(chunkIndex: Int): RecoveryAttempts? {
        val made = if (chunkIndex == this.chunkIndex) made else 0
        return if (made < MAX_RECOVERY_ATTEMPTS) RecoveryAttempts(chunkIndex, made + 1) else null
    }
}
