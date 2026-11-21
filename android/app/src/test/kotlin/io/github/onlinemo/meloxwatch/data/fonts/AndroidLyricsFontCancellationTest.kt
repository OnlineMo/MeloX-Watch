package io.github.onlinemo.meloxwatch.data.fonts

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
@Config(sdk = [26], manifest = Config.NONE)
class AndroidLyricsFontCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ignoredReadCancellationKeepsOwnershipUntilStreamAndDescriptorActuallyClose() = runBlocking {
        withTimeout(10_000) {
            val directory = temporary.newFolder("read-block")
            val stream = BlockingFontStream(blockClose = false)
            val io = LocalProviderIo()
            val files = store(directory, io, stream)
            val caller = async(Dispatchers.Default) { files.importFont(Fixture.request(), emptySet()) }
            try {
                assertTrue(stream.entered.await(5, TimeUnit.SECONDS))
                caller.cancel()
                assertFalse(caller.isCompleted)
                assertEquals(0, stream.inputCloses.get())
                assertEquals(0, stream.descriptorCloses.get())
                assertTrue(checkNotNull(directory.list()).single().endsWith(".part"))
                expectBusy { files.importFont(Fixture.request(), emptySet()) }
                stream.release.countDown()
                caller.join()
                io.awaitIdle()
                assertEquals(1, stream.inputCloses.get())
                assertEquals(1, stream.descriptorCloses.get())
                assertTrue(checkNotNull(directory.list()).isEmpty())
                assertTrue(files.importFont(Fixture.request(), emptySet()).asset.byteCount > 0)
            } finally { stream.release.countDown(); caller.cancel() }
        }
    }

    @Test fun cancellationDuringAnIgnoringCloseDoesNotRemoveItsStillOpenStagingFile() = runBlocking {
        withTimeout(10_000) {
            val directory = temporary.newFolder("close-block")
            val stream = BlockingFontStream(blockClose = true)
            val files = store(directory, LocalProviderIo(), stream)
            val caller = async(Dispatchers.Default) { files.importFont(Fixture.request(), emptySet()) }
            try {
                assertTrue(stream.entered.await(5, TimeUnit.SECONDS))
                caller.cancel()
                assertFalse(caller.isCompleted)
                assertEquals(0, stream.inputCloses.get())
                assertTrue(checkNotNull(directory.list()).single().endsWith(".part"))
                expectBusy { files.importFont(Fixture.request(), emptySet()) }
                stream.release.countDown()
                caller.join()
                assertEquals(1, stream.inputCloses.get())
                assertEquals(1, stream.descriptorCloses.get())
                assertTrue(checkNotNull(directory.list()).isEmpty())
            } finally { stream.release.countDown(); caller.cancel() }
        }
    }

    @Test fun unrelatedBusyProviderIsRejectedImmediatelyWithoutAQueuedFontRead() = runBlocking {
        withTimeout(10_000) {
            val io = LocalProviderIo()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val active = async(Dispatchers.Default) { io.read { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            val directory = File(temporary.root, "not-created")
            val source = BytesFontStream(Fixture.bytes())
            val files = store(directory, io, source)
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                expectBusy { files.importFont(Fixture.request(), emptySet()) }
                assertFalse(active.isCompleted)
                assertFalse(directory.exists())
                assertEquals(0, source.opens.get())
            } finally { release.countDown(); active.join() }
        }
    }

    @Test fun ignoredNativeValidationCancellationCannotOverlapANewCandidate() = runBlocking {
        withTimeout(10_000) {
            val directory = temporary.newFolder("native-block")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val delegate = FilesOnlyFontDecoder()
            val decoder = object : LyricsFontDecoder {
                override fun validate(file: File, info: SfntFontInfo, checkCancellation: () -> Unit): FontWeightCapability {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return delegate.validate(file, info, checkCancellation)
                }
                override fun decode(file: File, info: SfntFontInfo, capability: FontWeightCapability, requested: Float?): DecodedLyricsFont =
                    delegate.decode(file, info, capability, requested)
            }
            val source = BytesFontStream(Fixture.bytes())
            val files = AndroidLyricsFontFiles(directory, LocalProviderIo(), source, decoder, { Fixture.ROOM })
            val caller = async(Dispatchers.Default) { files.importFont(Fixture.request(), emptySet()) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(1, source.closes.get())
                caller.cancel()
                assertFalse(caller.isCompleted)
                expectBusy { files.importFont(Fixture.request(), emptySet()) }
                assertTrue(checkNotNull(directory.list()).single().endsWith(".part"))
                release.countDown()
                caller.join()
                assertTrue(checkNotNull(directory.list()).isEmpty())
            } finally { release.countDown(); caller.cancel() }
        }
    }

    @Test fun defaultFileOwnerRunsReadParseAndDecodeOffTheCallingThread() = runBlocking {
        val callingThread = Thread.currentThread().id
        val source = object : LyricsFontStreamOpener {
            override fun open(uri: String, signal: CancellationSignal): OpenedLyricsFontStream {
                assertTrue(Thread.currentThread().id != callingThread)
                return OpenedLyricsFontStream(ByteArrayInputStream(Fixture.bytes()))
            }
        }
        val delegate = FilesOnlyFontDecoder()
        val decoder = object : LyricsFontDecoder {
            override fun validate(file: File, info: SfntFontInfo, checkCancellation: () -> Unit): FontWeightCapability {
                assertTrue(Thread.currentThread().id != callingThread)
                return delegate.validate(file, info, checkCancellation)
            }
            override fun decode(file: File, info: SfntFontInfo, capability: FontWeightCapability, requested: Float?): DecodedLyricsFont {
                assertTrue(Thread.currentThread().id != callingThread)
                return delegate.decode(file, info, capability, requested)
            }
        }
        val files = AndroidLyricsFontFiles(temporary.newFolder("dispatcher"), LocalProviderIo(), source, decoder, { Fixture.ROOM })
        val prepared = files.importFont(Fixture.request(), emptySet())
        assertFalse(files.loadFont(LyricsFontSelection(prepared.asset, 600f), "H").usesSystemFont)
    }

    private fun store(directory: File, io: LocalProviderIo, source: LyricsFontStreamOpener): AndroidLyricsFontFiles =
        AndroidLyricsFontFiles(directory, io, source, FilesOnlyFontDecoder(), { Fixture.ROOM })

    private suspend fun expectBusy(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected busy") }
        catch (error: LyricsFontException) { assertEquals(LyricsFontIssue.BUSY, error.issue) }
    }

    private class BlockingFontStream(private val blockClose: Boolean) : LyricsFontStreamOpener {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inputCloses = AtomicInteger()
        val descriptorCloses = AtomicInteger()
        private val blocked = AtomicBoolean(false)
        override fun open(uri: String, signal: CancellationSignal): OpenedLyricsFontStream {
            val bytes = Fixture.bytes()
            val input = object : FilterInputStream(ByteArrayInputStream(bytes)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!blockClose) blockOnce()
                    return super.read(buffer, offset, length)
                }
                override fun close() {
                    if (blockClose) blockOnce()
                    super.close()
                    inputCloses.incrementAndGet()
                }
                private fun blockOnce() {
                    if (blocked.compareAndSet(false, true)) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            return OpenedLyricsFontStream(input, bytes.size.toLong()) { descriptorCloses.incrementAndGet() }
        }
    }
}
