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
 * @property availableVoices voices available on this device.
 */
data class ListenState(
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val currentIndex: Int = 0,
    val totalSentences: Int = 0,
    val speed: Float = 1.0f,
    val selectedVoiceName: String? = null,
    val availableVoices: List<Voice> = emptyList(),
)

/**
 * Wraps Android's [TextToSpeech] with play/pause, skip-by-sentence, speed, and voice controls.
 *
 * Prototype-only. The article text passed to [play] is chosen by [ListenIntegration]; in this
 * prototype it is a bundled demo article rather than the page's actual text.
 */
class ListenController(
    private val context: Context,
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
                    engine.language = Locale.getDefault()
                    engine.setOnUtteranceProgressListener(progressListener)
                    val voices = engine.voices?.toList().orEmpty()
                        .filter { !it.isNetworkConnectionRequired }
                        .sortedBy { it.name }
                    _state.value = _state.value.copy(
                        isReady = true,
                        availableVoices = voices,
                        selectedVoiceName = engine.voice?.name,
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
        if (tts == null) return
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
        tts?.voice = voice
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
