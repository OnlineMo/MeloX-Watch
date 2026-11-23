package io.github.onlinemo.meloxwatch.data.library

import android.content.Context
import androidx.room.RoomRawQuery
import io.github.onlinemo.meloxwatch.data.database.withTestReaderSql
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.transfer.CompanionRoomFixture
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** 真实 Room 行对比窗口投影与全库艺人关系，防止显示值及访问字段改变。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LibraryWindowProjectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: CompanionRoomFixture
    private lateinit var source: LibrarySource

    @Before fun open() = runBlocking {
        fixture = CompanionRoomFixture(RuntimeEnvironment.getApplication() as Context,
            File(temporary.root, "window-projection-room.db"))
        fixture.initialize()
        source = fixture.source()
        fixture.append(source, listOf(
            "one" to LocalAudioMetadata(title = "One", artists = listOf("Alpha", "Beta"), album = "Album",
                durationMs = 100_000L, discNumber = 1, trackNumber = 1),
            "two" to LocalAudioMetadata(title = "Two", artists = listOf("Gamma", "Delta"), album = "Album"),
            "three" to LocalAudioMetadata(title = "Three"),
        ))
    }

    @After fun close() { fixture.close() }

    @Test(timeout = 30_000L)
    fun everySortAndDirectionKeepsCompleteRowsAndRealBoundaryProbes() = runBlocking {
        val expected = fixture.tracks().map { it.trackKey }.toSet()
        assertEquals(3, expected.size)
        for (field in LibrarySortField.entries) for (descending in listOf(false, true)) {
            val view = fixture.library.openView(sort = LibrarySort(field, descending))
            try {
                val query = LibrarySql.rows(view, null, 100)
                val records = compareRows(query)
                assertEquals(expected, records.map { TrackKey(it.track.providerId, it.track.trackId) }.toSet())
                assertEquals(3, records.size)
                assertEquals(mapOf("One" to "Alpha", "Two" to "Gamma", "Three" to null),
                    records.associate { it.track.displayTitle to it.displayArtist })
                if (field == LibrarySortField.ARTIST) {
                    assertFalse(query.sql.contains("page_candidates AS MATERIALIZED"))
                } else {
                    assertTrue(query.sql.contains("page_candidates AS MATERIALIZED"))
                    assertEquals(1, plan(query).count { it == "MATERIALIZE page_candidates" })
                    assertEquals(1, plan(query).count { it == "MATERIALIZE display_artist_rows" })
                }
                verifyProbes(view, records)
                val keys = records.map { TrackKey(it.track.providerId, it.track.trackId) }
                assertEquals(records.toSet(), compareRows(LibrarySql.rowsForKeys(view, keys)).toSet())
                assertTrue(compareRows(LibrarySql.rowsForKeys(view, emptyList())).isEmpty())
                for (record in records) {
                    assertEquals(record, compareRows(LibrarySql.row(view, record.track.providerId, record.track.trackId)).single())
                }
            } finally { fixture.library.closeView(view.viewId) }
        }
    }

    @Test(timeout = 30_000L)
    fun renamedArtistsFiltersSearchRelativePathsAndGrantFencesRemainNativeAndFrozen() = runBlocking {
        val frozen = fixture.library.openView()
        try {
            val before = compareRows(LibrarySql.rows(frozen, null, 100))
            val alpha = fixture.group(TransferDisplayKind.ARTIST, "Alpha")
            fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ARTIST, alpha.entityId),
                TransferDisplayPatch.Rename("Zulu")))
            val groupsView = fixture.library.openView()
            val artistKey = try {
                fixture.database.libraryDao().groupRows(LibrarySql.groups(groupsView, LibraryGroupKind.ARTIST, null, 100))
                    .single { it.title == "Zulu" }.groupKey
            } finally { fixture.library.closeView(groupsView.viewId) }
            for (query in listOf(
                LibraryQuery(artistKey = artistKey),
                LibraryQuery(search = "Zulu"),
                LibraryQuery(sourceId = source.spec.sourceId, relativePathKeyword = "one"),
            )) {
                val view = fixture.library.openView(query)
                try {
                    val records = compareRows(LibrarySql.rows(view, null, 100))
                    assertEquals(listOf("One"), records.map { it.track.displayTitle })
                    assertEquals("Zulu", records.single().displayArtist)
                    verifyProbes(view, records)
                } finally { fixture.library.closeView(view.viewId) }
            }
            assertEquals(before, compareRows(LibrarySql.rows(frozen, null, 100)))
            fixture.library.setSourceIssue(source.spec.sourceId, source.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
            val revoked = fixture.library.openView()
            try {
                val records = compareRows(LibrarySql.rows(revoked, null, 100))
                assertEquals(3, records.size)
                assertTrue(records.all { it.availabilityRank == 2 })
                verifyProbes(revoked, records)
            } finally { fixture.library.closeView(revoked.viewId) }
            val readable = fixture.library.openView(LibraryQuery(readableOnly = true))
            try { assertTrue(compareRows(LibrarySql.rows(readable, null, 100)).isEmpty()) }
            finally { fixture.library.closeView(readable.viewId) }
            assertEquals(before, compareRows(LibrarySql.rows(frozen, null, 100)))
        } finally { fixture.library.closeView(frozen.viewId) }
    }

    private suspend fun verifyProbes(view: LibraryViewToken, records: List<LibraryRowRecord>) {
        val dao = fixture.database.libraryDao()
        val artistsRequired = view.sort.field == LibrarySortField.ARTIST ||
            view.query.artistKey != null || view.query.search.isNotBlank()
        for (record in records) for (before in listOf(false, true)) {
            val cursor = record.cursor(view)
            val actual = LibrarySql.hasBeyond(view, cursor, before)
            val reference = fullArtistRelation(LibrarySql.rows(view, cursor, 1, before))
            assertEquals(dao.rows(reference).isNotEmpty(), dao.scalar(actual) != 0L)
            assertEquals(if (artistsRequired) 1 else 0, plan(actual).count { it == "MATERIALIZE display_artist_rows" })
            assertEquals(1, plan(actual).count { it == "MATERIALIZE alias_rows" })
        }
    }

    private suspend fun compareRows(query: RoomRawQuery): List<LibraryRowRecord> {
        val dao = fixture.database.libraryDao()
        val actual = dao.rows(query)
        assertEquals(dao.rows(fullArtistRelation(query)), actual)
        return actual
    }

    /** 仅将艺人输入恢复为全库；候选窗口、权限和版本边界、顺序及参数绑定保持一致。 */
    private fun fullArtistRelation(query: RoomRawQuery): RoomRawQuery = RoomRawQuery(
        query.sql.replace("FROM page_artist_tracks t", "FROM scanned_tracks t"), query.getBindingFunction(),
    )

    private suspend fun plan(raw: RoomRawQuery): List<String> = fixture.database.withTestReaderSql {
        query("EXPLAIN QUERY PLAN ${raw.sql}") { statement ->
            raw.getBindingFunction()(statement)
            buildList { while (statement.step()) add(statement.getText(3)) }
        }
    }
}
