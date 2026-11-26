package io.github.onlinemo.meloxwatch.playback.resources

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import io.github.onlinemo.meloxwatch.data.cue.CueByteFixture
import io.github.onlinemo.meloxwatch.data.cue.CuePcmWaveParser
import io.github.onlinemo.meloxwatch.data.cue.CueReadLimits
import io.github.onlinemo.meloxwatch.model.CueFileBinding
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class CuePcmDataSourcesTest {
    @Test fun structureBudgetIncludesPrefixSuffixAndTheRepeatedBoundaryBlock() {
        val oddPaddedTail = byteArrayOf('J'.code.toByte(), 'U'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte(),
            1, 0, 0, 0, 7, 0)
        for (fixture in listOf(CueByteFixture(), CueByteFixture(tail = oddPaddedTail),
            CueByteFixture(sampleCount = 50, tail = oddPaddedTail))) {
            val facts = CuePcmWaveParser.inspect(ByteArrayInputStream(fixture.wave), fixture.wave.size.toLong())
            assertEquals(fixture.facts, facts)
            val reference = fixture.reference(1, end = null)
            val entry = QueueEntry("structure", reference.trackKey, 0L, 0L, "Structure segment",
                checkNotNull(reference.pcmWindow).durationUs / 1000L,
                checkNotNull(reference.binding).audio.location, cueReference = reference)
            val registry = RuntimePlaybackResources()
            val inputs = Bytes(fixture)
            val sources = CuePcmDataSources(inputs, registry)
            val resource = registry.registerCue(entry)
            val source = sources.createDataSource()
            try {
                assertEquals(44L + facts.dataBytes, source.open(DataSpec.Builder().setUri(resource.mediaUri).build()))
                assertEquals(CueReadLimits.waveRuntimeWorkBytes(facts), inputs.audioReadBytes.get())
                assertEquals(1, source.completedAudioProofs)
                if (facts.fileBytes < CueReadLimits.STREAM_BYTES && facts.dataOffset + facts.dataBytes < facts.fileBytes) {
                    assertEquals(facts.fileBytes * 3L, inputs.audioReadBytes.get())
                }
            } finally {
                source.close();
                resource.close();
                sources.close()
            }
        }
        val largestEvenRiff = CueReadLimits.RIFF_MAX_BYTES - 1L
        val maximum = CuePcmWaveFacts(largestEvenRiff, 44L, largestEvenRiff - 44L, 192_000, 1)
        assertEquals(largestEvenRiff + CueReadLimits.STREAM_BYTES, CueReadLimits.waveRuntimeWorkBytes(maximum))
    }
    @Test fun media3ReadsOnlyTheActualPcmWindowAndRandomByteSeeksPreserveThatWindow() {
        val fixture = CueByteFixture()
        val registry = RuntimePlaybackResources()
        val input = Bytes(fixture)
        val sources = CuePcmDataSources(input, registry)
        val resource = registry.registerCue(fixture.entry(2))
        val source = sources.createDataSource()
        try {
            val size = source.open(DataSpec.Builder().setUri(resource.mediaUri).build())
            val expectedPayload = fixture.wave.copyOfRange(44 + 44_688 * 4, fixture.wave.size)
            val expected = CuePcmWaveParser.transportHeader(fixture.facts, expectedPayload.size.toLong()) + expectedPayload
            assertEquals(expected.size.toLong(), size)
            assertArrayEquals(expected, all(source))
            source.close()
            source.open(DataSpec.Builder().setUri(resource.mediaUri).setPosition(47).setLength(113).build())
            assertArrayEquals(expected.copyOfRange(47, 160), all(source))
            assertEquals(2, input.audioOpens.get())
        } finally {
            source.close();
            resource.close();
            sources.close()
        }
    }
    @Test fun systemSizeDoesNoIoAndTheSameWindowHandlesRandomNativeReads() = runBlocking {
        val fixture = CueByteFixture()
        val registry = RuntimePlaybackResources()
        val input = Bytes(fixture)
        val sources = CuePcmDataSources(input, registry)
        val resource = registry.registerCue(fixture.entry(2))
        val platform = sources.platform(resource.mediaUri) { throw AssertionError("Unexpected platform error: $it") }
        try {
            val payload = fixture.wave.copyOfRange(44 + 44_688 * 4, fixture.wave.size)
            val expected = CuePcmWaveParser.transportHeader(fixture.facts, payload.size.toLong()) + payload
            assertEquals(expected.size.toLong(), platform.getSize())
            assertEquals(0, input.audioOpens.get())
            assertFalse(platform.supportsSeek)
            val bytes = ByteArray(31)
            assertEquals(31, platform.readAt(53, bytes, 0, bytes.size))
            assertArrayEquals(expected.copyOfRange(53, 84), bytes)
            assertTrue(platform.supportsSeek)
            assertEquals(-1, platform.readAt(expected.size.toLong(), bytes, 0, bytes.size))
        } finally {
            platform.close();
            withTimeout(5000) {
                platform.retirement.await();
                sources.awaitRetirement()
            }
            resource.close();
            sources.close()
        }
    }
    @Test fun independentCueAndAudioHashChangesRejectTheReadAndRetireItsInputs() {
        val fixture = CueByteFixture()
        for (changeCue in listOf(true, false)) {
            val registry = RuntimePlaybackResources()
            val input = Bytes(fixture).apply {
                corruptCue = changeCue;
                corruptAudio = !changeCue
            }
            val sources = CuePcmDataSources(input, registry)
            val resource = registry.registerCue(fixture.entry(1))
            try {
                val source = sources.createDataSource()
                assertEquals(QueuePlaybackIssue.SOURCE_CHANGED, assertThrows(MediaTransportException::class.java) {
                    source.open(DataSpec.Builder().setUri(resource.mediaUri).build())
                }.issue)
                assertEquals(0, input.openCount.get())
                runBlocking { withTimeout(1000) { sources.awaitRetirement() } }
            } finally {
                resource.close();
                sources.close()
            }
        }
    }
    @Test fun aFailedAudioCloseRetainsTheActualOwnerAndBlocksReplacementUntilRetirement() {
        val fixture = CueByteFixture()
        val registry = RuntimePlaybackResources()
        val input = Bytes(fixture)
        val sources = CuePcmDataSources(input, registry)
        val resource = registry.registerCue(fixture.entry(2))
        val source = sources.createDataSource()
        source.open(DataSpec.Builder().setUri(resource.mediaUri).build())
        input.failAudioClose = true
        assertThrows(IOException::class.java) { source.close() }
        assertTrue(requireNotNull(resource.cue).failedReadOwners.contains(source))
        assertEquals(QueuePlaybackIssue.BUSY, assertThrows(MediaTransportException::class.java) {
            sources.createDataSource().open(DataSpec.Builder().setUri(resource.mediaUri).build())
        }.issue)
        assertThrows(MediaTransportException::class.java) { runBlocking { sources.awaitRetirement() } }
        input.failAudioClose = false
        source.close()
        assertTrue(requireNotNull(resource.cue).failedReadOwners.isEmpty())
        assertEquals(0, input.openCount.get())
        runBlocking { sources.awaitRetirement() }
        resource.close();
        sources.close()
    }
    @Test fun twoThreeItemWindowsKeepSixTokensButOnlyThreeActualPcmReadsCanOpen() {
        val fixture = CueByteFixture()
        val registry = RuntimePlaybackResources()
        val input = Bytes(fixture)
        val sources = CuePcmDataSources(input, registry)
        val resources = (0 until 6).map { index -> registry.registerCue(fixture.entry(2).copy(queueEntryId = "window-$index")) }
        val readers = ArrayList<CuePcmDataSource>()
        fun open(index: Int): CuePcmDataSource = sources.createDataSource().also {
            readers += it
            it.open(DataSpec.Builder().setUri(resources[index].mediaUri).build())
        }
        try {
            val first = open(0)
            open(1);
            open(2)
            assertEquals(3, input.openCount.get())
            assertEquals(QueuePlaybackIssue.BUSY, assertThrows(MediaTransportException::class.java) { open(3) }.issue)
            assertEquals(3, input.openCount.get())
            resources[0].close()
            assertEquals(QueuePlaybackIssue.BUSY, assertThrows(QueuePlaybackException::class.java) {
                registry.registerCue(fixture.entry(2).copy(queueEntryId = "seventh-token"))
            }.failure.issue)
            first.close()
            assertEquals(2, input.openCount.get())
            open(3)
            assertEquals(3, input.openCount.get())
        } finally {
            readers.forEach(CuePcmDataSource::close)
            resources.forEach(RuntimePlaybackResource::close)
            sources.close()
        }
        assertEquals(0, input.openCount.get())
    }
    private fun all(source: CuePcmDataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val bytes = ByteArray(901)
        while (true) {
            val count = source.read(bytes, 0, bytes.size);
            if (count < 0) break;
            output.write(bytes, 0, count)
        }
        return output.toByteArray()
    }
    private class Bytes(private val fixture: CueByteFixture) : CuePcmInputFactory {
        val audioOpens = AtomicInteger()
        val audioReadBytes = AtomicLong()
        val openCount = AtomicInteger()
        var corruptCue = false
        var corruptAudio = false
        var failAudioClose = false
        override fun create(binding: CueFileBinding): CueSeekableInput {
            val audio = binding.objectId == "wave"
            val bytes = (if (audio) fixture.wave else fixture.cue).copyOf()
            if (audio && corruptAudio || !audio && corruptCue) bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
            return object : CueSeekableInput {
                private var position = 0
                private var opened = false
                override val length = bytes.size.toLong()
                override fun initialize() {
                    check(!opened);
                    opened = true;
                    openCount.incrementAndGet();
                    if (audio) audioOpens.incrementAndGet()
                }
                override fun seek(position: Long) {
                    check(opened);
                    require(position in 0L..length);
                    this.position = position.toInt()
                }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    check(opened)
                    if (position == bytes.size) return -1
                    val read = minOf(count, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + read);
                    position += read
                    if (audio) audioReadBytes.addAndGet(read.toLong())
                    return read
                }
                override fun cancel() = Unit
                override fun close() {
                    if (audio && failAudioClose) throw IOException("Controlled audio close failure")
                    if (opened) {
                        opened = false;
                        openCount.decrementAndGet()
                    }
                }
            }
        }
    }
}
