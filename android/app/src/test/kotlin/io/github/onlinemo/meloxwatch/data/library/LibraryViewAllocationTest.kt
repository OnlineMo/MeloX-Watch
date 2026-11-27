package io.github.onlinemo.meloxwatch.data.library

import android.content.Context
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** Real native rows and a paused caller dispatcher reproduce cancellation after IO commits but before token delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LibraryViewAllocationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository
    private lateinit var scope: CoroutineScope
    private val driver = ControlledDriver()
    private val dispatchers = mutableListOf<PausedReturnDispatcher>()

    @Before fun create() = runBlocking {
        database = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context, MeloXDatabase::class.java,
            File(temporary.root, "view-allocation.db").absolutePath).setDriver(driver).build()
        library = RoomLocalLibraryRepository(database)
        library.initialize()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun close() = runBlocking {
        driver.releaseCount.countDown()
        driver.blockCount.set(false)
        try {
            if (::scope.isInitialized) {
                scope.cancel()
                withTimeout(10_000L) {
                    while (scope.coroutineContext[Job]!!.children.any { !it.isCompleted }) {
                        dispatchers.forEach { it.runNext() }; delay(1L)
                    }
                }
            }
        } finally { if (::database.isInitialized) database.close() }
    }

    @Test(timeout = 30_000L) fun aCommittedViewRejectedAtTheReturnBoundaryIsRetiredWithoutClosingAnotherOwner() = runBlocking {
        val other = library.openView()
        val dispatcher = returnDispatcher()
        val delivered = AtomicReference<LibraryViewToken?>(null)
        val call = scope.async(dispatcher, CoroutineStart.UNDISPATCHED) { library.openView().also(delivered::set) }
        withTimeout(5_000L) { dispatcher.returnQueued.await() }
        assertEquals(2, database.libraryDao().views().size)
        assertEquals(null, delivered.get())
        call.cancel(CancellationException("Cancel before library token delivery"))
        drain(call, dispatcher)
        assertTrue(call.isCancelled)
        assertEquals(listOf(other.viewId), database.libraryDao().views().map { it.viewId })
        assertEquals(0L, library.page(other).view.totalCount)
        library.closeView(other.viewId)
    }

    @Test(timeout = 30_000L) fun aQueueCopyRejectedBeforeDeliveryPreservesItsSourceUiView() = runBlocking {
        val original = library.openView()
        val dispatcher = returnDispatcher()
        val call = scope.async(dispatcher, CoroutineStart.UNDISPATCHED) { library.openQueueView(original.viewId) }
        withTimeout(5_000L) { dispatcher.returnQueued.await() }
        assertEquals(2, database.libraryDao().views().size)
        call.cancel(CancellationException("Cancel before queue view delivery"))
        drain(call, dispatcher)
        assertEquals(listOf(original.viewId), database.libraryDao().views().map { it.viewId })
        library.page(original)
        library.closeView(original.viewId)
    }

    @Test(timeout = 30_000L) fun aFailedExactRetirementIsRetriedBeforeTheNextAllocation() = runBlocking {
        val other = library.openView()
        val dispatcher = returnDispatcher()
        val call = scope.async(dispatcher, CoroutineStart.UNDISPATCHED) { library.openView() }
        withTimeout(5_000L) { dispatcher.returnQueued.await() }
        val orphan = database.libraryDao().views().single { it.viewId != other.viewId }.viewId
        driver.failNextDelete.set(true)
        call.cancel(CancellationException("Cancel before rejected view delivery"))
        drain(call, dispatcher)
        assertEquals(setOf(other.viewId, orphan), database.libraryDao().views().map { it.viewId }.toSet())
        val next = library.openView()
        assertEquals(setOf(other.viewId, next.viewId), database.libraryDao().views().map { it.viewId }.toSet())
        library.page(other); library.page(next)
        library.closeView(other.viewId); library.closeView(next.viewId)
    }

    @Test(timeout = 30_000L) fun cancellingTheNativeCountDoesNotAllocateOrRetireAnUnrelatedToken() = runBlocking {
        val other = library.openView()
        val inserts = driver.viewInserts.get()
        driver.blockCount.set(true)
        val dispatcher = returnDispatcher()
        val call = scope.async(dispatcher, CoroutineStart.UNDISPATCHED) { library.openView() }
        try {
            assertTrue(driver.countEntered.await(5L, TimeUnit.SECONDS))
            call.cancel(CancellationException("Cancel the library count"))
            driver.releaseCount.countDown()
            drain(call, dispatcher)
            assertTrue(call.isCancelled)
            assertEquals(inserts, driver.viewInserts.get())
            assertEquals(listOf(other.viewId), database.libraryDao().views().map { it.viewId })
        } finally { driver.blockCount.set(false); driver.releaseCount.countDown() }
        library.page(other); library.closeView(other.viewId)
    }

    @Test(timeout = 30_000L) fun normalDeliveryRetainsAUsableViewUntilItsOwnerClosesIt() = runBlocking {
        val dispatcher = returnDispatcher()
        val call = scope.async(dispatcher, CoroutineStart.UNDISPATCHED) { library.openView() }
        withTimeout(5_000L) { dispatcher.returnQueued.await() }
        drain(call, dispatcher)
        assertFalse(call.isCancelled)
        val view = call.await()
        assertNotNull(database.libraryDao().view(view.viewId))
        library.page(view)
        library.closeView(view.viewId)
        assertTrue(database.libraryDao().views().isEmpty())
    }

    private suspend fun drain(job: Job, dispatcher: PausedReturnDispatcher) = withTimeout(10_000L) {
        while (!job.isCompleted) { dispatcher.runNext(); delay(1L) }
        while (dispatcher.runNext()) { }
    }

    private fun returnDispatcher() = PausedReturnDispatcher().also(dispatchers::add)

    private class PausedReturnDispatcher : CoroutineDispatcher() {
        private val queued = ConcurrentLinkedQueue<Runnable>()
        val returnQueued = CompletableDeferred<Unit>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.add(block); returnQueued.complete(Unit)
        }
        fun runNext(): Boolean = queued.poll()?.let { it.run(); true } ?: false
    }

    private class ControlledDriver(private val delegate: SQLiteDriver = BundledSQLiteDriver()) : SQLiteDriver by delegate {
        val failNextDelete = AtomicBoolean(false)
        val blockCount = AtomicBoolean(false)
        val countEntered = CountDownLatch(1)
        val releaseCount = CountDownLatch(1)
        val viewInserts = AtomicInteger(0)
        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    val text = sql.lowercase(Locale.ROOT).replace("`", "")
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean {
                            if (text.startsWith("insert") && "library_views" in text) viewInserts.incrementAndGet()
                            if (text.startsWith("delete from library_views") && failNextDelete.compareAndSet(true, false)) {
                                throw SQLiteException("Injected exact-view retirement failure")
                            }
                            if (blockCount.get() && "select count(*)" in text && "local_track_versions" in text) {
                                countEntered.countDown(); check(releaseCount.await(5L, TimeUnit.SECONDS))
                            }
                            return statement.step()
                        }
                    }
                }
            }
        }
    }
}
