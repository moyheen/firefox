/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.feature.listentopage.settings.ListenEnabledState
import mozilla.components.feature.listentopage.settings.ListenSettings
import mozilla.components.lib.state.ext.flow

/**
 * Stops each session once the user turns the feature off. It is one more source of [ListenAction.User], like a closed
 * tab: it watches the setting, and asks the store to stop.
 *
 * @param store The store whose sessions it stops.
 * @param settings Whether the feature is turned on.
 * @param scope Where it runs, for as long as the feature does.
 */
internal class ListenEnabledBinding(
    private val store: ListenStore,
    private val settings: ListenSettings,
    private val scope: CoroutineScope,
) {
    /** Starts watching the setting. */
    fun start(): Job = scope.launch {
        store
            .flow()
            .map { it.tabId != null }
            .distinctUntilChanged()
            .collectLatest { isInSession ->
                if (!isInSession) return@collectLatest

                settings.enabled.first { it == ListenEnabledState.Disabled }
                store.dispatch(
                    ListenAction.User.Session.StopRequested(ListenAction.User.Session.StopSource.FeatureDisabled)
                )
            }
    }
}
