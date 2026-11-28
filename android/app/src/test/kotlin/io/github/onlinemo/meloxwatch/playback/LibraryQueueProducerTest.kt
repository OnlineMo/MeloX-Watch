package io.github.onlinemo.meloxwatch.playback

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryLimits
import io.github.onlinemo.meloxwatch.data.library.LibrarySort
import io.github.onlinemo.meloxwatch.data.library.LibrarySortField
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 真实Room视图/lease与分页；这些用例不运行解码器，也不声明provider或设备验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LibraryQueueProducerTest {
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository
    private lateinit var counted: CountingLibrary
    private var elapsed = 1000L

    @Before
    fun open() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication() as Context, MeloXDatabase::class.java,
        ).setDriver(BundledSQLiteDriver()).build()
        library = RoomLocalLibraryRepository(database, { elapsed }, { 1_790_000_000_000L })
        counted = CountingLibrary(library)
        assertEquals(0L, library.initialize().totalTracks)
    }

    @After
    fun close() { database.close() }

    @Test(timeout = 30000L)
    fun adoptionIsLazyAndTheIndependentLeaseProducesEveryFrozenPage() = runBlocking {
        seed(205)
        val view = openView()
        val command = command(view)
        val producer = LibraryQueueProducer(counted, command)
        assertEquals(0, counted.queueOpens)
        assertEquals(1, database.libraryDao().views().size)

        val first = producer.source.read(0L, 100)
        assertEquals(view.revision, first.view.revision)
        assertEquals(205L, first.view.totalCount)
        assertTrue(first.view.id.length <= 128)
        assertEquals(100, first.entries.size)
        assertNotEquals(view.viewId, counted.lastQueueView?.viewId)
        library.closeView(view.viewId)

        val middle = producer.source.read(100L, 100)
        val last = producer.source.read(200L, 5)
        assertEquals(first.view, middle.view)
        assertEquals(first.view, last.view)
        val entries = first.entries + middle.entries + last.entries
        assertEquals((0L until 205L).toList(), entries.map { it.originalOrder })
        assertEquals(entries.map { it.originalOrder }, entries.map { it.playbackOrder })
        assertEquals(205, entries.map { it.queueEntryId }.toSet().size)
        assertEquals("Track00204.mp3", entries.last().displayName)
        assertEquals(1, counted.queueOpens)
        assertEquals(listOf(100, 100, 5), counted.pageSizes)
        invalidRead { producer.source.read(0L, 100) }
        producer.close()
        assertEquals(0, database.libraryDao().views().size)
    }

    @Test(timeout = 30000L)
    fun anUnavailableVisibleLeaseFailsBeforeReturningRows() = runBlocking {
        seed(3)
        val view = openView()
        val producer = LibraryQueueProducer(counted, command(view))
        library.closeView(view.viewId)
        expectLibrary(LibraryIssue.VIEW_EXPIRED) { producer.source.read(0L, 3) }
        producer.close()
        assertEquals(1, counted.queueOpens)
        assertEquals(0, counted.queueSources)
        assertEquals(0, database.libraryDao().views().size)
    }

    @Test(timeout = 30000L)
    fun revisionCountAndSelectedOrdinalMustMatchTheActualAdoptedView() = runBlocking {
        seed(3)
        val view = openView()
        val selected = library.page(view).tracks[1]
        val valid = command(view).copy(selected = selected.trackKey, selectedOriginalOrder = 1L)
        val changed = listOf(
            valid.copy(viewRevision = view.revision + 1L),
            valid.copy(viewCount = 4L),
            valid.copy(selectedOriginalOrder = 0L),
            valid.copy(selected = TrackKey(TrackKey.LOCAL_PROVIDER, "missing-selection")),
        )
        for (request in changed) {
            val producer = LibraryQueueProducer(counted, request)
            expectLibrary(LibraryIssue.SOURCE_CHANGED) { producer.source.read(0L, 3) }
            producer.close()
            assertEquals(1, database.libraryDao().views().size)
        }
        assertEquals(0, counted.queueSources)
        library.closeView(view.viewId)
    }

    @Test(timeout = 30000L)
    fun selectedInsertionKeepsStableIntentAcrossFreshProducerLeases() = runBlocking {
        seed(3)
        val view = openView()
        val tracks = library.page(view).tracks
        val selected = command(view).copy(selected = tracks[1].trackKey, selectedOriginalOrder = 1L,
            action = LibraryQueueAction.INSERT_NEXT)
        suspend fun produce(request: LibraryQueueCommand): Pair<FrozenQueueView, String> {
            val producer = LibraryQueueProducer(counted, request)
            try {
                val page = producer.source.read(0L, 1)
                assertEquals(1L, page.view.totalCount)
                assertEquals(request.selected, page.entries.single().trackKey)
                assertEquals(0L, page.entries.single().originalOrder)
                assertEquals(0L, page.entries.single().playbackOrder)
                assertTrue(page.view.id.length <= 128)
                return page.view to page.entries.single().queueEntryId
            } finally { producer.close() }
        }
        val first = produce(selected)
        val replaySource = produce(selected)
        assertEquals(first.first, replaySource.first)
        assertNotEquals(first.second, replaySource.second)
        assertNotEquals(first.first, produce(selected.copy(selected = tracks[2].trackKey, selectedOriginalOrder = 2L)).first)
        val otherView = openView()
        assertNotEquals(first.first, produce(selected.copy(viewId = otherView.viewId)).first)
        assertNotEquals(first.first, produce(selected.copy(action = LibraryQueueAction.INSERT_AND_PLAY)).first)
        assertEquals(0, counted.queueSources)
        library.closeView(otherView.viewId)
        library.closeView(view.viewId)
    }

    @Test(timeout = 30000L)
    fun expiryBetweenPagesStopsTheProducerRatherThanReadingLatestLibraryData() = runBlocking {
        seed(101)
        val view = openView()
        val producer = LibraryQueueProducer(counted, command(view))
        producer.source.read(0L, 100)
        elapsed += LibraryLimits.VIEW_LEASE_MS + 1L
        try {
            producer.source.read(100L, 1)
            throw AssertionError("Expired producer returned another page")
        } catch (error: QueueStoreException) { assertEquals(QueueStoreIssue.SOURCE_CHANGED, error.issue) }
        assertEquals(listOf(100), counted.pageSizes)
        producer.close()
        library.closeView(view.viewId)
    }

    @Test(timeout = 30000L)
    fun failedLeaseCleanupCanBeRetriedWhileFurtherSourceReadsStayClosed() = runBlocking {
        seed(3)
        val view = openView()
        val producer = LibraryQueueProducer(counted, command(view))
        producer.source.read(0L, 3)
        counted.closeFailures = 1
        expectLibrary(LibraryIssue.STORAGE_UNAVAILABLE) { producer.close() }
        assertEquals(2, database.libraryDao().views().size)
        closedRead { producer.source.read(3L, 1) }
        producer.close()
        producer.close()
        assertEquals(2, counted.closeAttempts)
        assertEquals(1, database.libraryDao().views().size)
        library.closeView(view.viewId)
    }

    private suspend fun openView() = library.openView(sort = LibrarySort(LibrarySortField.FILENAME_NATURAL))

    private fun command(view: LibraryViewToken) = LibraryQueueCommand(
        view.viewId, "library-operation", null, 0L, null, view.revision, view.totalCount, null, null,
        QueueMode(), LibraryQueueAction.REPLACE_COMPLETE_LIST,
    )

    private suspend fun seed(count: Int) {
        val spec = LocalSourceSpec("fixture-source", LocalSourceKind.TREE, "content://queue-library/tree/root",
            authority = "queue-library", rootObjectId = "root", providerIncarnation = "fixture-instance")
        val source = library.registerSource(spec, LocalGrantObservation(
            read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE,
        ))
        val scan = library.beginScan(source.spec.sourceId, source.authEpoch, library.rules().revision, ScanMode.FULL)
        library.commitSeen(scan, listOf(ObservedCandidate(source.spec.sourceId, "root", source.spec.uri, null,
            "Root", "vnd.android.document/directory", LocalNodeKind.DIRECTORY)))
        for (start in 0 until count step LibraryLimits.PAGE_SIZE) {
            val rows = (start until minOf(start + LibraryLimits.PAGE_SIZE, count)).map { index ->
                ObservedCandidate(source.spec.sourceId, "audio-$index", "content://queue-library/document/audio-$index",
                    "root", "Track${index.toString().padStart(5, '0')}.mp3", "audio/mpeg", LocalNodeKind.AUDIO,
                    sizeBytes = 128L, providerContentToken = "native:$index:1")
            }
            library.commitSeen(scan, rows)
        }
        while (true) {
            val directory = library.nextDirectory(scan) ?: break
            library.completeDirectory(scan, directory.objectId, true)
        }
        val coverage = ScanCoverage(true, true, true, true, false)
        library.setCoverage(scan, coverage)
        var after: String? = null
        while (true) {
            val page = library.stageAbsence(scan, after)
            if (page.complete) break
            after = checkNotNull(page.nextAliasId)
        }
        library.finishScan(scan, ScanCounters(), coverage)
    }

    private suspend fun expectLibrary(issue: LibraryIssue, action: suspend () -> Unit) {
        try { action() } catch (error: LibraryException) { assertEquals(issue, error.issue); return }
        throw AssertionError("Expected $issue")
    }

    private suspend fun invalidRead(action: suspend () -> Unit) {
        try { action() } catch (_: IllegalArgumentException) { return }
        throw AssertionError("Producer accepted a non-forward cursor")
    }

    private suspend fun closedRead(action: suspend () -> Unit) {
        try { action() } catch (_: IllegalStateException) { return }
        throw AssertionError("Closed producer returned rows")
    }

    private class CountingLibrary(private val delegate: LocalLibraryRepository) : LocalLibraryRepository by delegate {
        var queueOpens = 0
        var queueSources = 0
        var lastQueueView: LibraryViewToken? = null
        var closeFailures = 0
        var closeAttempts = 0
        val pageSizes = mutableListOf<Int>()

        override suspend fun openQueueView(fromViewId: String): LibraryViewToken {
            queueOpens += 1
            return delegate.openQueueView(fromViewId).also { lastQueueView = it }
        }

        override fun queueSource(view: LibraryViewToken): QueuePageSource {
            queueSources += 1
            val source = delegate.queueSource(view)
            return QueuePageSource { offset, limit -> source.read(offset, limit).also { pageSizes.add(it.entries.size) } }
        }

        override suspend fun closeView(viewId: String) {
            closeAttempts += 1
            if (closeFailures > 0) {
                closeFailures -= 1
                throw LibraryException(LibraryIssue.STORAGE_UNAVAILABLE)
            }
            delegate.closeView(viewId)
        }
    }
}
