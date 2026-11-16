/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.narration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Runs one session's synthesis one piece at a time, in the order it was asked for.
 *
 * One at a time because the engine cannot cancel one request without dropping everything it holds: a piece waits for
 * the one before it, including one that was cancelled, to finish. That is what lets a piece throw away audio or hand
 * the engine a new voice without the request in flight writing over it afterwards.
 *
 * @param session The scope of the session the work belongs to. Ending the session cancels it all.
 * @param after What has to finish before the first piece runs: the end of the session before this one.
 */
internal class NarrationWorker(private val session: CoroutineScope, private val after: Job?) {
    private var pieces = newScope()

    // The last piece given, which the next one waits for.
    private var last: Job? = null

    /** Runs [work] once every piece given before it has finished. */
    fun submit(work: suspend () -> Unit) {
        val before = last
        last = pieces.launch {
            after?.join()
            before?.join()
            work()
        }
    }

    /**
     * Cancels every piece given so far. A piece given after this still waits for them to finish, so that the engine
     * request in flight can complete before anything else uses the engine.
     */
    fun cancelAll() {
        val stopping = pieces.coroutineContext.job
        pieces = newScope()
        stopping.cancel()
        last = stopping
    }

    private fun newScope() = CoroutineScope(session.coroutineContext + SupervisorJob(session.coroutineContext.job))
}
