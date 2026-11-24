package io.github.onlinemo.meloxwatch.data.fonts

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real private files and original sfnt bytes; native glyph behavior is tested in its own suite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class AndroidLyricsFontFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun twoIndependentPrivateCopiesRestoreAndPruneOnlyUnreferencedApplicationFiles() = runBlocking {
        val directory = temporary.newFolder("private-fonts")
        val variable = Fixture.bytes()
        val first = store(directory, BytesFontStream(variable)).importFont(Fixture.request(), emptySet())
        val secondStore = store(directory, BytesFontStream(Fixture.bytes(Fixture.STATIC)))
        val second = secondStore.importFont(Fixture.request(Fixture.STATIC), setOf(first.asset.id))
        assertNotNull(secondStore.loadFont(LyricsFontSelection(first.asset, 600f), "MeloX").typeface)
        assertNotNull(secondStore.loadFont(LyricsFontSelection(second.asset), "Watch").typeface)
        assertEquals(variable.toList(), File(directory, first.asset.id + ".font").readBytes().toList())
        secondStore.prune(setOf(second.asset.id))
        assertFalse(File(directory, first.asset.id + ".font").exists())
        assertTrue(File(directory, second.asset.id + ".font").isFile)
    }

    @Test fun fileExtensionNeverSubstitutesForActualFontStructure() = runBlocking {
        val directory = temporary.newFolder("structure")
        val decoder = FilesOnlyFontDecoder()
        val real = store(directory, BytesFontStream(Fixture.bytes()), decoder)
            .importFont(Fixture.request().copy(displayName = "selected.txt"), emptySet())
        assertEquals(SfntOutline.TRUE_TYPE, real.info.outline)
        val invalid = store(directory, BytesFontStream("not a font at all".toByteArray()), decoder)
        expect(LyricsFontIssue.UNSUPPORTED_FORMAT) {
            invalid.importFont(Fixture.request().copy(displayName = "pretend.ttf"), setOf(real.asset.id))
        }
        assertEquals(1, decoder.validations.get())
        assertEquals(listOf(real.asset.id + ".font"), checkNotNull(directory.list()).toList())
    }

    @Test fun declaredAndActualOversizeAreRejectedAndCloseTheInputBeforeStagingRemoval() = runBlocking {
        val directory = temporary.newFolder("sizes")
        val declared = BytesFontStream(Fixture.bytes(), LyricsFontLimits.FILE_BYTES + 1L)
        val decoder = FilesOnlyFontDecoder()
        expect(LyricsFontIssue.TOO_LARGE) { store(directory, declared, decoder).importFont(Fixture.request(), emptySet()) }
        assertEquals(1, declared.closes.get())
        assertTrue(checkNotNull(directory.list()).isEmpty())
        val actual = BytesFontStream(ByteArray(LyricsFontLimits.FILE_BYTES + 1), null)
        expect(LyricsFontIssue.TOO_LARGE) { store(directory, actual, decoder).importFont(Fixture.request(), emptySet()) }
        assertEquals(1, actual.closes.get())
        assertEquals(0, decoder.validations.get())
        assertTrue(checkNotNull(directory.list()).isEmpty())
    }

    @Test fun freeSpaceReserveStopsARealCopyAndKeepsThePreviouslySavedPrivateFont() = runBlocking {
        val directory = temporary.newFolder("space")
        val first = store(directory, BytesFontStream(Fixture.bytes())).importFont(Fixture.request(), emptySet())
        val source = BytesFontStream(Fixture.bytes(Fixture.STATIC))
        val lowSpace = store(directory, source, available = { LyricsFontLimits.FREE_RESERVE_BYTES })
        expect(LyricsFontIssue.STORAGE_FULL) { lowSpace.importFont(Fixture.request(Fixture.STATIC), setOf(first.asset.id)) }
        assertTrue(File(directory, first.asset.id + ".font").isFile)
        assertEquals(listOf(first.asset.id + ".font"), checkNotNull(directory.list()).toList())
        assertEquals(1, source.closes.get())
    }

    @Test fun totalQuotaCountsActualRetainedBytesEvenWhenDiskRecordsAreCorrupt() = runBlocking {
        val directory = temporary.newFolder("quota")
        val ids = (0 until 3).map { Fixture.asset().id }.toSet()
        ids.forEachIndexed { index, id ->
            val size = if (index == 0) LyricsFontLimits.OWNED_BYTES else 1L
            java.io.RandomAccessFile(File(directory, "$id.font"), "rw").use { it.setLength(size) }
        }
        val decoder = FilesOnlyFontDecoder()
        expect(LyricsFontIssue.QUOTA_EXCEEDED) {
            store(directory, BytesFontStream(Fixture.bytes()), decoder).importFont(Fixture.request(), ids)
        }
        assertEquals(3, checkNotNull(directory.list()).size)
        assertEquals(0, decoder.validations.get())
    }

    @Test fun tamperedCopyMissingGlyphAndLowMemoryHaveExplicitSystemFallbacks() = runBlocking {
        val directory = temporary.newFolder("fallback")
        val decoder = FilesOnlyFontDecoder()
        val files = store(directory, BytesFontStream(Fixture.bytes()), decoder)
        val candidate = files.importFont(Fixture.request(), emptySet())
        val selection = LyricsFontSelection(candidate.asset, 600f)
        val missing = files.loadFont(selection, "永")
        assertTrue(missing.usesSystemFont)
        assertEquals(LyricsFontIssue.MISSING_GLYPHS, missing.issue)
        assertEquals(1, missing.missingCodePoints)
        decoder.failure = { OutOfMemoryError("Controlled font allocation failure") }
        val lowMemory = files.loadFont(selection, "H")
        assertTrue(lowMemory.usesSystemFont)
        assertEquals(LyricsFontIssue.LOW_MEMORY, lowMemory.issue)
        decoder.failure = null
        val path = File(directory, candidate.asset.id + ".font")
        val bytes = path.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        path.writeBytes(bytes)
        val tampered = files.loadFont(selection, "H")
        assertTrue(tampered.usesSystemFont)
        assertEquals(LyricsFontIssue.INTEGRITY_FAILED, tampered.issue)
        assertTrue(path.isFile)
    }

    @Test fun revokedImportPermissionPreservesSavedCopyAndRequiresLicenseAcknowledgment() = runBlocking {
        val directory = temporary.newFolder("denied")
        val first = store(directory, BytesFontStream(Fixture.bytes())).importFont(Fixture.request(), emptySet())
        val denied = BytesFontStream(Fixture.bytes(Fixture.STATIC)).apply { this.denied = true }
        val files = store(directory, denied)
        expect(LyricsFontIssue.LICENSE_ACKNOWLEDGMENT_REQUIRED) {
            files.importFont(Fixture.request().copy(licenseAcknowledgmentVersion = 0), setOf(first.asset.id))
        }
        assertEquals(0, denied.opens.get())
        expect(LyricsFontIssue.PERMISSION_DENIED) { files.importFont(Fixture.request(), setOf(first.asset.id)) }
        assertEquals(listOf(first.asset.id + ".font"), checkNotNull(directory.list()).toList())
    }

    @Test fun privateSymlinkCannotReadOrDeleteTheExternalOriginal() = runBlocking {
        val directory = temporary.newFolder("links")
        val original = Fixture.file(temporary.root)
        val bytes = original.readBytes()
        val asset = Fixture.asset(bytes)
        val link = File(directory, asset.id + ".font")
        Files.createSymbolicLink(link.toPath(), original.toPath())
        val result = store(directory, BytesFontStream(bytes)).loadFont(LyricsFontSelection(asset), "H")
        assertTrue(result.usesSystemFont)
        assertEquals(LyricsFontIssue.INTEGRITY_FAILED, result.issue)
        assertEquals(Fixture.sha256(bytes), Fixture.sha256(original.readBytes()))
        assertTrue(original.isFile)
    }

    private fun store(
        directory: File,
        source: LyricsFontStreamOpener,
        decoder: FilesOnlyFontDecoder = FilesOnlyFontDecoder(),
        available: (File) -> Long = { Fixture.ROOM },
    ): AndroidLyricsFontFiles = AndroidLyricsFontFiles(directory, LocalProviderIo(), source, decoder, available)

    private suspend fun expect(issue: LyricsFontIssue, block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected $issue") }
        catch (error: LyricsFontException) { assertEquals(issue, error.issue) }
    }
}
