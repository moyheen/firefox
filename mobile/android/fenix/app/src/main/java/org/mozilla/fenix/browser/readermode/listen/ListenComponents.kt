/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.readermode.listen

import android.content.Context
import org.mozilla.fenix.ext.components

/**
 * Application-scoped holder for the Reader Mode Listen prototype components.
 * Exposed via [org.mozilla.fenix.components.Components.listen] so the same
 * [ListenController] instance is shared across all variant entry points.
 */
class ListenComponents(context: Context) {
    val controller: ListenController by lazy {
        ListenController(context, context.components.settings)
    }
}
