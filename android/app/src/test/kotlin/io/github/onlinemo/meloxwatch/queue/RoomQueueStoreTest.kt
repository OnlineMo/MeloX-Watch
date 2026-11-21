package io.github.onlinemo.meloxwatch.queue

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.local.fixtureAudioProvider
import io.github.onlinemo.meloxwatch.data.local.fixtureDescriptor
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueuePhase
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.queue.record
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueLimits
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueOrderPolicy
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

/** 真正的文件库和 native SQLite；API26 SDK JAR 由 Gradle/cloud 锁定，不运行时下载。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomQueueStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var database: MeloXDatabase
    private lateinit var store: RoomQueueStore

    @Before
    fun openDatabase() = runBlocking {
        file = File(temporaryFolder.root, "queue.db")
        database = open(file)
        store = RoomQueueStore(database)
        assertNull(store.initialize())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test(timeout = 30000L)
    fun completeIdentityOrderModeAndPausedCheckpointSurviveReopening() = runBlocking {
        val request = request("restore", 3L).copy(mode = QueueMode(true, QueueRepeatMode.SINGLE, 2), startOriginalOrder = 1L)
        val result = store.replace(request, source(request.view, reversed = true))
        val current = entry(1L, 3L, reversed = true)
        val checkpoint = store.saveCheckpoint(result.snapshot.generationId, 0L, QueueCheckpoint(current.queueEntryId, 12345L, 1))
        val before = store.page(checkpoint.generationId, 0L)
        assertNull(before.first().durationMs)
        assertEquals(1, before.map { it.trackKey }.distinct().size)
        assertEquals(3, before.map { it.queueEntryId }.distinct().size)

        reopen()
        assertEquals(checkpoint, store.snapshot())
        assertEquals(before, store.page(checkpoint.generationId, 0L))
        assertEquals(listOf(2L, 1L, 0L), before.map { it.originalOrder })
        assertEquals(QueueMode(true, QueueRepeatMode.SINGLE, 2), checkpoint.mode)
        assertEquals(LocalAccess.TEMPORARY, before.first().location?.access)
        // URI 字符串与访问类型是快照，不代表重开后重新授予临时系统权限。
        assertEquals("content://test-audio/2", before.first().location?.uri)
        assertIndependentIntegrity(expectedEntries = 3L, expectedPosition = 12345L)
    }

    @Test(timeout = 60000L)
    fun fullQueuesAt10000And10001KeepTheirLastRowsAndBoundedWindows() = runBlocking {
        for (count in listOf(10000L, 10001L)) {
            val request = request("full-$count", count).copy(startOriginalOrder = 5000L, mode = QueueMode(shuffle = true))
            var largestPage = 0
            val producer = source(request.view, reversed = true)
            val result = store.replace(request, QueuePageSource { offset, limit ->
                assertTrue(limit in 1..QueueLimits.PAGE_SIZE)
                producer.read(offset, limit).also { largestPage = maxOf(largestPage, it.entries.size) }
            }).snapshot
            assertEquals(count, result.entryCount)
            assertEquals(count, database.queueDao().orderStats(result.generationId).entryCount)
            assertEquals(100, largestPage)
            assertEquals(count - 1L, store.page(result.generationId, count - 1L).single().playbackOrder)
            assertEquals(0L, store.page(result.generationId, count - 1L).single().originalOrder)
            assertEquals(count - 9950L, store.page(result.generationId, 9950L).size.toLong())

            val middle = store.window(result.generationId, "entry-5000")
            assertEquals(3, middle.entries.size)
            assertTrue(middle.hasPrevious && middle.hasNext)
            assertEquals(1, middle.currentIndex)
            val first = store.window(result.generationId, "entry-${count - 1L}")
            val last = store.window(result.generationId, "entry-0")
            assertEquals(2, first.entries.size)
            assertEquals(2, last.entries.size)
            assertFalse(first.hasPrevious)
            assertTrue(first.hasNext)
            assertTrue(last.hasPrevious)
            assertFalse(last.hasNext)
            reopen()
            assertEquals(result, store.snapshot())
            assertIndependentIntegrity(count, 0L)
        }
    }

    @Test(timeout = 30000L)
    fun sourceRevisionChangesAbortTheNewGenerationAndKeepTheOldQueue() = runBlocking {
        val old = store.replace(request("old", 3L), source(FrozenQueueView("view-old", 1L, 3L))).snapshot
        val next = request("changed", 205L)
        val normal = source(next.view)
        expectIssue(QueueStoreIssue.SOURCE_CHANGED) {
            store.replace(next, QueuePageSource { offset, limit ->
                normal.read(offset, limit).let { page ->
                    if (offset == 0L) page else page.copy(view = page.view.copy(revision = 2L))
                }
            })
        }
        assertDiscarded("changed", old)
        reopen()
        assertEquals(old, store.snapshot())
        assertIndependentIntegrity(3L, 0L)
    }

    @Test(timeout = 30000L)
    fun incompleteInputAndUnavailableProducerNeverPublishAPartialQueue() = runBlocking {
        val oldRequest = request("old", 3L)
        val old = store.replace(oldRequest, source(oldRequest.view)).snapshot
        for ((operation, failure) in listOf("short" to QueueStoreIssue.INCOMPLETE, "unavailable" to QueueStoreIssue.SOURCE_UNAVAILABLE)) {
            val next = request(operation, 205L)
            val normal = source(next.view)
            expectIssue(failure) {
                store.replace(next, QueuePageSource { offset, limit ->
                    if (offset == 0L) normal.read(offset, limit) else if (operation == "short") {
                        normal.read(offset, limit).copy(entries = emptyList())
                    } else {
                        throw IOException("controlled producer failure")
                    }
                })
            }
            assertDiscarded(operation, old)
        }
        reopen()
        assertEquals(old, store.snapshot())
    }

    @Test(timeout = 30000L)
    fun duplicateInstancesOrOrdersAreRejectedByRealSqlConstraints() = runBlocking {
        val oldRequest = request("old", 3L)
        val old = store.replace(oldRequest, source(oldRequest.view)).snapshot
        for (fault in listOf("same-instance", "same-order")) {
            val next = request(fault, 205L)
            val normal = source(next.view)
            expectIssue(QueueStoreIssue.INVALID_INPUT) {
                store.replace(next, QueuePageSource { offset, limit ->
                    val page = normal.read(offset, limit)
                    if (offset == 0L) page else page.copy(entries = page.entries.mapIndexed { index, value ->
                        if (index != 0) value else if (fault == "same-instance") value.copy(queueEntryId = "entry-0")
                        else value.copy(playbackOrder = 0L)
                    })
                })
            }
            assertDiscarded(fault, old)
        }
        reopen()
        assertIndependentIntegrity(3L, 0L)
    }

    @Test(timeout = 30000L)
    fun cancellationAfterAStagedPageDiscardsOnlyTheUnpublishedGeneration() = runBlocking {
        val oldRequest = request("old", 3L)
        val old = store.replace(oldRequest, source(oldRequest.view)).snapshot
        val next = request("cancelled", 205L)
        val normal = source(next.view)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replacement = launch {
            store.replace(next, QueuePageSource { offset, limit ->
                if (offset > 0L) {
                    entered.complete(Unit)
                    release.await()
                }
                normal.read(offset, limit)
            })
        }
        withTimeout(10000L) { entered.await() }
        replacement.cancelAndJoin()
        assertTrue(replacement.isCancelled)
        assertDiscarded("cancelled", old)
        reopen()
        assertEquals(old, store.snapshot())
    }

    @Test(timeout = 30000L)
    fun cancellationCleanupIsOneBoundedBatchAndReopeningReclaimsTheRest() = runBlocking {
        val oldRequest = request("old", 3L)
        val old = store.replace(oldRequest, source(oldRequest.view)).snapshot
        val next = request("large-cancelled", 505L)
        val normal = source(next.view)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replacement = launch {
            store.replace(next, QueuePageSource { offset, limit ->
                if (offset == 400L) {
                    entered.complete(Unit)
                    release.await()
                }
                normal.read(offset, limit)
            })
        }
        withTimeout(10000L) { entered.await() }
        replacement.cancelAndJoin()
        val abandoned = database.queueDao().operation(next.operationId) ?: throw AssertionError("Recovery identity required")
        assertEquals(QueuePhase.ABORTED, abandoned.phase)
        assertEquals(300L, database.queueDao().orderStats(abandoned.generationId).entryCount)
        assertEquals(old, store.snapshot())

        reopen()
        assertDiscarded(next.operationId, old)
        assertIndependentIntegrity(3L, 0L)
        val retried = store.replace(next, source(next.view)).snapshot
        assertEquals(abandoned.generationId, retried.generationId)
        assertEquals(505L, retried.entryCount)
    }

    @Test(timeout = 30000L)
    fun onlyOnePreparationWaitsAndProgressDoesNotInvalidateQueueRevision() = runBlocking {
        val oldRequest = request("old", 3L)
        val old = store.replace(oldRequest, source(oldRequest.view)).snapshot
        val next = request("pending", 205L)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val producer = source(next.view)
        val replacement = async {
            store.replace(next, QueuePageSource { offset, limit ->
                if (offset == 0L) {
                    entered.complete(Unit)
                    release.await()
                }
                producer.read(offset, limit)
            })
        }
        withTimeout(10000L) { entered.await() }
        try {
            val concurrent = request("busy", 2L)
            expectIssue(QueueStoreIssue.BUSY) { store.replace(concurrent, source(concurrent.view)) }
            val progress = store.saveCheckpoint(old.generationId, old.checkpointRevision, QueueCheckpoint(old.currentEntryId, 555L))
            assertEquals(old.queueRevision, progress.queueRevision)
            assertEquals(1L, progress.checkpointRevision)
        } finally {
            release.complete(Unit)
        }
        val committed = withTimeout(10000L) { replacement.await() }.snapshot
        assertEquals(old.queueRevision + 1L, committed.queueRevision)
        assertEquals(0L, committed.checkpointRevision)
        assertEquals(205L, committed.entryCount)
    }

    @Test(timeout = 30000L)
    fun staleCheckpointCannotOverwriteANewerSeekAndKnownDurationClamps() = runBlocking {
        val request = request("checkpoint", 3L)
        val result = store.replace(request, source(request.view, duration = 1000L)).snapshot
        val first = store.saveCheckpoint(result.generationId, 0L, QueueCheckpoint("entry-1", 9999L, 1))
        assertEquals(1000L, first.positionMs)
        expectIssue(QueueStoreIssue.CONFLICT) {
            store.saveCheckpoint(result.generationId, 0L, QueueCheckpoint("entry-0", 0L))
        }
        assertEquals(first, store.snapshot())
        reopen()
        assertEquals(first, store.snapshot())
        assertIndependentIntegrity(3L, 1000L)
    }

    @Test(timeout = 30000L)
    fun anotherCommittedQueueWinsOverAStalePreparedGeneration() = runBlocking {
        val oldRequest = request("old", 3L)
        val old = store.replace(oldRequest, source(oldRequest.view)).snapshot
        // 两个仓库只用于触发数据库 CAS；正式 app 仍只有一个 Store/构造者。
        val contender = RoomQueueStore(database)
        contender.initialize()
        val stale = request("stale", 205L)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val producer = source(stale.view)
        val staleResult = async {
            expectIssue(QueueStoreIssue.CONFLICT) {
                store.replace(stale, QueuePageSource { offset, limit ->
                    if (offset > 0L) {
                        entered.complete(Unit)
                        release.await()
                    }
                    producer.read(offset, limit)
                })
            }
        }
        withTimeout(10000L) { entered.await() }
        val newer = request("newer", 2L, old)
        val committed = try {
            contender.replace(newer, source(newer.view)).snapshot
        } finally {
            release.complete(Unit)
        }
        withTimeout(10000L) { staleResult.await() }
        assertDiscarded("stale", committed)
        reopen()
        assertEquals(committed, store.snapshot())
    }

    @Test(timeout = 30000L)
    fun sealedNonzeroSelectionUsesTheForwardOnlyProducerOnceAndGuardsOutsideTheTransaction() = runBlocking {
        val oldRequest = request("old", 3L)
        val old = store.replace(oldRequest, source(oldRequest.view)).snapshot
        val next = request("sealed-selection", 205L).copy(startOriginalOrder = 155L, mode = QueueMode(shuffle = true))
        val producer = source(next.view, reversed = true)
        val offsets = mutableListOf<Long>()
        var nextOffset = 0L
        var guarded = false
        val committed = store.replace(next, QueuePageSource { offset, limit ->
            assertEquals("A frozen producer cannot seek or restart", nextOffset, offset)
            assertFalse(database.hasCurrentWriterTransaction())
            offsets.add(offset)
            producer.read(offset, limit).also { nextOffset += it.entries.size }
        }) { candidate ->
            guarded = true
            assertFalse("Resource validation cannot hold a SQLite transaction", database.hasCurrentWriterTransaction())
            assertEquals(205L, nextOffset)
            val staged = database.queueDao().operation(next.operationId) ?: throw AssertionError("Sealed journal required")
            assertEquals(QueuePhase.SEALED, staged.phase)
            assertEquals(205L, database.queueDao().orderStats(staged.generationId).entryCount)
            val window = requireNotNull(candidate)
            assertEquals(staged.generationId, window.generationId)
            assertEquals(old.queueRevision + 1L, window.queueRevision)
            assertEquals(205L, window.totalCount)
            assertEquals(listOf(156L, 155L, 154L), window.entries.map { it.originalOrder })
            assertEquals(1, window.currentIndex)
            assertEquals(49L, window.current.playbackOrder)
            assertEquals(old, store.snapshot())
            // Old playback can advance while the new resource is checked; checkpoint revisions
            // do not change the queue CAS domain or force the forward-only producer to restart.
            val progressed = store.saveCheckpoint(old.generationId, old.checkpointRevision, QueueCheckpoint("entry-1", 555L))
            assertEquals(old.queueRevision, progressed.queueRevision)
            assertEquals(old.checkpointRevision + 1L, progressed.checkpointRevision)
        }.snapshot

        assertTrue(guarded)
        assertEquals(listOf(0L, 100L, 200L), offsets)
        assertEquals("entry-155", committed.currentEntryId)
        assertEquals(49L, committed.currentPlaybackOrder)
        assertEquals(0L, committed.checkpointRevision)
        assertEquals(3L, database.queueDao().orderStats(old.generationId).entryCount)
        reopen()
        assertEquals(committed, store.snapshot())
        assertEquals(0L, database.queueDao().orderStats(old.generationId).entryCount)
        assertIndependentIntegrity(205L, 0L)
    }

    @Test(timeout = 30000L)
    fun realProviderOpenDenialOrSameSizeContentChangeAfterSealPreservesTheOldQueue() = runBlocking {
        val oldRequest = request("old", 3L)
        val initial = store.replace(oldRequest, source(oldRequest.view)).snapshot
        val old = store.saveCheckpoint(initial.generationId, initial.checkpointRevision, QueueCheckpoint("entry-1", 8765L))
        val oldRows = store.page(old.generationId, 0L)

        for ((fault, issue) in listOf("open-denied" to SourceReadIssue.PERMISSION_DENIED, "changed-bytes" to SourceReadIssue.SOURCE_CHANGED)) {
            val audio = temporaryFolder.newFile("$fault.mp3").apply { writeBytes(ByteArray(2048) { (it % 113).toByte() }) }
            val authority = "queue-$fault"
            val provider = fixtureAudioProvider(authority, audio)
            val descriptor = fixtureDescriptor(authority, audio)
            val io = LocalProviderIo()
            val sources = AndroidLocalSources(RuntimeEnvironment.getApplication(), io)
            val next = request(fault, 205L).copy(startOriginalOrder = 155L)
            val producer = source(next.view)
            val selected = entry(155L, 205L).copy(trackKey = descriptor.trackKey, location = descriptor.location)
            expectSourceIssue(issue) {
                store.replace(next, QueuePageSource { offset, limit ->
                    producer.read(offset, limit).let { page ->
                        page.copy(entries = page.entries.map { if (it.originalOrder == 155L) selected else it })
                    }
                }) { candidate ->
                    assertFalse(database.hasCurrentWriterTransaction())
                    val journal = database.queueDao().operation(next.operationId) ?: throw AssertionError("Sealed journal required")
                    assertEquals(QueuePhase.SEALED, journal.phase)
                    assertEquals(descriptor.location, requireNotNull(candidate).current.location)
                    assertEquals(old, store.snapshot())
                    if (fault == "open-denied") provider.openDenied = true
                    else audio.writeBytes(ByteArray(2048) { ((it + 1) % 113).toByte() })
                    sources.validateRead(descriptor)
                }
            }
            withTimeout(10000L) { io.awaitIdle() }
            assertTrue(provider.queryReads > 0)
            assertEquals(if (fault == "open-denied") 0 else 1, provider.audioReads)
            assertDiscarded(next.operationId, old)
            assertEquals(oldRows, store.page(old.generationId, 0L))
            assertEquals(8765L, store.snapshot()?.positionMs)
        }
        reopen()
        assertEquals(old, store.snapshot())
        assertIndependentIntegrity(3L, 8765L)
    }

    @Test(timeout = 30000L)
    fun transientPreviewOpenPermissionLossAtTheSealedGuardCannotReplaceTheOldQueue() = runBlocking {
        val oldRequest = request("old", 3L)
        val initial = store.replace(oldRequest, source(oldRequest.view)).snapshot
        val old = store.saveCheckpoint(initial.generationId, initial.checkpointRevision, QueueCheckpoint("entry-1", 2345L))
        val oldRows = store.page(old.generationId, 0L)
        val audio = temporaryFolder.newFile("transient.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val provider = fixtureAudioProvider("queue-transient-open", audio)
        val io = LocalProviderIo()
        val sources = AndroidLocalSources(RuntimeEnvironment.getApplication(), io)
        val location = LocalLocationReference("temporary-preview", "content://queue-transient-open/audio")
        val next = request("transient-open-denied", 3L).copy(startOriginalOrder = 1L)
        val producer = source(next.view)
        expectSourceIssue(SourceReadIssue.PERMISSION_DENIED) {
            store.replace(next, QueuePageSource { offset, limit ->
                producer.read(offset, limit).let { page ->
                    page.copy(entries = page.entries.map { if (it.originalOrder == 1L) it.copy(location = location) else it })
                }
            }) { candidate ->
                val current = requireNotNull(candidate).current
                assertEquals(0L, current.location?.contentRevision)
                assertEquals(old, store.snapshot())
                assertFalse(database.hasCurrentWriterTransaction())
                provider.openDenied = true
                sources.verifyTransientRead(requireNotNull(current.location))
            }
        }
        withTimeout(10000L) { io.awaitIdle() }
        assertEquals(1, provider.queryReads)
        assertEquals(0, provider.audioReads)
        assertDiscarded(next.operationId, old)
        assertEquals(oldRows, store.page(old.generationId, 0L))
        reopen()
        assertEquals(old, store.snapshot())
        assertIndependentIntegrity(3L, 2345L)
    }

    @Test(timeout = 40000L)
    fun cancellationDuringAnIgnoringProviderQueryAbortsSealedRowsWithoutFreeingItsPhysicalChannel() = runBlocking {
        val oldRequest = request("old", 3L)
        val initial = store.replace(oldRequest, source(oldRequest.view)).snapshot
        val old = store.saveCheckpoint(initial.generationId, initial.checkpointRevision, QueueCheckpoint("entry-1", 4321L))
        val audio = temporaryFolder.newFile("cancelled.mp3").apply { writeBytes(ByteArray(1024) { 7 }) }
        val provider = fixtureAudioProvider("queue-cancelled-query", audio)
        val descriptor = fixtureDescriptor("queue-cancelled-query", audio)
        val io = LocalProviderIo()
        val sources = AndroidLocalSources(RuntimeEnvironment.getApplication(), io)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.beforeQuery = {
            entered.countDown()
            assertTrue("The fixture must always be released", release.await(20L, TimeUnit.SECONDS))
        }
        val next = request("cancelled-sealed", 505L).copy(startOriginalOrder = 355L)
        val producer = source(next.view)
        val selected = entry(355L, 505L).copy(trackKey = descriptor.trackKey, location = descriptor.location)
        val preparation = launch {
            store.replace(next, QueuePageSource { offset, limit ->
                producer.read(offset, limit).let { page ->
                    page.copy(entries = page.entries.map { if (it.originalOrder == 355L) selected else it })
                }
            }) { candidate ->
                assertEquals(descriptor.location, requireNotNull(candidate).current.location)
                assertFalse(database.hasCurrentWriterTransaction())
                sources.validateRead(descriptor)
            }
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(10L, TimeUnit.SECONDS)) }
            withTimeout(10000L) { preparation.cancelAndJoin() }
            assertTrue(preparation.isCancelled)
            assertEquals(old, store.snapshot())
            val abandoned = database.queueDao().operation(next.operationId) ?: throw AssertionError("Recovery journal required")
            assertEquals(QueuePhase.ABORTED, abandoned.phase)
            assertEquals(405L, database.queueDao().orderStats(abandoned.generationId).entryCount)
            expectSourceIssue(SourceReadIssue.BUSY) { sources.validateRead(descriptor) }
            assertEquals(1, provider.queryReads)
            assertEquals(0, provider.audioReads)
        } finally {
            release.countDown()
            withTimeout(10000L) { preparation.cancelAndJoin() }
            withTimeout(10000L) { io.awaitIdle() }
        }
        provider.beforeQuery = null
        reopen()
        assertDiscarded(next.operationId, old)
        assertIndependentIntegrity(3L, 4321L)
    }

    @Test(timeout = 30000L)
    fun aCompetitorPublishedWhileTheSealedGuardWaitsWinsTheFinalCas() = runBlocking {
        val oldRequest = request("old", 3L)
        val old = store.replace(oldRequest, source(oldRequest.view)).snapshot
        val contender = RoomQueueStore(database)
        contender.initialize()
        val stale = request("sealed-stale", 205L).copy(startOriginalOrder = 155L)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staleResult = async {
            expectIssue(QueueStoreIssue.CONFLICT) {
                store.replace(stale, source(stale.view)) { candidate ->
                    assertFalse(database.hasCurrentWriterTransaction())
                    assertEquals("entry-155", requireNotNull(candidate).current.queueEntryId)
                    val journal = database.queueDao().operation(stale.operationId) ?: throw AssertionError("Sealed journal required")
                    assertEquals(QueuePhase.SEALED, journal.phase)
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        val committed = try {
            withTimeout(10000L) { entered.await() }
            val newer = request("sealed-winner", 2L, old)
            contender.replace(newer, source(newer.view)).snapshot
        } finally {
            release.complete(Unit)
        }
        withTimeout(10000L) { staleResult.await() }
        assertDiscarded(stale.operationId, committed)
        reopen()
        assertEquals(committed, store.snapshot())
        assertIndependentIntegrity(2L, 0L)
    }

    @Test(timeout = 30000L)
    fun historicalOperationReplayAcknowledgesItWithoutReactivatingIt() = runBlocking {
        val firstRequest = request("first", 3L)
        val first = store.replace(firstRequest, source(firstRequest.view)).snapshot
        val secondRequest = request("second", 2L)
        val second = store.replace(secondRequest, source(secondRequest.view)).snapshot
        assertNotEquals(first.generationId, second.generationId)
        // Receipt handoff defers inactive cleanup; even replay cannot start a whole old-generation
        // deletion pass or rerun a resource guard before acknowledging a historical operation.
        assertEquals(3L, database.queueDao().orderStats(first.generationId).entryCount)
        val replay = store.replace(firstRequest, QueuePageSource { _, _ -> throw AssertionError("replay must not read its old producer") }) {
            throw AssertionError("replay must not validate current or historical resources")
        }
        assertTrue(replay.wasReplay)
        assertEquals(first.generationId, replay.appliedGenerationId)
        assertEquals(second, replay.snapshot)
        assertEquals(second, store.snapshot())
        assertEquals(3L, database.queueDao().orderStats(first.generationId).entryCount)
        reopen()
        assertEquals(second, store.snapshot())
        expectIssue(QueueStoreIssue.INVALID_INPUT) {
            store.replace(firstRequest.copy(mode = QueueMode(shuffle = true)), source(firstRequest.view))
        }
        assertEquals(0L, database.queueDao().orderStats(first.generationId).entryCount)
    }

    @Test(timeout = 30000L)
    fun anEmptySelectionPreservesTheQueueUntilExplicitClear() = runBlocking {
        val oldRequest = request("old", 3L)
        val old = store.replace(oldRequest, source(oldRequest.view)).snapshot
        val empty = request("empty-selection", 0L)
        val neverRead = QueuePageSource { _, _ -> throw AssertionError("zero references need no producer I/O") }
        expectIssue(QueueStoreIssue.INVALID_INPUT) { store.replace(empty, neverRead) }
        assertEquals(old, store.snapshot())
        var guarded = false
        val cleared = store.replace(empty.copy(operationId = "confirmed-clear", allowEmpty = true), neverRead) { candidate ->
            guarded = true
            assertNull(candidate)
            assertFalse(database.hasCurrentWriterTransaction())
            assertEquals(old, store.snapshot())
        }.snapshot
        assertTrue(guarded)
        assertEquals(0L, cleared.entryCount)
        assertNull(cleared.currentEntryId)
        assertEquals(emptyList<QueueEntry>(), store.page(cleared.generationId, 0L))
        reopen()
        assertEquals(cleared, store.snapshot())
        assertIndependentIntegrity(0L, 0L)
    }

    @Test(timeout = 30000L)
    fun reopeningRecoversInterruptedStagingWithoutDeletingTheActiveGeneration() = runBlocking {
        val oldRequest = request("old", 3L)
        val old = store.replace(oldRequest, source(oldRequest.view)).snapshot
        val abandoned = request("interrupted", 205L)
        database.withPreservedTransaction {
            database.queueDao().insertGeneration(abandoned.record("interrupted-generation"))
            database.queueDao().insertEntries((0L until 100L).map { entry(it, 205L).record("interrupted-generation") })
            assertEquals(1, database.queueDao().advanceStaging("interrupted-generation", 0L, 100L))
        }
        assertEquals(100L, database.queueDao().orderStats("interrupted-generation").entryCount)
        reopen()
        assertDiscarded("interrupted", old)
        val retry = store.replace(abandoned, source(abandoned.view))
        assertEquals("interrupted-generation", retry.snapshot.generationId)
        assertFalse(retry.wasReplay)
        assertEquals(205L, retry.snapshot.entryCount)
    }

    @Test(timeout = 30000L)
    fun anActualTransactionRollbackRemainsRolledBackOnAnIndependentConnection() = runBlocking {
        val request = request("rollback", 3L)
        val committed = store.replace(request, source(request.view)).snapshot
        try {
            database.withPreservedTransaction {
                assertEquals(1, database.queueDao().checkpoint(committed.generationId, 0L, 1L, "entry-1", 9000L, 1))
                throw IOException("abort after a real SQL mutation")
            }
        } catch (_: IOException) {
            // 故障发生在 SQL 写入后，Room 必须回滚事务，而不是靠假仓库返回旧对象。
        }
        assertEquals(committed, store.snapshot())
        reopen()
        assertEquals(committed, store.snapshot())
        assertIndependentIntegrity(3L, 0L)
    }

    @Test(timeout = 30000L)
    fun realSqliteFullPreservesTheOldQueueAndAllowsExplicitRetry() = runBlocking {
        val oldRequest = request("old", 3L)
        val old = store.replace(oldRequest, source(oldRequest.view)).snapshot
        // WAL 查询可走reader；连接级页上限必须在Room持有writer时设置和恢复。
        val previousMaximum = database.withPreservedTransaction {
            val previous = writerPragmaLong("max_page_count")
            val cap = writerPragmaLong("page_count") + 8L
            assertEquals(cap, writerPragmaLong("max_page_count = $cap"))
            previous
        }
        val next = request("full-disk", 205L)
        val title = "x".repeat(4096)
        try {
            val failure = expectIssue(QueueStoreIssue.STORAGE_FULL) {
                store.replace(next, source(next.view, title = title))
            }
            assertTrue(failure.cause is SQLiteFullException)
            assertTrue("The full-write error retains its distinct SQLite cleanup failure",
                requireNotNull(failure.cause).suppressed.any { it is SQLiteException && it !is SQLiteFullException })
            assertEquals(old, store.snapshot())
        } finally {
            database.withPreservedTransaction { writerPragmaLong("max_page_count = $previousMaximum") }
        }
        reopen()
        assertEquals(old, store.snapshot())
        assertIndependentIntegrity(3L, 0L)
        val retried = store.replace(next, source(next.view, title = title)).snapshot
        assertEquals(205L, retried.entryCount)
    }

    @Test(timeout = 30000L)
    fun aFutureSchemaIsRejectedWithoutDestructiveFallback() = runBlocking {
        val request = request("schema", 3L)
        store.replace(request, source(request.view))
        database.close()
        withContext(Dispatchers.IO) {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { independent ->
                independent.version = 7
            }
        }
        database = open(file)
        store = RoomQueueStore(database)
        expectIssue(QueueStoreIssue.RECOVERY_REQUIRED) { store.initialize() }
        withContext(Dispatchers.IO) {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { independent ->
                assertEquals(7, independent.version)
                independent.rawQuery("SELECT COUNT(*) FROM queue_entries", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(3L, cursor.getLong(0))
                }
            }
        }
    }

    @Test(timeout = 30000L)
    fun missingActiveRowsRequireRecoveryAndNeverMasqueradeAsAFullRestoredQueue() = runBlocking {
        val request = request("missing-row", 3L)
        store.replace(request, source(request.view))
        withContext(Dispatchers.IO) {
            database.withTestWriterSql { execute("DELETE FROM queue_entries WHERE queueEntryId = 'entry-2'") }
        }
        database.close()
        database = open(file)
        store = RoomQueueStore(database)
        expectIssue(QueueStoreIssue.RECOVERY_REQUIRED) { store.initialize() }
        expectIssue(QueueStoreIssue.RECOVERY_REQUIRED) { store.snapshot() }
        withContext(Dispatchers.IO) {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { independent ->
                independent.rawQuery("SELECT COUNT(*) FROM queue_entries", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(2L, cursor.getLong(0))
                }
            }
        }
    }

    @Test(timeout = 30000L)
    fun observerPresentsACompleteCommittedGeneration() = runBlocking {
        val request = request("observe", 205L)
        val observed = async { withTimeout(10000L) { store.snapshots().filterNotNull().first() } }
        val committed = store.replace(request, source(request.view)).snapshot
        val emitted = observed.await()
        assertEquals(committed, emitted)
        assertEquals(205L, store.page(emitted.generationId, 200L).last().originalOrder + 1L)
    }

    private fun open(file: File): MeloXDatabase = Room.databaseBuilder(
        RuntimeEnvironment.getApplication() as Context,
        MeloXDatabase::class.java,
        file.absolutePath,
    ).setDriver(BundledSQLiteDriver()).build()

    private suspend fun reopen() {
        database.close()
        database = open(file)
        store = RoomQueueStore(database)
        store.initialize()
    }

    private suspend fun request(operation: String, count: Long): QueueReplacementRequest = request(operation, count, store.snapshot())

    private fun request(operation: String, count: Long, base: QueueSnapshot?): QueueReplacementRequest =
        QueueReplacementRequest(operation, base?.generationId, base?.queueRevision ?: 0L, FrozenQueueView("view-$operation", 1L, count),
            orderPolicy = QueueOrderPolicy.PRESERVE_SUPPLIED)

    private fun source(
        view: FrozenQueueView,
        reversed: Boolean = false,
        duration: Long? = null,
        title: String? = null,
    ): QueuePageSource = QueuePageSource { offset, limit ->
        QueueInputPage(view, offset, List(limit) { index -> entry(offset + index, view.totalCount, reversed, duration, title) })
    }

    private fun entry(index: Long, count: Long, reversed: Boolean = false, duration: Long? = null, title: String? = null): QueueEntry = QueueEntry(
        queueEntryId = "entry-$index",
        trackKey = TrackKey(TrackKey.LOCAL_PROVIDER, "repeated-track"),
        originalOrder = index,
        playbackOrder = if (reversed) count - index - 1L else index,
        displayName = title,
        durationMs = duration,
        location = LocalLocationReference("location-$index", "content://test-audio/$index"),
    )

    private suspend fun expectIssue(expected: QueueStoreIssue, action: suspend () -> Unit): QueueStoreException {
        try {
            action()
        } catch (error: QueueStoreException) {
            try {
                assertEquals(expected, error.issue)
            } catch (failure: AssertionError) {
                failure.initCause(error)
                throw failure
            }
            return error
        }
        throw AssertionError("expected $expected")
    }

    private suspend fun expectSourceIssue(expected: SourceReadIssue, action: suspend () -> Unit): SourceReadException {
        try {
            action()
        } catch (error: SourceReadException) {
            assertEquals(expected, error.issue)
            return error
        }
        throw AssertionError("expected $expected")
    }

    private suspend fun assertDiscarded(operation: String, active: QueueSnapshot) {
        assertEquals(active, store.snapshot())
        val rejected = database.queueDao().operation(operation) ?: throw AssertionError("staged operation must retain its recovery identity")
        assertEquals(QueuePhase.ABORTED, rejected.phase)
        assertEquals(0L, database.queueDao().orderStats(rejected.generationId).entryCount)
        assertEquals(active.entryCount, database.queueDao().orderStats(active.generationId).entryCount)
    }

    private suspend fun writerPragmaLong(pragma: String): Long {
        assertTrue(database.hasCurrentWriterTransaction())
        return database.withTestWriterSql {
            query("PRAGMA $pragma") { statement ->
                assertTrue(statement.step())
                statement.getLong(0)
            }
        }
    }

    private suspend fun assertIndependentIntegrity(expectedEntries: Long, expectedPosition: Long) = withContext(Dispatchers.IO) {
        // 先关闭 Room 的 bundled driver，避免两种 SQLite 实现在同一文件上重叠持有连接。
        // 原生连接必须完成实际关闭，再恢复后续用例继续使用的 Room 夹具。
        database.close()
        var integrityFailure: Throwable? = null
        try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { independent ->
                independent.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ok", cursor.getString(0))
                }
                assertEquals(6, independent.version)
                independent.rawQuery("SELECT COUNT(*) FROM queue_entries", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(expectedEntries, cursor.getLong(0))
                }
                independent.rawQuery("SELECT positionMs FROM queue_head WHERE id = 1", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(expectedPosition, cursor.getLong(0))
                }
            }
        } catch (error: Throwable) {
            integrityFailure = error
            throw error
        } finally {
            try {
                database = open(file)
                store = RoomQueueStore(database)
                store.initialize()
            } catch (error: Throwable) {
                // 恢复夹具的失败只能附加到原始失败，不能掩盖完整性检查的结果。
                val primary = integrityFailure
                if (primary == null) throw error
                if (primary !== error) primary.addSuppressed(error)
            }
        }
    }
}
