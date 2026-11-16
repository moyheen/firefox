/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage

import mozilla.components.feature.listentopage.content.LanguageSource
import mozilla.components.feature.listentopage.narration.NarrationState
import mozilla.components.feature.listentopage.playback.PlaybackSpeed
import mozilla.components.feature.listentopage.voices.Voice
import mozilla.components.lib.state.Action

/** Actions for the [ListenStore]. */
sealed interface ListenAction : Action {

    /**
     * Something someone did: on the player, in the notification, on the tab being read, or in the settings. It carries
     * where it was done where that matters.
     */
    sealed interface User : ListenAction {

        /** Requests to start and end a listening session. */
        sealed interface Session : User {
            /**
             * The user asked to listen to an article.
             *
             * @property tabId The tab the article is in.
             * @property url The article.
             */
            data class ListenRequested(
                val tabId: String,
                val url: String,
            ) : Session

            /**
             * The listening session is over. It resets to the initial state.
             *
             * @property source What ended the session.
             */
            data class StopRequested(val source: StopSource = StopSource.CloseButton) : Session

            /** What ended a listening session. */
            enum class StopSource {
                /** The user tapped the player's close button. */
                CloseButton,

                /** The listened tab was closed or user navigated elsewhere. */
                TabClosed,

                /** The user left reader mode on the listened tab. */
                ReaderModeClosed,

                /**
                 * The media session ended outside the app, such as when the app was removed from the recent apps or the
                 * notification was dismissed.
                 */
                MediaSessionEndedOutsideApp,

                /** The user turned the feature off. */
                FeatureDisabled,
            }
        }

        /** Requests about the voice the article is read in. */
        sealed interface Voices : User {
            /**
             * The user picked a voice from the list.
             *
             * @property voice The voice selected for the article.
             */
            data class VoiceSelected(val voice: Voice) : Voices

            /** The user asked for the voices to pick from. */
            data object VoicesClicked : Voices

            /** The user dismissed the voices list without picking one. */
            data object VoicesDismissed : Voices
        }

        /**
         * What the user did with the player controls. Closing the player is [Session.StopRequested] rather than a
         * control of its own.
         */
        sealed interface Controls : User {
            /** The user asked to start or pause playback. */
            data object PlayPauseClicked : Controls

            /** The user asked to skip back. */
            data object RewindClicked : Controls

            /** The user asked to skip forward. */
            data object ForwardClicked : Controls

            /** The user asked for the speeds to pick from. */
            data object PlaybackSpeedClicked : Controls

            /**
             * The user picked a playback speed.
             *
             * @property playbackSpeed The speed to read the article out at.
             */
            data class PlaybackSpeedSelected(val playbackSpeed: PlaybackSpeed) : Controls

            /** The user dismissed the speeds list without picking one. */
            data object PlaybackSpeedDismissed : Controls

            /**
             * The reader chose a place in the article to carry on from.
             *
             * @property fraction How far into the article to move to.
             */
            data class SeekRequested(val fraction: Float) : Controls
        }

        /**
         * The player was expanded or collapsed.
         *
         * @property mode What the player shows.
         */
        data class ModeChanged(val mode: PlayerMode) : User

        /** The error that is shown was dismissed. */
        data object ErrorDismissed : User
    }

    /** A report of what the session did, or what happened to it. Only the session itself sends one. */
    sealed interface System : ListenAction {

        /** Reports about the article the session reads out. */
        sealed interface Content : System {
            /**
             * The article was extracted from the page.
             *
             * @property languageTag language of the article.
             * @property languageSource Where [languageTag] came from.
             * @property title The article title, or `null` when the page has none.
             * @property site The site the article is from, or `null` when its URL names none.
             */
            data class ContentReady(
                val languageTag: String,
                val languageSource: LanguageSource = LanguageSource.Page,
                val title: String? = null,
                val site: String? = null,
            ) : Content

            /** The page gave back no usable text. */
            data object ContentUnavailable : Content
        }

        /** Reports about the voices of the article language. */
        sealed interface Voices : System {
            /** Available voices were loaded from the engine. */
            data class AvailableVoicesLoaded(val voices: List<Voice>, val selectedVoice: Voice) : Voices

            /** The engine has no installed, network-free voice for the article language. */
            data object NoOfflineVoicesAvailable : Voices

            /**
             * The voice reading became unavailable, so the session moved on to another.
             *
             * @property voice The voice the article is read in now.
             */
            data class FallbackVoiceChosen(val voice: Voice) : Voices
        }

        /** Reports about what the player is doing. */
        sealed interface Playback : System {
            /**
             * The player reported a change.
             *
             * @property playbackState What the player is doing now.
             */
            data class StateChangeObserved(val playbackState: PlaybackState) : Playback

            /**
             * The player moved on to a chunk and is reading it out.
             *
             * @property chunk The chunk being read.
             * @property positionMs How far into the article the player has got.
             */
            data class PlaybackStarted(val chunk: ChunkState, val positionMs: Long) : Playback

            /**
             * Playback waits for audio that is still being made: the opening when a session starts, or the next chunk
             * after the player read out every chunk it had been given.
             */
            data object PlaybackWaiting : Playback

            /** The article was read to its end, or a seek moved playback to its end. */
            data object PlaybackEnded : Playback

            /** The player could not read the article out. */
            data object PlaybackFailed : Playback
        }

        /** Reports about what the speech engine did with the article. */
        sealed interface Synthesis : System {
            /** The engine could not turn the article into audio. */
            data object SynthesisFailed : Synthesis
        }
    }
}

/**
 * The narration moved on: its progress, or what the player is doing dispatched only by the session. It sits outside
 * [ListenAction] because Kotlin makes everything nested in an interface public.
 *
 * @property narration What the narrator now holds.
 */
internal data class NarrationChanged(val narration: NarrationState) : ListenAction.System

/**
 * A seek moved playback away from where a seek had finished the article. The player stays paused there, so the seek is
 * a paused one. Only the session dispatches it, so it is internal.
 */
internal data object SeekLeftFinish : ListenAction.System

/** The feature was closed: the session it held is over, and the store goes back to holding none. */
internal data object FeatureClosed : ListenAction.System
