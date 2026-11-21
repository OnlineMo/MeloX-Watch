package io.github.onlinemo.meloxwatch.data.themes

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightSupport
import io.github.onlinemo.meloxwatch.data.fonts.FilesOnlyFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 真 PNG/JPEG/WebP 和原始 OFL 字体交给 Android 原生解码；这些定义不是实机测量或运行记录。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeAssetValidatorNativeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val validator = ThemeAssetValidator()

    @Test fun realPngJpegAndWebpDecodeIntoBoundedVisiblePixels() {
        val images = listOf("image/png" to Fixture.png(), "image/jpeg" to Fixture.raster(Bitmap.CompressFormat.JPEG),
            "image/webp" to Fixture.raster(Bitmap.CompressFormat.WEBP))
        for ((type, bytes) in images) {
            val bitmap = validator.decodeImage(bytes, type, 16, false, CancellationSignal())
            try {
                assertTrue(bitmap.width in 1..16)
                assertTrue(bitmap.height in 1..16)
                assertTrue(bitmap.allocationByteCount <= ArtworkLimits.BITMAP_BYTES)
                assertEquals(255, Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun transparentMaskIconHasRealInkAndTransparentOutsidePixels() {
        val icon = Fixture.Asset("play", "assets/play.png", Fixture.png(icon = true))
        val value = Fixture.packageOf(assets = listOf(icon), icons = mapOf("play" to icon.id))
        val payload = Fixture.payload(temporary.newFolder(), value)
        validator.validate(payload, ThemeManifestReader.read(value.manifest), CancellationSignal())
        val bitmap = validator.decodeImage(icon.bytes, icon.mediaType, 32, true, CancellationSignal())
        try {
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals(255, Color.alpha(bitmap.getPixel(16, 16)))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun opaqueAndOversizedIconsCannotHideTheirFailureBehindAThemePalette() {
        for (asset in listOf(Fixture.Asset("play", "assets/play.png", Fixture.png()),
            Fixture.Asset("play", "assets/play.png", Fixture.png(icon = true, edge = ThemeLimits.ICON_EDGE + 1)))) {
            val value = Fixture.packageOf(assets = listOf(asset), icons = mapOf("play" to asset.id))
            Fixture.expect(ThemeIssue.INVALID_ICON) {
                validator.validate(Fixture.payload(temporary.newFolder(), value), ThemeManifestReader.read(value.manifest), CancellationSignal())
            }
        }
    }

    @Test fun coherentGiantPngHeaderFailsBeforeAnyGiantBitmapAllocation() {
        val bytes = Fixture.png()
        putBig32(bytes, 16, 90000)
        putBig32(bytes, 20, 90000)
        val crc = CRC32()
        crc.update(bytes, 12, 17)
        putBig32(bytes, 29, crc.value.toInt())
        Fixture.expect(ThemeIssue.IMAGE_TOO_LARGE) { validator.decodeImage(bytes, "image/png", 32, false, CancellationSignal()) }
    }

    @Test fun crcCorruptPngTrailingPayloadAndAnimatedChunkAreNotPassedToNativeFallback() {
        val valid = Fixture.png()
        val changed = valid.copyOf()
        changed[16] = (changed[16].toInt() xor 1).toByte()
        Fixture.expect(ThemeIssue.INTEGRITY_FAILED) { ThemeImageHeaders.verify(changed, "image/png") {} }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(valid + byteArrayOf(1), "image/png") {} }
        val animation = pngChunk("acTL", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0))
        val animated = valid.copyOfRange(0, 33) + animation + valid.copyOfRange(33, valid.size)
        Fixture.expect(ThemeIssue.UNSUPPORTED_RESOURCE) { ThemeImageHeaders.verify(animated, "image/png") {} }
    }

    @Test fun jpegPolyglotTailAndNestedImageCannotPassTheSoiEoiEnvelope() {
        val valid = Fixture.raster(Bitmap.CompressFormat.JPEG)
        ThemeImageHeaders.verify(valid, "image/jpeg") {}
        Fixture.expect(ThemeIssue.INVALID_IMAGE) {
            ThemeImageHeaders.verify(valid + byteArrayOf(1, 2, 0xff.toByte(), 0xd9.toByte()), "image/jpeg") {}
        }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(byteArrayOf(0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xd9.toByte()), "image/jpeg") {} }
    }

    @Test fun animatedWebpFlagsAndRiffLengthMismatchHaveNoFirstFrameSuccess() {
        val header = ByteArray(30)
        "RIFF".toByteArray().copyInto(header, 0)
        ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(22)
        "WEBPVP8X".toByteArray().copyInto(header, 8)
        ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(10)
        header[20] = 2
        Fixture.expect(ThemeIssue.UNSUPPORTED_RESOURCE) { ThemeImageHeaders.verify(header, "image/webp") {} }
        val valid = Fixture.raster(Bitmap.CompressFormat.WEBP)
        valid[4] = (valid[4].toInt() xor 1).toByte()
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(valid, "image/webp") {} }
    }

    @Test fun unchangedLicensedVariableTtfAndStaticOtfUseTheExistingNativeFontValidator() {
        val original = Fixture.font()
        val translation = Fixture.font("SourceCodePro-Regular.otf", "font.translation")
        val value = Fixture.packageOf(assets = listOf(original, translation), fonts = mapOf("original" to original.id,
            "translation" to translation.id))
        val directory = Fixture.payload(temporary.newFolder(), value)
        val result = validator.validate(directory, ThemeManifestReader.read(value.manifest), CancellationSignal())
        assertEquals(FontWeightSupport.NO_AXIS, result.getValue(LyricsFontRole.TRANSLATION).support)
        val capability = result.getValue(LyricsFontRole.ORIGINAL)
        assertEquals(if (Build.VERSION.SDK_INT >= 28) FontWeightSupport.AVAILABLE else FontWeightSupport.PLATFORM_UNAVAILABLE,
            capability.support)
        if (Build.VERSION.SDK_INT >= 28) assertTrue(checkNotNull(capability.observation).changed)
        assertArrayEquals(original.bytes, File(directory, original.path).readBytes())
        assertArrayEquals(translation.bytes, File(directory, translation.path).readBytes())
    }

    @Test fun malformedFontAndSameSizeResourceCorruptionRemainFailures() {
        val font = Fixture.font()
        val malformed = font.copy(bytes = ByteArray(font.bytes.size))
        val fontPackage = Fixture.packageOf(assets = listOf(malformed), fonts = mapOf("original" to font.id))
        Fixture.expect(ThemeIssue.INVALID_FONT) {
            validator.validate(Fixture.payload(temporary.newFolder(), fontPackage), ThemeManifestReader.read(fontPackage.manifest),
                CancellationSignal())
        }
        val image = Fixture.Asset("cover", "assets/cover.png", Fixture.png())
        val value = Fixture.packageOf(assets = listOf(image))
        val directory = Fixture.payload(temporary.newFolder(), value)
        val changed = image.bytes.copyOf()
        changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
        File(directory, image.path).writeBytes(changed)
        Fixture.expect(ThemeIssue.INTEGRITY_FAILED) {
            validator.validate(directory, ThemeManifestReader.read(value.manifest), CancellationSignal())
        }
    }

    @Test fun nativeFontLowMemoryKeepsItsResourceErrorMeaningAndOriginalFontBytes() {
        val font = Fixture.font()
        val value = Fixture.packageOf(assets = listOf(font), fonts = mapOf("original" to font.id))
        val directory = Fixture.payload(temporary.newFolder(), value)
        val decoder = FilesOnlyFontDecoder().apply { failure = { LyricsFontException(LyricsFontIssue.LOW_MEMORY) } }
        Fixture.expect(ThemeIssue.LOW_MEMORY) {
            ThemeAssetValidator(decoder).validate(directory, ThemeManifestReader.read(value.manifest), CancellationSignal())
        }
        assertEquals(1, decoder.validations.get())
        assertArrayEquals(font.bytes, File(directory, font.path).readBytes())
    }

    @Test fun undeclaredFontCopyIsRejectedBeforeEitherDeclaredFontReachesNativeValidation() {
        val font = Fixture.font()
        val value = Fixture.packageOf(assets = listOf(font), fonts = mapOf("original" to font.id))
        val directory = Fixture.payload(temporary.newFolder(), value)
        val unlisted = File(directory, "assets/unlisted.ttf").apply { writeBytes(font.bytes) }
        val decoder = FilesOnlyFontDecoder()
        Fixture.expect(ThemeIssue.UNDECLARED_RESOURCE) {
            ThemeAssetValidator(decoder).validate(directory, ThemeManifestReader.read(value.manifest), CancellationSignal())
        }
        assertEquals(0, decoder.validations.get())
        assertArrayEquals(font.bytes, unlisted.readBytes())
        assertArrayEquals(font.bytes, File(directory, font.path).readBytes())
    }

    @Test fun unexpectedDirectoryMissingFileAndLinkedResourceFailTheActualPayloadInventory() {
        val empty = Fixture.packageOf()
        val unexpected = Fixture.payload(temporary.newFolder(), empty)
        assertTrue(File(unexpected, "unlisted").mkdir())
        Fixture.expect(ThemeIssue.UNDECLARED_RESOURCE) {
            validator.validate(unexpected, ThemeManifestReader.read(empty.manifest), CancellationSignal())
        }
        val image = Fixture.Asset("cover", "assets/cover.png", Fixture.png())
        val value = Fixture.packageOf(assets = listOf(image))
        val directory = Fixture.payload(temporary.newFolder(), value)
        val resource = File(directory, image.path)
        assertTrue(resource.delete())
        Fixture.expect(ThemeIssue.MISSING_RESOURCE) {
            validator.validate(directory, ThemeManifestReader.read(value.manifest), CancellationSignal())
        }
        val outside = temporary.newFile().apply { writeBytes(image.bytes) }
        Files.createSymbolicLink(resource.toPath(), outside.toPath())
        Fixture.expect(ThemeIssue.LINK_OR_SPECIAL_FILE) {
            validator.validate(directory, ThemeManifestReader.read(value.manifest), CancellationSignal())
        }
        assertArrayEquals(image.bytes, outside.readBytes())
    }

    private fun putBig32(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value)
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val bytes = ByteArray(12 + data.size)
        putBig32(bytes, 0, data.size)
        type.toByteArray().copyInto(bytes, 4)
        data.copyInto(bytes, 8)
        val crc = CRC32()
        crc.update(bytes, 4, data.size + 4)
        putBig32(bytes, data.size + 8, crc.value.toInt())
        return bytes
    }
}
