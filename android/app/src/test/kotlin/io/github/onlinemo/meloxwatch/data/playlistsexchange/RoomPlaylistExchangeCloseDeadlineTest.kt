package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportConfirmation
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportDestination
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferEncoding
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real Room transaction contention; no detached cleanup or synthetic successful receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistExchangeCloseDeadlineTest : RoomPlaylistExchangeFixture() {
    @Test fun queuedCloseRetainsOnePreviewAndLaterRetryReleasesTheSameLease() = runBlocking {
        withTimeout(15_000L) {
            exchange = exchangeController(cleanupTimeoutMs = 100L)
            val document = input(knownM3u())
            val original = documentFile.readBytes()
            val preview = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
            val token = database.libraryDao().views().single().viewId
            val opens = provider.openModes.size
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blocker = launch(Dispatchers.Default) {
                database.withPreservedTransaction { entered.complete(Unit); release.await() }
            }
            try {
                entered.await()
                withTimeout(2_000L) { expectIssue(PlaylistIssue.BUSY) { exchange.closePreview(preview.previewId) } }
                repeat(2) {
                    withTimeout(2_000L) {
                        expectIssue(PlaylistIssue.BUSY) {
                            exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
                        }
                    }
                }
                assertFalse(blocker.isCompleted)
                assertEquals(opens, provider.openModes.size)
                assertNoPrivateStages()
            } finally {
                release.complete(Unit)
                blocker.join()
            }
            // A transaction barrier exposes any wrongly detached queued deletion before assertions.
            database.withPreservedTransaction { }
            assertEquals(listOf(token), database.libraryDao().views().map { it.viewId })
            assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_exchange_previews"))
            assertEquals(2L, scalar("SELECT COUNT(*) FROM playlist_exchange_rows"))
            assertEquals(0L, playlists.overview().playlistCount)
            expectIssue(PlaylistIssue.VIEW_EXPIRED) { exchange.importPage(preview.previewId, 0L) }
            exchange.closePreview(preview.previewId)
            assertEquals(0L, scalar("SELECT COUNT(*) FROM library_views"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_exchange_previews"))
            val next = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
            assertEquals(1L, scalar("SELECT COUNT(*) FROM library_views"))
            assertEquals(2L, next.memberCount)
            exchange.closePreview(next.previewId)
            assertTrue(documentFile.readBytes().contentEquals(original))
            registry.discard(document.selectionId)
            assertProviderClosed()
            assertNoPrivateStages()
            integrity()
        }
    }

    @Test fun acceptedImportSurvivesQueuedCleanupAndExactReplayCannotCreateAnotherPlaylist() = runBlocking {
        withTimeout(15_000L) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var blocker: Job? = null
            var blockOnce = true
            val owner = this
            val port = object : LocalPlaylistRepository by playlists {
                override suspend fun apply(request: PlaylistMutationRequest, source: PlaylistPageSource?): PlaylistCommit {
                    val committed = playlists.apply(request, source)
                    if (!committed.wasReplay && blockOnce) {
                        blockOnce = false
                        blocker = owner.launch(Dispatchers.Default) {
                            database.withPreservedTransaction { entered.complete(Unit); release.await() }
                        }
                        entered.await()
                    }
                    return committed
                }
            }
            exchange = exchangeController(port, cleanupTimeoutMs = 100L)
            val document = input(knownM3u())
            val original = documentFile.readBytes()
            val preview = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
            val token = database.libraryDao().views().single().viewId
            val opens = provider.openModes.size
            val request = PlaylistImportConfirmation("accepted-queued-import", preview.previewId,
                PlaylistImportDestination.New("accepted-queued-list", "Accepted list"), false)
            val caller = async(Dispatchers.Default) { exchange.confirmImport(request) }
            val accepted: PlaylistCommit
            try {
                entered.await()
                accepted = withTimeout(2_000L) { caller.await() }
                assertFalse(accepted.wasReplay)
                assertFalse(checkNotNull(blocker).isCompleted)
                assertEquals(opens, provider.openModes.size)
            } finally {
                release.complete(Unit)
                blocker?.join()
            }
            database.withPreservedTransaction { }
            assertEquals(listOf(token), database.libraryDao().views().map { it.viewId })
            assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_exchange_previews"))
            assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
            assertEquals(1L, playlists.overview().playlistCount)
            val replay = exchange.confirmImport(request)
            assertTrue(replay.wasReplay)
            assertEquals(accepted.appliedRevision, replay.appliedRevision)
            assertEquals(1L, playlists.overview().playlistCount)
            assertEquals(0L, scalar("SELECT COUNT(*) FROM library_views"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_exchange_previews"))
            assertEquals(listOf(replacement, sameNamedDecoy), members(checkNotNull(replay.snapshot)).map { it.reference.trackKey })
            assertEquals(opens, provider.openModes.size)
            assertTrue(documentFile.readBytes().contentEquals(original))
            registry.discard(document.selectionId)
            assertProviderClosed()
            assertNoPrivateStages()
            integrity()
        }
    }
}
