/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.readermode.listen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mozilla.fenix.R
import org.mozilla.fenix.theme.FirefoxTheme
import mozilla.components.ui.icons.R as iconsR

/**
 * Floating pill button used by [ListenVariant.TOOLBAR]. Reflects playback state
 * (play vs pause) and acts as a one-tap toggle, while also opening the full
 * [ListenSheetFragment] on first tap.
 */
@Composable
fun ListenToolbarPill(
    isPlaying: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(bottom = 24.dp)
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onTap)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(
                if (isPlaying) iconsR.drawable.mozac_ic_pause_24
                else iconsR.drawable.mozac_ic_play_fill_24,
            ),
            contentDescription = stringResource(
                if (isPlaying) R.string.reader_listen_pause_content_description
                else R.string.reader_listen_play_content_description,
            ),
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.reader_listen_label),
            style = FirefoxTheme.typography.button,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}
