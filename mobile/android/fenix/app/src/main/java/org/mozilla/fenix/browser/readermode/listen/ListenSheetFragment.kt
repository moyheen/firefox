/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.readermode.listen

import android.os.Bundle
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import mozilla.components.compose.base.button.FilledButton
import mozilla.components.compose.base.button.TextButton
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.theme.FirefoxTheme
import mozilla.components.ui.icons.R as iconsR

/**
 * Bottom sheet shown by all [ListenVariant] entry points. Exposes the full
 * playback controls (play/pause, skip, speed, voice) for the Reader Mode Listen prototype.
 */
class ListenSheetFragment : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FirefoxTheme {
                    ListenSheetContent(
                        controller = requireComponents.listen.controller,
                        onClose = ::dismiss,
                    )
                }
            }
        }
    }

    companion object {
        const val TAG = "ListenSheetFragment"
    }
}

@Composable
private fun ListenSheetContent(
    controller: ListenController,
    onClose: () -> Unit,
) {
    val state by controller.state.collectAsState()

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.reader_listen_sheet_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = FirefoxTheme.typography.headline6,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(iconsR.drawable.mozac_ic_cross_24),
                        contentDescription = stringResource(R.string.reader_listen_close_content_description),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val progressLabel = if (state.totalSentences > 0) {
                "${state.currentIndex + 1} / ${state.totalSentences}"
            } else {
                "—"
            }
            Text(
                text = progressLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = FirefoxTheme.typography.body2,
            )

            Spacer(Modifier.height(16.dp))

            TransportControls(
                isPlaying = state.isPlaying,
                onPlayPause = {
                    if (state.isPlaying) controller.pause() else controller.resume()
                },
                onSkipBack = controller::skipBack,
                onSkipForward = controller::skipForward,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.reader_listen_speed_label),
                color = MaterialTheme.colorScheme.onSurface,
                style = FirefoxTheme.typography.subtitle2,
            )
            Spacer(Modifier.height(8.dp))
            SpeedChips(
                current = state.speed,
                onSelect = controller::setSpeed,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.reader_listen_voice_label),
                color = MaterialTheme.colorScheme.onSurface,
                style = FirefoxTheme.typography.subtitle2,
            )
            Spacer(Modifier.height(8.dp))
            VoicePicker(
                selectedName = state.selectedVoiceName,
                voices = state.availableVoices,
                onSelect = controller::setVoice,
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.reader_listen_demo_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = FirefoxTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSkipBack, modifier = Modifier.size(56.dp)) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_back_24),
                contentDescription = stringResource(R.string.reader_listen_skip_back_content_description),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) {
                            iconsR.drawable.mozac_ic_pause_24
                        } else {
                            iconsR.drawable.mozac_ic_play_fill_24
                        },
                    ),
                    contentDescription = stringResource(
                        if (isPlaying) {
                            R.string.reader_listen_pause_content_description
                        } else {
                            R.string.reader_listen_play_content_description
                        },
                    ),
                    tint = Color.White,
                )
            }
        }

        IconButton(onClick = onSkipForward, modifier = Modifier.size(56.dp)) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_forward_24),
                contentDescription = stringResource(R.string.reader_listen_skip_forward_content_description),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SpeedChips(
    current: Float,
    onSelect: (Float) -> Unit,
) {
    val options = listOf(
        0.75f to R.string.reader_listen_speed_075x,
        1.0f to R.string.reader_listen_speed_1x,
        1.25f to R.string.reader_listen_speed_125x,
        1.5f to R.string.reader_listen_speed_15x,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (speed, label) ->
            val selected = kotlin.math.abs(current - speed) < 0.01f
            if (selected) {
                FilledButton(
                    text = stringResource(label),
                    onClick = { onSelect(speed) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                TextButton(
                    text = stringResource(label),
                    onClick = { onSelect(speed) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VoicePicker(
    selectedName: String?,
    voices: List<Voice>,
    onSelect: (Voice) -> Unit,
) {
    if (voices.isEmpty()) {
        Text(
            text = "No voices available",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = FirefoxTheme.typography.body2,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val displayed = if (expanded) voices else voices.take(3)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        displayed.forEach { voice ->
            val isSelected = voice.name == selectedName
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = voice.name,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    style = FirefoxTheme.typography.body2,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = if (isSelected) "Selected" else "Use",
                    onClick = { onSelect(voice) },
                )
            }
        }
        if (voices.size > 3) {
            TextButton(
                text = if (expanded) "Show fewer" else "Show all (${voices.size})",
                onClick = { expanded = !expanded },
            )
        }
    }
}
