package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExchangeRelativePathTest {
    @Test fun windowsSeparatorsAndDotSegmentsNormalizeWithoutDecodingPercentText() {
        val path = ExchangeRelativePath.parse("./Cafe\u0301\\./%2e%2e/%2f.flac")
        assertEquals(listOf("Café", "%2e%2e", "%2f.flac"), path.components)
        assertEquals("Café/%2e%2e/%2f.flac", path.toString())
        assertEquals(path, ExchangeRelativePath.parse("Café/%2e%2e/%2f.flac"))
    }

    @Test fun supplementaryMusicSymbolsRemainValidAndPathComponentsCannotBeMutated() {
        val symbol = String(Character.toChars(0x1d11e))
        val path = ExchangeRelativePath.parse("music/$symbol.flac")
        assertEquals(listOf("music", "$symbol.flac"), path.components)
        try {
            (path.components as MutableList<String>).add("another.flac")
            throw AssertionError("Expected immutable components")
        } catch (_: UnsupportedOperationException) {
            assertEquals(2, path.components.size)
        }
    }

    @Test fun absoluteUriParentAndEmptyComponentsCannotBecomeGrantedTreeHints() {
        runBlocking {
            for (value in listOf("/a.flac", "\\\\server\\a.flac", "C:\\a.flac", "https://example.invalid/a.flac",
                "a/../b.flac", "a//b.flac", "a/", ".", "", " ")) {
                expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) { ExchangeRelativePath.parse(value) }
            }
        }
    }

    @Test fun c0C1AndBmpOrSupplementaryFormatControlsAreRejected() {
        runBlocking {
            val controls = listOf("\u0001", "\u007f", "\u0085", "\u200d", "\u202e", "\u2028",
                "\u2029", String(Character.toChars(0xe0001)))
            for (control in controls) {
                expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) {
                    ExchangeRelativePath.parse("music${control}/one.flac")
                }
            }
        }
    }

    @Test fun unpairedSurrogatesAndNulHaveExplicitFailures() {
        runBlocking {
            for (value in listOf("a\ud800.flac", "a\udfff.flac")) {
                expectExchangeIssue(PlaylistExchangeIssue.INVALID_ENCODING) { ExchangeRelativePath.parse(value) }
            }
            expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) { ExchangeRelativePath.parse("a\u0000.flac") }
        }
    }

    @Test fun componentAndCompletePathBudgetsApplyAfterUnicodeNormalization() {
        runBlocking {
            assertEquals(512, ExchangeRelativePath.parse("a".repeat(512)).components.single().length)
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) { ExchangeRelativePath.parse("a".repeat(513)) }
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) {
                ExchangeRelativePath.parse("\u0344".repeat(257))
            }
            val expandsPastWholePathBudget = List(8) { "\u0344".repeat(256) }.joinToString("/")
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) {
                ExchangeRelativePath.parse(expandsPastWholePathBudget)
            }
        }
    }

    @Test fun depthCountsTheGrantedRootAndExcludesTheFinalAudioNameLikeCue() {
        runBlocking {
            val allowed = (List(15) { "d$it" } + "one.flac").joinToString("/")
            assertEquals(16, ExchangeRelativePath.parse(allowed).components.size)
            val tooDeep = (List(16) { "d$it" } + "one.flac").joinToString("/")
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) { ExchangeRelativePath.parse(tooDeep) }
        }
    }

    @Test fun playlistNamesCountUnicodeCodePointsAndRejectHiddenControls() {
        runBlocking {
            val symbol = String(Character.toChars(0x1d11e))
            assertEquals("Café", validateExchangeName("  Cafe\u0301  "))
            assertEquals(symbol.repeat(80), validateExchangeName(symbol.repeat(80)))
            expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) { validateExchangeName(symbol.repeat(81)) }
            for (name in listOf("a\u0085b", "a\u202eb", "a${String(Character.toChars(0xe0001))}b")) {
                expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) { validateExchangeName(name) }
            }
        }
    }

    @Test fun localTrackKeysCannotContainHiddenControlsOrAdoptOnlineProviders() {
        runBlocking {
            validateExchangeKey(TrackKey(TrackKey.LOCAL_PROVIDER, "stable-string-id"))
            expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) { validateExchangeKey(TrackKey("online", "id")) }
            expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) {
                validateExchangeKey(TrackKey(TrackKey.LOCAL_PROVIDER, "a${String(Character.toChars(0xe0001))}b"))
            }
            assertTrue(containsExchangeControl("a\u0085b"))
        }
    }
}
