package io.github.onlinemo.meloxwatch.playback.dsp

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.StatsDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.google.common.collect.ImmutableList
import io.github.onlinemo.meloxwatch.data.codec.ApeReplayGainEntry
import io.github.onlinemo.meloxwatch.data.codec.CodecFixtures
import io.github.onlinemo.meloxwatch.data.codec.CodecPcmResponseHeaders
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackDataSources
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import io.github.onlinemo.meloxwatch.playback.resources.metadata.BoundedExtractorsFactory
import io.github.onlinemo.meloxwatch.playback.resources.metadata.CapturedExtractorOutput
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real bytes, routed codec input, fixed adapter/extractor, sink configuration and PCM pipeline.
 * The terminal AudioSink is a public-interface fixture; this is not an AudioTrack/device test. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class CodecReplayGainIntegrationTest {
    private val uri = Uri.parse("content://codec-replaygain-fixtures/wrong-extension.mp3")
    private val fields = listOf(
        "REPLAYGAIN_TRACK_GAIN" to " -6.020599913 dB ",
        "REPLAYGAIN_TRACK_PEAK" to "0.75",
        "REPLAYGAIN_ALBUM_GAIN" to "-12.041199827 dB",
        "REPLAYGAIN_ALBUM_PEAK" to "1.0",
    )

    @Test fun allFourOriginalApeFieldsReachTheActualSinkAndGainIsAppliedExactlyOnce() {
        val capture = extract(CodecFixtures.apeTags(CodecFixtures.apeOne(first = 128), fields))
        val metadata = checkNotNull(capture.format.metadata)
        val actualEntries = (0 until metadata.length()).map { metadata[it] }.filterIsInstance<ApeReplayGainEntry>()
        assertEquals(fields.toMap(), actualEntries.associate { it.key to it.value })
        assertTrue(capture.headers is CodecPcmResponseHeaders)
        assertFalse(capture.headers.keys.any { it.contains("REPLAYGAIN", ignoreCase = true) })
        val tags = ReplayGainTagReader.read(metadata)
        assertEquals(ReplayGainTagSource.APE, tags.source)
        assertNull(tags.issue)
        assertEquals(-6.020599913, checkNotNull(tags.tags.trackGainDb), 0.0)
        assertEquals(-12.041199827, checkNotNull(tags.tags.albumGainDb), 0.0)
        assertEquals(0.75, checkNotNull(tags.tags.trackPeak), 0.0)
        assertEquals(1.0, checkNotNull(tags.tags.albumPeak), 0.0)
        assertEquals(128, shorts(capture.pcm).single().toInt())
        val track = process(capture, ReplayGainMode.TRACK)
        val album = process(capture, ReplayGainMode.ALBUM)
        assertEquals(64, shorts(track.bytes).single().toInt())
        assertEquals(32, shorts(album.bytes).single().toInt())
        assertEquals(ReplayGainTagSource.APE, track.actual.replayGainSource)
        assertEquals(ReplayGainTagSource.APE, album.actual.replayGainSource)
        assertArrayEquals(capture.pcm, process(capture, ReplayGainMode.OFF).bytes)
    }

    @Test fun dsfAndDffUseTheActualContainedId3FieldsOnTheSamePcmStream() {
        val channels = CodecFixtures.dsdTone(1000)
        val tag = id3(fields)
        val cases = listOf(
            CodecFixtures.dsf(channels) to taggedDsf(CodecFixtures.dsf(channels), tag),
            CodecFixtures.dff(channels) to taggedDff(CodecFixtures.dff(channels), tag),
        )
        for ((plain, tagged) in cases) {
            val baseline = extract(plain)
            val capture = extract(tagged)
            assertArrayEquals(baseline.pcm, capture.pcm)
            val metadata = checkNotNull(capture.format.metadata)
            val entries = (0 until metadata.length()).map { metadata[it] }.filterIsInstance<TextInformationFrame>()
            assertEquals(fields.toMap(), entries.associate { checkNotNull(it.description) to it.values.single() })
            val result = process(capture, ReplayGainMode.TRACK)
            assertEquals(ReplayGainTagSource.ID3, result.actual.replayGainSource)
            assertNull(result.actual.replayGainTagIssue)
            val expected = shorts(baseline.pcm)
            val output = shorts(result.bytes)
            assertEquals(expected.size, output.size)
            for (index in expected.indices) {
                assertTrue(abs(output[index].toInt() - (expected[index].toInt() * 0.5).roundToInt()) <= 1)
            }
        }
    }

    @Test fun leadingId3AndTheIndependentApeFooterKeepTheirRealOriginsAndConflicts() {
        val leading = id3(listOf("REPLAYGAIN_TRACK_GAIN" to "-3.0 dB"))
        val ape = CodecFixtures.apeTags(CodecFixtures.apeOne(first = 128), fields)
        val capture = extract(leading + ape)
        val result = ReplayGainTagReader.read(capture.format.metadata)
        assertEquals(ReplayGainTagSource.MIXED, result.source)
        assertEquals(ReplayGainTagIssue.CONFLICTING_VALUES, result.issue)
        assertNull(result.tags.trackGainDb)
        assertEquals(-12.041199827, checkNotNull(result.tags.albumGainDb), 0.0)
        assertArrayEquals(capture.pcm, process(capture, ReplayGainMode.TRACK).bytes)
        assertEquals(32, shorts(process(capture, ReplayGainMode.ALBUM).bytes).single().toInt())
    }

    @Test fun ordinaryHeaderStringsCannotImpersonateTheLocalTypedObservation() {
        val capture = extract(CodecFixtures.apeTags(CodecFixtures.apeOne(first = 128), fields),
            transformHeaders = { headers -> headers.toMap() + mapOf(
                "X-MeloX-ReplayGain-Track-Gain" to listOf("-6.020599913 dB"),
                "X-MeloX-ReplayGain-Track-Peak" to listOf("0.75")) })
        assertNull(capture.format.metadata)
        val result = process(capture, ReplayGainMode.TRACK)
        assertEquals(ReplayGainTagSource.NONE, result.actual.replayGainSource)
        assertArrayEquals(capture.pcm, result.bytes)
    }

    @Test fun anotherExtractorUriCannotBorrowTheCurrentContentSourcesObservation() {
        val another = extract(CodecFixtures.apeTags(CodecFixtures.apeOne(first = 128), fields),
            extractorUri = Uri.parse("content://codec-replaygain-fixtures/another.mp3"))
        assertNull(another.format.metadata)
        assertEquals(ReplayGainTagSource.NONE, process(another, ReplayGainMode.TRACK).actual.replayGainSource)
    }

    @Test fun aClosedHeaderObservationCannotAuthorizeTagsOnANewPeriodEvenAtTheSameUri() {
        val first = extract(CodecFixtures.apeTags(CodecFixtures.apeOne(first = 128), fields))
        val next = extract(CodecFixtures.apeOne(first = 128), transformHeaders = { first.headers })
        assertNull(next.format.metadata)
        assertEquals(ReplayGainTagSource.NONE, process(next, ReplayGainMode.TRACK).actual.replayGainSource)
        assertArrayEquals(next.pcm, process(next, ReplayGainMode.TRACK).bytes)
    }

    @Test fun aNewPeriodOnTheSameUriDoesNotReuseThePreviousTags() {
        val first = extract(CodecFixtures.apeTags(CodecFixtures.apeOne(first = 128), fields))
        assertEquals(ReplayGainTagSource.APE, process(first, ReplayGainMode.TRACK).actual.replayGainSource)
        val next = extract(CodecFixtures.apeOne(first = 128))
        assertNull(next.format.metadata)
        assertArrayEquals(next.pcm, process(next, ReplayGainMode.TRACK).bytes)
    }

    @Test fun aDffTagCannotBorrowBytesFromTheFollowingChunkToCompleteItsDeclaredSize() {
        val channels = CodecFixtures.dsdTone(1000)
        val header = id3(fields).copyOfRange(0, 10)
        val raw = taggedDff(CodecFixtures.dff(channels), header, chunk("JUNK", id3(fields)))
        val capture = extract(raw)
        val result = process(capture, ReplayGainMode.TRACK)
        assertEquals(ReplayGainTagSource.NONE, result.actual.replayGainSource)
        assertEquals(ReplayGainTagIssue.TAG_UNREADABLE, result.actual.replayGainTagIssue)
        assertArrayEquals(extract(CodecFixtures.dff(channels)).pcm, result.bytes)
    }

    @Test fun anOversizedDffTagIsRejectedFromItsHeaderWithoutCreatingThatTagArray() {
        val header = id3Header(1024 * 1024 + 1)
        val capture = extract(taggedDff(CodecFixtures.dff(CodecFixtures.dsdTone(1000)), header))
        val result = process(capture, ReplayGainMode.TRACK)
        assertEquals(ReplayGainTagIssue.METADATA_TOO_LARGE, result.actual.replayGainTagIssue)
        assertEquals(ReplayGainTagSource.NONE, result.actual.replayGainSource)
        assertArrayEquals(capture.pcm, result.bytes)
    }

    @Test fun apeTerminatingBytesCannotBeReinterpretedAsATrustedFooter() {
        val plain = CodecFixtures.apeOne(first = 128)
        val bytes = CodecFixtures.apeTags(plain, fields)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(32, bytes.size - plain.size)
        val capture = extract(bytes)
        assertNull(capture.format.metadata)
        assertArrayEquals(capture.pcm, process(capture, ReplayGainMode.TRACK).bytes)
    }

    @Test fun aMissingAlbumFieldStillDoesNotBorrowTheActualApeTrackGain() {
        val capture = extract(CodecFixtures.apeTags(CodecFixtures.apeOne(first = 128), fields.take(2)))
        val result = process(capture, ReplayGainMode.ALBUM)
        assertEquals(ReplayGainTagSource.APE, result.actual.replayGainSource)
        assertTrue(result.actual.replayGainTagMissing)
        assertArrayEquals(capture.pcm, result.bytes)
    }

    private data class Capture(
        val format: Format,
        val pcm: ByteArray,
        val headers: Map<String, List<String>>,
    )

    private fun extract(
        bytes: ByteArray,
        sourceUri: Uri = uri,
        extractorUri: Uri = sourceUri,
        transformHeaders: (Map<String, List<String>>) -> Map<String, List<String>> = { it },
    ): Capture {
        val owners = PlaybackDataSources(RuntimeEnvironment.getApplication(), RuntimePlaybackResources(),
            controlledLocalRaw = rawFactory(bytes))
        val source = StatsDataSource(owners.createDataSource())
        val adapter = BundledExtractorsAdapter(BoundedExtractorsFactory())
        val output = CapturedExtractorOutput()
        val spec = DataSpec.Builder().setUri(sourceUri).build()
        try {
            val length = source.open(spec)
            val headers = source.responseHeaders
            assertSame(headers, source.lastResponseHeaders)
            adapter.init(source, extractorUri, transformHeaders(headers), 0L, length, output)
            val requested = PositionHolder()
            var steps = 0
            while (true) {
                assertTrue("Extraction must complete within its fixture step bound", ++steps <= 4096)
                when (adapter.read(requested)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> {
                        source.close()
                        val available = source.open(spec.buildUpon().setPosition(requested.position).build())
                        adapter.init(source, extractorUri, transformHeaders(source.responseHeaders), requested.position,
                            requested.position + available, output)
                    }
                }
            }
            val audio = output.audio()
            return Capture(audio.formats.single(), audio.data, headers)
        } finally {
            try { adapter.release() } finally { try { source.close() } finally { owners.close() } }
        }
    }

    private fun rawFactory(bytes: ByteArray) = DataSource.Factory {
        object : DataSource {
            private var position = 0
            private var opened: Uri? = null
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                require(dataSpec.position in 0..bytes.size.toLong())
                position = dataSpec.position.toInt()
                opened = dataSpec.uri
                return bytes.size - dataSpec.position
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (position == bytes.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, bytes.size - position, 61)
                bytes.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }
            override fun getUri(): Uri? = opened
            override fun close() { opened = null }
        }
    }

    private data class Result(val bytes: ByteArray, val actual: DspActualState)

    private fun process(capture: Capture, mode: ReplayGainMode): Result {
        val runtime = DspRuntime().also { it.setSaved(DspStored(settings = DspSettings(replayGainMode = mode))) }
        val chain = DspAudioProcessorChain(runtime)
        val terminal = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) {
            proxy, method, arguments ->
            when (method.name) {
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "public PCM terminal fixture"
                else -> null
            }
        } as AudioSink
        val sink = DspAudioSink(terminal, chain)
        val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(chain.audioProcessors))
        try {
            sink.configure(capture.format, 0, null)
            pipeline.configure(AudioProcessor.AudioFormat(capture.format.sampleRate, capture.format.channelCount,
                capture.format.pcmEncoding))
            pipeline.flush()
            val input = ByteBuffer.allocateDirect(capture.pcm.size).order(ByteOrder.nativeOrder())
                .put(capture.pcm).also { it.flip() }
            val output = ByteArrayOutputStream()
            while (input.hasRemaining()) {
                val before = input.position()
                pipeline.queueInput(input)
                append(pipeline.getOutput(), output)
                assertTrue(input.position() > before)
            }
            pipeline.queueEndOfStream()
            var pulls = 0
            while (!pipeline.isEnded()) {
                assertTrue(++pulls <= 16)
                val buffer = pipeline.getOutput()
                assertTrue(buffer.hasRemaining())
                append(buffer, output)
            }
            return Result(output.toByteArray(), runtime.state.value)
        } finally { try { pipeline.reset() } finally { sink.release() } }
    }

    private fun append(bytes: ByteBuffer, output: ByteArrayOutputStream) {
        val copy = ByteArray(bytes.remaining())
        bytes.get(copy)
        output.write(copy)
    }

    private fun shorts(bytes: ByteArray): ShortArray =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { values ->
            ShortArray(values.remaining()).also { values.get(it) }
        }

    private fun id3(fields: List<Pair<String, String>>): ByteArray {
        val frames = ByteArrayOutputStream()
        DataOutputStream(frames).use { output ->
            for ((key, value) in fields) {
                val payload = byteArrayOf(0) + key.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
                    value.toByteArray(Charsets.ISO_8859_1)
                output.write("TXXX".toByteArray(Charsets.US_ASCII))
                output.writeInt(payload.size)
                output.writeShort(0)
                output.write(payload)
            }
        }
        val body = frames.toByteArray()
        return id3Header(body.size) + body
    }

    private fun id3Header(size: Int): ByteArray =
        "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 0, 0) +
            ByteArray(4) { index -> ((size ushr (21 - 7 * index)) and 127).toByte() }

    private fun taggedDsf(plain: ByteArray, tag: ByteArray): ByteArray {
        val header = plain.copyOf()
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(12, plain.size + tag.size.toLong())
            putLong(20, plain.size.toLong())
        }
        return header + tag
    }

    private fun taggedDff(plain: ByteArray, tag: ByteArray, following: ByteArray = byteArrayOf()): ByteArray {
        val bytes = plain + chunk("ID3 ", tag) + following
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putLong(4, bytes.size - 12L)
        return bytes
    }

    private fun chunk(id: String, payload: ByteArray): ByteArray =
        id.toByteArray(Charsets.US_ASCII) +
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(payload.size.toLong()).array() +
            payload + if (payload.size and 1 == 0) byteArrayOf() else byteArrayOf(0)
}
