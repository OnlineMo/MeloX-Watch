package io.github.onlinemo.meloxwatch.queue

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueuePhase
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueLimits
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

/** Actual file-backed Room transactions, independent SQLite integrity and paused durable recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomQueueMutationTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var database: MeloXDatabase
    private lateinit var store: RoomQueueStore
    private var randomCalls = 0

    @Before fun open(): Unit = runBlocking {
        file = File(folder.root, "mutations.db")
        database = connect()
        store = newStore()
        store.initialize()
    }

    @After fun close() { database.close() }

    @Test(timeout = 30000L)
    fun insertionKeepsExactOldInstancesAndRegeneratesSelectedInstanceIdsInSelectionOrder() = runBlocking {
        val old = replace("old", 5L, start = 2L)
        val selection = FrozenQueueView("selection", 9L, 2L)
        val changed = store.mutate(edit("insert", old, QueueMutation.InsertNext(selection, old.currentEntryId)),
            QueuePageSource { offset, limit ->
                QueueInputPage(selection, offset, List(limit) { index ->
                    entry(offset + index).copy(queueEntryId = "entry-2", displayName = "selected-" + (offset + index))
                })
            },
        ).snapshot
        val rows = store.page(changed.generationId, 0L)
        assertEquals(7L, changed.entryCount)
        assertEquals(listOf("entry-0", "entry-1", "entry-2"), rows.take(3).map { it.queueEntryId })
        assertEquals(listOf("selected-0", "selected-1"), rows.subList(3, 5).map { it.displayName })
        assertEquals(listOf("entry-3", "entry-4"), rows.takeLast(2).map { it.queueEntryId })
        assertEquals(7, rows.map { it.queueEntryId }.distinct().size)
        assertEquals(1, rows.map { it.trackKey }.distinct().size)
        assertEquals("entry-2", changed.currentEntryId)
        reopen()
        assertEquals(changed, store.snapshot())
        assertEquals(rows, store.page(changed.generationId, 0L))
        integrity(7L)
    }

    @Test(timeout = 30000L)
    fun anInsertAndPlayIntentPublishesTheFirstNewInstanceInsteadOfTheOldCurrent() = runBlocking {
        val old = replace("old", 5L, start = 2L)
        val selection = FrozenQueueView("selection", 1L, 2L)
        val changed = store.mutate(edit("insert-and-play", old, QueueMutation.InsertNext(selection, old.currentEntryId, startInserted = true)),
            source(selection),
        ).snapshot
        assertNotEquals(old.currentEntryId, changed.currentEntryId)
        assertEquals(3L, changed.currentPlaybackOrder)
        assertEquals(0L, changed.positionMs)
        assertEquals(0, changed.checkpoint.completedCycles)
        assertEquals(7L, changed.entryCount)
    }

    @Test(timeout = 30000L)
    fun emptyQueueAcceptsInsertionAndModeConfigurationWithoutClearingAnyLibrary() = runBlocking {
        val empty = store.mutate(QueueMutationRequest("empty-mode", null, 0L, null,
            QueueMutation.SetMode(QueueMode(repeat = QueueRepeatMode.LIST)))).snapshot
        assertEquals(0L, empty.entryCount)
        val selection = FrozenQueueView("selection", 1L, 2L)
        val changed = store.mutate(edit("first-insert", empty, QueueMutation.InsertNext(selection, null)), source(selection)).snapshot
        assertEquals(2L, changed.entryCount)
        assertEquals(QueueRepeatMode.LIST, changed.mode.repeat)
        assertEquals(0L, changed.currentPlaybackOrder)
        integrity(2L)
    }

    @Test(timeout = 30000L)
    fun commitPointUsesTheLatestCheckpointWithoutPuttingProgressIntoTheOperationFingerprint() = runBlocking {
        var old = replace("old", 5L, start = 2L, mode = QueueMode(repeat = QueueRepeatMode.SINGLE))
        old = store.saveCheckpoint(old.generationId, old.checkpointRevision, QueueCheckpoint(old.currentEntryId, 1234L, 6))
        val request = edit("move", old, QueueMutation.Move("entry-4", 1L))
        val changed = store.mutate(request) { candidate ->
            assertFalse(database.hasCurrentWriterTransaction())
            assertTrue(candidate.keepsCurrent)
            assertEquals(old.generationId, store.snapshot()?.generationId)
            store.saveCheckpoint(old.generationId, old.checkpointRevision, QueueCheckpoint(old.currentEntryId, 5678L, 7))
            null
        }.snapshot
        assertEquals(5678L, changed.positionMs)
        assertEquals(7, changed.checkpoint.completedCycles)
        assertEquals(old.currentEntryId, changed.currentEntryId)
        assertTrue(store.mutate(request).wasReplay)
        assertEquals(changed, store.snapshot())
    }

    @Test(timeout = 30000L)
    fun preciseNonCurrentRemovalKeepsDuplicateTracksAndTheCurrentCheckpoint() = runBlocking {
        var old = replace("old", 5L, start = 2L)
        old = store.saveCheckpoint(old.generationId, old.checkpointRevision, QueueCheckpoint(old.currentEntryId, 9012L, 1))
        val changed = store.mutate(edit("remove", old, QueueMutation.Remove("entry-1"))).snapshot
        val rows = store.page(changed.generationId, 0L)
        assertEquals(listOf("entry-0", "entry-2", "entry-3", "entry-4"), rows.map { it.queueEntryId })
        assertEquals(listOf(0L, 1L, 2L, 3L), rows.map { it.originalOrder })
        assertEquals(1, rows.map { it.trackKey }.distinct().size)
        assertEquals("entry-2", changed.currentEntryId)
        assertEquals(9012L, changed.positionMs)
        assertEquals(1, changed.checkpoint.completedCycles)
        integrity(4L)
    }

    @Test(timeout = 30000L)
    fun currentRemovalRequiresConfirmationAndCanPickOnlyABoundedSealedSuccessor() = runBlocking {
        val old = replace("old", 8L, start = 2L)
        expect(QueueStoreIssue.INVALID_INPUT) { store.mutate(edit("unconfirmed", old, QueueMutation.Remove("entry-2"))) }
        assertEquals(old, store.snapshot())
        val changed = store.mutate(edit("confirmed", old, QueueMutation.Remove("entry-2", confirmedCurrent = true))) { candidate ->
            assertTrue(candidate.currentRemoved)
            assertFalse(candidate.keepsCurrent)
            assertEquals(listOf("entry-3", "entry-4", "entry-5", "entry-6"), candidate.windows.map { it.current.queueEntryId })
            assertTrue(candidate.windows.all { it.entries.size <= QueueLimits.PLAYER_WINDOW_SIZE })
            QueueCheckpoint(candidate.windows[1].current.queueEntryId, 0L)
        }.snapshot
        assertEquals("entry-4", changed.currentEntryId)
        assertEquals(0L, changed.positionMs)
        assertEquals(0, changed.checkpoint.completedCycles)
        integrity(7L)
    }

    @Test(timeout = 30000L)
    fun removalAtTheTailFallsBackToTheNearestRemainingPredecessorAndTheLastRemovalIsEmpty() = runBlocking {
        val old = replace("old", 2L, start = 1L)
        val remaining = store.mutate(edit("remove-tail", old, QueueMutation.Remove("entry-1", confirmedCurrent = true))).snapshot
        assertEquals("entry-0", remaining.currentEntryId)
        assertEquals(0L, remaining.positionMs)
        val empty = store.mutate(edit("remove-last", remaining, QueueMutation.Remove("entry-0", confirmedCurrent = true))).snapshot
        assertEquals(0L, empty.entryCount)
        assertNull(empty.currentEntryId)
        assertEquals(0L, empty.positionMs)
        reopen()
        assertEquals(empty, store.snapshot())
        integrity(0L)
    }

    @Test(timeout = 30000L)
    fun movingAnInstanceAtBothBoundariesNeverDropsDuplicatesOrChangesItsPosition() = runBlocking {
        var old = replace("old", 5L, start = 2L)
        old = store.saveCheckpoint(old.generationId, old.checkpointRevision, QueueCheckpoint(old.currentEntryId, 6000L, 3))
        val first = store.mutate(edit("move-first", old, QueueMutation.Move("entry-2", 0L))).snapshot
        assertEquals(listOf("entry-2", "entry-0", "entry-1", "entry-3", "entry-4"), store.page(first.generationId, 0L).map { it.queueEntryId })
        assertEquals(6000L, first.positionMs)
        assertEquals(3, first.checkpoint.completedCycles)
        val last = store.mutate(edit("move-last", first, QueueMutation.Move("entry-2", 4L))).snapshot
        val rows = store.page(last.generationId, 0L)
        assertEquals(listOf("entry-0", "entry-1", "entry-3", "entry-4", "entry-2"), rows.map { it.queueEntryId })
        assertEquals(rows.map { it.originalOrder }, rows.map { it.playbackOrder })
        assertEquals(6000L, last.positionMs)
        assertEquals("entry-2", last.currentEntryId)
        reopen()
        assertEquals(rows, store.page(last.generationId, 0L))
        integrity(5L)
    }

    @Test(timeout = 120000L)
    fun realShuffleMaterializesTheEntire10001ItemQueueAndRestoresTheExactSavedPermutation() = runBlocking {
        val old = replace("old", 10001L, start = 5000L)
        val shuffled = store.mutate(edit("shuffle", old, QueueMutation.SetMode(QueueMode(shuffle = true)))).snapshot
        assertEquals(10001L, shuffled.entryCount)
        assertEquals(0L, shuffled.currentPlaybackOrder)
        assertEquals(5000L, shuffled.currentOriginalOrder)
        assertTrue(randomCalls > 0)
        val rows = allRows(shuffled)
        assertEquals(10001, rows.map { it.queueEntryId }.distinct().size)
        assertEquals((0L..10000L).toList(), rows.map { it.originalOrder }.sorted())
        assertNotEquals((0L..10000L).toList(), rows.map { it.originalOrder })
        assertEquals(10000L, rows.last().playbackOrder)
        assertEquals(2, store.window(shuffled.generationId, shuffled.currentEntryId!!).entries.size)
        reopen()
        assertEquals(shuffled, store.snapshot())
        assertEquals(rows, allRows(shuffled))
        integrity(10001L)
    }

    @Test(timeout = 30000L)
    fun repeatedShuffleDoesNotReshuffleAndDisablingLocatesTheSameInstanceInCanonicalOrder() = runBlocking {
        var old = replace("old", 8L, start = 3L, mode = QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2))
        old = store.saveCheckpoint(old.generationId, old.checkpointRevision, QueueCheckpoint(old.currentEntryId, 3210L, 1))
        val mode = old.mode.copy(shuffle = true)
        val shuffled = store.mutate(edit("shuffle", old, QueueMutation.SetMode(mode))).snapshot
        val originalPermutation = store.page(shuffled.generationId, 0L).map { it.queueEntryId }
        val calls = randomCalls
        val repeated = store.mutate(edit("same-shuffle", shuffled, QueueMutation.SetMode(mode))).snapshot
        assertEquals(calls, randomCalls)
        assertEquals(originalPermutation, store.page(repeated.generationId, 0L).map { it.queueEntryId })
        val restored = store.mutate(edit("original", repeated, QueueMutation.SetMode(mode.copy(shuffle = false)))).snapshot
        assertEquals((0L until 8L).toList(), store.page(restored.generationId, 0L).map { it.originalOrder })
        assertEquals("entry-3", restored.currentEntryId)
        assertEquals(3L, restored.currentPlaybackOrder)
        assertEquals(3210L, restored.positionMs)
        assertEquals(1, restored.checkpoint.completedCycles)
    }

    @Test(timeout = 30000L)
    fun editingShuffledPlaybackOrderPreservesTheCanonicalOriginalOrderForDisablingShuffle() = runBlocking {
        val old = replace("old", 8L, start = 3L)
        val shuffled = store.mutate(edit("shuffle", old, QueueMutation.SetMode(QueueMode(shuffle = true)))).snapshot
        val moved = store.mutate(edit("move-random", shuffled, QueueMutation.Move("entry-6", 1L))).snapshot
        val rows = store.page(moved.generationId, 0L)
        assertEquals("entry-6", rows[1].queueEntryId)
        assertEquals(6L, rows[1].originalOrder)
        assertEquals("entry-3", moved.currentEntryId)
        val restored = store.mutate(edit("unshuffle", moved, QueueMutation.SetMode(QueueMode()))).snapshot
        assertEquals((0L until 8L).toList(), store.page(restored.generationId, 0L).map { it.originalOrder })
        integrity(8L)
    }

    @Test(timeout = 30000L)
    fun finiteLoopConfigurationStartsCountingWithTheOngoingRoundButShuffleAlonePreservesCount() = runBlocking {
        var old = replace("old", 5L, start = 2L, mode = QueueMode(repeat = QueueRepeatMode.SINGLE))
        old = store.saveCheckpoint(old.generationId, old.checkpointRevision, QueueCheckpoint(old.currentEntryId, 9876L, 8))
        val limited = store.mutate(edit("limit", old, QueueMutation.SetMode(old.mode.copy(cycleLimit = 2)))).snapshot
        assertEquals(0, limited.checkpoint.completedCycles)
        assertEquals(9876L, limited.positionMs)
        assertEquals("entry-2", limited.currentEntryId)
    }

    @Test(timeout = 30000L)
    fun historicalMutationReplayAfterReplacementDoesNoProducerPreflightOrOldGenerationCleanup() = runBlocking {
        val old = replace("old", 5L, start = 2L)
        val selection = FrozenQueueView("selection", 1L, 2L)
        val request = edit("insert", old, QueueMutation.InsertNext(selection, old.currentEntryId))
        val first = store.mutate(request, source(selection))
        val later = replace("later", 3L)
        val inactiveRows = database.queueDao().orderStats(first.appliedGenerationId).entryCount
        val replay = store.mutate(request, beforePublish = { throw AssertionError("Replay must not validate a resource") })
        assertTrue(replay.wasReplay)
        assertEquals(first.appliedGenerationId, replay.appliedGenerationId)
        assertEquals(later, replay.snapshot)
        assertEquals(inactiveRows, database.queueDao().orderStats(first.appliedGenerationId).entryCount)
        assertEquals(later, store.snapshot())
        expect(QueueStoreIssue.INVALID_INPUT) {
            store.mutate(request.copy(mutation = QueueMutation.InsertNext(selection, old.currentEntryId, startInserted = true)), source(selection))
        }
    }

    @Test(timeout = 30000L)
    fun aChangedInsertionViewAndCancellationNeverPublishAPartialEditedQueue() = runBlocking {
        val old = replace("old", 205L, start = 2L)
        val selection = FrozenQueueView("selection", 1L, 205L)
        val changed = edit("changed-input", old, QueueMutation.InsertNext(selection, old.currentEntryId))
        expect(QueueStoreIssue.SOURCE_CHANGED) {
            store.mutate(changed, QueuePageSource { offset, limit ->
                source(selection).read(offset, limit).let {
                    if (offset > 0L) it.copy(view = selection.copy(revision = 2L)) else it
                }
            })
        }
        assertDiscarded(changed.operationId, old)
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val cancelled = edit("cancelled-input", old, QueueMutation.InsertNext(selection, old.currentEntryId))
        val job = launch {
            store.mutate(cancelled, QueuePageSource { offset, limit ->
                if (offset > 0L) { entered.complete(Unit); hold.await() }
                source(selection).read(offset, limit)
            })
        }
        withTimeout(10000L) { entered.await() }
        job.cancelAndJoin()
        assertEquals(old, store.snapshot())
        reopen()
        assertDiscarded(cancelled.operationId, old)
        integrity(205L)
    }

    @Test(timeout = 30000L)
    fun aConcurrentTrackSelectionWinsAndCannotBeOverwrittenByThePreparedEditCheckpoint() = runBlocking {
        val old = replace("old", 5L, start = 2L)
        var selected: QueueSnapshot? = null
        val request = edit("stale-current", old, QueueMutation.Move("entry-4", 0L))
        expect(QueueStoreIssue.CONFLICT) {
            store.mutate(request) {
                selected = store.saveCheckpoint(old.generationId, old.checkpointRevision, QueueCheckpoint("entry-3", 777L))
                QueueCheckpoint(old.currentEntryId, 4444L)
            }
        }
        assertEquals(selected, store.snapshot())
        assertEquals("entry-3", store.snapshot()?.currentEntryId)
        assertEquals(777L, store.snapshot()?.positionMs)
        assertDiscarded(request.operationId, selected!!)
        integrity(5L)
    }

    @Test(timeout = 30000L)
    fun invalidBoundsAnchorsAndUnsealedSlotsCannotMutateAnActiveGeneration() = runBlocking {
        val old = replace("old", 5L, start = 2L)
        expect(QueueStoreIssue.INVALID_INPUT) { store.mutate(edit("bad-move", old, QueueMutation.Move("entry-0", 5L))) }
        val selection = FrozenQueueView("selection", 1L, 1L)
        expect(QueueStoreIssue.INVALID_INPUT) {
            store.mutate(edit("bad-anchor", old, QueueMutation.InsertNext(selection, "entry-0")), source(selection))
        }
        assertEquals(0, database.queueDao().moveStagingSlot(old.generationId, 0L, -1L))
        assertEquals(0, database.queueDao().setStagingOrder(old.generationId, "entry-0", 3L))
        assertEquals(old, store.snapshot())
        integrity(5L)
    }

    @Test(timeout = 30000L)
    fun newReplacementWithShuffleActuallyGeneratesAnOrderAndPlainReplacementUsesOriginalOrder() = runBlocking {
        val shuffled = replace("shuffle-on-build", 8L, start = 3L, mode = QueueMode(shuffle = true))
        assertEquals(0L, shuffled.currentPlaybackOrder)
        assertEquals(3L, shuffled.currentOriginalOrder)
        assertTrue(randomCalls > 0)
        val base = store.snapshot()
        val view = FrozenQueueView("reverse-input", 1L, 8L)
        val request = QueueReplacementRequest("plain", base?.generationId, base?.queueRevision ?: 0L, view)
        val plain = store.replace(request, QueuePageSource { offset, limit ->
            QueueInputPage(view, offset, List(limit) { entry(offset + it).copy(playbackOrder = 7L - offset - it) })
        }).snapshot
        assertEquals((0L until 8L).toList(), store.page(plain.generationId, 0L).map { it.originalOrder })
        integrity(8L)
    }

    @Test(timeout = 30000L)
    fun actualSqliteFullDuringQueueCopyLeavesTheOldCompleteQueueAndAllowsRetry() = runBlocking {
        val view = FrozenQueueView("large-old", 1L, 205L)
        val old = store.replace(QueueReplacementRequest("large-old", null, 0L, view),
            QueuePageSource { offset, limit ->
                QueueInputPage(view, offset, List(limit) { entry(offset + it).copy(displayName = "x".repeat(4096)) })
            },
        ).snapshot
        val request = edit("copy-on-full-disk", old, QueueMutation.SetMode(QueueMode(repeat = QueueRepeatMode.LIST)))
        val maximum = database.withPreservedTransaction {
            val previous = writerPragma("max_page_count")
            val cap = writerPragma("page_count") + 8L
            assertEquals(cap, writerPragma("max_page_count = " + cap))
            previous
        }
        try {
            try { store.mutate(request); throw AssertionError("A bounded copy must fail at the real page ceiling") }
            catch (error: QueueStoreException) {
                assertEquals(QueueStoreIssue.STORAGE_FULL, error.issue)
                assertTrue(error.cause is SQLiteFullException)
            }
            assertEquals(old, store.snapshot())
        } finally {
            database.withPreservedTransaction { writerPragma("max_page_count = " + maximum) }
        }
        reopen()
        assertEquals(old, store.snapshot())
        integrity(205L)
        val retried = store.mutate(request).snapshot
        assertEquals(205L, retried.entryCount)
        assertEquals(QueueRepeatMode.LIST, retried.mode.repeat)
        integrity(205L)
    }

    private fun newStore() = RoomQueueStore(database, shuffleIndex = { randomCalls += 1; 1L })
    private fun connect() = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context,
        MeloXDatabase::class.java, file.absolutePath).setDriver(BundledSQLiteDriver()).build()

    private suspend fun reopen() {
        database.close()
        database = connect()
        store = newStore()
        store.initialize()
    }

    private suspend fun replace(operation: String, count: Long, start: Long = 0L, mode: QueueMode = QueueMode()): QueueSnapshot {
        val previous = store.snapshot()
        val view = FrozenQueueView("view-" + operation, 1L, count)
        return store.replace(QueueReplacementRequest(operation, previous?.generationId, previous?.queueRevision ?: 0L,
            view, mode, startOriginalOrder = start), source(view)).snapshot
    }

    private fun source(view: FrozenQueueView) = QueuePageSource { offset, limit ->
        assertTrue(limit in 1..QueueLimits.PAGE_SIZE)
        QueueInputPage(view, offset, List(limit) { entry(offset + it) })
    }

    private fun entry(order: Long) = QueueEntry("entry-" + order, TrackKey(TrackKey.LOCAL_PROVIDER, "repeated-track"),
        order, order, displayName = "Audio " + order, location = LocalLocationReference("location-" + order, "content://audio/" + order))

    private fun edit(operation: String, previous: QueueSnapshot, mutation: QueueMutation) =
        QueueMutationRequest(operation, previous.generationId, previous.queueRevision, previous.currentEntryId, mutation)

    private suspend fun allRows(snapshot: QueueSnapshot): List<QueueEntry> = buildList {
        var cursor = 0L
        while (cursor < snapshot.entryCount) {
            val page = store.page(snapshot.generationId, cursor)
            assertTrue(page.size <= QueueLimits.PAGE_SIZE)
            addAll(page)
            cursor = page.last().playbackOrder + 1L
        }
    }

    private suspend fun expect(issue: QueueStoreIssue, action: suspend () -> Unit) {
        try { action() } catch (error: QueueStoreException) { assertEquals(issue, error.issue); return }
        throw AssertionError("Expected " + issue)
    }

    private suspend fun assertDiscarded(operation: String, active: QueueSnapshot) {
        assertEquals(active, store.snapshot())
        val record = database.queueDao().operation(operation) ?: throw AssertionError("Durable operation required")
        assertEquals(QueuePhase.ABORTED, record.phase)
        assertEquals(0L, database.queueDao().orderStats(record.generationId).entryCount)
    }

    private suspend fun integrity(expectedCount: Long) = withContext(Dispatchers.IO) {
        // 两种 SQLite 实现不能重叠持有同一文件；先退役 Room，再用原生只读连接独立核验。
        database.close()
        var integrityFailure: Throwable? = null
        try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { independent ->
                independent.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ok", cursor.getString(0))
                }
                independent.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                independent.rawQuery("SELECT COUNT(*) FROM queue_entries WHERE generationId = (SELECT activeGenerationId FROM queue_head WHERE id = 1)", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(expectedCount, it.getLong(0))
                }
            }
        } catch (error: Throwable) {
            integrityFailure = error
            throw error
        } finally {
            try {
                // native 已实际关闭才重开。后续重试仍读取原文件，不新建空库或重置随机源计数。
                database = connect()
                store = newStore()
                store.initialize()
            } catch (error: Throwable) {
                val primary = integrityFailure
                if (primary == null) throw error
                if (primary !== error) primary.addSuppressed(error)
            }
        }
    }

    private suspend fun writerPragma(expression: String): Long = database.withTestWriterSql {
        query("PRAGMA " + expression) {
            assertTrue(it.step())
            it.getLong(0)
        }
    }
}
