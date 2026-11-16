/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.progress

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A time into the whole article, counting every chunk before the one it falls in.
 *
 * @property ms How far into the article, in milliseconds.
 */
@JvmInline
internal value class ArticleTime(val ms: Long) : Comparable<ArticleTime> {
    val duration: Duration
        get() = ms.milliseconds

    operator fun plus(offset: ChunkOffset): ArticleTime = ArticleTime(ms + offset.ms)

    override fun compareTo(other: ArticleTime): Int = ms.compareTo(other.ms)

    companion object {
        val ZERO = ArticleTime(0)
    }
}

/**
 * A time into one chunk, counted from the chunk's start.
 *
 * @property ms How far into the chunk, in milliseconds.
 */
@JvmInline internal value class ChunkOffset(val ms: Long)

/** A place in the article: which chunk holds it, and how far into that chunk it is. */
internal data class ChunkPosition(val chunkIndex: Int, val offset: ChunkOffset)
