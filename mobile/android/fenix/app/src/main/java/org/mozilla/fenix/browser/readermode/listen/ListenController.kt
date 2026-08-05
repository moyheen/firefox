/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.readermode.listen

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.annotation.RawRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.R
import org.mozilla.fenix.utils.Settings
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * State exposed by [ListenController].
 *
 * @property isReady true once the underlying TextToSpeech engine has finished initializing.
 * @property isPlaying true when audio is currently being produced.
 * @property currentIndex index of the sentence currently being spoken (0-based).
 * @property totalSentences total number of sentences queued for the current article.
 * @property speed playback speed multiplier (1.0 = normal).
 * @property selectedVoiceName name of the currently selected voice, or null if default.
 * @property availableVoices on-device voices available on this device.
 * @property noOnDeviceVoice true when no voice could be confirmed to synthesize without a network
 * connection, meaning playback is unavailable.
 */
data class ListenState(
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val currentIndex: Int = 0,
    val totalSentences: Int = 0,
    val speed: Float = 1.0f,
    val selectedVoiceName: String? = null,
    val availableVoices: List<Voice> = emptyList(),
    val noOnDeviceVoice: Boolean = false,
)

/**
 * Wraps Android's [TextToSpeech] with play/pause, skip-by-sentence, speed, and voice controls.
 *
 * Synthesis is restricted to on-device voices so that article text is never knowingly sent to a
 * cloud TTS backend. Voice selection is the only mechanism the platform offers for this, so the
 * restriction rests on never letting a [Voice] that reports requiring a network connection reach
 * the engine — see [isOnDevice] and its callers. When no on-device voice can be confirmed,
 * playback is refused rather than attempted.
 *
 * Note where that stops being a guarantee: whether a voice is local is self-reported by the TTS
 * engine, which runs in its own process. Nothing here can observe the engine's actual network use,
 * so an engine that misreports a voice cannot be detected from this side. Confirming a given
 * engine really stays local requires testing it with the device offline.
 *
 * Prototype-only. The article text passed to [play] is chosen by [ListenIntegration]; in this
 * prototype it is a bundled demo article rather than the page's actual text.
 */
class ListenController(
    private val context: Context,
    private val settings: Settings,
) {
    private val logger = Logger("ListenController")

    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var sentences: List<String> = emptyList()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    // Setting the language can itself make the engine select a voice for that
                    // locale, so this must stay above the on-device check below: reordering the
                    // two would check a voice the engine is about to replace.
                    engine.language = Locale.getDefault()
                    engine.setOnUtteranceProgressListener(progressListener)

                    val onDeviceVoices = engine.voices?.toList().orEmpty()
                        .filter(::isOnDevice)
                        .sortedBy { it.name }

                    // A voice the user picked in a previous session wins over the engine's default.
                    // It is looked up in the filtered list rather than trusted by name, so a voice
                    // whose data was uninstalled, or one saved under a different engine, simply
                    // fails to resolve and falls through to the choices below.
                    val savedVoice = settings.readerListenVoice
                        .takeIf { it.isNotEmpty() }
                        ?.let { saved -> onDeviceVoices.firstOrNull { it.name == saved } }

                    // The engine's default voice may well be a cloud voice, so only keep it if it
                    // synthesizes locally; otherwise fall back to an on-device voice, preferring
                    // one that speaks the current language. This reads getVoice() rather than
                    // getDefaultVoice(), since the latter describes the engine's default language
                    // instead of the language set above, which is what will actually be spoken.
                    val language = Locale.getDefault().language
                    val voice = savedVoice
                        ?: engine.voice?.takeIf(::isOnDevice)
                        ?: onDeviceVoices.firstOrNull { it.locale.language == language }
                        ?: onDeviceVoices.firstOrNull()
                    voice?.let { engine.voice = it }

                    // No verified on-device voice means we cannot promise the text stays local, so
                    // the feature reports itself unavailable rather than risking a cloud backend.
                    _state.value = _state.value.copy(
                        isReady = true,
                        availableVoices = onDeviceVoices,
                        selectedVoiceName = voice?.name,
                        noOnDeviceVoice = voice == null,
                    )
                }
            } else {
                logger.warn("TextToSpeech init failed, status=$status")
            }
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val index = utteranceId?.toIntOrNull() ?: return
            _state.value = _state.value.copy(isPlaying = true, currentIndex = index)
        }

        override fun onDone(utteranceId: String?) {
            val index = utteranceId?.toIntOrNull() ?: return
            if (index >= sentences.lastIndex) {
                _state.value = _state.value.copy(isPlaying = false, currentIndex = 0)
            }
        }

        @Deprecated("Deprecated in API 21")
        override fun onError(utteranceId: String?) {
            _state.value = _state.value.copy(isPlaying = false)
        }
    }

    /**
     * Start speaking the given text from the beginning. Splits into sentences so playback
     * can be paused, resumed, and skipped sentence-by-sentence.
     */
    fun play(text: String) {
        if (tts == null || _state.value.noOnDeviceVoice) return
        sentences = splitSentences(text)
        if (sentences.isEmpty()) return
        _state.value = _state.value.copy(
            totalSentences = sentences.size,
            currentIndex = 0,
            isPlaying = true,
        )
        queueFrom(0)
    }

    fun pause() {
        tts?.stop()
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun resume() {
        if (sentences.isEmpty()) return
        _state.value = _state.value.copy(isPlaying = true)
        queueFrom(_state.value.currentIndex)
    }

    /**
     * Entry-point convenience: if nothing has been queued yet, start with [text];
     * otherwise toggle pause/resume without re-loading the article.
     */
    fun togglePlayPause(text: String) {
        when {
            _state.value.isPlaying -> pause()
            sentences.isNotEmpty() -> resume()
            else -> play(text)
        }
    }

    fun stop() {
        tts?.stop()
        _state.value = _state.value.copy(isPlaying = false, currentIndex = 0)
    }

    fun skipForward() = jumpBy(1)

    fun skipBack() = jumpBy(-1)

    fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed)
        _state.value = _state.value.copy(speed = speed)
        if (_state.value.isPlaying) {
            queueFrom(_state.value.currentIndex)
        }
    }

    fun setVoice(voice: Voice) {
        if (!isOnDevice(voice)) {
            logger.warn("Refusing cloud voice ${voice.name}; on-device synthesis only")
            return
        }
        tts?.voice = voice
        settings.readerListenVoice = voice.name
        _state.value = _state.value.copy(selectedVoiceName = voice.name)
        if (_state.value.isPlaying) {
            queueFrom(_state.value.currentIndex)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun jumpBy(delta: Int) {
        if (sentences.isEmpty()) return
        val target = (_state.value.currentIndex + delta).coerceIn(0, sentences.lastIndex)
        queueFrom(target)
    }

    private fun queueFrom(startIndex: Int) {
        val engine = tts ?: return

        // Re-check rather than trusting the choice made at init: voice data can be installed or
        // removed while the app runs, and the engine may substitute a voice on its own.
        val voice = engine.voice
        if (voice != null && !isOnDevice(voice)) {
            logger.warn("Engine switched to cloud voice ${voice.name}; refusing to speak")
            _state.value = _state.value.copy(isPlaying = false, noOnDeviceVoice = true)
            return
        }

        sentences.subList(startIndex, sentences.size).forEachIndexed { offset, sentence ->
            val absoluteIndex = startIndex + offset
            engine.speak(
                sentence,
                if (offset == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                absoluteIndex.toString(),
            )
        }
        _state.value = _state.value.copy(currentIndex = startIndex)
    }

    private fun splitSentences(text: String): List<String> {
        return SENTENCE_DELIMITER.split(text.trim())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    companion object {
        private val SENTENCE_DELIMITER = Regex("(?<=[.!?])\\s+")

        /**
         * A voice is usable only if it synthesizes on-device: it must not require a network
         * connection, and it must already be installed (an uninstalled voice would need a
         * download before it could speak).
         */
        private fun isOnDevice(voice: Voice): Boolean =
            !voice.isNetworkConnectionRequired &&
                !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)

        @RawRes
        private val demoArticles = listOf(
            R.raw.demo_article_news,
            R.raw.demo_article_essay,
            R.raw.demo_article_howto,
        )

        /**
         * Pick a demo article deterministically from a key (URL or title), so the same page
         * always plays the same article across taps.
         */
        fun demoArticleFor(key: String): Int =
            demoArticles[key.hashCode().absoluteValue % demoArticles.size]
    }
}
