/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MODULE_PACKAGE = "mozilla.components.feature.listentopage"

private val SOURCE_ROOT = File("src/main/java/mozilla/components/feature/listentopage")

/**
 * The names each package offers the others. Anything else in a package is its own business, even where Kotlin's
 * `internal` lets another package of the module see it.
 */
private val API =
    mapOf(
        "content" to
            setOf("ContentExtractor", "Content", "LanguageSource", "PageContentExtractor", "PageMetadataExtractor"),
        "progress" to
            setOf(
                "ArticleTime",
                "ChunkOffset",
                "ChunkPosition",
                "ChunkTimeline",
                "ArticleMeasurements",
                "ArticleProgress",
                "offsetInMeasuredChunk",
            ),
        "synthesis" to
            setOf(
                "SpeechSynthesizer",
                "SynthesisQueue",
                "ChunkAudio",
                "TextChunker",
                "NothingToReadException",
                "NoOfflineVoiceAvailableException",
            ),
        "synthesis.audio" to setOf("AudioFileCache", "DirectoryAudioFileCache", "ChunkFiles"),
        "playback" to
            setOf(
                "PlaybackController",
                "ListenPlaybackController",
                "PlayableItem",
                "PlaybackStatus",
                "PlaybackEvent",
                "PlaybackPhase",
                "PlaybackSpeed",
                "ArticleDisplayData",
                "SEEK_BACK_INCREMENT_MS",
                "SEEK_FORWARD_INCREMENT_MS",
            ),
        "settings" to setOf("ListenSettings", "ListenEnabledState"),
        "voices" to setOf("Voice", "VoiceCatalog", "VoicePreferences", "VoiceSelector", "VoiceLoad"),
        "narration" to
            setOf(
                "Narrator",
                "NarrationCommand",
                "NarrationState",
                "NarrationEvent",
                "Article",
                "SessionEngines",
                "EngineVoiceCatalog",
            ),
        "facts" to
            setOf(
                "ListenToPageFacts",
                "emitEngineBoundFact",
                "emitChunkSynthesizedFact",
                "emitOpeningSynthesisStartedFact",
                "emitMediaControlFact",
            ),
        "" to setOf("ListenStore", "ListenState", "ListenAction", "ListenError", "PlayerMode", "VoiceState"),
    )

/** The packages each package may import from. A package may always import from itself. */
private val MAY_IMPORT =
    mapOf(
        "content" to emptySet(),
        "progress" to emptySet(),
        "settings" to setOf("voices"),
        "voices" to emptySet(),
        "narration" to setOf("content", "progress", "synthesis", "synthesis.audio", "playback", "facts", "voices"),
        "facts" to emptySet(),
        "synthesis" to setOf("facts", "synthesis.audio", "voices"),
        "synthesis.audio" to emptySet(),
        "playback" to setOf("facts"),
        "" to
            setOf(
                "content",
                "progress",
                "synthesis",
                "synthesis.audio",
                "playback",
                "settings",
                "facts",
                "voices",
                "narration",
            ),
        "ui" to setOf(""),
    )

/** The value types the player shows, which `ui` may import from beyond the root package. */
private val UI_VALUE_TYPES =
    setOf("playback.PlaybackPhase", "playback.PlaybackSpeed", "progress.ArticleProgress", "voices.Voice")

/**
 * Keeps the packages of the module apart, as if each were a module of its own: `synthesis` and `playback` do not know
 * about each other or the store, and each package is reached only through its API.
 */
class PackageBoundaryTest {

    @Test
    fun `test that every package imports only what it may`() {
        assertTrue("run from the module directory", SOURCE_ROOT.isDirectory)

        assertEquals(emptyList<String>(), violations())
    }

    private fun violations(): List<String> =
        SOURCE_ROOT.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file -> file.violations().map { "${file.relativeTo(SOURCE_ROOT)}: $it" } }
            .sorted()
            .toList()

    private fun File.violations(): List<String> {
        val lines = readLines()
        val pkg =
            lines
                .first { it.startsWith("package ") }
                .removePrefix("package $MODULE_PACKAGE")
                .removePrefix(".")
                .removePrefix("package ")
        val allowed = MAY_IMPORT[pkg] ?: return listOf("package '$pkg' has no rules")

        return lines
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").substringBefore(" as ") }
            .mapNotNull { import -> violationOf(pkg, allowed, import) }
    }

    @Suppress("ReturnCount")
    private fun violationOf(pkg: String, allowed: Set<String>, import: String): String? {
        if (import.startsWith("mozilla.components.browser.state")) return "imports $import"
        if (!import.startsWith("$MODULE_PACKAGE.")) return null

        val path = import.removePrefix("$MODULE_PACKAGE.")
        if (path == "R" || path.startsWith("R.")) return null

        // The package is the segments before the first type, so that a nested type is named by its outer one.
        val segments = path.split('.')
        val typeAt = segments.indexOfFirst { it.first().isUpperCase() }.takeIf { it >= 0 } ?: segments.lastIndex
        val name = segments[typeAt]
        val from = segments.take(typeAt).joinToString(".")
        // A package and its sub-packages, such as `synthesis` and `synthesis.audio`, are one package to each other.
        if (from == pkg || from.startsWith("$pkg.") || pkg.startsWith("$from.")) return null

        if (pkg == "ui" && path in UI_VALUE_TYPES) return null
        if (from !in allowed) return "imports $import from a package it may not"
        if (name !in API.getValue(from)) return "imports $import, which is not part of the API of '$from'"

        return null
    }
}
