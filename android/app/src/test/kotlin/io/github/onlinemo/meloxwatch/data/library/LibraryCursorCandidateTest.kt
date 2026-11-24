package io.github.onlinemo.meloxwatch.data.library

import android.app.Application
import android.content.Context
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.transfer.CompanionRoomFixture
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

/** 原查询作为 native 行为对照；这些定义不代表性能或设备验收已经通过。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LibraryCursorCandidateTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: CompanionRoomFixture

    @Before fun open() = runBlocking(Dispatchers.IO) {
        fixture = CompanionRoomFixture(RuntimeEnvironment.getApplication() as Context,
            File(temporary.root, "cursor-candidate-room.db"))
        fixture.initialize()
    }

    @After fun close() {
        fixture.close()
    }

    @Test(timeout = 30_000L)
    fun nativeWindowsAndExistenceMatchFullRowsAcrossSortsRanksAndBothDirections() = runBlocking(Dispatchers.IO) {
        seedPartitions()
        for (field in LibrarySortField.entries) {
            for (descending in listOf(false, true)) {
                val view = fixture.library.openView(sort = LibrarySort(field, descending),
                    owner = LibraryViewOwner.TRANSFER)
                try {
                    val all = fullRows(view)
                    assertEquals(6, all.size)
                    assertEquals(3, all.count { it.missingRank == 0 })
                    assertEquals(3, all.count { it.missingRank == 1 })
                    assertEquals(0L, all.single { it.track.displayTitle == "N" }.effectivePlayCount)
                    assertEquals(0L, all.single { it.track.displayTitle == "C" }.effectiveListeningMs)
                    assertEquals(30L, all.single { it.track.displayTitle == "M" }.effectivePlayCount)
                    assertWindow(view, null)
                    for (row in all) {
                        val cursor = row.cursor(view)
                        assertWindow(view, cursor)
                        assertWindow(view, cursor, before = true)
                    }
                } finally {
                    fixture.library.closeView(view.viewId)
                }
            }
        }
    }

    @Test(timeout = 30_000L)
    fun unavailableTitlesBeforeRawBoundaryKeepOriginalExistenceAndCompanionPages() = runBlocking(Dispatchers.IO) {
        seedPartitions()
        val view = fixture.library.openView(owner = LibraryViewOwner.TRANSFER)
        try {
            val all = fullRows(view)
            assertEquals(listOf("M", "N", "O", "A", "B", "C"), all.map { it.track.displayTitle })
            val lastAvailable = all.last { it.missingRank == 0 }.cursor(view)
            fixture.library.transferTransaction(view) {
                val candidate = checkNotNull(LibrarySql.rowsForTrackCursor(view, lastAvailable, 2))
                assertTrue(fixture.library.dao.rows(candidate).isEmpty())
                val exists = checkNotNull(LibrarySql.hasBeyondForTrackCursor(view, lastAvailable, before = false))
                assertEquals(0L, fixture.library.dao.scalar(exists))
                assertEquals(1L, fixture.library.dao.scalar(LibrarySql.hasBeyond(view, lastAvailable, before = false)))
                assertTrue(fixture.library.hasRowsBeyondUnlocked(view, lastAvailable, before = false))
                assertEquals(listOf("A", "B"), fixture.library.readOrderedRowsUnlocked(view, lastAvailable, 2)
                    .map { it.track.displayTitle })
            }
            assertWindow(view, lastAvailable)
            try {
                val first = fixture.firstPage(limit = 2)
                val second = fixture.nextPage(first, limit = 2)
                val third = fixture.nextPage(second, limit = 2)
                assertEquals(listOf("M", "N"), first.rows.map { (it as TransferCatalogRow.Track).title })
                assertEquals(listOf("O", "A"), second.rows.map { (it as TransferCatalogRow.Track).title })
                assertEquals(listOf("B", "C"), third.rows.map { (it as TransferCatalogRow.Track).title })
                assertEquals(all.map { it.track.trackId }, (first.rows + second.rows + third.rows)
                    .map { (it as TransferCatalogRow.Track).trackId })
                assertNull(third.nextCursor)
            } finally {
                fixture.backend.closePeerViews(fixture.peer, fixture.scope.sessionId)
            }
        } finally {
            fixture.library.closeView(view.viewId)
        }
    }

    @Test(timeout = 30_000L)
    fun candidateTrackIdsKeepEveryValidVersionForPrimaryArtistAndSearch() = runBlocking(Dispatchers.IO) {
        val source = fixture.source("cursor-versions")
        fixture.append(source, listOf(
            "version-original" to LocalAudioMetadata(title = "A", artists = listOf("Alpha")),
            "version-middle" to LocalAudioMetadata(title = "M", artists = listOf("Alpha")),
            "version-tail" to LocalAudioMetadata(title = "Y", artists = listOf("Beta")),
        ))
        // 正常搜索路径先注册原艺人组；单个 raw displayName 不等同搜索映射。
        fixture.group(TransferDisplayKind.ARTIST, "Alpha")
        val original = fixture.tracks().single { it.title == "A" }
        val newVersionId = "cursor-second-valid-version"
        val earlier = fixture.library.libraryTransaction {
            val meta = checkNotNull(fixture.library.dao.meta())
            val old = checkNotNull(fixture.library.dao.currentTrack(original.trackKey.trackId))
            val revision = meta.revision + 1L
            // 不关闭旧版本，证明标识候选不能仅保留命中边界的 versionId。
            val next = old.copy(versionId = newVersionId, validFrom = revision, validTo = null,
                displayTitle = "Z", title = "Z", titleKey = LibrarySortKeys.text("Z"), titleSection = "Z",
                artist = "Zulu", artists = LibraryCodec.strings(listOf("Zulu")),
                artistKey = LibrarySortKeys.text("Zulu"), artistSection = "Z",
                searchText = LibrarySortKeys.normalize("Z Zulu version-original.mp3"))
            fixture.library.dao.insertTrackVersion(next)
            fixture.library.dao.insertArtists(listOf(LibraryArtistEntity(newVersionId, 0,
                LibrarySortKeys.text("Zulu"), "Zulu")))
            assertEquals(1, fixture.library.dao.updateMeta(meta.copy(revision = revision)))
            old
        }
        for (query in listOf(LibraryQuery(), LibraryQuery(search = "Alpha"))) {
            val view = fixture.library.openView(query, owner = LibraryViewOwner.TRANSFER)
            try {
                val all = fullRows(view)
                assertEquals(2, all.count { it.track.trackId == original.trackKey.trackId })
                val cursor = all.single { it.track.displayTitle == "M" }.cursor(view)
                fixture.library.transferTransaction(view) {
                    val expected = fixture.library.dao.rows(LibrarySql.rows(view, cursor, 2))
                    val candidate = fixture.library.dao.rows(checkNotNull(LibrarySql.rowsForTrackCursor(view, cursor, 2)))
                    assertEquals(expected.groupingBy { it }.eachCount(), candidate.groupingBy { it }.eachCount())
                    val selected = candidate.single { it.track.versionId == newVersionId }
                    assertEquals("Alpha", selected.displayArtist)
                    assertEquals(listOf("Zulu"), selected.track.metadata().artists)
                }
                assertWindow(view, cursor)
                assertWindow(view, cursor, limit = 1)
                assertWindow(view, cursor, before = true, limit = 1)
            } finally {
                fixture.library.closeView(view.viewId)
            }
        }
        // raw 前窗为B/C，C被正常搜索排除；同标识的远端Z不能补成B/Z假满窗。
        fixture.library.libraryTransaction {
            fixture.library.dao.updateTrackVersion(earlier.copy(displayTitle = "B", title = "B",
                titleKey = LibrarySortKeys.text("B"), titleSection = "B",
                searchText = LibrarySortKeys.normalize("B Alpha version-original.mp3")))
        }
        fixture.append(source, listOf(
            "raw-prefix-cursor" to LocalAudioMetadata(title = "A", artists = listOf("Alpha")),
            "raw-prefix-filtered" to LocalAudioMetadata(title = "C", artists = listOf("Beta")),
            "raw-prefix-matching" to LocalAudioMetadata(title = "D", artists = listOf("Alpha")),
        ))
        val filtered = fixture.library.openView(LibraryQuery(search = "Alpha"), owner = LibraryViewOwner.TRANSFER)
        try {
            val cursor = fullRows(filtered).single { it.track.displayTitle == "A" }.cursor(filtered)
            fixture.library.transferTransaction(filtered) {
                val expected = fixture.library.dao.rows(LibrarySql.rows(filtered, cursor, 2))
                val candidate = fixture.library.dao.rows(checkNotNull(LibrarySql.rowsForTrackCursor(filtered, cursor, 2)))
                assertEquals(listOf("B", "D"), expected.map { it.track.displayTitle })
                assertEquals(listOf("B"), candidate.map { it.track.displayTitle })
            }
            assertWindow(filtered, cursor)
        } finally {
            fixture.library.closeView(filtered.viewId)
        }
    }

    @Test(timeout = 30_000L)
    fun nativeKeyWindowsKeepProvidersDuplicatesValidVersionsAndOneHundredKeyBindings() = runBlocking(Dispatchers.IO) {
        seedPartitions()
        fixture.group(TransferDisplayKind.ARTIST, "Alpha")
        val original = fixture.tracks().single { it.title == "N" }
        val newVersionId = "key-window-second-valid-version"
        fixture.library.libraryTransaction {
            val meta = checkNotNull(fixture.library.dao.meta())
            val old = checkNotNull(fixture.library.dao.currentTrack(original.trackKey.trackId))
            val revision = meta.revision + 1L
            // 同标识的两个有效版本保留不同原艺人；查询仍须解析完整显示关系。
            val next = old.copy(versionId = newVersionId, validFrom = revision, validTo = null,
                displayTitle = "N version", title = "N version", titleKey = LibrarySortKeys.text("N version"),
                artist = "Zulu", artists = LibraryCodec.strings(listOf("Zulu")),
                artistKey = LibrarySortKeys.text("Zulu"), artistSection = "Z",
                searchText = LibrarySortKeys.normalize("N version Zulu readable-3.mp3"))
            fixture.library.dao.insertTrackVersion(next)
            fixture.library.dao.insertArtists(listOf(LibraryArtistEntity(newVersionId, 0,
                LibrarySortKeys.text("Zulu"), "Zulu")))
            assertEquals(1, fixture.library.dao.updateMeta(meta.copy(revision = revision)))
        }
        for (query in listOf(LibraryQuery(), LibraryQuery(search = "Alpha"))) {
            val view = fixture.library.openView(query, owner = LibraryViewOwner.TRANSFER)
            try {
                val all = fullRows(view)
                if (query.search.isEmpty()) assertEquals(7, all.size)
                assertEquals(2, all.count { it.track.trackId == original.trackKey.trackId })
                val changed = all.single { it.track.versionId == newVersionId }
                assertEquals("Alpha", changed.displayArtist)
                assertEquals(listOf("Zulu"), changed.track.metadata().artists)
                val exactKeys = all.map { TrackKey(it.track.providerId, it.track.trackId) }.distinct()
                val hundredKeys = exactKeys + List(LibraryLimits.PAGE_SIZE - exactKeys.size) { index ->
                    TrackKey(TrackKey.LOCAL_PROVIDER, "absent-key-" + index)
                }
                assertEquals(100, hundredKeys.size)
                val wrongProvider = TrackKey("different-provider", original.trackKey.trackId)
                val partial = listOf(original.trackKey, original.trackKey, wrongProvider,
                    TrackKey(TrackKey.LOCAL_PROVIDER, "absent-key"))
                for (keys in listOf(emptyList(), listOf(wrongProvider), partial, hundredKeys)) {
                    val expected = all.filter { TrackKey(it.track.providerId, it.track.trackId) in keys }
                    val actual = fixture.library.transferTransaction(view) {
                        fixture.library.dao.rows(LibrarySql.rowsForKeys(view, keys))
                    }
                    assertEquals(expected.groupingBy { it }.eachCount(), actual.groupingBy { it }.eachCount())
                }
            } finally {
                fixture.library.closeView(view.viewId)
            }
        }
    }

    private suspend fun fullRows(view: LibraryViewToken): List<LibraryRowRecord> =
        fixture.library.transferTransaction(view) {
            fixture.library.dao.rows(LibrarySql.rows(view, null, LibraryLimits.PAGE_SIZE))
        }

    private suspend fun assertWindow(view: LibraryViewToken, cursor: LibraryCursor?,
        before: Boolean = false, limit: Int = 2) {
        val expected = fixture.library.transferTransaction(view) {
            val original = fixture.library.dao.rows(LibrarySql.rows(view, cursor, limit, before))
            val selected = fixture.library.readOrderedRowsUnlocked(view, cursor, limit, before)
            assertEquals(original, selected)
            if (cursor != null) {
                val hasOriginal = fixture.library.dao.scalar(LibrarySql.hasBeyond(view, cursor, before)) != 0L
                assertEquals(hasOriginal, fixture.library.hasRowsBeyondUnlocked(view, cursor, before))
                if (view.sort.field == LibrarySortField.ARTIST || view.sort.field == LibrarySortField.ALBUM) {
                    assertNull(LibrarySql.rowsForTrackCursor(view, cursor, limit, before))
                    assertNull(LibrarySql.hasBeyondForTrackCursor(view, cursor, before))
                }
            }
            val ordered = if (before) original.asReversed() else original
            val first = ordered.firstOrNull()?.cursor(view)
            val last = ordered.lastOrNull()?.cursor(view)
            val next = last?.takeIf {
                fixture.library.dao.scalar(LibrarySql.hasBeyond(view, it, before = false)) != 0L
            }
            val previous = first?.takeIf {
                fixture.library.dao.scalar(LibrarySql.hasBeyond(view, it, before = true)) != 0L
            }
            LibraryPage(view, ordered.map { it.model() }, next, previous)
        }
        val actual = if (before) fixture.library.previousPage(view, checkNotNull(cursor), limit)
            else fixture.library.page(view, cursor, limit)
        assertEquals(expected, actual)
    }

    private suspend fun seedPartitions() {
        val readable = fixture.source("cursor-readable")
        fixture.append(readable, listOf(
            "readable-20" to LocalAudioMetadata(title = "M", artists = listOf("Zulu"), album = "Album Z",
                albumArtist = "Band", durationMs = 90_000L, discNumber = 1, trackNumber = 2),
            "readable-3" to LocalAudioMetadata(title = "N", artists = listOf("Alpha"), album = "Album A",
                albumArtist = "Band", durationMs = 120_000L),
            "readable-001" to LocalAudioMetadata(title = "O", discNumber = 2, trackNumber = 1),
        ))
        val revoked = fixture.source("cursor-revoked")
        fixture.append(revoked, listOf(
            "missing-1" to LocalAudioMetadata(title = "A", artists = listOf("Alpha"), album = "Album A",
                albumArtist = "Band", durationMs = 10_000L),
            "missing-2" to LocalAudioMetadata(title = "B", artists = listOf("Zulu"), album = "Album Z",
                albumArtist = "Band", discNumber = 1, trackNumber = 5),
            "missing-3" to LocalAudioMetadata(title = "C", durationMs = 90_000L, trackNumber = 1),
        ))
        fixture.library.setSourceIssue(revoked.spec.sourceId, revoked.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
        fixture.database.withTestWriterTransaction {
            execute("UPDATE library_meta SET statisticsEpoch = 7 WHERE id = 1")
            val statistics = listOf(
                "readable-20.mp3" to Triple(7L, 30L, 1_000L),
                "readable-3.mp3" to Triple(6L, 1_000L, 1_000_000L),
                "readable-001.mp3" to Triple(7L, 2L, 10_000L),
                "missing-1.mp3" to Triple(7L, 100L, 100_000L),
                "missing-2.mp3" to Triple(7L, 0L, 0L),
                "missing-3.mp3" to Triple(6L, 999L, 999_999L),
            )
            for ((filename, values) in statistics) {
                execute("UPDATE local_track_versions SET statisticsEpoch = ?, playCount = ?, listeningMs = ? " +
                    "WHERE filename = ? AND validTo IS NULL", values.first, values.second, values.third, filename)
            }
            assertEquals(2L, scalarLong("SELECT COUNT(*) FROM local_track_versions " +
                "WHERE validTo IS NULL AND statisticsEpoch != 7 AND playCount > 0"))
        }
    }
}
