package io.github.onlinemo.meloxwatch.data.codec

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.library.LibraryCodec
import io.github.onlinemo.meloxwatch.data.local.LocalMetadataReader
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class LocalCodecMetadataTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun realCustomDecodeCreatesMetadataAndParametersWithoutNativeCodecDependency() {
        val reader = LocalMetadataReader(RuntimeEnvironment.getApplication(), LocalProviderIo())
        val channels = CodecFixtures.dsdTone(1000)
        for ((index, raw) in listOf(CodecFixtures.dsf(channels), CodecFixtures.dff(channels), CodecFixtures.apeOne()).withIndex()) {
            val file = files.newFile("wrong-extension-$index.mp3").apply { writeBytes(raw) }
            val metadata = reader.readPrivateFile(file, CancellationSignal())
            assertEquals(2, metadata.parserVersion)
            val parameters = checkNotNull(metadata.codecParameters)
            assertEquals(if (index == 0) "DSF" else if (index == 1) "DFF" else "APE", parameters.container)
            assertEquals(if (index < 2) 2_822_400 else 44_100, parameters.inputSampleRateHz)
            assertEquals(if (index < 2) 88_200 else 44_100, parameters.pcmSampleRateHz)
            assertEquals(16, parameters.pcmBitsPerSample)
            assertEquals(metadata, LibraryCodec.metadata(LibraryCodec.metadata(metadata)))
        }
    }

    @Test fun oldJsonRemainsReadableAndMalformedParameterObjectsAreRejected() {
        val file = files.newFile("sample.dsf").apply { writeBytes(CodecFixtures.dsf(CodecFixtures.dsdTone(1000))) }
        val reader = LocalMetadataReader(RuntimeEnvironment.getApplication(), LocalProviderIo())
        val parsed = reader.readPrivateFile(file, CancellationSignal())
        val legacy = JSONObject(LibraryCodec.metadata(parsed)).apply { remove("codecParameters") }.toString()
        assertEquals(parsed.copy(codecParameters = null), LibraryCodec.metadata(legacy))
        val malformed = JSONObject(LibraryCodec.metadata(parsed)).apply { put("codecParameters", "invalid") }.toString()
        try { LibraryCodec.metadata(malformed); fail("Typed parameters cannot silently disappear") }
        catch (_: org.json.JSONException) {}
    }

    @Test fun footerLabelsKeepDistinctApeArtistsAfterActualDecode() {
        val raw = CodecFixtures.apeTags(CodecFixtures.apeOne(), listOf("TITLE" to "Synthetic sample", "ARTIST" to "First\u0000Second"))
        val file = files.newFile("tags.ape").apply { writeBytes(raw) }
        val reader = LocalMetadataReader(RuntimeEnvironment.getApplication(), LocalProviderIo())
        val metadata = reader.readPrivateFile(file, CancellationSignal())
        assertEquals("Synthetic sample", metadata.title)
        assertEquals(listOf("First", "Second"), metadata.artists)
        assertEquals("APE", checkNotNull(metadata.codecParameters).container)
    }

    @Test fun structurallyBadApeCannotBecomeANormalLibraryEntry() {
        val raw = CodecFixtures.apeOne().apply { this[80] = (this[80].toInt() xor 1).toByte() }
        val file = files.newFile("fake-supported.ape").apply { writeBytes(raw) }
        val reader = LocalMetadataReader(RuntimeEnvironment.getApplication(), LocalProviderIo())
        try { reader.readPrivateFile(file, CancellationSignal()); fail("Actual decode must fail") }
        catch (error: SourceReadException) { assertEquals(SourceReadIssue.METADATA_UNREADABLE, error.issue) }
    }
}
