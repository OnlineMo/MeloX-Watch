package io.github.onlinemo.meloxwatch.data.lyrics.active

import android.content.Context
import android.content.ContextWrapper
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LyricsDocumentCopiesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private val bytes = "[00:01]original lyrics".toByteArray()
    private val candidate get() = ObservedCandidate("selected", "selected-document",
        "content://lyrics.copy.fixture/document/selected", null, "selected.lrc", "text/plain", LocalNodeKind.OTHER,
        bytes.size.toLong())

    @Before fun setup() {
        val root = temporary.newFolder("private-copy-data")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
    }

    @Test fun confirmedBytesInstallAtomicallyAndTheirPinProtectsTheCopyUntilDatabaseCommitEnds(): Unit = runBlocking {
        var syncs = 0
        val original = File(temporary.root, "selected.lrc").apply { writeBytes(bytes) }
        val copies = LyricsDocumentCopies(context, LocalProviderIo(), openInput = { _, _ -> original.inputStream() },
            freeBytes = { ROOM_BYTES }, syncDirectory = { syncs++ })
        val copy = copies.create(candidate, sha256(bytes))
        val token = checkNotNull(OwnedLyricsProvider.token(context, android.net.Uri.parse(copy.uri)))
        val completed = OwnedLyricsProvider.finalFile(context, token)
        assertArrayEquals(bytes, completed.readBytes())
        assertArrayEquals(bytes, original.readBytes())
        assertEquals(listOf("$token.lrc"), checkNotNull(OwnedLyricsProvider.root(context).list()).toList())
        assertEquals(1, syncs)
        var databaseReads = 0
        copies.recover {
            databaseReads++
            false
        }
        assertTrue(completed.isFile)
        assertEquals(0, databaseReads)
        copy.release()
        copy.release()
        copies.recover {
            databaseReads++
            false
        }
        assertEquals(1, databaseReads)
        assertFalse(completed.exists())
        assertArrayEquals(bytes, original.readBytes())
        assertEquals(2, syncs)
    }

    @Test fun recoveryKeepsAReferencedCopyAndRemovesOnlyAnUnreferencedOrphanAndStage(): Unit = runBlocking {
        val copies = store()
        val referenced = copies.create(candidate, sha256(bytes))
        referenced.release()
        val orphan = copies.create(candidate, sha256(bytes))
        orphan.release()
        val stageToken = UUID.randomUUID().toString()
        OwnedLyricsProvider.stagingFile(context, stageToken).writeBytes(byteArrayOf(1))
        val queries = ArrayList<String>()
        copies.recover { uri ->
            queries += uri
            uri == referenced.uri
        }
        assertEquals(setOf(referenced.uri, orphan.uri), queries.toSet())
        val remaining = checkNotNull(OwnedLyricsProvider.root(context).list()).toList()
        val referencedToken = checkNotNull(OwnedLyricsProvider.token(context, android.net.Uri.parse(referenced.uri)))
        assertEquals(listOf("$referencedToken.lrc"), remaining)
        assertArrayEquals(bytes, OwnedLyricsProvider.finalFile(context, referencedToken).readBytes())
    }

    @Test fun changedContentAndIncorrectDeclaredLengthNeverLeaveACompletedCopy(): Unit = runBlocking {
        val copies = store()
        expect(SourceReadIssue.SOURCE_CHANGED) { copies.create(candidate, "a".repeat(64)) }
        assertTrue(checkNotNull(OwnedLyricsProvider.root(context).list()).isEmpty())
        expect(SourceReadIssue.SOURCE_CHANGED) { copies.create(candidate.copy(sizeBytes = bytes.size + 1L), sha256(bytes)) }
        assertTrue(checkNotNull(OwnedLyricsProvider.root(context).list()).isEmpty())
        val copy = copies.create(candidate, sha256(bytes))
        assertEquals(sha256(bytes), copy.sha256)
        copy.release()
    }

    @Test fun lowSpaceAndQuotaFailurePreserveExistingCopiesAndDoNotInstallNewBytes(): Unit = runBlocking {
        val token = UUID.randomUUID().toString()
        val original = OwnedLyricsProvider.finalFile(context, token)
        original.writeBytes(byteArrayOf(7, 8))
        val noSpace = store(freeBytes = LyricsDocumentCopies.FREE_RESERVE_BYTES)
        expect(SourceReadIssue.STORAGE_FULL) { noSpace.create(candidate, sha256(bytes)) }
        assertArrayEquals(byteArrayOf(7, 8), original.readBytes())
        assertEquals(listOf("$token.lrc"), checkNotNull(OwnedLyricsProvider.root(context).list()).toList())
        original.writeBytes(ByteArray(LyricsDocumentCopies.DISK_BYTES.toInt()))
        expect(SourceReadIssue.STORAGE_FULL) { store().create(candidate, sha256(bytes)) }
        assertEquals(LyricsDocumentCopies.DISK_BYTES, original.length())
        assertEquals(listOf("$token.lrc"), checkNotNull(OwnedLyricsProvider.root(context).list()).toList())
    }

    @Test fun failingDirectorySyncAfterTheAtomicMoveCannotReturnOrLeaveAnUncommittedAssociation(): Unit = runBlocking {
        val copies = LyricsDocumentCopies(context, LocalProviderIo(), openInput = { _, _ -> ByteArrayInputStream(bytes) },
            freeBytes = { ROOM_BYTES }, syncDirectory = { throw IOException("Controlled directory sync failure") })
        expect(SourceReadIssue.SOURCE_UNAVAILABLE) { copies.create(candidate, sha256(bytes)) }
        assertTrue(checkNotNull(OwnedLyricsProvider.root(context).list()).isEmpty())
    }

    @Test fun emptyInputCannotConsumeAnUnboundedNumberOfZeroByteAssociationObjects(): Unit = runBlocking {
        val copies = LyricsDocumentCopies(context, LocalProviderIo(), openInput = { _, _ -> ByteArrayInputStream(byteArrayOf()) },
            freeBytes = { ROOM_BYTES }, syncDirectory = {})
        expect(SourceReadIssue.INVALID_SOURCE) {
            copies.create(candidate.copy(sizeBytes = 0L), sha256(byteArrayOf()))
        }
        assertTrue(checkNotNull(OwnedLyricsProvider.root(context).list()).isEmpty())
    }

    @Test fun recoveryCrossesSeveralPagesOnceAndKeepsEveryReferencedObject(): Unit = runBlocking {
        val tokens = (0 until 70).map { UUID.randomUUID().toString() }
        tokens.forEach { token -> OwnedLyricsProvider.finalFile(context, token).writeBytes(byteArrayOf(1)) }
        val referenced = tokens.filterIndexed { index, _ -> index % 3 == 0 }.toSet()
        val observed = HashSet<String>()
        store().recover { uri ->
            val token = checkNotNull(OwnedLyricsProvider.token(context, android.net.Uri.parse(uri)))
            assertTrue("Each reference is queried once", observed.add(token))
            token in referenced
        }
        assertEquals(tokens.toSet(), observed)
        val retained = checkNotNull(OwnedLyricsProvider.root(context).list()).map { it.removeSuffix(".lrc") }.toSet()
        assertEquals(referenced, retained)
        referenced.forEach { token -> assertArrayEquals(byteArrayOf(1), OwnedLyricsProvider.finalFile(context, token).readBytes()) }
    }

    @Test fun aReferenceWriteCannotRaceACollectorBetweenItsDatabaseReadAndPhysicalDelete(): Unit = runBlocking {
        val copies = store()
        val copy = copies.create(candidate, sha256(bytes))
        copy.release()
        val checking = CompletableDeferred<Unit>()
        val finishCheck = CompletableDeferred<Unit>()
        val collector = async(Dispatchers.Default) {
            copies.recover {
                checking.complete(Unit)
                withContext(NonCancellable) { finishCheck.await() }
                true
            }
        }
        try {
            withTimeout(5_000L) { checking.await() }
            collector.cancel()
            expect(SourceReadIssue.BUSY) { copies.withReferenceWrite { error("Must not enter the reference writer") } }
            expect(SourceReadIssue.BUSY) { copies.create(candidate, sha256(bytes)) }
            finishCheck.complete(Unit)
            collector.join()
            val token = checkNotNull(OwnedLyricsProvider.token(context, android.net.Uri.parse(copy.uri)))
            assertArrayEquals(bytes, OwnedLyricsProvider.finalFile(context, token).readBytes())
            var written = false
            copies.withReferenceWrite { written = true }
            assertTrue(written)
        } finally {
            finishCheck.complete(Unit)
            collector.cancel()
            collector.join()
        }
    }

    @Test fun ignoredReadCancellationRetainsTheRealChannelAndStageUntilTheStreamActuallyCloses(): Unit = runBlocking {
        cancellation(blockOnClose = false)
    }

    @Test fun ignoredCloseCancellationCannotDeleteTheOpenStageOrAdmitAnotherCopyEarly(): Unit = runBlocking {
        cancellation(blockOnClose = true)
    }

    @Test fun cancellationAfterCopyResumeDoesNoDiskCleanupOnTheCallerAndLeavesABoundedUnreferencedCopy(): Unit = runBlocking {
        val callerThread = Thread.currentThread()
        val syncThreads = ConcurrentLinkedQueue<Thread>()
        val dispatcher = HeldCallerDispatcher()
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val io = LocalProviderIo()
        val copies = LyricsDocumentCopies(context, io, openInput = { _, _ ->
            openEntered.countDown()
            check(releaseOpen.await(5L, TimeUnit.SECONDS))
            ByteArrayInputStream(bytes)
        },
            freeBytes = { ROOM_BYTES }, syncDirectory = { syncThreads.add(Thread.currentThread()) })
        val caller = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { copies.create(candidate, sha256(bytes)) }
        try {
            assertTrue(openEntered.await(5L, TimeUnit.SECONDS))
            releaseOpen.countDown()
            dispatcher.awaitQueued()
            assertEquals(1, syncThreads.size)
            assertTrue(syncThreads.all { it !== callerThread })
            val installed = checkNotNull(OwnedLyricsProvider.root(context).listFiles()).single()
            assertTrue(installed.name.endsWith(".lrc"))
            assertArrayEquals(bytes, installed.readBytes())
            caller.cancel()
            dispatcher.runNext()
            caller.join()
            assertEquals(1, syncThreads.size)
            assertTrue(installed.isFile)
            copies.withReferenceWrite { Unit }
            var references = 0
            copies.recover {
                references++
                false
            }
            assertEquals(1, references)
            assertFalse(installed.exists())
            assertEquals(2, syncThreads.size)
            assertTrue(syncThreads.all { it !== callerThread })
        } finally {
            releaseOpen.countDown()
            caller.cancel()
            dispatcher.finish(caller)
            caller.join()
        }
    }

    @Test fun cancellationAfterCursorResumeClosesTheRealDirectoryOnThePhysicalWorkerBeforeReleasingItsGate(): Unit = runBlocking {
        val callerThread = Thread.currentThread()
        val dispatcher = HeldCallerDispatcher()
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeThreads = ConcurrentLinkedQueue<Thread>()
        val io = LocalProviderIo()
        val copies = LyricsDocumentCopies(context, io, openInput = { _, _ -> ByteArrayInputStream(bytes) },
            freeBytes = { ROOM_BYTES }, syncDirectory = {}, openRecoveryDirectory = { root ->
                openEntered.countDown()
                check(releaseOpen.await(5L, TimeUnit.SECONDS))
                val stream = Files.newDirectoryStream(root.toPath())
                object : DirectoryStream<Path> {
                    override fun iterator(): MutableIterator<Path> = stream.iterator()
                    override fun close() {
                        closeThreads.add(Thread.currentThread())
                        stream.close()
                        closeEntered.countDown()
                        check(releaseClose.await(5L, TimeUnit.SECONDS))
                    }
                }
            })
        val caller = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { copies.recover { false } }
        try {
            assertTrue(openEntered.await(5L, TimeUnit.SECONDS))
            releaseOpen.countDown()
            dispatcher.awaitQueued()
            assertTrue(closeThreads.isEmpty())
            caller.cancel()
            dispatcher.runNext()
            assertTrue(closeEntered.await(5L, TimeUnit.SECONDS))
            assertEquals(1, closeThreads.size)
            assertTrue(closeThreads.all { it !== callerThread })
            assertFalse(caller.isCompleted)
            expect(SourceReadIssue.BUSY) { copies.withReferenceWrite { error("Cursor cleanup still owns the gate") } }
            releaseClose.countDown()
            dispatcher.awaitQueued()
            dispatcher.runNext()
            caller.join()
            copies.withReferenceWrite { Unit }
            assertEquals(1, closeThreads.size)
            assertTrue(checkNotNull(OwnedLyricsProvider.root(context).list()).isEmpty())
        } finally {
            releaseOpen.countDown()
            releaseClose.countDown()
            caller.cancel()
            dispatcher.finish(caller)
            caller.join()
        }
    }

    private suspend fun cancellation(blockOnClose: Boolean) = withTimeout(10_000L) {
        val input = BlockingLyricsInput(bytes, blockOnClose)
        val io = LocalProviderIo()
        val copies = LyricsDocumentCopies(context, io, openInput = { _, _ -> input }, freeBytes = { ROOM_BYTES }, syncDirectory = {})
        val caller = async(Dispatchers.Default) { copies.create(candidate, sha256(bytes)) }
        try {
            assertTrue(input.entered.await(5L, TimeUnit.SECONDS))
            caller.cancel()
            assertFalse(caller.isCompleted)
            assertEquals(0, input.closes.get())
            assertTrue(checkNotNull(OwnedLyricsProvider.root(context).list()).single().endsWith(".part"))
            expect(SourceReadIssue.BUSY) { copies.create(candidate, sha256(bytes)) }
            assertEquals(0, input.closes.get())
            input.release.countDown()
            caller.join()
            io.awaitIdle()
            assertEquals(1, input.closes.get())
            assertTrue(checkNotNull(OwnedLyricsProvider.root(context).list()).isEmpty())
            val replacement = store().create(candidate, sha256(bytes))
            assertEquals(sha256(bytes), replacement.sha256)
            replacement.release()
        } finally {
            input.release.countDown()
            caller.cancel()
            caller.join()
        }
    }

    private fun store(freeBytes: Long = ROOM_BYTES) = LyricsDocumentCopies(context, LocalProviderIo(),
        openInput = { _, _ -> ByteArrayInputStream(bytes) }, freeBytes = { freeBytes }, syncDirectory = {})

    private suspend fun expect(issue: SourceReadIssue, action: suspend () -> Unit) {
        try {
            action()
            throw AssertionError("Expected controlled copy failure")
        } catch (error: SourceReadException) {
            assertEquals(issue, error.issue)
        }
    }

    private class BlockingLyricsInput(bytes: ByteArray, private val blockOnClose: Boolean) : FilterInputStream(ByteArrayInputStream(bytes)) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        private val blocked = AtomicBoolean()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!blockOnClose) blockOnce()
            return super.read(buffer, offset, length)
        }
        override fun close() {
            if (blockOnClose) blockOnce()
            super.close()
            closes.incrementAndGet()
        }
        private fun blockOnce() {
            if (blocked.compareAndSet(false, true)) {
                entered.countDown()
                check(release.await(5L, TimeUnit.SECONDS))
            }
        }
    }

    /** The real IO worker returns first; only caller continuation delivery is held. */
    private class HeldCallerDispatcher : CoroutineDispatcher() {
        private val queued = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.put(block) }
        fun awaitQueued() {
            val next = checkNotNull(queued.poll(5L, TimeUnit.SECONDS)) { "Caller continuation was not queued" }
            check(queued.offer(next))
        }
        fun runNext() { checkNotNull(queued.poll(5L, TimeUnit.SECONDS)).run() }
        fun finish(job: Job) {
            var delivered = 0
            while (!job.isCompleted) {
                check(++delivered <= 4) { "Cancellation cleanup did not finish within its bounded continuations" }
                runNext()
            }
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object { private const val ROOM_BYTES = 1_073_741_824L }
}
