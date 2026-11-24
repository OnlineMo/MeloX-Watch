package io.github.onlinemo.meloxwatch.data.fonts

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SfntFontParserTest {
    private val parser = SfntFontParser()

    @Test fun unmodifiedRealTtfHasItsExactWeightAxisAndUnicodeGlyphs() {
        val info = parser.parse(Fixture.bytes())
        assertEquals(SfntOutline.TRUE_TYPE, info.outline)
        assertEquals(FontWeightAxis(100f, 400f, 700f), info.weightAxis)
        assertTrue(info.hasGlyph('H'.code))
        assertTrue(info.hasGlyph('0'.code))
        assertFalse(info.hasGlyph(0x6C38))
        assertFalse(info.hasGlyph(0xD800))
    }

    @Test fun unmodifiedRealOtfUsesCffAndDoesNotInventAWeightAxis() {
        val info = parser.parse(Fixture.bytes(Fixture.STATIC))
        assertEquals(SfntOutline.CFF, info.outline)
        assertNull(info.weightAxis)
        assertTrue(info.hasGlyph('M'.code))
        assertFalse(info.hasGlyph(0x110000))
    }

    @Test fun directoryOverflowDuplicateAndOverlappingTablesAreRejected() {
        val overflow = Fixture.bytes().apply { Fixture.put32(this, 20, Int.MAX_VALUE) }
        Fixture.expect(LyricsFontIssue.INVALID_FONT) { parser.parse(overflow) }
        val duplicate = Fixture.bytes().apply { copyInto(this, 28, 12, 16) }
        Fixture.expect(LyricsFontIssue.INVALID_FONT) { parser.parse(duplicate) }
        val overlap = Fixture.bytes().apply { Fixture.put32(this, 36, Fixture.i32(this, 20)) }
        Fixture.expect(LyricsFontIssue.INVALID_FONT) { parser.parse(overlap) }
    }

    @Test fun reversedAxisAndGlyphLocationRangesCannotReachTheNativeDecoder() {
        val reversed = Fixture.bytes().apply {
            val table = Fixture.tableOffset(this, "fvar")
            val axis = table + Fixture.u16(this, table + 4)
            Fixture.put32(this, axis + 4, 800 * 65536)
        }
        Fixture.expect(LyricsFontIssue.INVALID_FONT) { parser.parse(reversed) }
        val locations = Fixture.bytes().apply {
            val offset = Fixture.tableOffset(this, "loca")
            val head = Fixture.tableOffset(this, "head")
            if (Fixture.u16(this, head + 50) == 0) Fixture.put16(this, offset, 0xFFFF)
            else Fixture.put32(this, offset, Int.MAX_VALUE)
        }
        Fixture.expect(LyricsFontIssue.INVALID_FONT) { parser.parse(locations) }
    }

    @Test fun inputLimitsAndCollectionsAreRejectedBeforeDirectoryAllocation() {
        Fixture.expect(LyricsFontIssue.TOO_LARGE) { parser.parse(ByteArray(LyricsFontLimits.FILE_BYTES + 1)) }
        Fixture.expect(LyricsFontIssue.INVALID_FONT) { parser.parse(byteArrayOf(0, 1)) }
        val collection = ByteArray(12).apply { Fixture.put32(this, 0, 0x74746366) }
        Fixture.expect(LyricsFontIssue.UNSUPPORTED_FORMAT) { parser.parse(collection) }
    }

    @Test fun noUnicodeCmapDoesNotClaimThatSymbolGlyphsCoverLyrics() {
        val bytes = Fixture.bytes().apply {
            val cmap = Fixture.tableOffset(this, "cmap")
            repeat(Fixture.u16(this, cmap + 2)) { index ->
                Fixture.put16(this, cmap + 4 + index * 8, 3)
                Fixture.put16(this, cmap + 6 + index * 8, 0)
            }
        }
        Fixture.expect(LyricsFontIssue.UNSUPPORTED_FORMAT) { parser.parse(bytes) }
    }

    @Test fun textCoverageUsesUnicodeScalarsAndKeepsMissingGlyphsVisible() {
        val info = parser.parse(Fixture.bytes())
        val coverage = info.checkText("H\n永\uDBFF\uDFFF", {})
        assertEquals(2, coverage.missing)
        assertEquals(0x6C38, coverage.firstMissing)
        assertEquals(0, info.checkText("MeloX Watch\t\n", {}).missing)
    }

    @Test fun parserAndTextValidationCooperateWithCallerCancellation() {
        var checks = 0
        try {
            parser.parse(Fixture.bytes()) { if (++checks == 20) throw CancellationException("Controlled parse cancellation") }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(20, checks) }
        val info = parser.parse(Fixture.bytes())
        try {
            info.checkText("H".repeat(4096)) { throw CancellationException("Controlled text cancellation") }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) { }
        var textChecks = 0
        try {
            info.checkText("H" + "\uDBFF\uDFFF".repeat(4096)) {
                if (++textChecks == 3) throw CancellationException("Controlled scalar cancellation")
            }
            throw AssertionError("Expected cancellation between surrogate pairs")
        } catch (_: CancellationException) { assertEquals(3, textChecks) }
    }
}
