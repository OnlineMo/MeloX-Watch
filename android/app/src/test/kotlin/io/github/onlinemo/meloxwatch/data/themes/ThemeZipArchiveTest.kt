package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class ThemeZipArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun storedAndDeflatedSignedDescriptorPackagesExtractExactlyTheirDeclaredBytes() {
        val asset = Fixture.Asset("cover", "assets/cover.png", ByteArray(2048) { (it * 37 + it / 7).toByte() })
        val value = Fixture.packageOf(assets = listOf(asset))
        for ((method, descriptor) in listOf(0 to false, 8 to false, 8 to true, 0 to true)) {
            val original = value.zip(method, descriptor)
            val archiveFile = write(original)
            val archive = ThemeZipArchive(archiveFile)
            val index = archive.inspect {}
            val manifest = ThemeManifestReader.read(archive.readManifest(index) {})
            val payload = temporary.newFolder()
            var admitted = 0L
            archive.extract(index, manifest, payload, { admitted += it }) {}
            assertArrayEquals(value.manifest, File(payload, "theme.json").readBytes())
            assertArrayEquals(asset.bytes, File(payload, asset.path).readBytes())
            assertEquals(value.manifest.size.toLong() + asset.bytes.size, admitted)
            assertArrayEquals(original, archiveFile.readBytes())
        }
    }

    @Test fun centralAndLocalNamesFlagsAndDescriptorChecksMustAgree() {
        val base = Fixture.ZipRecord("theme.json", Fixture.packageOf().manifest, method = 8, descriptor = true)
        for (record in listOf(base.copy(localName = "other.json"), base.copy(localFlags = 0x800),
            base.copy(descriptorCrc = base.crc32 xor 1), base.copy(descriptorSignature = 0))) {
            Fixture.expect(ThemeIssue.INVALID_ARCHIVE) { ThemeZipArchive(write(Fixture.archive(listOf(record)))).inspect {} }
        }
    }

    @Test fun crcCorruptionCannotBeAcceptedMerelyBecauseTheHeaderSizesMatch() {
        val bytes = Fixture.packageOf().manifest
        val record = Fixture.ZipRecord("theme.json", bytes, crc32 = Fixture.crc(bytes) xor 1)
        val archive = ThemeZipArchive(write(Fixture.archive(listOf(record))))
        val index = archive.inspect {}
        Fixture.expect(ThemeIssue.INTEGRITY_FAILED) { archive.readManifest(index) {} }
    }

    @Test fun extraCompressedBytesAfterTheRealDeflateEndAreRejected() {
        val bytes = Fixture.packageOf().manifest
        val compressed = deflate(bytes) + byteArrayOf(0x7f)
        val archive = ThemeZipArchive(write(Fixture.archive(listOf(Fixture.ZipRecord("theme.json", bytes,
            method = 8, compressed = compressed)))))
        val index = archive.inspect {}
        Fixture.expect(ThemeIssue.INTEGRITY_FAILED) { archive.readManifest(index) {} }
    }

    @Test fun actualInflationCannotWriteBeyondTheDeclaredResourceEvenWithCoherentHeaders() {
        val actual = ByteArray(4096) { (it * 41 + it / 13).toByte() }
        val declared = actual.copyOf(actual.size - 1)
        val resource = Fixture.Asset("cover", "assets/cover.png", declared)
        val value = Fixture.packageOf(assets = listOf(resource))
        val records = listOf(Fixture.ZipRecord("theme.json", value.manifest, method = 8),
            Fixture.ZipRecord(resource.path, actual, method = 8, expandedBytes = declared.size.toLong()))
        val archive = ThemeZipArchive(write(Fixture.archive(records)))
        val index = archive.inspect {}
        val manifest = ThemeManifestReader.read(archive.readManifest(index) {})
        val payload = temporary.newFolder()
        Fixture.expect(ThemeIssue.INTEGRITY_FAILED) { archive.extract(index, manifest, payload, {}) {} }
        assertTrue(File(payload, resource.path).length() <= declared.size)
    }

    @Test fun trueCompressionBombAndDeclaredTotalOverflowFailBeforeExtraction() {
        Fixture.expect(ThemeIssue.EXPANSION_RATIO) {
            ThemeZipArchive(write(Fixture.archive(listOf(Fixture.ZipRecord("theme.json", ByteArray(4096), method = 8))))).inspect {}
        }
        val overflow = listOf(
            Fixture.ZipRecord("theme.json", byteArrayOf(), method = 8, expandedBytes = 16L * 1024 * 1024,
                compressedCount = 1024 * 1024),
            Fixture.ZipRecord("assets/cover.png", byteArrayOf(), method = 8, expandedBytes = 17L * 1024 * 1024,
                compressedCount = 1024 * 1024),
        )
        Fixture.expect(ThemeIssue.EXPANDED_TOO_LARGE) { ThemeZipArchive(write(Fixture.archive(overflow))).inspect {} }
    }

    @Test fun symlinkDeviceFifoExecutableAndWindowsReparseAttributesAreRefused() {
        for (attributes in listOf(0xa1ffL shl 16, 0x21b6L shl 16, 0x11b6L shl 16, 0x81edL shl 16, 0x400L, 0x8L)) {
            Fixture.expect(ThemeIssue.LINK_OR_SPECIAL_FILE) {
                ThemeZipArchive(write(Fixture.archive(listOf(Fixture.ZipRecord("theme.json", Fixture.packageOf().manifest,
                    externalAttributes = attributes))))).inspect {}
            }
        }
    }

    @Test fun duplicateCaseFoldedPathsAndUnsafeLocalNamesAreRejected() {
        Fixture.expect(ThemeIssue.DUPLICATE_PATH) {
            ThemeZipArchive(write(Fixture.archive(listOf(Fixture.ZipRecord("theme.json", Fixture.packageOf().manifest),
                Fixture.ZipRecord("assets/Cover.png", byteArrayOf(1)), Fixture.ZipRecord("assets/cover.png", byteArrayOf(2)))))).inspect {}
        }
        for (name in listOf("../theme.json", "assets/a/../../theme.json", "assets\\cover.png", "assets/C:/cover.png")) {
            Fixture.expect(ThemeIssue.INVALID_PATH) {
                ThemeZipArchive(write(Fixture.archive(listOf(Fixture.ZipRecord(name, byteArrayOf(1)))))).inspect {}
            }
        }
    }

    @Test fun missingAndUndeclaredResourcesAndCaseChangedNamesCannotPassTheManifestBinding() {
        val asset = Fixture.Asset("cover", "assets/Cover.png", byteArrayOf(1))
        val value = Fixture.packageOf(assets = listOf(asset))
        val missing = ThemeZipArchive(write(Fixture.archive(listOf(Fixture.ZipRecord("theme.json", value.manifest)))))
        Fixture.expect(ThemeIssue.MISSING_RESOURCE) {
            missing.validateDeclarations(missing.inspect {}, ThemeManifestReader.read(value.manifest))
        }
        val extra = ThemeZipArchive(write(Fixture.archive(value.records() + Fixture.ZipRecord("assets/unused.png", byteArrayOf(1)))))
        Fixture.expect(ThemeIssue.UNDECLARED_RESOURCE) {
            extra.validateDeclarations(extra.inspect {}, ThemeManifestReader.read(value.manifest))
        }
        val renamed = ThemeZipArchive(write(Fixture.archive(listOf(Fixture.ZipRecord("theme.json", value.manifest),
            Fixture.ZipRecord("assets/cover.png", asset.bytes)))))
        Fixture.expect(ThemeIssue.INTEGRITY_FAILED) {
            renamed.validateDeclarations(renamed.inspect {}, ThemeManifestReader.read(value.manifest))
        }
    }

    @Test fun onlyDeclaredParentDirectoriesCanAppearAndTheyHaveNoPayload() {
        val asset = Fixture.Asset("cover", "assets/cover.png", byteArrayOf(1))
        val value = Fixture.packageOf(assets = listOf(asset))
        val archive = ThemeZipArchive(write(Fixture.archive(value.records() + Fixture.ZipRecord("assets/", byteArrayOf(), method = 8,
            externalAttributes = 0x41edL shl 16))))
        val index = archive.inspect {}
        archive.extract(index, ThemeManifestReader.read(value.manifest), temporary.newFolder(), {}) {}
        val unexpected = ThemeZipArchive(write(Fixture.archive(value.records() + Fixture.ZipRecord("unreferenced/", byteArrayOf()))))
        Fixture.expect(ThemeIssue.UNDECLARED_RESOURCE) {
            unexpected.validateDeclarations(unexpected.inspect {}, ThemeManifestReader.read(value.manifest))
        }
    }

    @Test fun encryptionLocalExtraFieldsSfxPrefixAndTrailingPayloadHaveNoPermissiveFallback() {
        val value = Fixture.packageOf()
        Fixture.expect(ThemeIssue.UNSUPPORTED_ARCHIVE) {
            ThemeZipArchive(write(Fixture.archive(listOf(Fixture.ZipRecord("theme.json", value.manifest, flags = 0x801))))).inspect {}
        }
        Fixture.expect(ThemeIssue.INVALID_ARCHIVE) {
            ThemeZipArchive(write(Fixture.archive(listOf(Fixture.ZipRecord("theme.json", value.manifest,
                extra = byteArrayOf(1, 0, 0, 0)))))).inspect {}
        }
        Fixture.expect(ThemeIssue.INVALID_ARCHIVE) { ThemeZipArchive(write(byteArrayOf(1) + value.zip())).inspect {} }
        Fixture.expect(ThemeIssue.UNSUPPORTED_ARCHIVE) { ThemeZipArchive(write(value.zip() + byteArrayOf(1))).inspect {} }
    }

    @Test fun zip64SentinelsAndExcessiveDirectoryEntryCountsAreRejected() {
        val bytes = Fixture.packageOf().zip()
        bytes[bytes.size - 12] = 0xff.toByte()
        bytes[bytes.size - 11] = 0xff.toByte()
        Fixture.expect(ThemeIssue.UNSUPPORTED_ARCHIVE) { ThemeZipArchive(write(bytes)).inspect {} }
        val records = (0..ThemeLimits.ZIP_ENTRIES).map { Fixture.ZipRecord("assets/r$it.png", byteArrayOf()) }
        Fixture.expect(ThemeIssue.TOO_MANY_RESOURCES) { ThemeZipArchive(write(Fixture.archive(records))).inspect {} }
    }

    @Test fun cancellationDuringRealInflationStopsBeforePublishingTheCompletePayload() {
        val asset = Fixture.Asset("cover", "assets/cover.png", ByteArray(8192) { (it * 31 + it / 3).toByte() })
        val value = Fixture.packageOf(assets = listOf(asset))
        val archive = ThemeZipArchive(write(value.zip(8, true)))
        val index = archive.inspect {}
        val payload = temporary.newFolder()
        var writes = 0
        try {
            archive.extract(index, ThemeManifestReader.read(value.manifest), payload, {
                if (++writes == 2) throw CancellationException("Controlled extraction cancellation")
            }) {}
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(2, writes)
            assertFalse(File(payload, asset.path).length() == asset.bytes.size.toLong())
        }
    }

    private fun write(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0)
                result.write(buffer, 0, count)
            }
            return result.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
