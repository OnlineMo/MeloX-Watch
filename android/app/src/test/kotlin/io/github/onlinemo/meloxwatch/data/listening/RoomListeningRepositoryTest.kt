package io.github.onlinemo.meloxwatch.data.listening

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.data.library.LibrarySort
import io.github.onlinemo.meloxwatch.data.library.LibrarySortField
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Database event fixtures validate persistence, not the device's actual audible intervals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomListeningRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository
    private lateinit var listening: RoomListeningRepository
    private val epoch = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()

    @Before fun openDatabase() = runBlocking {
        file = File(temporaryFolder.root, "listening.db")
        database = open()
        library = RoomLocalLibraryRepository(database)
        listening = RoomListeningRepository(library)
        assertEquals(ListeningEpochs(), listening.state())
    }
    @After fun closeDatabase() { database.close() }

    @Test(timeout = 30000L)
    fun actualPositiveTotalsCreateHistoryAndCrossTheThresholdOnceAcrossReopenAndReplay() = runBlocking {
        val key = seedTrack()
        val first = update(key, total = 18_000L, duration = 40_000L)
        val firstCommit = listening.submit(first)
        assertTrue(firstCommit.historyRecorded)
        assertFalse(firstCommit.playCounted)
        assertFalse(firstCommit.wasReplay)
        assertEquals(18_000L, listening.summary().totalListeningMs)
        val second = advance(first, 22_000L)
        assertTrue(listening.submit(second).playCounted)
        val replay = listening.submit(second)
        assertTrue(replay.wasReplay)
        assertEquals(22_000L, replay.acceptedListeningMs)
        assertEquals(1L, listening.summary().totalPlayCount)
        assertEquals(1L, listening.summary().historyCount)
        assertEquals(22_000L, listening.historyPage().items.single().listeningMs)
        assertEquals("Actual local title", listening.trackSummary(key).displayName)
        reopen()
        val older = listening.submit(first)
        assertTrue(older.wasReplay)
        assertEquals(22_000L, older.acceptedListeningMs)
        assertEquals(1L, listening.trackSummary(key).playCount)
        assertEquals(22_000L, listening.trackSummary(key).listeningMs)
    }

    @Test(timeout = 30000L)
    fun unknownDurationUsesThirtySecondsAndLateShortDurationUsesTheRealSampleDay() = runBlocking {
        val key = TrackKey.newLocal()
        val unknown = update(key, 29_999L)
        assertFalse(listening.submit(unknown).playCounted)
        assertTrue(listening.submit(advance(unknown, 30_000L)).playCounted)

        val short = update(key, 12_000L)
        assertFalse(listening.submit(short).playCounted)
        val nextDay = Instant.parse("2026-11-16T02:00:00Z").toEpochMilli()
        val learned = short.copy(knownDurationMs = 20_000L, sampledEpochMs = nextDay,
            sampledElapsedMs = short.sampledElapsedMs + 5_000L, daySegments = emptyList())
        assertTrue(listening.submit(learned).playCounted)
        assertTrue(listening.submit(learned).wasReplay)
        assertEquals(42_000L, listening.summary().totalListeningMs)
        assertEquals(2L, listening.summary().totalPlayCount)
        val days = listening.dayPage().items.associateBy { it.localDate }
        assertEquals(1L, days.getValue("2026-11-15").playCount)
        assertEquals(1L, days.getValue("2026-11-16").playCount)
        assertEquals(0L, days.getValue("2026-11-16").listeningMs)
        val history = listening.historyPage().items.first { it.roundId == short.roundId }
        assertEquals(short.sampledEpochMs, history.lastProgressEpochMs)
        assertEquals(20_000L, history.knownDurationMs)
    }

    @Test(timeout = 30000L)
    fun dateAndTimezoneChangesKeepMonotonicTimeAndCountAtTheCrossingSegment() = runBlocking {
        val key = TrackKey.newLocal()
        val first = update(key, 20_000L)
        listening.submit(first)
        val changedClock = first.copy(totalListeningMs = 35_000L, sampledElapsedMs = first.sampledElapsedMs + 15_000L,
            sampledEpochMs = Instant.parse("2024-02-01T01:00:00Z").toEpochMilli(), sampledZoneId = "Asia/Shanghai",
            daySegments = listOf(ListeningDayAccumulation(1L, "2024-02-01", "Asia/Shanghai", 15_000L)))
        listening.submit(changedClock)
        val days = listening.dayPage().items.associateBy { it.localDate to it.zoneId }
        assertEquals(20_000L, days.getValue("2026-11-15" to "UTC").listeningMs)
        assertEquals(0L, days.getValue("2026-11-15" to "UTC").playCount)
        assertEquals(15_000L, days.getValue("2024-02-01" to "Asia/Shanghai").listeningMs)
        assertEquals(1L, days.getValue("2024-02-01" to "Asia/Shanghai").playCount)
        assertEquals(35_000L, listening.summary().totalListeningMs)
        assertEquals(changedClock.sampledEpochMs, listening.historyPage().items.single().lastProgressEpochMs)
        assertEquals(first.firstProgressEpochMs, listening.historyPage().items.single().firstProgressEpochMs)
    }

    @Test(timeout = 30000L)
    fun todaySumsSavedSameDateAcrossZonesAndClearSwitchesItsEpochWithoutRewritingSegments() = runBlocking {
        val key = TrackKey.newLocal()
        val first = update(key, 10_000L, 40_000L)
        listening.submit(first)
        val changedZone = first.copy(totalListeningMs = 25_000L, sampledElapsedMs = first.sampledElapsedMs + 15_000L,
            sampledEpochMs = first.sampledEpochMs + 15_000L, sampledZoneId = "Asia/Shanghai",
            daySegments = listOf(ListeningDayAccumulation(1L, "2026-11-15", "Asia/Shanghai", 15_000L)))
        listening.submit(changedZone)
        listening.submit(update(key, 30_000L))
        val otherDay = update(key, 30_000L).copy(
            firstProgressEpochMs = Instant.parse("2026-11-14T12:00:00Z").toEpochMilli(),
            sampledEpochMs = Instant.parse("2026-11-14T12:00:30Z").toEpochMilli(),
            daySegments = listOf(ListeningDayAccumulation(0L, "2026-11-14", "UTC", 30_000L)))
        listening.submit(otherDay)
        val savedDays = listening.dayPage().items
        assertEquals(ListeningTodaySummary("2026-11-15", "Asia/Shanghai", 2L, 55_000L),
            listening.today("2026-11-15", "Asia/Shanghai"))
        assertEquals(ListeningTodaySummary("2026-11-15", "America/Los_Angeles", 2L, 55_000L),
            listening.today("2026-11-15", "America/Los_Angeles"))
        assertEquals(ListeningTodaySummary("2026-11-14", "UTC", 1L, 30_000L), listening.today("2026-11-14", "UTC"))
        assertEquals(ListeningTodaySummary("2026-11-16", "UTC", 0L, 0L), listening.today("2026-11-16", "UTC"))
        assertEquals(savedDays, listening.dayPage().items)
        assertEquals(3, savedDays.size)
        assertEquals(3L, listening.summary().totalPlayCount)
        assertEquals(85_000L, listening.summary().totalListeningMs)
        reopen()
        assertEquals(55_000L, listening.today("2026-11-15", "UTC").listeningMs)
        val cleared = listening.clearStatistics(0L)
        assertEquals(ListeningTodaySummary("2026-11-15", "Asia/Shanghai", 0L, 0L, cleared.statisticsEpoch),
            listening.today("2026-11-15", "Asia/Shanghai"))
        assertEquals(3L, listening.summary().historyCount)
        listening.submit(update(key, 30_000L, epochs = cleared))
        assertEquals(ListeningTodaySummary("2026-11-15", "UTC", 1L, 30_000L, cleared.statisticsEpoch),
            listening.today("2026-11-15", "UTC"))
    }

    @Test(timeout = 30000L)
    fun todayReturnsItsActualPersistedEpochAcrossClearReplayAndReopen() = runBlocking {
        val key = TrackKey.newLocal()
        val original = update(key, 30_000L)
        listening.submit(original)
        val saved = listening.today("2026-11-15", "UTC")
        assertEquals(ListeningTodaySummary("2026-11-15", "UTC", 1L, 30_000L), saved)
        val firstClear = listening.clearStatistics(saved.statisticsEpoch)
        val empty = ListeningTodaySummary("2026-11-15", "UTC", 0L, 0L, firstClear.statisticsEpoch)
        assertEquals(empty, listening.today("2026-11-15", "UTC"))
        assertEquals(0L, saved.statisticsEpoch)
        assertEquals(30_000L, saved.listeningMs)
        reopen()
        assertEquals(empty, listening.today("2026-11-15", "UTC"))
        val historyClear = listening.clearHistory(firstClear.historyEpoch)
        listening.submit(original)
        assertEquals(empty, listening.today("2026-11-15", "UTC"))
        listening.submit(update(key, 30_000L, epochs = historyClear))
        val fresh = ListeningTodaySummary("2026-11-15", "UTC", 1L, 30_000L, firstClear.statisticsEpoch)
        assertEquals(fresh, listening.today("2026-11-15", "UTC"))
        reopen()
        assertEquals(fresh, listening.today("2026-11-15", "UTC"))
        val secondClear = listening.clearStatistics(fresh.statisticsEpoch)
        val cleared = fresh.copy(playCount = 0L, listeningMs = 0L, statisticsEpoch = secondClear.statisticsEpoch)
        assertEquals(cleared, listening.today("2026-11-15", "UTC"))
        assertEquals(cleared.copy(localDate = "2026-11-16"), listening.today("2026-11-16", "UTC"))
        reopen()
        assertEquals(cleared, listening.today("2026-11-15", "UTC"))
    }

    @Test(timeout = 30000L)
    fun todayRejectsInvalidOrNoncanonicalDateAndZoneAsTypedErrors() = runBlocking {
        for (date in listOf("", "2026-11-5", "2026-02-30", "2026-11-15 ", "1".repeat(17))) {
            expectIssue(ListeningIssue.INVALID_INPUT) { listening.today(date, "UTC") }
        }
        for (zone in listOf("", " UTC", "Not/AZone", "+8", "x".repeat(257))) {
            expectIssue(ListeningIssue.INVALID_INPUT) { listening.today("2026-11-15", zone) }
        }
        assertEquals(ListeningTodaySummary("2026-11-15", "+08:00", 0L, 0L), listening.today("2026-11-15", "+08:00"))
        assertEquals(0L, listening.summary().historyCount)
        assertEquals(0L, listening.summary().totalListeningMs)
    }

    @Test(timeout = 30000L)
    fun historyAndStatisticsClearIndependentlyAndOriginalRoundEpochsCannotRestoreThem() = runBlocking {
        val key = seedTrack()
        val original = update(key, 10_000L, 20_000L)
        listening.submit(original)
        val historyCleared = listening.clearHistory(0L)
        assertEquals(1L, historyCleared.historyEpoch)
        assertEquals(0L, historyCleared.statisticsEpoch)
        listening.submit(advance(original, 15_000L))
        assertEquals(0L, listening.summary().historyCount)
        assertEquals(15_000L, listening.summary().totalListeningMs)
        assertEquals(1L, listening.summary().totalPlayCount)
        val afterHistory = update(key, 10_000L, 20_000L, epochs = historyCleared)
        listening.submit(afterHistory)
        assertEquals(1L, listening.summary().historyCount)

        val statisticsCleared = listening.clearStatistics(0L)
        assertEquals(1L, statisticsCleared.statisticsEpoch)
        assertEquals(0L, listening.summary().totalPlayCount)
        assertEquals(0L, listening.summary().totalListeningMs)
        listening.submit(advance(afterHistory, 15_000L))
        listening.submit(advance(original, 20_000L))
        assertEquals(1L, listening.summary().historyCount)
        assertEquals(15_000L, listening.historyPage().items.single().listeningMs)
        assertEquals(0L, listening.summary().totalListeningMs)
        val fresh = update(key, 30_000L, epochs = statisticsCleared)
        listening.submit(fresh)
        assertEquals(1L, listening.summary().totalPlayCount)
        assertEquals(30_000L, listening.summary().totalListeningMs)
        assertEquals(2L, listening.summary().historyCount)
        assertEquals(1L, library.overview().visibleTracks)
        expectIssue(ListeningIssue.CONFLICT) { listening.clearHistory(0L) }
        expectIssue(ListeningIssue.CONFLICT) { listening.clearStatistics(0L) }
        expectIssue(ListeningIssue.CONFLICT) { listening.submit(advance(original, 25_000L).copy(epochs = statisticsCleared)) }
    }

    @Test(timeout = 30000L)
    fun clearBeforeTheFirstFlushStillPersistsOriginalEpochBindingAcrossReopen() = runBlocking {
        val key = seedTrack()
        val pending = update(key, 30_000L)
        listening.clearHistory(0L)
        val cleared = listening.clearStatistics(0L)
        val first = listening.submit(pending)
        assertEquals(30_000L, first.acceptedListeningMs)
        assertFalse(first.historyRecorded)
        assertFalse(first.playCounted)
        assertEquals(0L, listening.summary().historyCount)
        assertEquals(0L, listening.summary().totalPlayCount)
        assertEquals(0L, listening.summary().totalListeningMs)
        assertEquals(0L, database.listeningDao().round(pending.roundId)?.historyEpoch)
        assertEquals(0L, database.listeningDao().round(pending.roundId)?.statisticsEpoch)
        reopen()
        assertTrue(listening.submit(pending).wasReplay)
        val later = listening.submit(advance(pending, 35_000L))
        assertEquals(35_000L, later.acceptedListeningMs)
        assertFalse(later.historyRecorded)
        assertFalse(later.playCounted)
        expectIssue(ListeningIssue.CONFLICT) { listening.submit(advance(pending, 40_000L).copy(epochs = cleared)) }
        assertEquals(0L, listening.summary().historyCount)
        assertEquals(0L, listening.summary().totalPlayCount)
        assertEquals(0L, listening.summary().totalListeningMs)
        listening.submit(update(key, 30_000L, epochs = cleared))
        assertEquals(1L, listening.summary().historyCount)
        assertEquals(1L, listening.summary().totalPlayCount)
        assertEquals(30_000L, listening.summary().totalListeningMs)
    }

    @Test(timeout = 30000L)
    fun clearStatisticsResetsNewLibraryViewsAtomicallyAndPreservesThePinnedOldSort() = runBlocking {
        val key = seedTrack()
        val before = library.openView(sort = LibrarySort(LibrarySortField.LISTENING_TIME, true))
        listening.submit(update(key, 30_000L))
        val counted = library.openView(sort = LibrarySort(LibrarySortField.PLAY_COUNT, true))
        assertEquals(0L, library.page(before).tracks.single().listeningMs)
        assertEquals(30_000L, library.page(counted).tracks.single().listeningMs)
        assertEquals(1L, library.page(counted).tracks.single().playCount)
        listening.clearStatistics(0L)
        val cleared = library.openView(sort = LibrarySort(LibrarySortField.PLAY_COUNT, true))
        assertEquals(0L, library.page(cleared).tracks.single().listeningMs)
        assertEquals(0L, library.page(cleared).tracks.single().playCount)
        assertEquals(1L, library.page(counted).tracks.single().playCount)
        assertEquals(30_000L, library.page(counted).tracks.single().listeningMs)
        assertEquals(0L, library.page(before).tracks.single().listeningMs)
        assertEquals(1L, listening.summary().historyCount)
        for (view in listOf(before, counted, cleared)) library.closeView(view.viewId)
    }

    @Test(timeout = 30000L)
    fun immutableIdentitySegmentsAndElapsedBoundsRejectInventedOrRewrittenProgress() = runBlocking {
        val first = update(TrackKey.newLocal(), 1_000L)
        listening.submit(first)
        expectIssue(ListeningIssue.INVALID_INPUT) {
            listening.submit(first.copy(totalListeningMs = 2_001L, sampledElapsedMs = first.sampledElapsedMs + 1_000L,
                daySegments = listOf(ListeningDayAccumulation(0L, "2026-11-15", "UTC", 2_001L))))
        }
        expectIssue(ListeningIssue.INVALID_INPUT) { listening.submit(advance(first, 2_000L).copy(daySegments = emptyList())) }
        expectIssue(ListeningIssue.CONFLICT) { listening.submit(advance(first, 2_000L).copy(trackKey = TrackKey.newLocal())) }
        expectIssue(ListeningIssue.CONFLICT) {
            listening.submit(advance(first, 2_000L).copy(daySegments = listOf(ListeningDayAccumulation(0L, "2026-11-16", "UTC", 2_000L))))
        }
        expectIssue(ListeningIssue.INVALID_INPUT) {
            listening.submit(advance(first, 2_000L).copy(daySegments = listOf(ListeningDayAccumulation(2L, "2026-11-16", "UTC", 1_000L))))
        }
        val next = first.copy(totalListeningMs = 2_000L, sampledElapsedMs = 3_000L,
            daySegments = listOf(ListeningDayAccumulation(1L, "2026-11-16", "UTC", 1_000L)))
        listening.submit(next)
        expectIssue(ListeningIssue.CONFLICT) {
            listening.submit(next.copy(totalListeningMs = 2_700L, sampledElapsedMs = 4_000L, daySegments = listOf(
                ListeningDayAccumulation(0L, "2026-11-15", "UTC", 1_500L),
                ListeningDayAccumulation(1L, "2026-11-16", "UTC", 1_200L))))
        }
        assertEquals(2_000L, listening.summary().totalListeningMs)
        expectIssue(ListeningIssue.SOURCE_CHANGED) { listening.submit(update(TrackKey.newLocal(), 1L, epochs = ListeningEpochs(statisticsEpoch = 2L))) }
    }

    @Test(timeout = 120000L)
    fun historyKeeps1000DetailsWhileIndependentTotalsAndRoundDeduplicationKeepAll1001() = runBlocking {
        val key = TrackKey.newLocal()
        val first = update(key, 1L, 1L)
        listening.submit(first)
        for (index in 1..1000) listening.submit(update(key, 1L, 1L).copy(firstProgressEpochMs = epoch + index,
            sampledEpochMs = epoch + index + 1L))
        assertEquals(1000L, listening.summary().historyCount)
        assertEquals(1001L, listening.summary().totalPlayCount)
        assertEquals(1001L, listening.summary().totalListeningMs)
        val items = ArrayList<ListeningHistoryItem>()
        var cursor: ListeningHistoryCursor? = null
        do {
            val page = listening.historyPage(cursor)
            assertTrue(page.items.size <= ListeningLimits.PAGE_SIZE)
            items += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(1000, items.size)
        assertEquals(1000, items.map { it.roundId }.distinct().size)
        assertFalse(items.any { it.roundId == first.roundId })
        val old = listening.submit(first)
        assertTrue(old.wasReplay)
        assertFalse(old.historyRecorded)
        assertTrue(old.playCounted)
        assertEquals(1001L, listening.trackSummary(key).playCount)
        reopen()
        assertTrue(listening.submit(first).wasReplay)
        assertEquals(1000L, listening.summary().historyCount)
        assertEquals(1001L, listening.summary().totalPlayCount)
    }

    @Test(timeout = 30000L)
    fun trackAndDayPagesUseRealKeysetsAndClearInvalidatesTheirCursors() = runBlocking {
        val date = LocalDate.parse("2026-11-15")
        for (index in 0 until 105) {
            val day = date.minusDays(index.toLong())
            val time = Instant.parse("${day}T12:00:00Z").toEpochMilli()
            val update = update(TrackKey.newLocal(), 30_000L).copy(displayName = "Saved title $index",
                firstProgressEpochMs = time, sampledEpochMs = time + 30_000L,
                daySegments = listOf(ListeningDayAccumulation(0L, day.toString(), "UTC", 30_000L)))
            listening.submit(update)
        }
        val tracks = listening.trackPage()
        val remainingTracks = listening.trackPage(tracks.nextCursor)
        assertEquals(100, tracks.items.size)
        assertEquals(5, remainingTracks.items.size)
        assertNull(remainingTracks.nextCursor)
        assertEquals(105, (tracks.items + remainingTracks.items).map { it.trackKey }.distinct().size)
        assertTrue(tracks.items.all { it.displayName?.startsWith("Saved title ") == true })
        val days = listening.dayPage()
        val remainingDays = listening.dayPage(days.nextCursor)
        assertEquals(100, days.items.size)
        assertEquals(5, remainingDays.items.size)
        assertNull(remainingDays.nextCursor)
        listening.clearStatistics(0L)
        expectIssue(ListeningIssue.SOURCE_CHANGED) { listening.trackPage(tracks.nextCursor) }
        expectIssue(ListeningIssue.SOURCE_CHANGED) { listening.dayPage(days.nextCursor) }
        expectIssue(ListeningIssue.INVALID_INPUT) { listening.historyPage(limit = 101) }
        expectIssue(ListeningIssue.INVALID_INPUT) { listening.trackPage(limit = 0) }
    }

    @Test(timeout = 30000L)
    fun sqlFailureRollsBackRoundHistoryDayTrackAndLibraryProjectionTogether() = runBlocking {
        val key = seedTrack()
        database.withTestWriterTransaction {
            execute("CREATE TRIGGER reject_listening_track BEFORE INSERT ON listening_track_totals BEGIN SELECT RAISE(ABORT, 'fixture rejection'); END")
        }
        val update = update(key, 30_000L)
        expectIssue(ListeningIssue.INVALID_INPUT) { listening.submit(update) }
        assertEquals(0L, listening.summary().historyCount)
        assertEquals(0L, listening.summary().totalListeningMs)
        assertNull(database.listeningDao().round(update.roundId))
        assertTrue(listening.dayPage().items.isEmpty())
        val before = library.openView()
        assertEquals(0L, library.page(before).tracks.single().playCount)
        library.closeView(before.viewId)
        database.withTestWriterTransaction { execute("DROP TRIGGER reject_listening_track") }
        listening.submit(update)
        assertEquals(1L, listening.summary().totalPlayCount)
        assertEquals(1L, listening.summary().historyCount)
        assertEquals(30_000L, listening.summary().totalListeningMs)
    }

    @Test(timeout = 30000L)
    fun sqlFailureDuringClearRollsBackBothEpochsAndCompareAndSetAllowsOnlyOneConfirmation() = runBlocking {
        val key = seedTrack()
        listening.submit(update(key, 30_000L))
        val before = listening.summary()
        val revision = library.overview().revision
        database.withTestWriterTransaction {
            execute("CREATE TRIGGER reject_statistics_clear BEFORE UPDATE ON listening_meta WHEN NEW.statisticsEpoch != OLD.statisticsEpoch BEGIN SELECT RAISE(ABORT, 'fixture rejection'); END")
        }
        expectIssue(ListeningIssue.INVALID_INPUT) { listening.clearStatistics(0L) }
        assertEquals(before, listening.summary())
        assertEquals(revision, library.overview().revision)
        assertEquals(0L, database.libraryDao().meta()?.statisticsEpoch)
        database.withTestWriterTransaction { execute("DROP TRIGGER reject_statistics_clear") }
        val one = async(Dispatchers.Default) { runCatching { listening.clearStatistics(0L) } }
        val two = async(Dispatchers.Default) { runCatching { listening.clearStatistics(0L) } }
        val outcomes = listOf(one.await(), two.await())
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(ListeningIssue.CONFLICT, (outcomes.single { it.isFailure }.exceptionOrNull() as ListeningException).issue)
        assertEquals(1L, listening.state().statisticsEpoch)
        assertEquals(1L, listening.summary().historyCount)
    }

    @Test(timeout = 30000L)
    fun summaryFlowInitializesLocallyAndSavedTitlesRemainReachableWithoutLibraryMembership() = runBlocking {
        val key = TrackKey.newLocal()
        assertEquals(0L, listening.observeSummary().first().totalListeningMs)
        val next = async { withTimeout(10000L) { listening.observeSummary().first { it.totalPlayCount == 1L } } }
        val update = update(key, 30_000L).copy(displayName = "Saved original title")
        listening.submit(update)
        assertEquals(30_000L, next.await().totalListeningMs)
        assertEquals("Saved original title", listening.trackPage().items.single().displayName)
        assertEquals("Saved original title", listening.historyPage().items.single().displayName)
        assertEquals(0L, library.overview().totalTracks)
        assertNull(database.queueDao().head())
    }

    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context,
        MeloXDatabase::class.java, file.absolutePath).setDriver(BundledSQLiteDriver())
        .addMigrations(MeloXDatabase.MIGRATION_1_2).build()
    private suspend fun reopen() {
        database.close()
        database = open()
        library = RoomLocalLibraryRepository(database)
        listening = RoomListeningRepository(library)
        listening.state()
    }
    private suspend fun seedTrack(): TrackKey {
        val source = library.registerSource(LocalSourceSpec("listen-source", LocalSourceKind.DOCUMENT,
            "content://listening-fixture/document/object", authority = "listening-fixture", rootObjectId = "object"),
            LocalGrantObservation(read = ObservedCapability.AVAILABLE))
        val token = library.beginScan(source.spec.sourceId, source.authEpoch, library.rules().revision, ScanMode.FULL)
        library.commitSeen(token, listOf(ObservedCandidate(source.spec.sourceId, "object", source.spec.uri, null,
            "Actual local file.mp3", "audio/mpeg", LocalNodeKind.AUDIO, providerContentToken = "native:1")))
        library.commitMetadata(token, listOf(MetadataOutcome("object", LocalAudioMetadata(title = "Actual local title"))))
        val coverage = ScanCoverage(false, true, true, true, false)
        library.setCoverage(token, coverage)
        library.finishScan(token, ScanCounters(), coverage)
        val view = library.openView()
        try { return library.page(view).tracks.single().trackKey } finally { library.closeView(view.viewId) }
    }
    private fun update(key: TrackKey, total: Long, duration: Long? = null, epochs: ListeningEpochs = ListeningEpochs()) =
        ListeningRoundUpdate(UUID.randomUUID().toString(), key, "Original event title", epochs,
            epoch, 1000L, "UTC", epoch + total, 1000L + total, "UTC", total, duration,
            listOf(ListeningDayAccumulation(0L, "2026-11-15", "UTC", total)))
    private fun advance(update: ListeningRoundUpdate, total: Long) = update.copy(totalListeningMs = total,
        sampledElapsedMs = update.firstProgressElapsedMs + total, sampledEpochMs = update.firstProgressEpochMs + total,
        daySegments = listOf(ListeningDayAccumulation(0L, "2026-11-15", "UTC", total)))
    private suspend fun expectIssue(issue: ListeningIssue, action: suspend () -> Unit) {
        try { action() } catch (error: ListeningException) { assertEquals(issue, error.issue); return }
        throw AssertionError("expected $issue")
    }
}
