package io.github.onlinemo.meloxwatch.playback.resources.metadata

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.wav.WavExtractor
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.assertTrue

/** 驱动真实 extractor 的公开生命周期与 seek 请求；不调用 app helper 伪造 Format/sample。 */
@UnstableApi
internal class ExtractorFixtureHarness(
    val kind: GuardedContainer,
    private val bytes: ByteArray,
    unknownLength: Boolean = false,
    maximumRead: Int = 31,
    val extractor: Extractor = BoundedExtractor(native(kind), kind),
) : AutoCloseable {
    val output = CapturedExtractorOutput()
    val transport = FixtureReader(bytes, maximumRead)
    private val length = if (unknownLength) C.LENGTH_UNSET.toLong() else bytes.size.toLong()
    private var input = DefaultExtractorInput(transport, 0, length)
    private var initialized = false
    val seekRequests = ArrayList<Long>()

    fun sniff(): Boolean {
        val recognized = extractor.sniff(input)
        input.resetPeekPosition()
        return recognized
    }

    fun drain(): CapturedExtractorOutput {
        start()
        continueReading()
        return output
    }

    fun start() {
        if (!initialized) {
            extractor.init(output)
            initialized = true
        }
    }

    fun readStep(): Int {
        check(initialized)
        val requested = PositionHolder()
        val result = extractor.read(input, requested)
        if (result == Extractor.RESULT_SEEK) {
            seekRequests += requested.position
            restartInput(requested.position)
        }
        return result
    }

    fun seekAndDrain(position: Long, timeUs: Long) {
        extractor.seek(position, timeUs)
        restartInput(position)
        continueReading()
    }

    private fun continueReading() {
        repeat(4096) {
            if (readStep() == Extractor.RESULT_END_OF_INPUT) return
        }
        throw AssertionError("Fixture extraction did not finish within its step bound")
    }

    private fun restartInput(position: Long) {
        assertTrue(position in 0..bytes.size.toLong())
        transport.position = position.toInt()
        input = DefaultExtractorInput(transport, position, length)
    }

    override fun close() = extractor.release()

    companion object {
        fun native(kind: GuardedContainer): Extractor = when (kind) {
            GuardedContainer.MP3 -> Mp3Extractor()
            GuardedContainer.FLAC -> FlacExtractor()
            GuardedContainer.OGG -> OggExtractor()
            GuardedContainer.MP4 -> Mp4Extractor()
            GuardedContainer.FRAGMENTED_MP4 -> FragmentedMp4Extractor()
            GuardedContainer.WAVE -> WavExtractor()
            GuardedContainer.ADTS -> AdtsExtractor()
        }
    }
}

@UnstableApi
internal class FixtureReader(private val bytes: ByteArray, private val maximumRead: Int) : DataReader {
    var position = 0
    var bytesRead = 0L
        private set
    var largestRequest = 0
        private set

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        largestRequest = maxOf(largestRequest, length)
        if (length == 0) return 0
        if (position == bytes.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, maximumRead, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        bytesRead += count
        return count
    }
}

@UnstableApi
internal class CapturedExtractorOutput : ExtractorOutput {
    val tracks = LinkedHashMap<Int, CapturedTrack>()
    val seekMaps = ArrayList<SeekMap>()
    override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { CapturedTrack(type) }
    override fun endTracks() = Unit
    override fun seekMap(seekMap: SeekMap) { seekMaps += seekMap }
    fun audio(): CapturedTrack = tracks.values.single { it.type == C.TRACK_TYPE_AUDIO }
}

@UnstableApi
internal class CapturedTrack(val type: Int) : TrackOutput {
    val formats = ArrayList<Format>()
    val sampleSizes = ArrayList<Int>()
    val sampleTimesUs = ArrayList<Long>()
    private val written = ByteArrayOutputStream()
    val data: ByteArray get() = written.toByteArray()

    override fun format(format: Format) { formats += format }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        val chunk = ByteArray(minOf(length, 4096))
        val count = input.read(chunk, 0, chunk.size)
        if (count < 0) {
            if (!allowEndOfInput) throw EOFException("Incomplete fixture sample")
            return count
        }
        capture(chunk, count)
        return count
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        val chunk = ByteArray(length)
        data.readBytes(chunk, 0, length)
        capture(chunk, length)
    }

    private fun capture(bytes: ByteArray, count: Int) {
        assertTrue(written.size().toLong() + count <= 4L * MetadataLimits.RAW_BLOCK)
        written.write(bytes, 0, count)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        assertTrue(sampleSizes.size < 4096)
        assertTrue(size >= 0 && size <= written.size() && offset >= 0)
        sampleSizes += size
        sampleTimesUs += timeUs
    }
}
