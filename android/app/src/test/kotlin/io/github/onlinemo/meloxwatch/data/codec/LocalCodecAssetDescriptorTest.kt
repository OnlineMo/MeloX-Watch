package io.github.onlinemo.meloxwatch.data.codec

import android.content.res.AssetFileDescriptor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class LocalCodecAssetDescriptorTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun actualKnownLengthAssetStreamsQualifyDsfDffAndApeAtANonzeroOffset() {
        val channels = CodecFixtures.dsdTone(1000, bits = 512)
        val cases = listOf(
            "DSF" to CodecFixtures.dsf(channels),
            "DFF" to CodecFixtures.dff(channels),
            "APE" to CodecFixtures.apeOne(first = 17),
        )
        for ((container, raw) in cases) {
            val original = wrapped(container, raw)
            val before = original.readBytes()
            val parcel = ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY)
            AssetFileDescriptor(parcel, PREFIX_BYTES, raw.size.toLong()).use { descriptor ->
                descriptor.createInputStream().use { stream ->
                    assertTrue(stream is AssetFileDescriptor.AutoCloseInputStream)
                    assertEquals(PREFIX_BYTES, stream.channel.position())
                    val metadata = LocalCodecMetadata.read(
                        stream,
                        descriptor.startOffset,
                        descriptor.declaredLength,
                        CancellationSignal(),
                    ) {
                        fail("A real custom decode must qualify this selected asset")
                        LocalAudioMetadata()
                    }
                    val parameters = checkNotNull(metadata.codecParameters)
                    assertEquals(container, parameters.container)
                    assertEquals(if (container == "APE") 44_100 else 2_822_400, parameters.inputSampleRateHz)
                    assertEquals(if (container == "APE") 44_100 else 88_200, parameters.pcmSampleRateHz)
                    assertEquals(16, parameters.pcmBitsPerSample)
                    assertTrue(stream.channel.position() in PREFIX_BYTES..(PREFIX_BYTES + raw.size))
                }
            }
            assertFalse(parcel.fileDescriptor.valid())
            assertArrayEquals(before, original.readBytes())
        }
    }

    @Test fun id3SniffFallbackRestoresTheOriginalStreamCounterAndAllNativeBytes() {
        val emptyId3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0)
        assertNativeFallback("id3-wave", emptyId3 + wave())
    }

    @Test fun ordinaryNativeFallbackHonorsTheSelectedAssetLengthAndSentinels() {
        assertNativeFallback("ordinary-wave", wave())
    }

    @Test fun aTruncatedDeclaredAssetCannotUseAdjacentBytesToQualifyApe() {
        val raw = CodecFixtures.apeOne(first = 37)
        val original = wrapped("truncated-ape", raw)
        val before = original.readBytes()
        val parcel = ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY)
        AssetFileDescriptor(parcel, PREFIX_BYTES, raw.size - 4L).use { descriptor ->
            descriptor.createInputStream().use { stream ->
                try {
                    LocalCodecMetadata.read(
                        stream,
                        descriptor.startOffset,
                        descriptor.declaredLength,
                        CancellationSignal(),
                    ) {
                        fail("Definite damaged APE must not use native fallback")
                        LocalAudioMetadata()
                    }
                    fail("Declared extent must exclude the adjacent final frame bytes")
                } catch (error: SourceReadException) {
                    assertEquals(SourceReadIssue.METADATA_UNREADABLE, error.issue)
                    assertNotNull(error.cause)
                }
                assertTrue(stream.channel.position() <= PREFIX_BYTES + descriptor.declaredLength)
            }
        }
        assertFalse(parcel.fileDescriptor.valid())
        assertArrayEquals(before, original.readBytes())
    }

    private fun assertNativeFallback(name: String, raw: ByteArray) {
        val original = wrapped(name, raw)
        val before = original.readBytes()
        val expected = LocalAudioMetadata(title = name, mimeType = "audio/wav")
        val parcel = ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY)
        AssetFileDescriptor(parcel, PREFIX_BYTES, raw.size.toLong()).use { descriptor ->
            descriptor.createInputStream().use { stream ->
                var calls = 0
                val actual = LocalCodecMetadata.read(
                    stream,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                    CancellationSignal(),
                ) { nativeInput ->
                    calls++
                    assertArrayEquals(raw, nativeInput.readBytes())
                    assertEquals(-1, nativeInput.read())
                    expected
                }
                assertSame(expected, actual)
                assertEquals(1, calls)
                assertEquals(PREFIX_BYTES + raw.size, stream.channel.position())
                assertEquals(0, stream.available())
            }
        }
        assertFalse(parcel.fileDescriptor.valid())
        assertArrayEquals(before, original.readBytes())
    }

    private fun wrapped(name: String, bytes: ByteArray): File = files.newFile(name).apply {
        writeBytes(ByteArray(PREFIX_BYTES.toInt()) { 0x35 } + bytes + ByteArray(23) { 0x6a })
    }

    private fun wave(): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(40)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(44_100)
        putInt(88_200)
        putShort(2)
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(4)
        putShort(1234)
        putShort(-2345)
    }.array()

    private companion object {
        const val PREFIX_BYTES = 19L
    }
}
