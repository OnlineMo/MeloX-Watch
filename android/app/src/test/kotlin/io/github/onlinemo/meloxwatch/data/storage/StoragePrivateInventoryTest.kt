package io.github.onlinemo.meloxwatch.data.storage

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class StoragePrivateInventoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = object : ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = temporary.root
    }

    @Test(timeout = 30_000L)
    fun actualFileSizesAreMeasuredWithoutDirectoryQuotaPaddingOrAnyMutation() = runBlocking {
        val root = temporary.newFolder("documents")
        val original = File(root, "saved.lrc").apply { writeBytes("actual text".toByteArray()) }
        val stage = File(root, "pending.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val before = original.readBytes()
        val spec = flat(root, StorageArea.LYRICS, mutableStage = true)
        val report = StoragePrivateInventory(context, LocalProviderIo()).measure { listOf(spec) }
        assertEquals(before.size.toLong(), report.lyricsCopies.bytes)
        assertEquals(stage.length(), report.staging.observedBytes)
        assertTrue(report.lyricsCopies.complete)
        assertFalse(report.staging.complete)
        assertArrayEquals(before, original.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), stage.readBytes())
    }

    @Test(timeout = 30_000L)
    fun onlyAMissingDirectoryBelowAHealthyParentIsACompleteZeroMeasurement() = runBlocking {
        val missing = File(temporary.root, "absent")
        val wrongParent = File(temporary.root, "not-a-directory").apply { writeBytes(byteArrayOf(1)) }
        val healthy = StoragePrivateInventory(context, LocalProviderIo()).measure { listOf(flat(missing, StorageArea.FONTS)) }
        assertEquals(0L, healthy.fonts.bytes)
        assertTrue(healthy.fonts.complete)
        assertFalse(missing.exists())
        val invalid = StoragePrivateInventory(context, LocalProviderIo()).measure {
            listOf(flat(File(wrongParent, "missing"), StorageArea.FONTS))
        }
        assertNull(invalid.fonts.observedBytes)
        assertFalse(invalid.fonts.complete)
        assertArrayEquals(byteArrayOf(1), wrongParent.readBytes())
    }

    @Test(timeout = 30_000L)
    fun linksAndUnknownEntriesArePreservedAndCannotProduceACompleteSubtotal() = runBlocking {
        val root = temporary.newFolder("private")
        val other = temporary.newFolder("outside")
        val sentinel = File(other, "preserve").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        Files.createSymbolicLink(File(root, "linked.lrc").toPath(), sentinel.toPath())
        val unknown = File(root, "unrecognized.data").apply { writeBytes(byteArrayOf(7)) }
        val measured = File(root, "ordinary.lrc").apply { writeBytes(byteArrayOf(8, 9)) }
        val report = StoragePrivateInventory(context, LocalProviderIo()).measure { listOf(flat(root, StorageArea.LYRICS)) }
        assertFalse(report.lyricsCopies.complete)
        assertTrue(report.lyricsCopies.unknownFiles > 0L)
        assertArrayEquals(byteArrayOf(4, 5, 6), sentinel.readBytes())
        assertArrayEquals(byteArrayOf(7), unknown.readBytes())
        assertArrayEquals(byteArrayOf(8, 9), measured.readBytes())
        assertTrue(Files.isSymbolicLink(File(root, "linked.lrc").toPath()))
    }

    @Test(timeout = 30_000L)
    fun anAppendBetweenBatchesDoesNotTurnAnUnchangedDirectoryTimestampIntoASealedStage() = runBlocking {
        val root = temporary.newFolder("append")
        val stage = File(root, "000.part").apply { writeBytes(byteArrayOf(1)) }
        repeat(110) { File(root, "${it.toString().padStart(3, '0')}.lrc").writeBytes(byteArrayOf(2)) }
        val beforeDirectoryTime = Files.getLastModifiedTime(root.toPath())
        val changed = AtomicBoolean(false)
        val nexts = AtomicInteger()
        val inventory = StoragePrivateInventory(context, LocalProviderIo(), openDirectory = { path ->
            orderedStream(path) {
                if (nexts.incrementAndGet() == 101 && changed.compareAndSet(false, true)) {
                    stage.appendBytes(byteArrayOf(3, 4))
                    Files.setLastModifiedTime(root.toPath(), beforeDirectoryTime)
                }
            }
        })
        val report = inventory.measure { listOf(flat(root, StorageArea.LYRICS, mutableStage = true)) }
        assertTrue(changed.get())
        assertEquals(beforeDirectoryTime, Files.getLastModifiedTime(root.toPath()))
        assertEquals(3L, stage.length())
        assertFalse(report.staging.complete)
        assertEquals(StorageIssue.INCOMPLETE, report.staging.issue)
    }

    @Test(timeout = 30_000L)
    fun aPartialMovingIntoReceivedStorageInvalidatesItsRecoverySubtotalInsteadOfCountingItTwice() = runBlocking {
        val incoming = temporary.newFolder("incoming")
        val received = temporary.newFolder("received")
        val input = File(incoming, "000.part").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        repeat(110) { File(incoming, "${it.toString().padStart(3, '0')}.lrc").writeBytes(byteArrayOf(5)) }
        val moved = AtomicBoolean(false)
        val nexts = AtomicInteger()
        val inventory = StoragePrivateInventory(context, LocalProviderIo(), openDirectory = { path ->
            orderedStream(path) {
                if (path == incoming.toPath() && nexts.incrementAndGet() == 101 && moved.compareAndSet(false, true)) {
                    Files.move(input.toPath(), File(received, "received.part").toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            }
        })
        val report = inventory.measure {
            listOf(flat(incoming, StorageArea.LYRICS), flat(received, StorageArea.ARTWORK))
        }
        assertTrue(moved.get())
        assertFalse(input.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(received, "received.part").readBytes())
        assertNull(report.staging.bytes)
        assertFalse(report.staging.complete)
    }

    @Test(timeout = 30_000L)
    fun cancellationKeepsTheReadAndCursorOwnedUntilTheActualBlockingProviderRetires() = runBlocking {
        val root = temporary.newFolder("blocking")
        File(root, "saved.lrc").writeBytes(byteArrayOf(1))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val io = LocalProviderIo()
        val inventory = StoragePrivateInventory(context, io, openDirectory = { path ->
            val actual = Files.newDirectoryStream(path)
            object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> {
                    val iterator = actual.iterator()
                    return object : MutableIterator<Path> {
                        override fun hasNext(): Boolean {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            return iterator.hasNext()
                        }
                        override fun next(): Path = iterator.next()
                        override fun remove() = throw UnsupportedOperationException()
                    }
                }
                override fun close() { actual.close(); closed.set(true) }
            }
        })
        val reading = async(Dispatchers.Default) { inventory.measure { listOf(flat(root, StorageArea.LYRICS)) } }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            reading.cancel()
            assertFalse(reading.isCompleted)
            assertFalse(closed.get())
            try { inventory.measure { listOf(flat(root, StorageArea.LYRICS)) }; throw AssertionError("Busy cursor was replaced") }
            catch (failure: StorageException) { assertEquals(StorageIssue.BUSY, failure.issue) }
            release.countDown()
            reading.join()
            assertTrue(closed.get())
            assertEquals(1L, inventory.measure { listOf(flat(root, StorageArea.LYRICS)) }.lyricsCopies.bytes)
        } finally { release.countDown(); reading.cancel(); reading.join() }
    }

    @Test(timeout = 30_000L)
    fun aFailedNativeCloseRetainsThatCursorAndARetryClosesItBeforeOpeningNewFiles() = runBlocking {
        val root = temporary.newFolder("close")
        File(root, "saved.lrc").writeBytes(byteArrayOf(1))
        val closes = AtomicInteger()
        val opens = AtomicInteger()
        val inventory = StoragePrivateInventory(context, LocalProviderIo(), openDirectory = { path ->
            opens.incrementAndGet()
            val actual = Files.newDirectoryStream(path)
            object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = actual.iterator()
                override fun close() {
                    if (closes.incrementAndGet() <= 3) throw ControlledCloseFailure()
                    actual.close()
                }
            }
        })
        try { inventory.measure { listOf(flat(root, StorageArea.LYRICS)) }; throw AssertionError("Failed close was accepted") }
        catch (failure: StorageException) {
            assertEquals(StorageIssue.CLEANUP_REQUIRED, failure.issue)
            assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it is ControlledCloseFailure })
        }
        assertEquals(1, opens.get())
        assertEquals(1L, inventory.measure { listOf(flat(root, StorageArea.LYRICS)) }.lyricsCopies.bytes)
        assertEquals(2, opens.get())
        assertTrue(closes.get() >= 4)
    }

    private fun flat(root: File, area: StorageArea, mutableStage: Boolean = false) = StorageDirectorySpec(root, area, 1, null,
        classify = { parts, directory ->
            val name = parts.singleOrNull()
            when {
                directory || name == null -> null
                name.endsWith(".lrc") -> area
                name.endsWith(".part") -> StorageArea.RECOVERY
                else -> null
            }
        }, mutableFile = { mutableStage && it.last().endsWith(".part") })

    private class ControlledCloseFailure : IOException("Controlled native close failure")

    private fun orderedStream(path: Path, beforeNext: () -> Unit): DirectoryStream<Path> {
        val actual = Files.newDirectoryStream(path)
        val paths = actual.toList().sortedBy { it.fileName.toString() }
        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> {
                val iterator = paths.iterator()
                return object : MutableIterator<Path> {
                    override fun hasNext() = iterator.hasNext()
                    override fun next(): Path { beforeNext(); return iterator.next() }
                    override fun remove() = throw UnsupportedOperationException()
                }
            }
            override fun close() = actual.close()
        }
    }
}
