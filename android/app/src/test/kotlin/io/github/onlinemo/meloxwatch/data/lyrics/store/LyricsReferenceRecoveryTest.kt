package io.github.onlinemo.meloxwatch.data.lyrics.store

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocumentCopies
import io.github.onlinemo.meloxwatch.data.lyrics.active.OwnedLyricsProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Uses the actual Room DAO, commits and private files; no decoded Unavailable result authorizes deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class LyricsReferenceRecoveryTest : LyricsRoomPlaybackFixture() {
    private lateinit var copyContext: Context
    private val bytes = "[00:01]preserved original lyrics".toByteArray(Charsets.UTF_8)
    private val candidate get() = ObservedCandidate("selected-lyrics", "selected-document",
        "content://lyrics-reference-fixture/document/selected", null, "selected.lrc", "text/plain",
        LocalNodeKind.FILE, sizeBytes = bytes.size.toLong())

    @Before
    fun initializeCopyDirectory() {
        val application = RuntimeEnvironment.getApplication() as Context
        copyContext = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(temporaryFolder.root, "lyrics-no-backup").apply { mkdirs() }
        }
    }

    @Test(timeout = 30_000L)
    fun rawPrimaryAndTranslationQueriesUseExactStoredUrisWithoutDecodingFilteringOrTruncation() = runBlocking {
        val primary = "content://RAW.example/document/%2F" + "x".repeat(8_300)
        val translation = "content://RAW.example/document/%2f?raw=1#fragment"
        execute("INSERT INTO lyrics_track_preferences (trackId, schemaVersion, revision, userDelayMs, primaryUri, translationUri) " +
            "VALUES (?, 9, X'01', 17, ?, ?)", trackKey.trackId, primary, translation)
        assertEquals(LyricsTrackReadResult.Unavailable(LyricsTrackIssue.CORRUPT_RECORD), store.read(trackKey))
        assertTrue(store.isDocumentUriReferenced(primary))
        assertTrue(store.isDocumentUriReferenced(translation))
        assertFalse(store.isDocumentUriReferenced(primary.take(8_193)))
        assertFalse(store.isDocumentUriReferenced(primary.replace("RAW.example", "raw.example")))
        assertFalse(store.isDocumentUriReferenced(translation.replace("%2f", "%2F")))
        assertFalse(store.isDocumentUriReferenced("content://RAW.example/document/%2f"))
        // Recovery must also retain references whose parent is no longer a supported local read.
        execute("UPDATE local_tracks SET providerId = 'netease' WHERE trackId = ?", trackKey.trackId)
        assertTrue(store.isDocumentUriReferenced(primary))
        assertTrue(store.isDocumentUriReferenced(translation))
        reopen()
        assertTrue(store.isDocumentUriReferenced(primary))
        assertTrue(store.isDocumentUriReferenced(translation))
        assertIntegrity()
    }

    @Test(timeout = 30_000L)
    fun realGatedCommitAndReopenRetainBothAssociatedCopiesAndRecoverOnlyTheOrphanAndStage() = runBlocking {
        val copies = copies()
        val primary = copies.create(candidate, hash(bytes))
        val translation = copies.create(candidate, hash(bytes))
        val orphan = copies.create(candidate, hash(bytes))
        try {
            val gated = ReferenceGatedLyricsTrackStore(store, copies)
            val next = LyricsTrackPreferences(trackKey, primary = association(primary), translation = association(translation), userDelayMs = -100)
            val saved = next.copy(revision = 1L)
            assertEquals(LyricsTrackCommitResult.Saved(saved), gated.commit(0L, next, fence) { true })
            assertEquals(LyricsTrackCommitResult.Conflict(saved), gated.commit(0L, next, fence) { true })
            assertEquals(LyricsTrackCommitResult.Unchanged(saved), gated.commit(1L, saved, fence) { true })
            assertEquals(LyricsTrackCommitResult.Stale(null), gated.commit(1L, saved.copy(userDelayMs = 100), fence) { false })
            primary.release()
            translation.release()
            orphan.release()
            val stage = OwnedLyricsProvider.stagingFile(copyContext, UUID.randomUUID().toString()).apply { writeBytes(byteArrayOf(1)) }
            reopen()
            val queries = ArrayList<String>()
            copies.recover { uri -> queries += uri; store.isDocumentUriReferenced(uri) }
            assertEquals(setOf(primary.uri, translation.uri, orphan.uri), queries.toSet())
            assertArrayEquals(bytes, file(primary).readBytes())
            assertArrayEquals(bytes, file(translation).readBytes())
            assertFalse(file(orphan).exists())
            assertFalse(stage.exists())
            assertEquals(LyricsTrackReadResult.Ready(saved), ReferenceGatedLyricsTrackStore(store, copies).read(trackKey))
            assertIntegrity()
        } finally { primary.release(); translation.release(); orphan.release() }
    }

    @Test(timeout = 30_000L)
    fun unsupportedSchemaAndMalformedRowsKeepTheirActualRawCopyReferencesAcrossRecoveryAndReopen() = runBlocking {
        val copies = copies()
        val primary = copies.create(candidate, hash(bytes))
        val translation = copies.create(candidate, hash(bytes))
        try {
            execute("INSERT INTO lyrics_track_preferences (trackId, schemaVersion, revision, userDelayMs, primaryUri, translationUri) " +
                "VALUES (?, 2, 0, 0, ?, ?)", trackKey.trackId, primary.uri, translation.uri)
            primary.release()
            translation.release()
            assertEquals(LyricsTrackReadResult.Unavailable(LyricsTrackIssue.UNSUPPORTED_SCHEMA), store.read(trackKey))
            copies.recover(store::isDocumentUriReferenced)
            assertArrayEquals(bytes, file(primary).readBytes())
            assertArrayEquals(bytes, file(translation).readBytes())
            execute("UPDATE lyrics_track_preferences SET schemaVersion = 1, revision = X'01'")
            assertEquals(LyricsTrackReadResult.Unavailable(LyricsTrackIssue.CORRUPT_RECORD), store.read(trackKey))
            copies.recover(store::isDocumentUriReferenced)
            reopen()
            assertEquals(LyricsTrackReadResult.Unavailable(LyricsTrackIssue.CORRUPT_RECORD), store.read(trackKey))
            assertTrue(store.isDocumentUriReferenced(primary.uri))
            assertTrue(store.isDocumentUriReferenced(translation.uri))
            copies.recover(store::isDocumentUriReferenced)
            assertArrayEquals(bytes, file(primary).readBytes())
            assertArrayEquals(bytes, file(translation).readBytes())
        } finally { primary.release(); translation.release() }
    }

    @Test(timeout = 30_000L)
    fun aRealReferenceQueryFailurePreservesItsCopyAndRetainsTheGateUntilTheNativeCursorCloses() = runBlocking {
        val closeEntered = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val copies = copies(openDirectory = { root ->
            val directory = Files.newDirectoryStream(root.toPath())
            object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = directory.iterator()
                override fun close() {
                    closeEntered.countDown()
                    check(finishClose.await(10L, TimeUnit.SECONDS))
                    directory.close()
                    closeCount.incrementAndGet()
                }
            }
        })
        val copy = copies.create(candidate, hash(bytes))
        copy.release()
        execute("DROP TABLE lyrics_track_preferences")
        assertEquals(LyricsTrackReadResult.Unavailable(LyricsTrackIssue.IO), store.read(trackKey))
        val collector = async(Dispatchers.Default) { failureFrom { copies.recover(store::isDocumentUriReferenced) } }
        try {
            withContext(Dispatchers.IO) { assertTrue(closeEntered.await(10L, TimeUnit.SECONDS)) }
            assertArrayEquals(bytes, file(copy).readBytes())
            assertFalse(collector.isCompleted)
            expectBusy { copies.withReferenceWrite { error("Failed recovery still owns its directory") } }
            val gated = ReferenceGatedLyricsTrackStore(store, copies)
            assertEquals(LyricsTrackCommitResult.Failed(null, LyricsTrackIssue.BUSY),
                gated.commit(0L, LyricsTrackPreferences(trackKey, userDelayMs = 100), fence) { true })
        } finally { finishClose.countDown() }
        val failure = withTimeout(10_000L) { collector.await() }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is androidx.sqlite.SQLiteException })
        assertArrayEquals(bytes, file(copy).readBytes())
        assertEquals(1, closeCount.get())
        copies.withReferenceWrite { Unit }
    }

    @Test(timeout = 30_000L)
    fun aCollectorBetweenItsRealQueryAndDeleteRejectsAssociationClearAndDelayCommitsThenAllowsRetry() = runBlocking {
        val copies = copies()
        val copy = copies.create(candidate, hash(bytes))
        val gated = ReferenceGatedLyricsTrackStore(store, copies)
        val next = LyricsTrackPreferences(trackKey, primary = association(copy))
        val saved = next.copy(revision = 1L)
        assertEquals(LyricsTrackCommitResult.Saved(saved), gated.commit(0L, next, fence) { true })
        copy.release()
        val queried = CompletableDeferred<Unit>()
        val finishQuery = CompletableDeferred<Unit>()
        val collector = async(Dispatchers.Default) {
            copies.recover { uri ->
                val referenced = store.isDocumentUriReferenced(uri)
                queried.complete(Unit)
                finishQuery.await()
                referenced
            }
        }
        try {
            withTimeout(10_000L) { queried.await() }
            assertEquals(LyricsTrackReadResult.Ready(saved), gated.read(trackKey))
            for (proposal in listOf(saved.copy(primary = null), saved.copy(userDelayMs = 100), saved.copy(translation = association(copy)))) {
                assertEquals(LyricsTrackCommitResult.Failed(null, LyricsTrackIssue.BUSY), gated.commit(1L, proposal, fence) { true })
            }
            assertEquals(1L, scalar("SELECT revision FROM lyrics_track_preferences"))
            assertArrayEquals(bytes, file(copy).readBytes())
        } finally { finishQuery.complete(Unit) }
        withTimeout(10_000L) { collector.await() }
        val cleared = saved.copy(primary = null)
        assertEquals(LyricsTrackCommitResult.Saved(cleared.copy(revision = 2L)), gated.commit(1L, cleared, fence) { true })
        copies.recover(store::isDocumentUriReferenced)
        assertFalse(file(copy).exists())
        val delayed = cleared.copy(revision = 2L, userDelayMs = 100)
        assertEquals(LyricsTrackCommitResult.Saved(delayed.copy(revision = 3L)), gated.commit(2L, delayed, fence) { true })
        assertIntegrity()
    }

    @Test(timeout = 30_000L)
    fun cancelledRealCommitAfterDmlKeepsItsCopyAndGateThroughRollbackThenAllowsOrphanRecovery() = runBlocking {
        val copies = copies()
        val copy = copies.create(candidate, hash(bytes))
        val gated = ReferenceGatedLyricsTrackStore(store, copies)
        val next = LyricsTrackPreferences(trackKey, primary = association(copy))
        val entered = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val caller = async(Dispatchers.IO) {
            try {
                gated.commit(0L, next, fence) {
                    if (calls.incrementAndGet() == 4) {
                        assertTrue(database.hasCurrentWriterTransaction())
                        assertEquals(1L, scalarInside("SELECT revision FROM lyrics_track_preferences"))
                        entered.complete(Unit)
                        withContext(NonCancellable) { finishWrite.await() }
                    }
                    true
                }
            } finally { copy.release() }
        }
        try {
            withTimeout(10_000L) { entered.await() }
            caller.cancel()
            assertFalse(caller.isCompleted)
            expectBusy { copies.recover(store::isDocumentUriReferenced) }
            expectBusy { copies.create(candidate, hash(bytes)) }
            assertEquals(LyricsTrackCommitResult.Failed(null, LyricsTrackIssue.BUSY),
                gated.commit(0L, next.copy(userDelayMs = 100), fence) { true })
            assertArrayEquals(bytes, file(copy).readBytes())
        } finally { finishWrite.complete(Unit) }
        withTimeout(10_000L) { caller.join() }
        assertTrue(caller.isCancelled)
        assertEquals(4, calls.get())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM lyrics_track_preferences"))
        assertFalse(store.isDocumentUriReferenced(copy.uri))
        assertArrayEquals(bytes, file(copy).readBytes())
        copies.recover(store::isDocumentUriReferenced)
        assertFalse(file(copy).exists())
        val delayed = LyricsTrackPreferences(trackKey, userDelayMs = 100)
        assertEquals(LyricsTrackCommitResult.Saved(delayed.copy(revision = 1L)), gated.commit(0L, delayed, fence) { true })
        assertIntegrity()
    }

    @Test(timeout = 30_000L)
    fun cancellationOfARealReferenceCollectorNeverBecomesFalseOrReleasesTheGateBeforeCleanup() = runBlocking {
        val copies = copies()
        val copy = copies.create(candidate, hash(bytes))
        val gated = ReferenceGatedLyricsTrackStore(store, copies)
        val next = LyricsTrackPreferences(trackKey, primary = association(copy))
        assertEquals(LyricsTrackCommitResult.Saved(next.copy(revision = 1L)), gated.commit(0L, next, fence) { true })
        copy.release()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val collector = async(Dispatchers.Default) {
            copies.recover { uri ->
                val referenced = store.isDocumentUriReferenced(uri)
                assertTrue(referenced)
                entered.complete(Unit)
                withContext(NonCancellable) { finish.await() }
                referenced
            }
        }
        try {
            withTimeout(10_000L) { entered.await() }
            collector.cancel()
            assertFalse(collector.isCompleted)
            expectBusy { copies.withReferenceWrite { error("The canceled collector still owns the cursor") } }
            assertArrayEquals(bytes, file(copy).readBytes())
        } finally { finish.complete(Unit) }
        withTimeout(10_000L) { collector.join() }
        assertTrue(collector.isCancelled)
        assertArrayEquals(bytes, file(copy).readBytes())
        assertTrue(store.isDocumentUriReferenced(copy.uri))
        copies.withReferenceWrite { Unit }
        copies.recover(store::isDocumentUriReferenced)
        assertArrayEquals(bytes, file(copy).readBytes())
    }

    @Test(timeout = 30_000L)
    fun onlyGateAcquisitionBusyIsTranslatedAndEveryDelegateFailureOrCancellationPropagatesUnchanged() = runBlocking {
        val copies = copies()
        val failures = listOf(
            SourceReadException(SourceReadIssue.BUSY), SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE),
            CancellationException("Controlled delegate cancellation"), IllegalStateException("Controlled delegate failure"),
        )
        for (failure in failures) {
            val delegate = object : LyricsTrackStore by store {
                override suspend fun commit(expectedRevision: Long, next: LyricsTrackPreferences, fence: LyricsTrackWriteFence,
                    stillCurrent: suspend () -> Boolean): LyricsTrackCommitResult { throw failure }
            }
            val gated = ReferenceGatedLyricsTrackStore(delegate, copies)
            assertEquals(LyricsTrackReadResult.Ready(LyricsTrackPreferences(trackKey)), gated.read(trackKey))
            assertSame(failure, failureFrom { gated.commit(0L, LyricsTrackPreferences(trackKey, userDelayMs = 100), fence) { true } })
            copies.withReferenceWrite { Unit }
        }
    }

    private fun copies(openDirectory: (File) -> DirectoryStream<Path> = { Files.newDirectoryStream(it.toPath()) }) =
        LyricsDocumentCopies(copyContext, LocalProviderIo(), openInput = { _, _ -> ByteArrayInputStream(bytes) },
            freeBytes = { 1_073_741_824L }, syncDirectory = {}, openRecoveryDirectory = openDirectory)

    private fun association(copy: LyricsDocumentCopies.Copy) = LyricsAssociation(
        checkNotNull(OwnedLyricsProvider.token(copyContext, Uri.parse(copy.uri))), "selected-lyrics", copy.uri, 0,
        LyricsEncodingChoice.AUTO_BOM_OR_UTF8, copy.sha256,
    )

    private fun file(copy: LyricsDocumentCopies.Copy): File = OwnedLyricsProvider.finalFile(copyContext,
        checkNotNull(OwnedLyricsProvider.token(copyContext, Uri.parse(copy.uri))))

    private suspend fun expectBusy(action: suspend () -> Any?) {
        try { action(); throw AssertionError("The shared operation gate must reject the competing call") }
        catch (failure: SourceReadException) { assertEquals(SourceReadIssue.BUSY, failure.issue) }
    }

    private suspend fun failureFrom(action: suspend () -> Any?): Exception = try {
        action()
        throw AssertionError("A real query or injected delegate failure is required")
    } catch (failure: Exception) { failure }

    private fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
