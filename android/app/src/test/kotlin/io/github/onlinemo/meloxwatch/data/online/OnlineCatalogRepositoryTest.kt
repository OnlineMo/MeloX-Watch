package io.github.onlinemo.meloxwatch.data.online

import android.content.Context
import androidx.room.Room
import io.github.onlinemo.meloxwatch.data.auth.ControlledSessionStore
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.data.auth.fixtureSession
import io.github.onlinemo.meloxwatch.data.netease.NeteaseAuthApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseCatalogApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseEapi
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.data.network.jsonLong
import io.github.onlinemo.meloxwatch.data.network.jsonObject
import io.github.onlinemo.meloxwatch.data.network.jsonString
import io.github.onlinemo.meloxwatch.data.online.cache.OnlineCatalogDatabase
import io.github.onlinemo.meloxwatch.data.online.cache.RoomOnlineCatalogCache
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogQuery
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real bounded Room cache rows and synthetic EAPI responses; this suite does not assert audio or device behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OnlineCatalogRepositoryTest {
    private lateinit var database: OnlineCatalogDatabase
    private lateinit var sessions: SessionRepository
    private lateinit var repository: OnlineCatalogRepository
    private val offsets = CopyOnWriteArrayList<Long>()
    private val query = OnlineCatalogQuery(OnlineCatalogKind.SEARCH, "Fixture query")
    @Volatile private var fail = false
    @Volatile private var block = false
    private var now = 1_000L
    private var songBase = 201L
    private var searchCount = 205L
    private var dailyCount = 0
    private var omitDetails = false
    private val entered = CompletableDeferred<Unit>()

    @Before fun open() = runBlocking {
        OkHttp.initialize(RuntimeEnvironment.getApplication())
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context, OnlineCatalogDatabase::class.java).build()
        sessions = SessionRepository(ControlledSessionStore())
        sessions.commit(fixtureSession(), sessions.loginEpoch()) { accept -> accept() }
        val eapi = NeteaseEapi(HttpExchange { request ->
            if (fail) throw NetworkException(NetworkIssue.UNAVAILABLE)
            if (block) {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            when {
                request.url.endsWith("/eapi/search/get") -> {
                    val offset = catalogFields(request)["offset"].jsonLong()
                    offsets.add(offset)
                    val ids = (offset until minOf(searchCount, offset + 100L)).map { songBase + it }
                    catalogSearch(ids, searchCount)
                }
                request.url.endsWith("/eapi/v6/playlist/detail") -> catalogJson(
                    "{\"code\":200,\"playlist\":{\"id\":991,\"trackCount\":3,\"trackIds\":[{\"id\":201},{\"id\":202},{\"id\":201}],\"tracks\":[${catalogSong(201)}]}}")
                request.url.endsWith("/eapi/v3/song/detail") -> {
                    val ids = (StrictJson.decode(catalogFields(request)["c"].jsonString().toByteArray()) as JsonArray)
                        .values.map { it.jsonObject()["id"].jsonLong() }
                    val songs = ids.filter { !omitDetails }.joinToString(",") { catalogSong(it) }
                    catalogJson("{\"code\":200,\"songs\":[$songs]}")
                }
                request.url.endsWith("/eapi/v3/discovery/recommend/songs") -> {
                    val songs = (0 until dailyCount).joinToString(",") { "{\"id\":${5_000L + it}}" }
                    catalogJson("{\"code\":200,\"data\":{\"dailySongs\":[$songs]}}")
                }
                else -> error("Unexpected synthetic catalog endpoint")
            }
        })
        repository = OnlineCatalogRepository(sessions, NeteaseAuthApi(eapi), NeteaseCatalogApi(eapi), RoomOnlineCatalogCache(database), { now })
    }
    @After fun close() {
        sessions.close()
        database.close()
    }

    @Test fun aCompleteSearchConsumesRealOffsetsAndFreezesAllRowsWithoutReaderHttp() = runBlocking {
        val initial = repository.load(query)
        assertEquals(100L, initial.count)
        assertFalse(initial.complete)
        val complete = repository.finish(initial, query)
        assertEquals(listOf(0L, 100L, 200L), offsets.toList())
        assertEquals(205L, complete.count)
        assertTrue(complete.complete)
        val requests = offsets.size
        repository.freeze(complete).use { lease ->
            assertEquals(205L, lease.view.totalCount)
            val middle = lease.read(100L, 100)
            assertEquals(100, middle.entries.size)
            assertEquals("301", middle.entries.first().trackKey.trackId)
            assertTrue(middle.entries.all { it.location == null && it.accountId == "101" })
            assertEquals(5, lease.read(200L, 100).entries.size)
        }
        assertEquals(requests, offsets.size)
    }

    @Test fun aDailyListBeyondTenThousandIsDurableAndItsReaderStillUsesOneBoundedPage() = runBlocking {
        dailyCount = 10_501
        val view = repository.load(OnlineCatalogQuery(OnlineCatalogKind.DAILY))
        assertEquals(10_501L, view.count)
        assertTrue(view.complete)
        repository.freeze(view).use { lease ->
            val tail = lease.read(10_500L, 100)
            assertEquals(1, tail.entries.size)
            assertEquals(10_500L, tail.entries.single().originalOrder)
            assertEquals("15500", tail.entries.single().trackKey.trackId)
            assertNull(tail.entries.single().location)
        }
    }

    @Test fun cachedReadsRetainTheirTimeAndPerformNoNewProviderCall() = runBlocking {
        val initial = repository.load(query)
        val calls = offsets.size
        now = 9_000L
        val cached = repository.load(query)
        assertEquals(initial, cached)
        assertEquals(1_000L, cached.cachedAtEpochMs)
        assertEquals(calls, offsets.size)
    }

    @Test fun restoredAccountsReadCachedPagesOfflineWithoutBecomingVerifiedOrAuthorizingPlayback() = runBlocking {
        val retained = repository.finish(repository.load(query), query)
        val restored = SessionRepository(ControlledSessionStore(fixtureSession().stored))
        val calls = AtomicInteger()
        val offline = NeteaseEapi(HttpExchange {
            calls.incrementAndGet()
            throw NetworkException(NetworkIssue.UNAVAILABLE)
        })
        val reader = OnlineCatalogRepository(restored, NeteaseAuthApi(offline), NeteaseCatalogApi(offline), RoomOnlineCatalogCache(database))
        try {
            assertEquals(retained, reader.load(query))
            assertEquals(100, reader.page(retained, 100L).size)
            assertEquals(retained, reader.current(retained.id))
            assertEquals(0, calls.get())
            assertEquals(SessionValidity.RESTORED_UNVERIFIED, restored.snapshot.value.validity)
            assertEquals(OnlineCatalogIssue.OFFLINE, failure { reader.freeze(retained) })
            assertEquals(1, calls.get())
            assertEquals(SessionValidity.RESTORED_UNVERIFIED, restored.snapshot.value.validity)
            assertEquals(retained, reader.load(query))
            assertEquals(1, calls.get())
        } finally { restored.close() }
    }

    @Test fun aDifferentRestoredAccountCannotReadOrLocateTheEarlierAccountsCache() = runBlocking {
        val retained = repository.load(query)
        val restored = SessionRepository(ControlledSessionStore(fixtureSession("102").stored))
        val calls = AtomicInteger()
        val offline = NeteaseEapi(HttpExchange {
            calls.incrementAndGet()
            throw NetworkException(NetworkIssue.UNAVAILABLE)
        })
        val reader = OnlineCatalogRepository(restored, NeteaseAuthApi(offline), NeteaseCatalogApi(offline), RoomOnlineCatalogCache(database))
        try {
            assertEquals(OnlineCatalogIssue.STALE_VIEW, failure { reader.page(retained, 0L) })
            assertEquals(OnlineCatalogIssue.STALE_VIEW, failure { reader.current(retained.id) })
            assertEquals(0, calls.get())
            assertEquals(OnlineCatalogIssue.OFFLINE, failure { reader.load(query) })
            assertEquals(1, calls.get())
            assertEquals(100, database.catalog().rows(retained.id, 0L, 100).size)
        } finally { restored.close() }
    }

    @Test fun theLatestCompletedRefreshWinsWithRepeatedOrBackwardClockValues() = runBlocking {
        searchCount = 1L
        val original = repository.load(query)
        repository.freeze(original).use { lease ->
            songBase = 501L
            val repeated = repository.load(query, refresh = true)
            assertEquals(original.cachedAtEpochMs, repeated.cachedAtEpochMs)
            assertEquals(repeated, repository.load(query))
            assertEquals("501", repository.page(repeated, 0L).single().id)
            now = 900L
            songBase = 601L
            val backward = repository.load(query, refresh = true)
            assertEquals(900L, backward.cachedAtEpochMs)
            assertEquals(backward, repository.load(query))
            assertEquals("601", repository.page(backward, 0L).single().id)
            assertEquals("201", lease.read(0L, 1).entries.single().trackKey.trackId)
        }
    }

    @Test fun failedRefreshPreservesTheOldPublishedRowsTimeAndView() = runBlocking {
        val old = repository.finish(repository.load(query), query)
        fail = true
        now = 2_000L
        assertEquals(OnlineCatalogIssue.OFFLINE, failure { repository.load(query, refresh = true) })
        val retained = repository.load(query)
        assertEquals(old, retained)
        assertEquals(1L, database.catalog().viewCount())
        assertEquals(100, repository.page(retained, 0L).size)
    }

    @Test(timeout = 10_000) fun cancelledRefreshRemovesOnlyItsUnpublishedStage() = runBlocking {
        val old = repository.load(query)
        block = true
        val refresh = async { repository.load(query, refresh = true) }
        entered.await()
        refresh.cancelAndJoin()
        block = false
        assertEquals(old, repository.load(query))
        assertEquals(1L, database.catalog().viewCount())
        assertEquals(100, repository.page(old, 0L).size)
    }

    @Test fun accountScopesDoNotInheritAnotherAccountsCachedRows() = runBlocking {
        val first = repository.load(query)
        sessions.finishLogout(sessions.beginLogout())
        assertEquals(OnlineCatalogIssue.LOGIN_REQUIRED, failure { repository.load(query) })
        sessions.commit(fixtureSession("102"), sessions.loginEpoch()) { accept -> accept() }
        songBase = 501L
        val second = repository.load(query)
        assertTrue(first.id != second.id)
        assertEquals("501", repository.page(second, 0L).first().id)
        assertEquals(OnlineCatalogIssue.STALE_VIEW, failure { repository.page(first, 0L) })
    }

    @Test fun aLeaseSurvivesARefreshAndASecondLeaseCannotReplaceIt() = runBlocking {
        searchCount = 2L
        val old = repository.load(query)
        val lease = repository.freeze(old)
        try {
            assertEquals(OnlineCatalogIssue.BUSY, failure { repository.freeze(old) })
            songBase = 501L
            now = 2_000L
            val fresh = repository.load(query, refresh = true)
            assertEquals("201", lease.read(0L, 1).entries.single().trackKey.trackId)
            assertEquals("501", repository.page(fresh, 0L).first().id)
        } finally { lease.close() }
    }

    @Test fun duplicatePlaylistInstancesKeepDifferentQueueEntryIds() = runBlocking {
        val view = repository.load(OnlineCatalogQuery(OnlineCatalogKind.PLAYLIST, "991"))
        assertTrue(view.complete)
        repository.freeze(view).use { lease ->
            val entries = lease.read(0L, 3).entries
            assertEquals(listOf("201", "202", "201"), entries.map { it.trackKey.trackId })
            assertEquals(3, entries.map { it.queueEntryId }.distinct().size)
        }
    }

    @Test fun missingMetadataRetainsTheStableSongReferenceRatherThanTruncatingTheList() = runBlocking {
        omitDetails = true
        val view = repository.load(OnlineCatalogQuery(OnlineCatalogKind.PLAYLIST, "991"))
        assertEquals(3L, view.count)
        assertEquals(1L, view.missingMetadataCount)
        assertTrue(view.complete)
        repository.freeze(view).use { lease ->
            val missing = lease.read(1L, 1).entries.single()
            assertEquals("202", missing.trackKey.trackId)
            assertNull(missing.displayName)
            assertNull(missing.durationMs)
        }
    }

    @Test fun cacheEvictionIsBoundedAndNeverRemovesALeasedView() = runBlocking {
        searchCount = 1L
        val first = repository.load(query)
        repository.freeze(first).use { lease ->
            repeat(13) { index ->
                now += 1_000L
                repository.load(OnlineCatalogQuery(OnlineCatalogKind.SEARCH, "Fixture $index"))
            }
            assertTrue(database.catalog().viewCount() <= RoomOnlineCatalogCache.MAX_VIEWS)
            assertEquals("201", lease.read(0L, 1).entries.single().trackKey.trackId)
        }
    }

    @Test fun anAccountRevocationInvalidatesAPreparedLeaseWithoutEditingItsRows() = runBlocking {
        val view = repository.finish(repository.load(query), query)
        val lease = repository.freeze(view)
        try {
            sessions.finishLogout(sessions.beginLogout())
            assertEquals(OnlineCatalogIssue.LOGIN_REQUIRED, failure { lease.read(0L, 1) })
            assertEquals(100, database.catalog().rows(view.id, 0L, 100).size)
            assertEquals(5, database.catalog().rows(view.id, 200L, 100).size)
        } finally { lease.close() }
    }

    @Test(timeout = 10_000L) fun anIndexedCachedPageHydratesItsExactVisibleBatchWithoutCompletingASkippedPrefix() = runBlocking {
        val ids = (201L..405L).toList()
        val requests = CopyOnWriteArrayList<String>()
        val detailIds = CopyOnWriteArrayList<List<Long>>()
        val eapi = NeteaseEapi(HttpExchange { request ->
            requests.add(request.url.substringAfter("/eapi/"))
            when {
                request.url.endsWith("/eapi/song/like/get") -> {
                    assertEquals("101", catalogFields(request)["uid"].jsonString())
                    catalogJson("{\"code\":200,\"ids\":[${ids.joinToString(",")}]}")
                }
                request.url.endsWith("/eapi/v3/song/detail") -> {
                    val selected = (StrictJson.decode(catalogFields(request)["c"].jsonString().toByteArray()) as JsonArray)
                        .values.map { it.jsonObject()["id"].jsonLong() }
                    detailIds.add(selected)
                    catalogJson("{\"code\":200,\"songs\":[${selected.joinToString(",") { catalogSong(it) }}]}")
                }
                else -> error("Unexpected indexed page fixture endpoint")
            }
        })
        val catalog = OnlineCatalogRepository(sessions, NeteaseAuthApi(eapi), NeteaseCatalogApi(eapi), RoomOnlineCatalogCache(database))
        val liked = OnlineCatalogQuery(OnlineCatalogKind.LIKED)
        val initial = catalog.load(liked)
        assertEquals(205L, initial.count)
        assertEquals(100L, initial.metadataCount)
        assertFalse(initial.complete)
        assertEquals(100, catalog.page(initial, 0L).size)

        // Pure cache reads can browse ahead offline without starting a login or metadata request.
        val cachedLast = catalog.page(initial, 200L)
        assertEquals(listOf("401", "402", "403", "404", "405"), cachedLast.map { it.id })
        assertTrue(cachedLast.all { it.title == null && it.metadataMissing })
        assertEquals(listOf((201L..300L).toList()), detailIds.toList())

        val far = catalog.next(initial, liked, first = 200L)
        assertTrue(far.revision > initial.revision)
        assertEquals(100L, far.metadataCount)
        assertFalse(far.complete)
        assertEquals((401L..405L).map { "Fixture song $it" }, catalog.page(far, 200L).map { it.title })

        val second = catalog.next(far, liked, first = 100L)
        assertTrue(second.revision > far.revision)
        assertEquals(205L, second.count)
        assertEquals(200L, second.metadataCount)
        assertFalse(second.complete)
        val secondPage = catalog.page(second, 100L)
        assertEquals(100, secondPage.size)
        assertEquals((301L..400L).map { "Fixture song $it" }, secondPage.map { it.title })
        assertTrue(secondPage.none { it.metadataMissing })
        assertEquals(OnlineCatalogIssue.STALE_VIEW, failure { catalog.page(initial, 100L) })

        val complete = catalog.next(second, liked, first = 200L)
        assertTrue(complete.complete)
        assertEquals(205L, complete.metadataCount)
        assertEquals(0L, complete.missingMetadataCount)
        assertEquals(listOf((201L..300L).toList(), (401L..405L).toList(), (301L..400L).toList()), detailIds.toList())
        assertEquals(listOf("song/like/get", "v3/song/detail", "v3/song/detail", "v3/song/detail"), requests.toList())
    }

    @Test(timeout = 30_000L) fun tenThousandAndOneIndexedInstancesFreezeWithoutAnyFurtherMetadataHttp() = runBlocking {
        val ids = (0 until 10_001).map { index -> if (index == 5_000 || index == 10_000) 201L else 201L + index }
        val requests = CopyOnWriteArrayList<String>()
        val detailIds = CopyOnWriteArrayList<List<Long>>()
        val eapi = NeteaseEapi(HttpExchange { request ->
            requests.add(request.url.substringAfter("/eapi/"))
            when {
                request.url.endsWith("/eapi/v6/playlist/detail") -> {
                    assertEquals("991", catalogFields(request)["id"].jsonString())
                    val index = ids.joinToString(",") { "{\"id\":$it}" }
                    catalogJson("{\"code\":200,\"playlist\":{\"id\":991,\"trackCount\":10001,\"trackIds\":[$index],\"tracks\":[]}}")
                }
                request.url.endsWith("/eapi/v3/song/detail") -> {
                    val selected = (StrictJson.decode(catalogFields(request)["c"].jsonString().toByteArray()) as JsonArray)
                        .values.map { it.jsonObject()["id"].jsonLong() }
                    detailIds.add(selected)
                    catalogJson("{\"code\":200,\"songs\":[${selected.joinToString(",") { catalogSong(it) }}]}")
                }
                else -> error("Unexpected complete index fixture endpoint")
            }
        })
        val catalog = OnlineCatalogRepository(sessions, NeteaseAuthApi(eapi), NeteaseCatalogApi(eapi), RoomOnlineCatalogCache(database))
        val playlist = OnlineCatalogQuery(OnlineCatalogKind.PLAYLIST, "991")
        val initial = catalog.load(playlist)
        assertEquals(10_001L, initial.count)
        assertEquals(100L, initial.metadataCount)
        assertFalse(initial.complete)
        assertEquals(OnlineCatalogIssue.STALE_VIEW, failure { catalog.freeze(initial.copy(count = 10_000L)) })

        val queueView = catalog.finish(initial, playlist)
        assertEquals(initial, queueView)
        assertFalse(queueView.complete)
        catalog.freeze(queueView).use { lease ->
            assertEquals(10_001L, lease.view.totalCount)
            assertEquals(OnlineCatalogIssue.BUSY, failure { catalog.next(queueView, playlist, first = 100L) })
            val entryIds = mutableSetOf<String>()
            var offset = 0L
            while (offset < lease.view.totalCount) {
                val page = lease.read(offset, 100)
                assertEquals(lease.view, page.view)
                assertEquals(ids.subList(offset.toInt(), minOf(ids.size, offset.toInt() + 100)).map { it.toString() },
                    page.entries.map { it.trackKey.trackId })
                page.entries.forEachIndexed { index, entry ->
                    assertEquals(offset + index, entry.originalOrder)
                    assertEquals("101", entry.accountId)
                    assertNull(entry.location)
                    assertTrue(entryIds.add(entry.queueEntryId))
                }
                offset += page.entries.size
            }
            assertEquals(10_001L, offset)
            assertEquals(10_001, entryIds.size)
            val repeated = listOf(0L, 5_000L, 10_000L).map { lease.read(it, 1).entries.single() }
            assertEquals(listOf("201", "201", "201"), repeated.map { it.trackKey.trackId })
            assertEquals(3, repeated.map { it.queueEntryId }.distinct().size)
        }
        assertEquals(initial, catalog.current(initial.id))
        assertEquals(listOf((201L..300L).toList()), detailIds.toList())
        assertEquals(listOf("v6/playlist/detail", "v3/song/detail"), requests.toList())
    }

    private suspend fun failure(action: suspend () -> Unit): OnlineCatalogIssue? = try {
        action()
        null
    } catch (error: OnlineCatalogException) { error.issue }
}
