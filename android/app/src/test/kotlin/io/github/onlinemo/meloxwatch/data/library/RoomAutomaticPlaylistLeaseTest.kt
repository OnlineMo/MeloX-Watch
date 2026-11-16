package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOperationResult
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.ui.playlists.FolderPlaylistCreateRequest
import io.github.onlinemo.meloxwatch.ui.playlists.KeywordPlaylistCreateRequest
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistFolderChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Actual Room lease/receipt failures; definitions do not establish cloud or device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomAutomaticPlaylistLeaseTest : PlaylistRecoveryRoomFixture() {
    @Test(timeout = 30_000L)
    fun keywordCloseFailureRetainsTheOnlyLeaseAndTheSamePreviewIdCanRetryCleanup() = runBlocking {
        val keyword = RoomKeywordPlaylistController(database, playlists, library, { elapsed })
        val preview = keyword.preview("Same")
        assertEquals(2L, preview.memberCount)
        val original = database.libraryDao().views().single().viewId
        rejectLibraryClose()
        expectIssue(PlaylistIssue.INVALID_INPUT) { keyword.closePreview(preview.previewId) }
        expectIssue(PlaylistIssue.VIEW_EXPIRED) { keyword.create(keywordRequest(preview.previewId)) }
        expectIssue(PlaylistIssue.INVALID_INPUT) { keyword.preview(".mp3") }
        assertEquals(listOf(original), database.libraryDao().views().map { it.viewId })
        allowLibraryClose()
        keyword.closePreview(preview.previewId)
        keyword.closePreview(preview.previewId)
        assertEquals(0, database.libraryDao().views().size)
        val next = keyword.preview(".mp3")
        assertNotEquals(preview.previewId, next.previewId)
        assertEquals(1, database.libraryDao().views().size)
        keyword.closePreview(next.previewId)
        assertNull(database.playlistDao().receipt("keyword-create"))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun folderCloseFailureInvalidatesThePreviewWithoutLosingItsRetryableLease() = runBlocking {
        val folder = RoomFolderPlaylistController(database, playlists, library, { elapsed })
        val choice = folderChoice()
        val preview = folder.preview(choice)
        assertEquals("Music", preview.choice.label)
        assertEquals(2L, preview.memberCount)
        val original = database.libraryDao().views().single().viewId
        rejectLibraryClose()
        expectIssue(PlaylistIssue.INVALID_INPUT) { folder.closePreview(preview.previewId) }
        expectIssue(PlaylistIssue.VIEW_EXPIRED) { folder.create(folderRequest(preview.previewId)) }
        expectIssue(PlaylistIssue.INVALID_INPUT) { folder.preview(choice) }
        assertEquals(listOf(original), database.libraryDao().views().map { it.viewId })
        allowLibraryClose()
        folder.closePreview(preview.previewId)
        folder.closePreview(preview.previewId)
        val next = folder.preview(choice)
        assertNotEquals(preview.previewId, next.previewId)
        assertEquals(1, database.libraryDao().views().size)
        folder.closePreview(next.previewId)
        assertNull(database.playlistDao().receipt("folder-create"))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun anUnacceptedKeywordPreviewKeepsItsFailedCleanupAndCannotAcquireASecondView() = runBlocking {
        val keyword = RoomKeywordPlaylistController(database, playlists, library, { elapsed })
        corruptNewPreview()
        rejectLibraryClose()
        expectIssue(PlaylistIssue.INVALID_INPUT) { keyword.preview("Same") }
        val original = database.libraryDao().views().single().viewId
        stopCorruptingNewPreview()
        expectIssue(PlaylistIssue.INVALID_INPUT) { keyword.preview(".mp3") }
        assertEquals(listOf(original), database.libraryDao().views().map { it.viewId })
        allowLibraryClose()
        val accepted = keyword.preview(".mp3")
        assertEquals(2L, accepted.memberCount)
        assertNotEquals(original, database.libraryDao().views().single().viewId)
        keyword.closePreview(accepted.previewId)
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun anUnacceptedFolderPreviewRetainsItsTokenUntilCleanupSucceedsBeforeTheNextAcquisition() = runBlocking {
        val folder = RoomFolderPlaylistController(database, playlists, library, { elapsed })
        val choice = folderChoice()
        corruptNewPreview()
        rejectLibraryClose()
        expectIssue(PlaylistIssue.INVALID_INPUT) { folder.preview(choice) }
        val original = database.libraryDao().views().single().viewId
        stopCorruptingNewPreview()
        expectIssue(PlaylistIssue.INVALID_INPUT) { folder.preview(choice) }
        assertEquals(listOf(original), database.libraryDao().views().map { it.viewId })
        allowLibraryClose()
        val accepted = folder.preview(choice)
        assertEquals(2L, accepted.memberCount)
        assertNotEquals(original, database.libraryDao().views().single().viewId)
        folder.closePreview(accepted.previewId)
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun aSuccessfulKeywordReceiptSurvivesCleanupFailureAndReplaysWithoutRevalidatingTheChangedLibrary() = runBlocking {
        val keyword = RoomKeywordPlaylistController(database, playlists, library, { elapsed })
        val preview = keyword.preview("Same")
        val request = keywordRequest(preview.previewId)
        val original = database.libraryDao().views().single().viewId
        rejectLibraryClose()
        val accepted = keyword.create(request)
        assertEquals(PlaylistOperationResult.CREATED, accepted.result)
        assertFalse(accepted.wasReplay)
        assertEquals(2L, accepted.snapshot?.memberCount)
        val receipt = checkNotNull(database.playlistDao().receipt(request.operationId))
        assertEquals(listOf(original), database.libraryDao().views().map { it.viewId })
        library.setShortAudioFilter(library.rules().revision, true)
        val changedLibrary = library.overview()
        expectIssue(PlaylistIssue.INVALID_INPUT) { keyword.preview(".mp3") }
        val replay = keyword.create(request)
        assertTrue(replay.wasReplay)
        assertEquals(accepted.snapshot, replay.snapshot)
        assertEquals(receipt, database.playlistDao().receipt(request.operationId))
        assertEquals(changedLibrary, library.overview())
        expectIssue(PlaylistIssue.INVALID_INPUT) { keyword.create(request.copy(name = "Different accepted intent")) }
        allowLibraryClose()
        keyword.closePreview(preview.previewId)
        assertEquals(0, database.libraryDao().views().size)
        assertTrue(keyword.create(request).wasReplay)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM queue_generations"))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun aSuccessfulFolderReceiptRemainsSuccessfulAfterLeaseCleanupFailureAndSourceRevocation() = runBlocking {
        val folder = RoomFolderPlaylistController(database, playlists, library, { elapsed })
        val choice = folderChoice()
        val preview = folder.preview(choice)
        val request = folderRequest(preview.previewId)
        val original = database.libraryDao().views().single().viewId
        rejectLibraryClose()
        val accepted = folder.create(request)
        assertEquals(PlaylistOperationResult.CREATED, accepted.result)
        assertFalse(accepted.wasReplay)
        assertEquals(2L, accepted.snapshot?.memberCount)
        val receipt = checkNotNull(database.playlistDao().receipt(request.operationId))
        val currentSource = checkNotNull(library.source(source.spec.sourceId))
        library.setSourceIssue(currentSource.spec.sourceId, currentSource.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
        expectIssue(PlaylistIssue.INVALID_INPUT) { folder.preview(choice) }
        val replay = folder.create(request)
        assertTrue(replay.wasReplay)
        assertEquals(accepted.snapshot, replay.snapshot)
        assertEquals(receipt, database.playlistDao().receipt(request.operationId))
        assertEquals(listOf(original), database.libraryDao().views().map { it.viewId })
        expectIssue(PlaylistIssue.INVALID_INPUT) { folder.create(request.copy(name = "Different accepted intent")) }
        allowLibraryClose()
        folder.closePreview(preview.previewId)
        assertEquals(0, database.libraryDao().views().size)
        assertTrue(folder.create(request).wasReplay)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM queue_generations"))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun failedTemporaryOverlapCleanupKeepsItsOwnerAndBlocksANewIndexViewUntilRetry() = runBlocking {
        val folder = RoomFolderPlaylistController(database, playlists, library, { elapsed })
        val choice = folderChoice()
        val first = folder.preview(choice)
        val base = checkNotNull(folder.create(folderRequest(first.previewId)).snapshot)
        execute("""CREATE TRIGGER reject_automatic_overlap_close BEFORE DELETE ON playlist_views BEGIN
            SELECT RAISE(ABORT, 'fixture overlap close failure'); END""")
        expectIssue(PlaylistIssue.RECOVERY_REQUIRED) { folder.preview(choice, base) }
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_views"))
        val original = database.withPreservedTransaction {
            database.openHelper.writableDatabase.query("SELECT viewId FROM playlist_views").use {
                assertTrue(it.moveToFirst())
                it.getString(0)
            }
        }
        expectIssue(PlaylistIssue.RECOVERY_REQUIRED) { folder.preview(choice, base) }
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_views WHERE viewId = '$original'"))
        execute("DROP TRIGGER reject_automatic_overlap_close")
        val accepted = folder.preview(choice, base)
        assertEquals(2L, accepted.memberCount)
        assertEquals(0L, accepted.addedCount)
        assertEquals(0L, accepted.removedCount)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_views"))
        assertEquals(1, database.libraryDao().views().size)
        folder.closePreview(accepted.previewId)
        assertEquals(base, playlists.snapshot(base.playlistId))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun cancellationDuringRealKeywordCopyPropagatesAndKeepsFailedCleanupForAnExplicitCloseRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val real = playlists
        val gated = object : LocalPlaylistRepository by real {
            override suspend fun apply(request: PlaylistMutationRequest, source: PlaylistPageSource?): PlaylistCommit =
                real.apply(request, source?.let { input -> PlaylistPageSource { offset, limit ->
                    assertFalse(database.inTransaction())
                    entered.complete(Unit)
                    release.await()
                    input.read(offset, limit)
                } })
        }
        val keyword = RoomKeywordPlaylistController(database, gated, library, { elapsed })
        val preview = keyword.preview("Same")
        val request = keywordRequest(preview.previewId)
        val original = database.libraryDao().views().single().viewId
        rejectLibraryClose()
        var cancellation: CancellationException? = null
        val job = launch {
            try { keyword.create(request) }
            catch (error: CancellationException) { cancellation = error; throw error }
        }
        try {
            withTimeout(10_000L) { entered.await() }
            expectIssue(PlaylistIssue.BUSY) { keyword.preview(".mp3") }
            withTimeout(10_000L) { job.cancelAndJoin() }
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { job.cancelAndJoin() }
        }
        assertTrue(checkNotNull(cancellation).suppressed.any { it is LibraryException && it.issue == LibraryIssue.INVALID_INPUT })
        assertNull(database.playlistDao().receipt(request.operationId))
        assertNull(playlists.snapshot(request.playlistId))
        assertEquals(listOf(original), database.libraryDao().views().map { it.viewId })
        expectIssue(PlaylistIssue.INVALID_INPUT) { keyword.preview(".mp3") }
        assertEquals(1, database.libraryDao().views().size)
        allowLibraryClose()
        keyword.closePreview(preview.previewId)
        assertEquals(0, database.libraryDao().views().size)
        expectIssue(PlaylistIssue.VIEW_EXPIRED) { keyword.create(request) }
        val next = keyword.preview(".mp3")
        keyword.closePreview(next.previewId)
        integrity()
    }

    private suspend fun folderChoice(): PlaylistFolderChoice {
        val root = checkNotNull(database.libraryDao().node(source.spec.sourceId, "root-object"))
        return PlaylistFolderChoice(PlaylistFolderBinding(source.spec.sourceId, root.nodeId), "caller supplied label")
    }

    private fun keywordRequest(previewId: String) = KeywordPlaylistCreateRequest(
        "keyword-create", "keyword-created-list", "Keyword list", previewId, broadMatchConfirmed = true, emptyMatchConfirmed = false)

    private fun folderRequest(previewId: String) = FolderPlaylistCreateRequest(
        "folder-create", "folder-created-list", "Folder list", previewId)

    private suspend fun rejectLibraryClose() = execute("""CREATE TRIGGER reject_automatic_library_close BEFORE DELETE ON library_views BEGIN
        SELECT RAISE(ABORT, 'fixture library close failure'); END""")

    private suspend fun allowLibraryClose() = execute("DROP TRIGGER reject_automatic_library_close")

    private suspend fun corruptNewPreview() = execute("""CREATE TRIGGER corrupt_automatic_preview AFTER INSERT ON library_views BEGIN
        UPDATE library_views SET fingerprint = 'fixture-invalid-fingerprint' WHERE viewId = NEW.viewId; END""")

    private suspend fun stopCorruptingNewPreview() = execute("DROP TRIGGER corrupt_automatic_preview")

    private suspend fun execute(sql: String) = database.withPreservedTransaction {
        database.openHelper.writableDatabase.execSQL(sql)
    }
}
