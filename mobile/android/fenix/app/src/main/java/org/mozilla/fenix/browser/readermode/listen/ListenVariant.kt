/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.readermode.listen

/**
 * Placement variants for the Reader Mode Listen prototype entry point.
 * Switchable at runtime via the secret debug settings.
 */
enum class ListenVariant(val value: String) {
    OFF("off"),
    TOOLBAR("toolbar"),
    BANNER("banner"),
    ;

    companion object {
        fun fromValue(value: String?): ListenVariant =
            entries.firstOrNull { it.value == value } ?: OFF
    }
}
