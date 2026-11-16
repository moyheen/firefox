/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryAttemptsTest {

    @Test
    fun `test that a chunk is made again up to the most attempts allowed`() {
        var attempts: RecoveryAttempts? = RecoveryAttempts()
        repeat(MAX_RECOVERY_ATTEMPTS) { attempts = attempts?.next(chunkIndex = 2) }

        assertEquals(RecoveryAttempts(chunkIndex = 2, made = MAX_RECOVERY_ATTEMPTS), attempts)
        assertNull(attempts?.next(chunkIndex = 2))
    }

    @Test
    fun `test that a failure on another chunk starts counting again`() {
        val exhausted = RecoveryAttempts(chunkIndex = 2, made = MAX_RECOVERY_ATTEMPTS)

        assertEquals(RecoveryAttempts(chunkIndex = 3, made = 1), exhausted.next(chunkIndex = 3))
    }
}
