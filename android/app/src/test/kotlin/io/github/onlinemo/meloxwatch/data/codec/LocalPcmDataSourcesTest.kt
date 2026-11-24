package io.github.onlinemo.meloxwatch.data.codec

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.wav.WavExtractor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class LocalPcmDataSourcesTest {
    private val uri = Uri.parse("content://codec-fixtures/false-extension.mp3")
    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()

    @Test fun allThreeActualContainersFeedTheLockedMedia3WavExtractor() {
        val channels = CodecFixtures.dsdTone(1000)
        for (raw in listOf(CodecFixtures.dsf(channels), CodecFixtures.dff(channels), CodecFixtures.apeOne())) {
            LocalPcmDataSources(factory(raw)).use { sources ->
                val source = sources.createDataSource()
                try {
                    assertTrue(source.open(spec()) > 44)
                    assertEquals(uri, source.uri)
                    assertEquals(listOf("audio/wav"), source.responseHeaders["content-type"])
                    val pcm = readAll(source)
                    var cursor = 0
                    val reader = DataReader { bytes, offset, count ->
                        if (cursor == pcm.size) -1 else minOf(count, pcm.size - cursor).also { read ->
                            pcm.copyInto(bytes, offset, cursor, cursor + read); cursor += read
                        }
                    }
                    assertTrue(WavExtractor().sniff(DefaultExtractorInput(reader, 0, pcm.size.toLong())))
                } finally { source.close() }
            }
        }
    }

    @Test fun pcmByteSeekIsExactForUnalignedPositionsAndRepeatedOpens() {
        val raw = CodecFixtures.dsf(CodecFixtures.dsdTone(1000))
        LocalPcmDataSources(factory(raw)).use { sources ->
            val source = sources.createDataSource()
            source.open(spec()); val expected = readAll(source); source.close()
            for (position in listOf(0L, 43L, 44L, 45L, 128L, expected.size - 1L, expected.size.toLong())) {
                val count = minOf(7L, expected.size - position)
                if (count == 0L) {
                    assertEquals(0L, source.open(spec(position)))
                    assertEquals(-1, source.read(ByteArray(1), 0, 1))
                } else {
                    assertEquals(count, source.open(spec(position, count)))
                    assertArrayEquals(expected.copyOfRange(position.toInt(), (position + count).toInt()), readAll(source))
                }
                source.close()
            }
        }
    }

    @Test fun boundedRequestMayExtendPastEndAndOutOfRangeUsesTheMedia3Reason() {
        LocalPcmDataSources(factory(CodecFixtures.apeOne())).use { sources ->
            val source = sources.createDataSource()
            try { assertEquals(100L, source.open(spec(44, 100))); assertEquals(2, readAll(source).size) }
            finally { source.close() }
            try { source.open(spec(100)); fail("Out of range") }
            catch (error: IOException) { assertTrue(DataSourceException.isCausedByPositionOutOfRange(error)) }
            finally { source.close() }
        }
    }

    @Test fun unknownOriginalLengthCannotQualifyCustomDecodedAudio() {
        LocalPcmDataSources(factory(CodecFixtures.dsf(CodecFixtures.dsdTone(1000)), unknown = true)).use { sources ->
            val source = sources.createDataSource()
            try { source.open(spec()); fail("Unknown length must fail") }
            catch (error: CodecReadException) { assertEquals(CodecReadIssue.UNKNOWN_LENGTH, error.issue) }
            finally { source.close() }
        }
    }

    @Test fun changedOrLostKnownExtentOnSeekCannotBecomeDecodedAudio() {
        val raw = CodecFixtures.dsf(CodecFixtures.dsdTone(1000, bits = 65_536))
        for (lost in listOf(true, false)) {
            LocalPcmDataSources(factory(raw, reportedLength = { position, available ->
                if (position == 0L) available else if (lost) C.LENGTH_UNSET.toLong() else available + 1
            })).use { sources ->
                val source = sources.createDataSource()
                try {
                    source.open(spec(position = 5_000L))
                    // 读取第二个DSF块触发非零物理seek，先核原extent再交付PCM。
                    readAll(source)
                    fail("The known source extent must remain observable")
                }
                catch (error: CodecReadException) { assertEquals(CodecReadIssue.SOURCE_CHANGED, error.issue) }
                finally { source.close() }
            }
        }
    }

    @Test fun powerSavingHasAnExplicitFailureAndOwnerCloseCancelsFurtherPcm() {
        val raw = CodecFixtures.dsf(CodecFixtures.dsdTone(1000))
        LocalPcmDataSources(factory(raw), powerSaveMode = { true }).use { sources ->
            val source = sources.createDataSource()
            try { source.open(spec()); fail("Power policy must apply") }
            catch (error: CodecReadException) { assertEquals(CodecReadIssue.LOW_POWER_RESTRICTED, error.issue) }
            finally { source.close() }
        }
        val sources = LocalPcmDataSources(factory(raw))
        val source = sources.createDataSource()
        source.open(spec()); sources.close()
        try { source.read(ByteArray(1), 0, 1); fail("Owner close must cancel") }
        catch (_: InterruptedIOException) {}
        finally { source.close() }
    }

    @Test fun failedPhysicalCloseRetainsItsSlotUntilTheSameSourceIsReleased() {
        var failClose = false
        val sources = LocalPcmDataSources(factory(CodecFixtures.apeOne(), closeFailure = { failClose }))
        val source = sources.createDataSource()
        source.open(spec()); failClose = true
        try { source.close(); fail("Close failure must propagate") } catch (_: IOException) {}
        val later = sources.createDataSource()
        try { later.open(spec()); fail("Unconfirmed release must not be hidden") }
        catch (error: CodecReadException) { assertEquals(CodecReadIssue.SOURCE_CHANGED, error.issue) }
        finally { later.close() }
        failClose = false
        source.close()
        sources.close()
    }

    @Test fun originalReaderForOtherSignaturesIsPreserved() {
        val raw = "ordinary unrecognized bytes".toByteArray()
        LocalPcmDataSources(factory(raw)).use { sources ->
            val source = sources.createDataSource()
            try { source.open(spec()); assertArrayEquals(raw, readAll(source)); assertTrue(source.responseHeaders.isEmpty()) }
            finally { source.close() }
        }
    }

    private fun factory(bytes: ByteArray, unknown: Boolean = false, closeFailure: () -> Boolean = { false },
        reportedLength: (Long, Long) -> Long = { _, available -> available }) = DataSource.Factory {
        object : DataSource {
            private var position = 0
            private var opened: Uri? = null
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                require(dataSpec.position <= bytes.size)
                position = dataSpec.position.toInt(); opened = dataSpec.uri
                return if (unknown) C.LENGTH_UNSET.toLong() else reportedLength(dataSpec.position, bytes.size - dataSpec.position)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position == bytes.size) return -1
                val count = minOf(length, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + count); position += count; return count
            }
            override fun getUri(): Uri? = opened
            override fun close() { if (closeFailure()) throw IOException("Fixture close failure"); opened = null }
        }
    }
    private fun readAll(source: DataSource): ByteArray {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(61)
        while (true) {
            val count = source.read(chunk, 0, chunk.size)
            if (count < 0) break
            assertTrue(count > 0); bytes.write(chunk, 0, count)
        }
        return bytes.toByteArray()
    }
}
