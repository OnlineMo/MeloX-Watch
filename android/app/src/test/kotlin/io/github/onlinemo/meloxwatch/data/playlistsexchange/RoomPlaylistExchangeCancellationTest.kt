package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferEncoding
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferFormat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistExchangeCancellationTest : RoomPlaylistExchangeFixture() {
    @Test fun cancelledImportReturnsWhileThePhysicalProviderAndAllBorrowedResourcesRemainOwned() = runBlocking {
        withTimeout(15_000L) {
            exchange = exchangeController(cleanupTimeoutMs = 100L)
            val document = input(knownM3u())
            val original = documentFile.readBytes()
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            provider.beforeOpen = { if (provider.openModes.size == 3) blockPhysical(entered, release) }
            val caller = async(Dispatchers.Default) {
                exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
            }
            try {
                entered.await()
                withTimeout(2_000L) { caller.cancelAndJoin() }
                assertEquals(1L, scalar("SELECT COUNT(*) FROM library_views"))
                assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_exchange_previews"))
                assertEquals(1, privateFileCount())
                repeat(2) {
                    expectIssue(PlaylistIssue.BUSY) {
                        exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
                    }
                    assertEquals(1, privateFileCount())
                    assertEquals(1L, scalar("SELECT COUNT(*) FROM library_views"))
                }
                expectIssue(PlaylistIssue.BUSY) { registry.borrowImport(document) }
                assertEquals(3, provider.openModes.size)
                assertEquals(0L, playlists.overview().playlistCount)
            } finally { release.countDown() }
            withTimeout(2_000L) { providerIo.awaitIdle() }
            provider.beforeOpen = null
            exchange.closePreview("cancelled-import-cleanup")
            assertEquals(0L, scalar("SELECT COUNT(*) FROM library_views"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_exchange_previews"))
            assertTrue(documentFile.readBytes().contentEquals(original))
            registry.discard(document.selectionId)
            assertProviderClosed()
            assertNoPrivateStages()
            integrity()
        }
    }

    @Test fun cancelledVerificationKeepsOnePendingExportAndLateCleanupClearsItsOwnBytesWithoutRewriting() = runBlocking {
        withTimeout(15_000L) {
            exchange = exchangeController(cleanupTimeoutMs = 100L)
            val original = createOrdinary()
            val view = playlists.openView(original.playlistId)
            val document = output("cancelled-export")
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            provider.beforeOpen = { if (provider.openModes.size == 4) blockPhysical(entered, release) }
            val caller = async(Dispatchers.Default) {
                exchange.export("cancelled-export", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            }
            try {
                entered.await()
                assertTrue(documentFile.length() > 0L)
                withTimeout(2_000L) { caller.cancelAndJoin() }
                assertEquals(1, privateFileCount())
                assertEquals(1L, scalar("SELECT COUNT(*) FROM library_views"))
                assertEquals(2L, scalar("SELECT COUNT(*) FROM playlist_views"))
                assertNull(database.playlistExchangeDao().exportReceipt("cancelled-export"))
                expectIssue(PlaylistIssue.BUSY) {
                    exchange.export("cancelled-export", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
                }
                expectIssue(PlaylistIssue.BUSY) { registry.borrowExport(document, "cancelled-export") }
                assertEquals(4, provider.openModes.size)
            } finally { release.countDown() }
            withTimeout(2_000L) { providerIo.awaitIdle() }
            provider.beforeOpen = null
            exchange.closePreview("cancelled-export-cleanup")
            assertEquals(0L, documentFile.length())
            assertEquals(5, provider.openModes.size)
            assertEquals(0L, scalar("SELECT COUNT(*) FROM library_views"))
            assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_views"))
            assertEquals(original, playlists.snapshot(original.playlistId))
            registry.discard(document.selectionId)
            playlists.closeView(view.viewId)
            assertProviderClosed()
            assertNoPrivateStages()
            integrity()
        }
    }

    @Test fun theCleanupProviderReadAlsoHasADeadlineAndCanBeRetriedAfterItsPhysicalWorkerReturns() = runBlocking {
        withTimeout(15_000L) {
            exchange = exchangeController(cleanupTimeoutMs = 100L)
            val original = createOrdinary()
            val view = playlists.openView(original.playlistId)
            val document = output("blocking-cleanup-export")
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            provider.beforeOpen = {
                when (provider.openModes.size) {
                    4 -> throw IOException("Controlled verification failure")
                    5 -> blockPhysical(entered, release)
                }
            }
            val caller = async(Dispatchers.Default) {
                runCatching { exchange.export("blocking-cleanup-export", view, document,
                    PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8) }
            }
            try {
                entered.await()
                val failure = withTimeout(2_000L) { caller.await() }.exceptionOrNull() as PlaylistException
                assertEquals(PlaylistIssue.SOURCE_UNAVAILABLE, failure.issue)
                assertTrue(documentFile.length() > 0L)
                assertEquals(1, privateFileCount())
                expectIssue(PlaylistIssue.BUSY) { exchange.closePreview("retry-blocking-cleanup") }
                expectIssue(PlaylistIssue.BUSY) { registry.borrowExport(document, "blocking-cleanup-export") }
                assertEquals(5, provider.openModes.size)
            } finally { release.countDown() }
            withTimeout(2_000L) { providerIo.awaitIdle() }
            provider.beforeOpen = null
            exchange.closePreview("retry-blocking-cleanup")
            assertEquals(0L, documentFile.length())
            assertEquals(6, provider.openModes.size)
            assertNull(database.playlistExchangeDao().exportReceipt("blocking-cleanup-export"))
            registry.discard(document.selectionId)
            playlists.closeView(view.viewId)
            assertProviderClosed()
            assertNoPrivateStages()
            integrity()
        }
    }

    @Test fun aLateRemappedTargetIsProtectedAndTheSettledCleanupDoesNotPermanentlyBlockAnotherSelection() = runBlocking {
        withTimeout(15_000L) {
            exchange = exchangeController(cleanupTimeoutMs = 100L)
            val original = createOrdinary()
            val view = playlists.openView(original.playlistId)
            val document = output("late-remapped-export")
            val foreign = temporaryFolder.newFile("late-foreign.txt").apply { writeText("Foreign user data") }
            val foreignBytes = foreign.readBytes()
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            provider.beforeOpen = { if (provider.openModes.size == 4) blockPhysical(entered, release) }
            val caller = async(Dispatchers.Default) {
                exchange.export("late-remapped-export", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            }
            try {
                entered.await()
                withTimeout(2_000L) { caller.cancelAndJoin() }
                provider.document = foreign
            } finally { release.countDown() }
            withTimeout(2_000L) { providerIo.awaitIdle() }
            provider.beforeOpen = null
            expectIssue(PlaylistIssue.SOURCE_CHANGED) { exchange.closePreview("late-remapped-cleanup") }
            assertTrue(foreign.readBytes().contentEquals(foreignBytes))
            assertTrue(documentFile.length() > 0L)
            assertNoPrivateStages()
            expectIssue(PlaylistIssue.CONFLICT) {
                exchange.export("late-remapped-export", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            }
            assertTrue(foreign.readBytes().contentEquals(foreignBytes))
            registry.discard(document.selectionId)
            playlists.closeView(view.viewId)
            assertProviderClosed()
            assertNoPrivateStages()
            integrity()
        }
    }

    @Test fun aCommittedReceiptSurvivesCleanupFailureAndExactReplayDoesNoProviderWork() = runBlocking {
        val original = createOrdinary()
        val view = playlists.openView(original.playlistId)
        var closes = 0
        val port = object : LocalPlaylistRepository by playlists {
            override suspend fun closeView(viewId: String) {
                closes++
                if (closes == 1) throw PlaylistException(PlaylistIssue.STORAGE_UNAVAILABLE)
                playlists.closeView(viewId)
            }
        }
        exchange = exchangeController(port, cleanupTimeoutMs = 100L)
        val document = output("committed-cleanup-failure")
        val receipt = exchange.export("committed-cleanup-failure", view, document,
            PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
        val accepted = documentFile.readBytes()
        assertEquals(exchangeTestSha256(accepted), receipt.sha256)
        assertEquals(1, closes)
        assertEquals(receipt, exchange.export("committed-cleanup-failure", view, document,
            PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8))
        assertEquals(1, closes)
        assertEquals(4, provider.openModes.size)
        documentFile.writeText("Later user edit after the accepted export")
        val later = documentFile.readBytes()
        exchange.closePreview("finish-committed-cleanup")
        assertEquals(2, closes)
        assertEquals(4, provider.openModes.size)
        assertTrue(documentFile.readBytes().contentEquals(later))
        assertEquals(receipt, exchange.export("committed-cleanup-failure", view, document,
            PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8))
        registry.discard(document.selectionId)
        playlists.closeView(view.viewId)
        assertProviderClosed()
        assertNoPrivateStages()
        integrity()
    }

    @Test fun cancellationAfterTheDurableReceiptNeverClearsTheAcceptedTarget() = runBlocking {
        withTimeout(15_000L) {
            val original = createOrdinary()
            val view = playlists.openView(original.playlistId)
            val entered = CompletableDeferred<Unit>()
            var closes = 0
            val port = object : LocalPlaylistRepository by playlists {
                override suspend fun closeView(viewId: String) {
                    closes++
                    if (closes == 1) { entered.complete(Unit); awaitCancellation() }
                    playlists.closeView(viewId)
                }
            }
            exchange = exchangeController(port, cleanupTimeoutMs = 100L)
            val document = output("cancel-after-receipt")
            val caller = async(Dispatchers.Default) {
                exchange.export("cancel-after-receipt", view, document, PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            }
            entered.await()
            val accepted = documentFile.readBytes()
            assertTrue(database.playlistExchangeDao().exportReceipt("cancel-after-receipt") != null)
            withTimeout(2_000L) { caller.cancelAndJoin() }
            assertTrue(documentFile.readBytes().contentEquals(accepted))
            val receipt = exchange.export("cancel-after-receipt", view, document,
                PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            assertEquals(exchangeTestSha256(accepted), receipt.sha256)
            assertEquals(4, provider.openModes.size)
            exchange.closePreview("finish-cancel-after-receipt")
            assertTrue(documentFile.readBytes().contentEquals(accepted))
            registry.discard(document.selectionId)
            playlists.closeView(view.viewId)
            assertProviderClosed()
            assertNoPrivateStages()
            integrity()
        }
    }

    private fun privateFileCount(): Int = java.io.File(context.noBackupFilesDir, "playlist-exchange").listFiles()?.size ?: 0

    /** The physical thread deliberately ignores cancellation until the test opens the gate. */
    private fun blockPhysical(entered: CompletableDeferred<Unit>, release: CountDownLatch) {
        entered.complete(Unit)
        if (!release.await(10L, TimeUnit.SECONDS)) throw IOException("Controlled fixture gate timed out")
    }
}
