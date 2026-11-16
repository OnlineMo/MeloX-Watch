package io.github.onlinemo.meloxwatch.data.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CueRelativePathTest {
    @Test fun relativeSeparatorsDotsAndUnicodeNormalizeToNamesWithoutUriDecoding() {
        assertEquals(CueRelativePath.parse("music/Café.flac"), CueRelativePath.parse("./music\\Cafe\u0301.flac"))
        assertEquals(listOf("%2e%2e", "a.flac"), CueRelativePath.parse("%2e%2e/a.flac").components)
        assertEquals(listOf(" a.flac "), CueRelativePath.parse(" a.flac ").components)
        assertEquals(listOf("music", "\uD83C\uDFB5.flac"), CueRelativePath.parse("music/\uD83C\uDFB5.flac").components)
    }

    @Test fun absoluteDriveUncUriParentTraversalAndHiddenControlsAreRejected() {
        for (path in listOf("/a.flac", "\\a.flac", "C:\\music\\a.flac", "\\\\server\\music\\a.flac",
                "content://provider/a.flac", "https://example.invalid/a.flac", "../a.flac", "sub/../a.flac",
                "a\u0000.flac", "a\u202e.flac", "a//b.flac", "a/", ".")) {
            expectCueIssue(CueIssue.INVALID_PATH) { CueRelativePath.parse(path) }
        }
        for (name in listOf("a\uDB40\uDC01.flac", "a\uDB40\uDC20.flac", "a\uD800.flac", "a\uDC00.flac")) {
            expectCueIssue(CueIssue.INVALID_PATH) { CueRelativePath.parse(name) }
            expectCueIssue(CueIssue.INVALID_PATH) { cueNameKey(name, CueNamePolicy.EXACT) }
            expectCueIssue(CueIssue.INVALID_PATH) { cueNameKey(name, CueNamePolicy.FOLD_CASE) }
        }
    }

    @Test fun componentTotalLengthAndDepthBoundsNeverReturnAPartialPath() {
        assertEquals(512, CueRelativePath.parse("x".repeat(512)).components.single().length)
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CueRelativePath.parse("x".repeat(513)) }
        val maximumDepth = (1..16).joinToString("/") { "a" }
        assertEquals(16, CueRelativePath.parse(maximumDepth).components.size)
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CueRelativePath.parse("$maximumDepth/a") }
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CueRelativePath.parse("x".repeat(CueLimits.PATH_CHARACTERS + 1)) }
    }

    @Test fun caseFoldingIsExplicitAndNfcEquivalenceDoesNotInventAFallback() {
        assertNotEquals(cueNameKey("Track.FLAC", CueNamePolicy.EXACT), cueNameKey("track.flac", CueNamePolicy.EXACT))
        assertEquals(cueNameKey("Track.FLAC", CueNamePolicy.FOLD_CASE), cueNameKey("track.flac", CueNamePolicy.FOLD_CASE))
        assertEquals(cueNameKey("Café.flac", CueNamePolicy.EXACT), cueNameKey("Cafe\u0301.flac", CueNamePolicy.EXACT))
    }
}
