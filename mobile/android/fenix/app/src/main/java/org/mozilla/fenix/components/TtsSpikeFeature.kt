/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.support.base.log.logger.Logger

/**
 * SPIKE: installs a bundled WebExtension that drives the Firefox AI Runtime
 * (browser.trial.ml, SpeechT5) to synthesize speech and play it via Web Audio.
 * Validates the on-device "Listen to page" pipeline without a GeckoView rebuild.
 * Not a shipping feature — remove once the flow is validated.
 */
object TtsSpikeFeature {
    private val logger = Logger("tts-spike")

    internal const val EXTENSION_ID = "tts-spike@mozilla.org"
    internal const val EXTENSION_URL = "resource://android/assets/extensions/tts-spike/"

    /**
     * Installs the spike extension and pre-grants its optional `trialML`
     * permission (which is never auto-granted) so no user prompt is needed.
     */
    fun install(runtime: WebExtensionRuntime) {
        runtime.installBuiltInWebExtension(
            EXTENSION_ID,
            EXTENSION_URL,
            onSuccess = {
                logger.debug("Installed TTS spike webextension: ${it.id}")
                runtime.addOptionalPermissions(
                    extensionId = EXTENSION_ID,
                    permissions = listOf("trialML"),
                    onSuccess = { logger.debug("Granted trialML to TTS spike") },
                    onError = { throwable ->
                        logger.error("Failed to grant trialML to TTS spike", throwable)
                    },
                )
            },
            onError = { throwable ->
                logger.error("Failed to install TTS spike webextension", throwable)
            },
        )
    }
}
