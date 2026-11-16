/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// What an item carries in its extras, written by [PlayableItem.toMediaItem] and read back by the readers below it.
private const val KEY_CHUNK_INDEX = "mozilla.components.feature.listentopage.CHUNK_INDEX"
private const val KEY_STARTS_AT_MS = "mozilla.components.feature.listentopage.STARTS_AT_MS"
private const val KEY_LENGTH_MS = "mozilla.components.feature.listentopage.LENGTH_MS"
private const val KEY_MEASURED = "mozilla.components.feature.listentopage.MEASURED"

/**
 * The audio of one chunk of the article, and where it sits in the article.
 *
 * @property index Which chunk of the article it is.
 * @property file Where the audio is.
 * @property startsAt Where the chunk starts in the article.
 * @property length How long the chunk lasts: as it was measured, or as it was estimated when it could not be.
 * @property isMeasured Whether [length] was measured rather than estimated.
 */
data class PlayableItem(
    val index: Int,
    val file: File,
    val startsAt: Duration,
    val length: Duration,
    val isMeasured: Boolean = false,
)

/** Turns [PlayableItem] into something the player accepts, as [displayData] describes it. */
internal fun PlayableItem.toMediaItem(displayData: ArticleDisplayData): MediaItem {
    val extras =
        Bundle().apply {
            putInt(KEY_CHUNK_INDEX, index)
            putLong(KEY_STARTS_AT_MS, startsAt.inWholeMilliseconds)
            putLong(KEY_LENGTH_MS, length.inWholeMilliseconds)
            putBoolean(KEY_MEASURED, isMeasured)
        }

    return MediaItem.Builder()
        .setUri(Uri.fromFile(file))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(displayData.title).setArtist(displayData.site).setExtras(extras).build()
        )
        .build()
}

/** Which chunk of the article the item is, or `null` when it is not one. */
internal fun MediaItem.chunkIndex(): Int? =
    mediaMetadata.extras?.takeIf { it.containsKey(KEY_CHUNK_INDEX) }?.getInt(KEY_CHUNK_INDEX)

/** Where the item starts in the article, or zero when it is not a chunk. */
internal fun MediaItem.startsAt(): Duration = (mediaMetadata.extras?.getLong(KEY_STARTS_AT_MS) ?: 0L).milliseconds

/** How long the item lasts, measured or estimated, or `null` when it is not known. */
internal fun MediaItem.length(): Duration? =
    mediaMetadata.extras?.getLong(KEY_LENGTH_MS)?.takeIf { it > 0 }?.milliseconds

/** How long the item lasts, or `null` when it has not been measured. */
internal fun MediaItem.measuredLength(): Duration? =
    length()?.takeIf { mediaMetadata.extras?.getBoolean(KEY_MEASURED) == true }
