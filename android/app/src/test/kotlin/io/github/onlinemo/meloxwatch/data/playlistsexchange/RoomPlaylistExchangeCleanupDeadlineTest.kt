package io.github.onlinemo.meloxwatch.data.playlistsexchange

import androidx.room.useWriterConnection
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferEncoding
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferFormat
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Cancellation must not forget a queued metadata owner or leave a deletion for after retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class RoomPlaylistExchangeCleanupDeadlineTest : RoomPlaylistExchangeFixture() {
    @Test(timeout = 30_000L)
    fun cancelledCallerStillBoundsQueuedPreviewCleanupAndCannotDeleteTheRetainedLeaseLater() = runBlocking {
        withTimeout(15_000L) {
            exchange = exchangeController(cleanupTimeoutMs = 100L)
            val document = input(knownM3u())
            val original = documentFile.readBytes()
            val preview = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
            val token = database.libraryDao().views().single().viewId
            val modes = provider.openModes.toList()
            val databaseRoot = database.useWriterConnection {
                var execution = checkNotNull(currentCoroutineContext()[Job])
                while (execution.parent is Continuation<*>) execution = checkNotNull(execution.parent)
                checkNotNull(execution.parent)
            }
            assertFalse(databaseRoot is Continuation<*>)
            assertTrue(databaseRoot.isActive)
            val probe = RoomExecutionProbe(databaseRoot)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable?>()
            var caller: Job? = null
            val blocker = launch(Dispatchers.Default) {
                database.withPreservedTransaction {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                withTimeout(2_000L) { entered.await() }
                val closing = launch(Dispatchers.IO + probe) {
                    try {
                        exchange.closePreview(preview.previewId)
                        observed.complete(null)
                    } catch (error: Throwable) {
                        observed.complete(error)
                    }
                }
                caller = closing
                // Capture the protected cleanup's real Room execution and occupied writer wait.
                val queued = awaitQueued(probe, closing)
                closing.cancel()
                val error = withTimeout(2_000L) { observed.await() }
                assertTrue(error is PlaylistException)
                assertEquals(PlaylistIssue.BUSY, (error as PlaylistException).issue)
                withTimeout(2_000L) { closing.join() }
                assertTrue(closing.isCancelled)
                assertFalse(closing.children.any())
                assertTrue(queued.execution.isCompleted)
                assertTrue(queued.acquisition.isCompleted)
                assertFalse(queued.execution.children.any())
                assertTrue(databaseRoot.isActive)
                assertFalse(blocker.isCompleted)
                assertFalse(release.isCompleted)
                assertEquals(modes, provider.openModes)
                expectIssue(PlaylistIssue.VIEW_EXPIRED) { exchange.importPage(preview.previewId, 0L) }
                assertNoPrivateStages()
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) {
                    withTimeout(5_000L) {
                        blocker.join()
                        caller?.cancelAndJoin()
                        providerIo.awaitIdle()
                    }
                }
            }
            // A real writer barrier observes a wrongly detached queued deletion after release.
            database.withPreservedTransaction { }
            assertEquals(listOf(token), database.libraryDao().views().map { it.viewId })
            assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_exchange_previews"))
            assertEquals(2L, scalar("SELECT COUNT(*) FROM playlist_exchange_rows"))
            assertEquals(0L, playlists.overview().playlistCount)
            exchange.closePreview(preview.previewId)
            assertEquals(0L, scalar("SELECT COUNT(*) FROM library_views"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_exchange_previews"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_exchange_rows"))
            assertEquals(modes, provider.openModes)
            assertTrue(documentFile.readBytes().contentEquals(original))
            registry.discard(document.selectionId)
            assertProviderClosed()
            assertNoPrivateStages()
            integrity()
        }
    }

    private suspend fun awaitQueued(probe: RoomExecutionProbe, waiting: Job): QueuedCleanup = withTimeout(2_000L) {
        val execution = probe.entered.await()
        // Before connection entry, the held writer leaves only acquireWithTimeout's timeout child.
        var acquisition = execution.children.firstOrNull()
        while (acquisition == null) {
            assertFalse("Cleanup returned before entering the occupied Room writer wait", waiting.isCompleted)
            yield()
            acquisition = execution.children.firstOrNull()
        }
        val queued = QueuedCleanup(execution, checkNotNull(acquisition))
        assertTrue(queued.execution.isActive)
        assertTrue(queued.acquisition.isActive)
        assertFalse(waiting.isCompleted)
        queued
    }

    private data class QueuedCleanup(val execution: Job, val acquisition: Job)

    private class RoomExecutionProbe(private val databaseRoot: Job) :
        AbstractCoroutineContextElement(Key), ThreadContextElement<Unit> {
        companion object Key : CoroutineContext.Key<RoomExecutionProbe>
        val entered = CompletableDeferred<Job>()

        override fun updateThreadContext(context: CoroutineContext) {
            val execution = context[Job] ?: return
            if (execution.parent === databaseRoot) entered.complete(execution)
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) = Unit
    }
}
