package io.github.onlinemo.meloxwatch.playback.resources

import android.content.Context
import android.os.CancellationSignal
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import io.github.onlinemo.meloxwatch.data.cue.CueLargeTreeFixture
import io.github.onlinemo.meloxwatch.data.cue.AndroidCueOwnedInput
import io.github.onlinemo.meloxwatch.data.cue.CueOwnedInput
import io.github.onlinemo.meloxwatch.data.cue.CueException
import io.github.onlinemo.meloxwatch.data.cue.CueIssue
import io.github.onlinemo.meloxwatch.data.cue.CueReadLimits
import io.github.onlinemo.meloxwatch.model.CueFileBinding
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 云端测试定义把真实TREE字节接入native Room及两个传输owner；不证明设备输出。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CueLargeTreePlaybackTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: CueLargeTreeFixture
    @Before fun open() = runBlocking {
        fixture = CueLargeTreeFixture(RuntimeEnvironment.getApplication() as Context, temporary.root)
        fixture.open()
    }
    @After fun close() { fixture.close() }

    @Test(timeout = 120_000L)
    fun scanAndRuntimeWorkBudgetsFollowTheirActualCompletePasses() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val scanInputs = ScanCountingInputs(context)
        val counted = CueLargeTreeFixture(context, temporary.newFolder("counted"), scanInputs::create)
        counted.open()
        try {
            val tracks = counted.scan("counted")
            val reference = checkNotNull(tracks[1].cueReference)
            val binding = checkNotNull(reference.binding)
            val complete = scanInputs.records.single { it.objectId == "counted-wave" && it.eofs.get() == 2 }
            assertEquals(2L * binding.audio.sizeBytes, complete.readBytes.get())
            assertEquals(listOf(0L), complete.seeks.toList())
            assertEquals(1, complete.initializations.get())
            assertTrue(complete.closed.get())
            val registry = RuntimePlaybackResources()
            val inputs = CountingInputs(AndroidCuePcmInputFactory(context))
            val sources = CuePcmDataSources(inputs, registry, proofs())
            val resource = registry.registerCue(counted.entry(reference))
            val source = sources.createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(resource.mediaUri).build())
                assertEquals(CueReadLimits.waveRuntimeWorkBytes(binding.pcm), inputs.audioReadBytes.get())
                assertEquals(1, source.completedAudioProofs)
                for (position in listOf(53L, 100_051L, 100_009L, 53L)) {
                    source.seekVirtual(position)
                    val bytes = ByteArray(19)
                    assertEquals(bytes.size, source.read(bytes, 0, bytes.size))
                    assertArrayEquals(counted.expected(reference, position, bytes.size), bytes)
                }
                assertEquals(1, source.completedAudioProofs)
                assertEquals(1, inputs.audioInitializations.get())
                assertTrue(inputs.audioReadBytes.get() < binding.audio.sizeBytes * 2L)
            } finally {
                source.close();
                resource.close();
                sources.close()
            }
        } finally { counted.close() }
    }

    @Test(timeout = 180_000L)
    fun runtimeCompletionRejectsTheFormerExtraPassTimeWhileScanKeepsItsTwoPassBudget() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val elapsed = AtomicLong(1000L)
        val hop = AtomicLong(1000L)
        val armed = AtomicBoolean()
        val scanInputs = ScanCountingInputs(context) { if (armed.get()) elapsed.set(hop.get()) }
        val counted = CueLargeTreeFixture(context, temporary.newFolder("clocked"), scanInputs::create, elapsed::get)
        counted.open()
        try {
            val track = counted.scan("clocked")[1]
            val reference = checkNotNull(track.cueReference)
            val binding = checkNotNull(reference.binding)
            val read = counted.room.library.resolveCueRead(track.trackKey, reference)
            val runtimeWork = Math.addExact(CueReadLimits.waveRuntimeWorkBytes(binding.pcm),
                Math.multiplyExact(binding.cue.sizeBytes, 2L))
            val runtimeTimeout = CueReadLimits.timeoutMs(runtimeWork)
            val scanTimeout = CueReadLimits.timeoutMs(CueReadLimits.waveProofWorkBytes(binding.audio.sizeBytes))
            assertTrue(runtimeTimeout < scanTimeout)
            data class Completion(val elapsedMs: Long, val runtimeAllowed: Boolean, val scanAllowed: Boolean)
            val cases = listOf(Completion(runtimeTimeout - 1L, true, true), Completion(runtimeTimeout, false, true),
                Completion((runtimeTimeout + scanTimeout) / 2L, false, true),
                Completion(scanTimeout - 1L, false, true), Completion(scanTimeout, false, false), Completion(-1L, false, false))
            for (case in cases) {
                elapsed.set(1000L)
                hop.set(1000L + case.elapsedMs)
                armed.set(true)
                val registry = RuntimePlaybackResources()
                val inputs = CountingInputs(AndroidCuePcmInputFactory(context)) { file, result ->
                    if (file.objectId == "clocked-wave" && result == -1) elapsed.set(hop.get())
                }
                val sources = CuePcmDataSources(inputs, registry, proofs(), elapsed::get)
                val resource = registry.registerCue(counted.entry(reference))
                val source = sources.createDataSource()
                try {
                    val data = DataSpec.Builder().setUri(resource.mediaUri).build()
                    if (case.runtimeAllowed) {
                        source.open(data)
                        assertTrue(source.verified)
                        assertEquals(1, source.completedAudioProofs)
                    } else {
                        assertEquals(QueuePlaybackIssue.SOURCE_READ_TIMEOUT,
                            assertThrows(MediaTransportException::class.java) { source.open(data) }.issue)
                        assertFalse(source.verified)
                    }
                } finally {
                    source.close();
                    resource.close();
                    sources.close()
                }
                elapsed.set(1000L)
                if (case.scanAllowed) {
                    val result = counted.sources.proveCueWave(read.audio)
                    assertEquals(binding.pcm, result.pcm)
                    assertEquals(read.audio.verifiedContentToken, result.descriptor.verifiedContentToken)
                } else {
                    assertEquals(CueIssue.READ_TIMEOUT, assertThrows(CueException::class.java) {
                        runBlocking { counted.sources.proveCueWave(read.audio) }
                    }.issue)
                }
                armed.set(false)
            }
        } finally {
            armed.set(false);
            counted.close()
        }
    }

    @Test(timeout = 90_000L)
    fun ordinaryTreeAboveFourMiBScansAndStreamsBothExactPcmWindowsWithOriginalAudioPreserved() = runBlocking {
        val tracks = fixture.scan("large")
        val original = CueLargeTreeFixture.fileToken(fixture.file("large"))
        assertTrue(fixture.file("large").length() > 4L * 1024L * 1024L)
        val registry = RuntimePlaybackResources()
        val sources = CuePcmDataSources(AndroidCuePcmInputFactory(fixture.context), registry, proofs())
        for (track in tracks) {
            val reference = checkNotNull(track.cueReference)
            val resource = registry.registerCue(fixture.entry(reference))
            val input = sources.createDataSource()
            try {
                val total = input.open(DataSpec.Builder().setUri(resource.mediaUri).build())
                assertEquals(44L + checkNotNull(reference.pcmWindow).payloadBytes, total)
                val bytes = ByteArray(CueReadLimits.STREAM_BYTES)
                var position = 0L
                while (true) {
                    val count = input.read(bytes, 0, bytes.size)
                    if (count < 0) break
                    assertArrayEquals(fixture.expected(reference, position, count), bytes.copyOf(count))
                    position += count
                }
                assertEquals(total, position)
                assertEquals(1, input.completedAudioProofs)
            } finally {
                input.close();
                resource.close()
            }
        }
        sources.close()
        assertEquals(original, CueLargeTreeFixture.fileToken(fixture.file("large")))
        assertTrue(temporary.root.walkTopDown().filter { it.extension == "proof" }.none())
    }

    @Test(timeout = 90_000L)
    fun nativeBackwardForwardAndSameBlockReadsKeepOneLargeAudioEpochAndOneFullProof() = runBlocking {
        val reference = checkNotNull(fixture.scan("native")[1].cueReference)
        val registry = RuntimePlaybackResources()
        val input = CountingInputs(AndroidCuePcmInputFactory(fixture.context))
        val sources = CuePcmDataSources(input, registry, proofs())
        val resource = registry.registerCue(fixture.entry(reference))
        val platform = sources.platform(resource.mediaUri) { throw AssertionError("Unexpected CUE issue: $it") }
        try {
            assertEquals(0, input.audioInitializations.get())
            assertFalse(platform.supportsSeek)
            val total = platform.getSize()
            for (position in listOf(0L, 53L, 100_051L, 100_009L, total - 19L, 53L)) {
                val bytes = ByteArray(19)
                assertEquals(19, platform.readAt(position, bytes, 0, bytes.size))
                assertArrayEquals(fixture.expected(reference, position, bytes.size), bytes)
            }
            assertTrue(platform.supportsSeek)
            assertEquals(1, input.audioInitializations.get())
            assertTrue(input.audioReadBytes.get() < checkNotNull(reference.binding).audio.sizeBytes * 2L)
            assertEquals(-1, platform.readAt(total, ByteArray(1), 0, 1))
        } finally {
            platform.close();
            withTimeout(5000L) {
                platform.retirement.await();
                sources.awaitRetirement()
            }
            resource.close();
            sources.close()
        }
    }

    @Test(timeout = 90_000L)
    fun sameInodeSizeAndMtimeMutationIsRejectedBeforeAnyNewBytesReachTheNativeBuffer() = runBlocking {
        val reference = checkNotNull(fixture.scan("mutation")[1].cueReference)
        val registry = RuntimePlaybackResources()
        val sources = CuePcmDataSources(AndroidCuePcmInputFactory(fixture.context), registry, proofs())
        val resource = registry.registerCue(fixture.entry(reference))
        var issue: QueuePlaybackIssue? = null
        val platform = sources.platform(resource.mediaUri) { issue = it }
        val position = 100_011L
        try {
            val first = ByteArray(29)
            assertEquals(29, platform.readAt(position, first, 0, first.size))
            assertArrayEquals(fixture.expected(reference, position, first.size), first)
            val file = fixture.file("mutation")
            val length = file.length()
            val modified = file.lastModified()
            RandomAccessFile(file, "rw").use { writer ->
                val physical = checkNotNull(reference.pcmWindow).physicalByteOffset + position - 44L
                writer.seek(physical);
                val old = writer.readUnsignedByte();
                writer.seek(physical);
                writer.write(old xor 1)
            }
            assertTrue(file.setLastModified(modified))
            assertEquals(length, file.length())
            assertEquals(modified, file.lastModified())
            val sentinel = ByteArray(29) { 0x5a.toByte() }
            assertEquals(QueuePlaybackIssue.SOURCE_CHANGED, assertThrows(MediaTransportException::class.java) {
                platform.readAt(position, sentinel, 0, sentinel.size)
            }.issue)
            assertArrayEquals(ByteArray(29) { 0x5a.toByte() }, sentinel)
            assertEquals(QueuePlaybackIssue.SOURCE_CHANGED, issue)
        } finally {
            platform.close();
            withTimeout(5000L) {
                platform.retirement.await();
                sources.awaitRetirement()
            }
            resource.close();
            sources.close()
        }
    }

    @Test(timeout = 90_000L)
    fun cueChangedDuringLargeAudioProofCannotPublishAnOpenVerifiedEpoch() = runBlocking {
        val reference = checkNotNull(fixture.scan("late")[1].cueReference)
        val actual = AndroidCuePcmInputFactory(fixture.context)
        val changed = AtomicBoolean()
        val input = CuePcmInputFactory { binding ->
            val owned = actual.create(binding)
            object : CueSeekableInput by owned {
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int = owned.read(buffer, offset, count).also { result ->
                    if (binding.objectId == "late-wave" && result == -1 && changed.compareAndSet(false, true)) {
                        val cue = checkNotNull(fixture.provider.documents["late-cue"])
                        val original = cue.readText()
                        cue.writeText(original.replace("First segment", "Other segment"))
                        assertEquals(original.toByteArray().size.toLong(), cue.length())
                    }
                }
            }
        }
        val registry = RuntimePlaybackResources()
        val sources = CuePcmDataSources(input, registry, proofs())
        val resource = registry.registerCue(fixture.entry(reference))
        val source = sources.createDataSource()
        try {
            assertEquals(QueuePlaybackIssue.SOURCE_CHANGED, assertThrows(MediaTransportException::class.java) {
                source.open(DataSpec.Builder().setUri(resource.mediaUri).build())
            }.issue)
            assertTrue(changed.get())
            assertFalse(source.verified)
            withTimeout(5000L) { sources.awaitRetirement() }
            assertTrue(temporary.root.walkTopDown().filter { it.extension == "proof" }.none())
        } finally {
            source.close();
            resource.close();
            sources.close()
        }
    }

    @Test(timeout = 120_000L)
    fun largeTreeWholeGroupPreviewAndConfirmedAccessMappingRetainLogicalKeysAndPlayableWindows() = runBlocking {
        val old = fixture.scan("old")
        val target = fixture.scan("new")
        val keys = old.map { it.trackKey }
        val plan = fixture.room.library.previewCueAdoption(keys[0], target[0].trackKey)
        assertTrue(plan.differences.identityChanged)
        assertFalse(plan.differences.differentOrUnknownContent)
        for (member in plan.sources) {
            fixture.sources.validateCueRead(member.cueRead)
            fixture.sources.validateCueRead(member.audioRead)
        }
        fixture.room.library.libraryTransaction { fixture.room.library.commitCueAdoptionUnlocked(plan, true, false) }
        for (key in keys) {
            val read = fixture.room.library.resolveCueRead(key)
            assertEquals(key, read.reference.trackKey)
            assertEquals("source-new", read.reference.actualDocument.sourceId)
            assertEquals(plan.aliasId, checkNotNull(read.reference.binding).adoptionAliasId)
            assertEquals(read.audio, fixture.sources.validateCueRead(read.audio).copy(contentValidation = read.audio.contentValidation))
            assertTransport(read.reference)
        }
    }

    private fun assertTransport(reference: LocalCueReference) {
        val registry = RuntimePlaybackResources()
        val sources = CuePcmDataSources(AndroidCuePcmInputFactory(fixture.context), registry, proofs())
        val resource = registry.registerCue(fixture.entry(reference))
        val source = sources.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(resource.mediaUri).build())
            val position = 57L
            source.seekVirtual(position)
            val bytes = ByteArray(31)
            assertEquals(31, source.read(bytes, 0, bytes.size))
            assertArrayEquals(fixture.expected(reference, position, bytes.size), bytes)
            assertEquals(1, source.completedAudioProofs)
        } finally {
            source.close();
            resource.close();
            sources.close()
        }
    }
    private fun proofs() = CueBlockProofs.privateDirectory(File(temporary.root, "proof-index"))
    private class CountingInputs(private val actual: CuePcmInputFactory,
        private val onRead: (CueFileBinding, Int) -> Unit = { _, _ -> },
    ) : CuePcmInputFactory {
        val audioInitializations = AtomicInteger()
        val audioReadBytes = AtomicLong()
        override fun create(binding: CueFileBinding): CueSeekableInput {
            val audio = binding.objectId.endsWith("-wave")
            val owned = actual.create(binding)
            return object : CueSeekableInput by owned {
                override fun initialize() {
                    owned.initialize();
                    if (audio) audioInitializations.incrementAndGet()
                }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int = owned.read(buffer, offset, count).also {
                    if (audio && it > 0) audioReadBytes.addAndGet(it.toLong())
                    onRead(binding, it)
                }
            }
        }
    }
    private class ScanCountingInputs(private val context: Context, private val onEof: () -> Unit = {}) {
        class ReadRecord(val objectId: String) {
            val readBytes = AtomicLong()
            val eofs = AtomicInteger()
            val initializations = AtomicInteger()
            val seeks = CopyOnWriteArrayList<Long>()
            val closed = AtomicBoolean()
        }
        val records = CopyOnWriteArrayList<ReadRecord>()
        fun create(binding: CueFileBinding, signal: CancellationSignal): CueOwnedInput {
            val actual = AndroidCueOwnedInput(context, binding, signal)
            val record = ReadRecord(binding.objectId).also { records += it }
            return object : CueOwnedInput by actual {
                override fun initialize() {
                    actual.initialize();
                    record.initializations.incrementAndGet()
                }
                override fun seek(position: Long) {
                    actual.seek(position);
                    record.seeks += position
                }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int = actual.read(buffer, offset, count).also {
                    if (it > 0) record.readBytes.addAndGet(it.toLong())
                    if (it == -1) {
                        record.eofs.incrementAndGet();
                        if (binding.objectId.endsWith("-wave")) onEof()
                    }
                }
                override fun close() {
                    actual.close();
                    record.closed.set(true)
                }
            }
        }
    }
}
