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

    @Test fun androidJpegIccProfileIsBoundedAndMalformedOrRepeatedColorPayloadsAreRejected(): Unit {
        val valid = Fixture.raster(Bitmap.CompressFormat.JPEG)
        val marker = iccMarker(valid)
        val count = unsigned16(valid, marker + 2)
        val profile = marker + 18
        ThemeImageHeaders.verify(valid, "image/jpeg") {}
        val bitmap = validator.decodeImage(valid, "image/jpeg", 32, false, CancellationSignal())
        try { assertEquals(255, Color.alpha(bitmap.getPixel(16, 16))) }
        finally { bitmap.recycle() }

        val multipart = valid.copyOf().also { it[marker + 16] = 2 }
        Fixture.expect(ThemeIssue.UNSUPPORTED_RESOURCE) { ThemeImageHeaders.verify(multipart, "image/jpeg") {} }
        val arbitrary = valid.copyOf().also { it[marker + 4] = 'X'.code.toByte() }
        Fixture.expect(ThemeIssue.UNSUPPORTED_RESOURCE) { ThemeImageHeaders.verify(arbitrary, "image/jpeg") {} }
        val signature = valid.copyOf().also { it[profile + 36] = 0 }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(signature, "image/jpeg") {} }
        val truncated = valid.copyOf().also { putBig32(it, profile, count - 15) }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(truncated, "image/jpeg") {} }
        val escapingTag = valid.copyOf().also { putBig32(it, profile + 136, count) }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(escapingTag, "image/jpeg") {} }
        val repeated = valid.copyOfRange(0, marker) + valid.copyOfRange(marker, marker + count + 2) +
            valid.copyOfRange(marker, valid.size)
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(repeated, "image/jpeg") {} }
    }

    @Test fun androidWebpIccIsBoundedAndMustMatchItsExtendedHeader(): Unit {
        val valid = Fixture.raster(Bitmap.CompressFormat.WEBP)
        assertEquals("VP8X", String(valid, 12, 4, Charsets.US_ASCII))
        assertEquals(0x20, (valid[20].toInt() and 0x20))
        val marker = webpChunkOffset(valid, "ICCP")
        val count = little32(valid, marker + 4)
        val end = marker + 8 + count + (count and 1)
        val profile = marker + 8
        ThemeImageHeaders.verify(valid, "image/webp") {}
        val bitmap = validator.decodeImage(valid, "image/webp", 32, false, CancellationSignal())
        try { assertEquals(255, Color.alpha(bitmap.getPixel(16, 16))) }
        finally { bitmap.recycle() }

        val noFlag = valid.copyOf().also { it[20] = (it[20].toInt() and 0xdf).toByte() }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(noFlag, "image/webp") {} }
        val noProfile = riffLength(valid.copyOfRange(0, marker) + valid.copyOfRange(end, valid.size))
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(noProfile, "image/webp") {} }
        val noExtendedHeader = riffLength(valid.copyOfRange(0, 12) + valid.copyOfRange(30, valid.size))
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(noExtendedHeader, "image/webp") {} }
        val signature = valid.copyOf().also { it[profile + 36] = 0 }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(signature, "image/webp") {} }
        val wrongSize = valid.copyOf().also { putBig32(it, profile, count - 1) }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(wrongSize, "image/webp") {} }
        val escapingTag = valid.copyOf().also { putBig32(it, profile + 136, count) }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(escapingTag, "image/webp") {} }
        val originalChunk = valid.copyOfRange(marker, end)
        val repeated = riffLength(valid.copyOfRange(0, marker) + originalChunk + valid.copyOfRange(marker, valid.size))
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(repeated, "image/webp") {} }
        val late = riffLength(valid.copyOfRange(0, marker) + valid.copyOfRange(end, valid.size) + originalChunk)
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(late, "image/webp") {} }

        val oversizedProfile = valid.copyOfRange(profile, profile + count).copyOf(64 * 1024 + 1)
        putBig32(oversizedProfile, 0, oversizedProfile.size)
        val oversized = riffLength(valid.copyOfRange(0, marker) + webpChunk("ICCP", oversizedProfile) +
            valid.copyOfRange(end, valid.size))
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(oversized, "image/webp") {} }
        for (unsupported in listOf("EXIF", "XMP ", "ANIM")) {
            val added = riffLength(valid + webpChunk(unsupported, byteArrayOf(0, 0)))
            Fixture.expect(ThemeIssue.UNSUPPORTED_RESOURCE) { ThemeImageHeaders.verify(added, "image/webp") {} }
        }
    }

    @Test fun webpAlphaChunksAndLosslessHintsPreserveActualTransparency(): Unit {
        val opaque = Fixture.raster(Bitmap.CompressFormat.WEBP)
        val falseAlpha = opaque.copyOf().also { it[20] = (it[20].toInt() or 0x10).toByte() }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(falseAlpha, "image/webp") {} }
        val lateOpaqueAlpha = riffLength(opaque + webpChunk("ALPH", byteArrayOf(0, 0)))
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(lateOpaqueAlpha, "image/webp") {} }

        val alpha = nativeAlphaWebp()
        assertWebpTransparency(alpha)
        val marker = webpChunkOffset(alpha, "ALPH")
        val count = little32(alpha, marker + 4)
        val end = marker + 8 + count + (count and 1)
        val originalChunk = alpha.copyOfRange(marker, end)
        val noFlag = alpha.copyOf().also { it[20] = (it[20].toInt() and 0xef).toByte() }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(noFlag, "image/webp") {} }
        val missing = riffLength(alpha.copyOfRange(0, marker) + alpha.copyOfRange(end, alpha.size))
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(missing, "image/webp") {} }
        val late = riffLength(alpha.copyOfRange(0, marker) + alpha.copyOfRange(end, alpha.size) + originalChunk)
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(late, "image/webp") {} }
        val reserved = alpha.copyOf().also { it[marker + 8] = (it[marker + 8].toInt() or 0x80).toByte() }
        Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(reserved, "image/webp") {} }

        if (Build.VERSION.SDK_INT >= 30) {
            val lossless = nativeAlphaWebp(lossless = true)
            assertWebpTransparency(lossless)
            val image = webpChunkOffset(lossless, "VP8L")
            assertEquals(0x10, lossless[image + 12].toInt() and 0x10)
            val noHint = lossless.copyOf().also { it[image + 12] = (it[image + 12].toInt() and 0xef).toByte() }
            // The official codec specification makes this hint nonauthoritative; real alpha still decodes.
            assertWebpTransparency(noHint)
            val mixed = riffLength(lossless.copyOfRange(0, image) + originalChunk + lossless.copyOfRange(image, lossless.size))
            Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(mixed, "image/webp") {} }
            val futureCodec = lossless.copyOf().also { it[image + 12] = (it[image + 12].toInt() or 0x20).toByte() }
            Fixture.expect(ThemeIssue.INVALID_IMAGE) { ThemeImageHeaders.verify(futureCodec, "image/webp") {} }
            val imageCount = little32(lossless, image + 4)
            val simple = riffLength(lossless.copyOfRange(0, 12) +
                lossless.copyOfRange(image, image + 8 + imageCount + (imageCount and 1)))
            assertWebpTransparency(simple)
        }
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

    private fun nativeAlphaWebp(lossless: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.TRANSPARENT)
            for (y in 8 until 24) for (x in 8 until 24) bitmap.setPixel(x, y, Color.RED)
            val output = java.io.ByteArrayOutputStream()
            val format = if (lossless && Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS
                else Bitmap.CompressFormat.WEBP
            assertTrue(bitmap.compress(format, 85, output))
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertWebpTransparency(bytes: ByteArray) {
        ThemeImageHeaders.verify(bytes, "image/webp") {}
        val bitmap = validator.decodeImage(bytes, "image/webp", 32, false, CancellationSignal())
        try {
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals(255, Color.alpha(bitmap.getPixel(16, 16)))
            assertTrue(bitmap.allocationByteCount <= ArtworkLimits.BITMAP_BYTES)
        } finally {
            bitmap.recycle()
        }
    }

    private fun webpChunkOffset(bytes: ByteArray, type: String): Int {
        var offset = 12
        while (offset <= bytes.size - 8) {
            val count = little32(bytes, offset + 4)
            assertTrue(count >= 0 && count <= bytes.size - offset - 8)
            if (String(bytes, offset, 4, Charsets.US_ASCII) == type) return offset
            offset += 8 + count + (count and 1)
        }
        throw AssertionError("The Android-generated WebP must contain its original ICC color profile")
    }

    private fun little32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun riffLength(bytes: ByteArray): ByteArray = bytes.also {
        ByteBuffer.wrap(it, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(it.size - 8)
    }

    private fun webpChunk(type: String, data: ByteArray): ByteArray {
        val bytes = ByteArray(8 + data.size + (data.size and 1))
        type.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size)
        data.copyInto(bytes, 8)
        return bytes
    }

    private fun iccMarker(bytes: ByteArray): Int {
        var offset = 2
        while (offset < bytes.size - 4) {
            assertEquals(255, bytes[offset].toInt() and 255)
            val marker = bytes[offset + 1].toInt() and 255
            if (marker == 226) return offset
            if (marker == 218 || marker == 217) break
            val count = unsigned16(bytes, offset + 2)
            assertTrue(count >= 2 && count <= bytes.size - offset - 2)
            offset += count + 2
        }
        throw AssertionError("The Android-generated JPEG must contain its original ICC color profile")
    }

    private fun unsigned16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

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
