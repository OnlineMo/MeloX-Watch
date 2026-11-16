package io.github.onlinemo.meloxwatch.data.playlistsexchange

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.ui.playlists.AuthorizedPlaylistDocument
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Provider/descriptor and token-ownership regression, using the framework Context and process UID.
 * Robolectric 4.16.1 lacks system URI-grant enforcement; real grant/revoke acceptance is device work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [26, 30],
    manifest = Config.NONE,
    shadows = [PlaylistDocumentFixtureOsShadow::class, PlaylistDocumentFixtureParcelShadow::class],
)
class PlaylistDocumentRegistryTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test fun importUsesRealDocumentAndPreservesOriginalBytesAndGrantInventory() = runBlocking {
        val fixture = fixture()
        val grantInventory = fixture.context.contentResolver.persistedUriPermissions.toList()
        val root = PlaylistFolderBinding("authorized-library", "authorized-root")
        val document = fixture.registry.registerImport(fixture.uri, READ, root)
        assertTrue(Process.myUid() >= Process.FIRST_APPLICATION_UID)
        assertTrue(DocumentsContract.isDocumentUri(fixture.context, fixture.uri))
        assertEquals(fixture.provider.displayName, document.label)
        val lease = fixture.registry.borrowImport(document)
        assertEquals(fixture.uri, lease.uri)
        assertEquals(root, lease.rootBinding)
        assertEquals(document.label, lease.label)
        assertEquals(listOf("r", "r"), fixture.provider.openModes.toList())
        assertTrue(fixture.provider.queryCount.get() >= 4)
        lease.close()
        fixture.registry.discard(document.selectionId)
        fixture.registry.discard(document.selectionId)
        assertIssue(PlaylistIssue.VIEW_EXPIRED) { fixture.registry.borrowImport(document) }
        assertEquals(grantInventory, fixture.context.contentResolver.persistedUriPermissions.toList())
        fixture.assertPreservedAndClosed()
    }

    @Test fun exportIsBoundToOperationRoleAndFrozenDtoWithoutTruncatingTarget() = runBlocking {
        val fixture = fixture()
        val document = fixture.registry.registerExport(fixture.uri, READ_WRITE, "export-operation")
        val queryCount = fixture.provider.queryCount.get()
        assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.borrowImport(document) }
        assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.borrowExport(document, "different-operation") }
        assertIssue(PlaylistIssue.INVALID_INPUT) {
            fixture.registry.borrowExport(document.copy(label = "forged-name"), "export-operation")
        }
        assertIssue(PlaylistIssue.VIEW_EXPIRED) {
            fixture.registry.borrowExport(AuthorizedPlaylistDocument("unknown-selection", document.label), "export-operation")
        }
        assertEquals(queryCount, fixture.provider.queryCount.get())
        val lease = fixture.registry.borrowExport(document, "export-operation")
        assertNull(lease.rootBinding)
        assertEquals(listOf("rw", "rw"), fixture.provider.openModes.toList())
        lease.close()
        fixture.registry.discard(document.selectionId)
        fixture.assertPreservedAndClosed()
    }

    @Test fun oldCloseCannotReleaseANewBorrowerOfTheSameToken() = runBlocking {
        val fixture = fixture()
        val document = fixture.registry.registerImport(fixture.uri, READ)
        val old = fixture.registry.borrowImport(document)
        assertIssue(PlaylistIssue.BUSY) { fixture.registry.borrowImport(document) }
        old.close()
        val current = fixture.registry.borrowImport(document)
        old.close()
        assertIssue(PlaylistIssue.BUSY) { fixture.registry.borrowImport(document) }
        current.close()
        fixture.registry.borrowImport(document).close()
        fixture.registry.discard(document.selectionId)
        fixture.assertPreservedAndClosed()
    }

    @Test fun returnedFlagsAndDocumentRoutingCannotBeReplacedByUriText() = runBlocking {
        val fixture = fixture()
        assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.registerImport(fixture.uri, 0) }
        assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.registerImport(fixture.uri, WRITE) }
        for (flags in listOf(0, READ, WRITE)) {
            assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.registerExport(fixture.uri, flags, "export-operation") }
        }
        assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.registerExport(fixture.uri, READ_WRITE, " ") }
        assertEquals(0, fixture.provider.queryCount.get())
        val invalid = listOf(Uri.fromFile(fixture.file), Uri.parse("https://invalid.example/playlist.m3u8"),
            Uri.parse("content://${fixture.uri.authority}/audio"),
            DocumentsContract.buildTreeDocumentUri(requireNotNull(fixture.uri.authority), "root"))
        for (uri in invalid) assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.registerImport(uri, READ) }
        assertEquals(0, fixture.provider.queryCount.get())
        assertEquals(0, fixture.provider.childQueryCount.get())
        fixture.registry.registerImport(fixture.uri, READ).also { fixture.registry.discard(it.selectionId) }
        fixture.assertPreservedAndClosed()
    }

    @Test fun malformedOrDirectoryMetadataClosesCursorsAndDoesNotConsumeSlots() = runBlocking {
        val fixture = fixture()
        val provider = fixture.provider
        provider.mimeType = DocumentsContract.Document.MIME_TYPE_DIR
        assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.registerImport(fixture.uri, READ) }
        provider.mimeType = "audio/x-mpegurl"
        provider.returnedDocumentId = "different-document"
        assertIssue(PlaylistIssue.SOURCE_CHANGED) { fixture.registry.registerImport(fixture.uri, READ) }
        provider.returnedDocumentId = FixturePlaylistDocumentProvider.DOCUMENT_ID
        provider.displayName = "hidden\u202ename"
        assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.registerImport(fixture.uri, READ) }
        provider.displayName = "fixture.m3u8"
        provider.documentFlags = null
        assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.registerImport(fixture.uri, READ) }
        provider.documentFlags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        provider.missingColumn = DocumentsContract.Document.COLUMN_DOCUMENT_ID
        assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.registerImport(fixture.uri, READ) }
        provider.missingColumn = null
        provider.extraRow = true
        assertIssue(PlaylistIssue.INVALID_INPUT) { fixture.registry.registerImport(fixture.uri, READ) }
        provider.extraRow = false
        provider.empty = true
        assertIssue(PlaylistIssue.SOURCE_UNAVAILABLE) { fixture.registry.registerImport(fixture.uri, READ) }
        provider.empty = false
        assertTrue(provider.openModes.isEmpty())
        val documents = List(4) { fixture.registry.registerImport(fixture.uri, READ) }
        assertEquals(4, documents.map { it.selectionId }.toSet().size)
        assertIssue(PlaylistIssue.BUSY) { fixture.registry.registerImport(fixture.uri, READ) }
        documents.forEach { fixture.registry.discard(it.selectionId) }
        fixture.assertPreservedAndClosed()
    }

    @Test fun actualProviderNameAndMimeAreRecheckedBeforeBorrow() = runBlocking {
        val fixture = fixture()
        val document = fixture.registry.registerImport(fixture.uri, READ)
        val openCount = fixture.provider.openModes.size
        fixture.provider.displayName = "renamed.m3u8"
        assertIssue(PlaylistIssue.SOURCE_CHANGED) { fixture.registry.borrowImport(document) }
        fixture.provider.displayName = document.label
        fixture.provider.mimeType = DocumentsContract.Document.MIME_TYPE_DIR
        assertIssue(PlaylistIssue.SOURCE_CHANGED) { fixture.registry.borrowImport(document) }
        fixture.provider.mimeType = "audio/x-mpegurl"
        assertEquals(openCount, fixture.provider.openModes.size)
        fixture.registry.borrowImport(document).close()
        fixture.registry.discard(document.selectionId)
        fixture.assertPreservedAndClosed()
    }

    @Test fun metadataChangesDuringProbeRejectRegistrationAfterClosingDescriptor() = runBlocking {
        val fixture = fixture()
        fixture.provider.beforeOpen = { fixture.provider.displayName = "changed-during-open.m3u8" }
        assertIssue(PlaylistIssue.SOURCE_CHANGED) { fixture.registry.registerImport(fixture.uri, READ) }
        assertTrue(fixture.provider.queryCount.get() >= 2)
        assertTrue(fixture.provider.allDescriptorsClosed)
        fixture.provider.beforeOpen = null
        val fresh = fixture.registry.registerImport(fixture.uri, READ)
        assertEquals("changed-during-open.m3u8", fresh.label)
        fixture.registry.discard(fresh.selectionId)
        fixture.assertPreservedAndClosed()
    }

    @Test fun writeFlagIsInsufficientWhenTheProviderActuallyRejectsRw() = runBlocking {
        val fixture = fixture()
        fixture.provider.writable = false
        assertIssue(PlaylistIssue.SOURCE_UNAVAILABLE) { fixture.registry.registerExport(fixture.uri, READ_WRITE, "export-operation") }
        assertEquals(listOf("rw"), fixture.provider.openModes.toList())
        fixture.provider.writable = true
        val document = fixture.registry.registerExport(fixture.uri, READ_WRITE, "export-operation")
        fixture.provider.documentFlags = 0
        assertIssue(PlaylistIssue.SOURCE_UNAVAILABLE) { fixture.registry.borrowExport(document, "export-operation") }
        fixture.provider.documentFlags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        fixture.provider.writable = false
        assertIssue(PlaylistIssue.SOURCE_UNAVAILABLE) { fixture.registry.borrowExport(document, "export-operation") }
        fixture.provider.writable = true
        fixture.registry.borrowExport(document, "export-operation").close()
        fixture.registry.discard(document.selectionId)
        fixture.assertPreservedAndClosed()
    }

    @Test fun providerErrorsAndCancellationDoNotExposeMessagesCausesOrUris() = runBlocking {
        val fixture = fixture()
        val secret = "private-provider-credential-marker"
        fixture.provider.queryFailure = SecurityException("$secret ${fixture.uri}", IOException(secret))
        val denied = assertIssue(PlaylistIssue.SOURCE_UNAVAILABLE) { fixture.registry.registerImport(fixture.uri, READ) }
        assertSanitized(denied, secret, fixture.uri)
        fixture.provider.queryFailure = null
        fixture.provider.openFailure = IOException("$secret ${fixture.uri}", IllegalStateException(secret))
        val unavailable = assertIssue(PlaylistIssue.SOURCE_UNAVAILABLE) { fixture.registry.registerImport(fixture.uri, READ) }
        assertSanitized(unavailable, secret, fixture.uri)
        fixture.provider.openFailure = null
        fixture.provider.queryFailure = CancellationException("$secret ${fixture.uri}")
        try {
            fixture.registry.registerImport(fixture.uri, READ)
            throw AssertionError("Expected cancellation")
        } catch (error: CancellationException) {
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertFalse(error.message.orEmpty().contains(secret))
            assertFalse(error.message.orEmpty().contains(fixture.uri.toString()))
        }
        fixture.provider.queryFailure = null
        fixture.registry.registerImport(fixture.uri, READ).also { fixture.registry.discard(it.selectionId) }
        fixture.assertPreservedAndClosed()
    }

    @Test fun discardedActiveLeaseRetainsCapacityUntilOwnerFinishesItsIo() = runBlocking {
        val fixture = fixture()
        val documents = List(4) { fixture.registry.registerImport(fixture.uri, READ) }
        val document = documents.first()
        val lease = fixture.registry.borrowImport(document)
        fixture.registry.discard(document.selectionId)
        assertIssue(PlaylistIssue.VIEW_EXPIRED) { fixture.registry.borrowImport(document) }
        assertIssue(PlaylistIssue.BUSY) { fixture.registry.registerImport(fixture.uri, READ) }
        // An already delivered owner can finish and close its actual stream after UI discard.
        val bytes = fixture.io.read { signal ->
            val descriptor = requireNotNull(fixture.context.contentResolver.openFileDescriptor(lease.uri, "r", signal))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        assertTrue(bytes.contentEquals(fixture.original))
        lease.close()
        val replacement = fixture.registry.registerImport(fixture.uri, READ)
        assertNotEquals(document.selectionId, replacement.selectionId)
        lease.close()
        fixture.registry.borrowImport(replacement).close()
        (documents.drop(1) + replacement).forEach { fixture.registry.discard(it.selectionId) }
        fixture.assertPreservedAndClosed()
    }

    @Test fun cancelledRegistrationCannotFreeCapacityBeforeIgnoringCursorCloses() = runBlocking {
        withTimeout(10_000) {
            val fixture = fixture()
            val retained = List(3) { fixture.registry.registerImport(fixture.uri, READ) }
            val closedBefore = fixture.provider.cursorCloseCount.get()
            val entered = CountDownLatch(1)
            val returnQuery = CountDownLatch(1)
            val closing = CountDownLatch(1)
            val closeCursor = CountDownLatch(1)
            fixture.provider.beforeQuery = { entered.countDown(); check(returnQuery.await(5, TimeUnit.SECONDS)) }
            fixture.provider.beforeCursorClose = { closing.countDown(); check(closeCursor.await(5, TimeUnit.SECONDS)) }
            val caller = async(Dispatchers.Default) { fixture.registry.registerImport(fixture.uri, READ) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                caller.cancelAndJoin()
                assertIssue(PlaylistIssue.BUSY) { fixture.registry.registerImport(fixture.uri, READ) }
                returnQuery.countDown()
                assertTrue(closing.await(5, TimeUnit.SECONDS))
                assertEquals(closedBefore, fixture.provider.cursorCloseCount.get())
                assertIssue(PlaylistIssue.BUSY) { fixture.registry.registerImport(fixture.uri, READ) }
                fixture.provider.beforeQuery = null
                fixture.provider.beforeCursorClose = null
                closeCursor.countDown()
                fixture.io.awaitIdle()
                val replacement = fixture.registry.registerImport(fixture.uri, READ)
                (retained + replacement).forEach { fixture.registry.discard(it.selectionId) }
                fixture.assertPreservedAndClosed()
            } finally {
                returnQuery.countDown()
                closeCursor.countDown()
                caller.cancel()
                withContext(NonCancellable) { withTimeout(5_000) { fixture.io.awaitIdle() } }
            }
        }
    }

    @Test fun discardDuringBorrowValidationRejectsDeliveryButWaitsForPhysicalCleanup() = runBlocking {
        withTimeout(10_000) {
            val fixture = fixture()
            val documents = List(4) { fixture.registry.registerImport(fixture.uri, READ) }
            val document = documents.first()
            val opensBefore = fixture.provider.openModes.size
            val entered = CountDownLatch(1)
            val returnQuery = CountDownLatch(1)
            val closing = CountDownLatch(1)
            val closeCursor = CountDownLatch(1)
            fixture.provider.beforeQuery = { entered.countDown(); check(returnQuery.await(5, TimeUnit.SECONDS)) }
            fixture.provider.beforeCursorClose = { closing.countDown(); check(closeCursor.await(5, TimeUnit.SECONDS)) }
            val caller = async(Dispatchers.Default) {
                assertIssue(PlaylistIssue.VIEW_EXPIRED) { fixture.registry.borrowImport(document) }.issue
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                fixture.registry.discard(document.selectionId)
                assertIssue(PlaylistIssue.VIEW_EXPIRED) { fixture.registry.borrowImport(document) }
                assertIssue(PlaylistIssue.BUSY) { fixture.registry.registerImport(fixture.uri, READ) }
                returnQuery.countDown()
                assertTrue(closing.await(5, TimeUnit.SECONDS))
                assertIssue(PlaylistIssue.BUSY) { fixture.registry.registerImport(fixture.uri, READ) }
                fixture.provider.beforeQuery = null
                fixture.provider.beforeCursorClose = null
                closeCursor.countDown()
                assertEquals(PlaylistIssue.VIEW_EXPIRED, caller.await())
                assertEquals(opensBefore, fixture.provider.openModes.size)
                fixture.io.awaitIdle()
                val replacement = fixture.registry.registerImport(fixture.uri, READ)
                (documents.drop(1) + replacement).forEach { fixture.registry.discard(it.selectionId) }
                fixture.assertPreservedAndClosed()
            } finally {
                returnQuery.countDown()
                closeCursor.countDown()
                caller.cancel()
                withContext(NonCancellable) { withTimeout(5_000) { fixture.io.awaitIdle() } }
            }
        }
    }

    @Test fun sharedChannelBusyDoesNotInvalidateTokenOrLeakARegistrationSlot() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val fixture = fixture(LocalProviderIo(dispatcher))
        suspend fun register(): AuthorizedPlaylistDocument {
            val pending = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                fixture.registry.registerImport(fixture.uri, READ)
            }
            dispatcher.runNext()
            return pending.await()
        }
        val documents = List(3) { register() }
        val occupying = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { fixture.io.read { "unrelated" } }
        repeat(4) {
            assertIssue(PlaylistIssue.BUSY) { fixture.registry.registerImport(fixture.uri, READ) }
            assertIssue(PlaylistIssue.BUSY) { fixture.registry.borrowImport(documents.first()) }
        }
        dispatcher.runNext()
        assertEquals("unrelated", occupying.await())
        val replacement = register()
        val borrowing: Deferred<DocumentLease> = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            fixture.registry.borrowImport(documents.first())
        }
        dispatcher.runNext()
        borrowing.await().close()
        (documents + replacement).forEach { fixture.registry.discard(it.selectionId) }
        assertTrue(dispatcher.empty)
        fixture.assertPreservedAndClosed()
    }

    @Test fun cancelledQueuedBorrowRetiresAfterThePhysicalWorkerAcknowledgesCancellation() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val fixture = fixture(LocalProviderIo(dispatcher))
        val registering = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            fixture.registry.registerImport(fixture.uri, READ)
        }
        dispatcher.runNext()
        val document = registering.await()
        val before = fixture.provider.queryCount.get()
        val cancelled = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            fixture.registry.borrowImport(document)
        }
        cancelled.cancelAndJoin()
        assertIssue(PlaylistIssue.BUSY) { fixture.registry.borrowImport(document) }
        assertEquals(before, fixture.provider.queryCount.get())
        dispatcher.runNext()
        val fresh = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            fixture.registry.borrowImport(document)
        }
        dispatcher.runNext()
        fresh.await().close()
        fixture.registry.discard(document.selectionId)
        assertTrue(dispatcher.empty)
        fixture.assertPreservedAndClosed()
    }

    private fun fixture(io: LocalProviderIo = LocalProviderIo()): Fixture {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = temporary.newFile("playlist.m3u8").also { it.writeText("#EXTM3U\nrelative/song.mp3\n") }
        val authority = "${context.packageName}.playlist-registry-fixture"
        val provider = fixturePlaylistDocumentProvider(context, authority, file)
        val uri = DocumentsContract.buildDocumentUri(authority, FixturePlaylistDocumentProvider.DOCUMENT_ID)
        return Fixture(context, file, file.readBytes(), provider, uri, io, PlaylistDocumentRegistry(context, io))
    }

    private data class Fixture(
        val context: Context,
        val file: File,
        val original: ByteArray,
        val provider: FixturePlaylistDocumentProvider,
        val uri: Uri,
        val io: LocalProviderIo,
        val registry: PlaylistDocumentRegistry,
    ) {
        fun assertPreservedAndClosed() {
            assertTrue(file.isFile)
            assertTrue(file.readBytes().contentEquals(original))
            assertEquals(0, provider.deleteCount.get())
            assertEquals(provider.cursorCount.get(), provider.cursorCloseCount.get())
            assertTrue(provider.allDescriptorsClosed)
        }
    }

    private suspend fun assertIssue(issue: PlaylistIssue, action: suspend () -> Any?): PlaylistException {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: PlaylistException) { assertEquals(issue, error.issue); return error }
    }

    private fun assertSanitized(error: PlaylistException, secret: String, uri: Uri) {
        val text = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        assertEquals(error.issue.name, error.message)
        assertFalse(text.contains(secret))
        assertFalse(text.contains(uri.toString()))
        assertTrue(error.suppressed.isEmpty())
        assertNull(error.cause?.cause)
        assertTrue(error.cause?.suppressed?.isEmpty() == true)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        val empty: Boolean get() = work.isEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runNext() { check(work.isNotEmpty()); work.removeFirst().run() }
    }

    private companion object {
        const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val READ_WRITE = READ or WRITE
    }
}
