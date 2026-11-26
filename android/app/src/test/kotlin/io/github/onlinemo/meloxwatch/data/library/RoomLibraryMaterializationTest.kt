package io.github.onlinemo.meloxwatch.data.library

import android.content.Context
import androidx.room.RoomRawQuery
import io.github.onlinemo.meloxwatch.data.database.withTestReaderSql
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.transfer.CompanionRoomFixture
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/** Real scan and companion writers feed the production Bundled SQLite queries and frozen views. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomLibraryMaterializationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: CompanionRoomFixture

    @Before fun open() = runBlocking {
        fixture = CompanionRoomFixture(RuntimeEnvironment.getApplication() as Context,
            File(temporary.root, "library-materialization-room.db"))
        fixture.initialize()
    }

    @After fun close() { fixture.close() }

    @Test(timeout = 30_000L)
    fun nativePlanReusesBothSharedRelationsAndReturnsTheOriginalScannedRows() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf(
            "one" to LocalAudioMetadata(title = "One", artists = listOf("Alpha", "Beta"), album = "Album"),
            "two" to LocalAudioMetadata(title = "Two", artists = listOf("Gamma", "Delta"), album = "Album"),
            "three" to LocalAudioMetadata(title = "Three"),
        ))
        fixture.database.withTestReaderSql {
            val version = checkNotNull(scalarText("SELECT sqlite_version()")).split('.').map { it.toInt() }
            assertTrue(version[0] > 3 || version[0] == 3 && version[1] >= 35)
            assertEquals(8L, scalarLong("PRAGMA user_version"))
        }
        val view = fixture.library.openView()
        try {
            val query = LibrarySql.rows(view, null, 3)
            val plan = plan(query)
            assertEquals(1, plan.count { it.detail == "MATERIALIZE display_artist_rows" })
            assertEquals(1, plan.count { it.detail == "MATERIALIZE alias_rows" })
            assertSelectorUsesSharedRelation(plan, "candidate", "trackId")
            assertSelectorUsesSharedRelation(plan, "ar", "locationId")

            val rows = equalRows(query)
            assertEquals(listOf("One", "Three", "Two"), rows.map { it.track.displayTitle })
            assertEquals(listOf("Alpha", null, "Gamma"), rows.map { it.displayArtist })
            assertEquals(3, rows.map { it.track.trackId }.toSet().size)
            assertTrue(rows.all { it.availabilityRank == 0 && it.selectedAliasId != null && it.selectedUri != null })
            assertEquals(3L, fixture.database.libraryDao().scalar(LibrarySql.count(view)))
        } finally { fixture.library.closeView(view.viewId) }
    }

    @Test(timeout = 30_000L)
    fun sharedObjectAliasesUserNamesAndRevokedGrantsMatchTheOriginalQueryAtEachRevision() = runBlocking {
        val first = fixture.source("first")
        val second = fixture.source("second")
        appendSharedObject(first, "one")
        appendSharedObject(second, "two")
        val dao = fixture.database.libraryDao()
        val firstAlias = checkNotNull(dao.alias(first.spec.sourceId, "one"))
        val secondAlias = checkNotNull(dao.alias(second.spec.sourceId, "two"))
        assertNotEquals(firstAlias.aliasId, secondAlias.aliasId)
        assertEquals(firstAlias.locationId, secondAlias.locationId)
        assertEquals(1L, fixture.library.overview().totalTracks)

        val frozen = fixture.library.openView()
        try {
            val original = equalRows(LibrarySql.rows(frozen, null, 10)).single()
            assertEquals("Alpha", original.displayArtist)
            val alpha = fixture.group(TransferDisplayKind.ARTIST, "Alpha")
            fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ARTIST, alpha.entityId),
                TransferDisplayPatch.Rename("Zulu")))

            val renamed = equalViewRows().single()
            assertEquals(original.track, renamed.track)
            assertEquals("Zulu", renamed.displayArtist)
            assertEquals(original.selectedLocationId, renamed.selectedLocationId)
            val groupsView = fixture.library.openView()
            try {
                val groupsQuery = LibrarySql.groups(groupsView, LibraryGroupKind.ARTIST, null, 10)
                val groups = dao.groupRows(groupsQuery)
                assertEquals(dao.groupRows(withoutHints(groupsQuery)), groups)
                assertEquals(setOf("Beta", "Zulu"), groups.map { it.title }.toSet())
                assertTrue(groups.all { it.trackCount == 1L })
                assertEquals(renamed, equalRows(LibrarySql.rows(
                    groupsView.copy(query = LibraryQuery(artistKey = groups.single { it.title == "Zulu" }.groupKey)), null, 10)).single())
            } finally { fixture.library.closeView(groupsView.viewId) }

            val revokedId = checkNotNull(renamed.selectedSourceId)
            val revoked = if (revokedId == first.spec.sourceId) first else second
            val remaining = if (revokedId == first.spec.sourceId) second else first
            val remainingAlias = if (revokedId == first.spec.sourceId) secondAlias else firstAlias
            fixture.library.setSourceIssue(revokedId, revoked.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
            val fallback = equalViewRows().single()
            assertEquals(original.track, fallback.track)
            assertEquals("Zulu", fallback.displayArtist)
            assertEquals(0, fallback.availabilityRank)
            assertEquals(remaining.spec.sourceId, fallback.selectedSourceId)
            assertEquals(remainingAlias.aliasId, fallback.selectedAliasId)
            assertEquals(original.selectedLocationId, fallback.selectedLocationId)
            val scoped = equalViewRows(LibraryQuery(sourceId = revokedId)).single()
            assertEquals(2, scoped.availabilityRank)
            assertEquals(revokedId, scoped.selectedSourceId)
            assertEquals(original.track, scoped.track)
            assertTrue(equalViewRows(LibraryQuery(sourceId = revokedId, readableOnly = true)).isEmpty())

            fixture.library.setSourceIssue(remaining.spec.sourceId, remaining.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
            val unavailable = equalViewRows().single()
            assertEquals(2, unavailable.availabilityRank)
            assertEquals(original.track, unavailable.track)
            assertEquals("Zulu", unavailable.displayArtist)
            assertTrue(equalViewRows(LibraryQuery(readableOnly = true)).isEmpty())
            assertEquals(original, equalRows(LibrarySql.rows(frozen, null, 10)).single())
            fixture.database.withTestReaderSql {
                assertEquals(1L, scalarLong("SELECT COUNT(*) FROM local_tracks"))
                assertEquals(2L, scalarLong("SELECT COUNT(*) FROM local_access_aliases"))
                assertEquals(1L, scalarLong("SELECT COUNT(*) FROM local_track_locations WHERE validTo IS NULL"))
                assertEquals(2L, scalarLong("SELECT COUNT(*) FROM local_artist_values WHERE versionId = ?", original.track.versionId))
                query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
            }
        } finally { fixture.library.closeView(frozen.viewId) }
    }

    private suspend fun appendSharedObject(source: LibrarySource, objectId: String) {
        val library = fixture.library
        val token = library.beginScan(source.spec.sourceId,
            checkNotNull(library.source(source.spec.sourceId)).authEpoch, library.rules().revision, ScanMode.INCREMENTAL)
        val root = ObservedCandidate(source.spec.sourceId, checkNotNull(source.spec.rootObjectId), source.spec.uri,
            null, source.spec.sourceId, "vnd.android.document/directory", LocalNodeKind.DIRECTORY)
        val candidate = fixture.audio(source, objectId).copy(
            entityProof = "document:shared-materialized-object", providerContentToken = "native:shared-materialized-object:1")
        library.commitSeen(token, listOf(root, candidate))
        library.commitMetadata(token, listOf(MetadataOutcome(objectId,
            LocalAudioMetadata(title = "Shared", artists = listOf("Alpha", "Beta"), album = "Album"))))
        while (true) {
            val directory = library.nextDirectory(token) ?: break
            library.completeDirectory(token, directory.objectId, true)
        }
        val coverage = ScanCoverage(true, true, true, true, false)
        library.setCoverage(token, coverage)
        library.finishScan(token, ScanCounters(), coverage)
    }

    private suspend fun equalViewRows(query: LibraryQuery = LibraryQuery()): List<LibraryRowRecord> {
        val view = fixture.library.openView(query)
        try { return equalRows(LibrarySql.rows(view, null, 10)) }
        finally { fixture.library.closeView(view.viewId) }
    }

    private suspend fun equalRows(query: RoomRawQuery): List<LibraryRowRecord> {
        val dao = fixture.database.libraryDao()
        val current = dao.rows(query)
        assertEquals(dao.rows(withoutHints(query)), current)
        return current
    }

    /** Remove only the two optimization hints; SQL payload, revision and bindings stay identical. */
    private fun withoutHints(query: RoomRawQuery): RoomRawQuery = RoomRawQuery(
        query.sql.replace("display_artist_rows AS MATERIALIZED (", "display_artist_rows AS (")
            .replace("alias_rows AS MATERIALIZED (", "alias_rows AS ("),
        query.getBindingFunction(),
    )

    private data class PlanNode(val id: Long, val parent: Long, val detail: String)

    private suspend fun plan(raw: RoomRawQuery): List<PlanNode> = fixture.database.withTestReaderSql {
        query("EXPLAIN QUERY PLAN ${raw.sql}") { statement ->
            raw.getBindingFunction()(statement)
            buildList { while (statement.step()) add(PlanNode(statement.getLong(0), statement.getLong(1), statement.getText(3))) }
        }
    }

    private fun assertSelectorUsesSharedRelation(plan: List<PlanNode>, alias: String, key: String) {
        val lookup = plan.single { it.detail.startsWith("SEARCH $alias USING ") }
        assertTrue(lookup.detail, lookup.detail.contains("($key=?)"))
        val selector = plan.single { it.id == lookup.parent }
        assertTrue(selector.detail, selector.detail.startsWith("CORRELATED SCALAR SUBQUERY"))
        val descendants = mutableSetOf(selector.id)
        var changed: Boolean
        do {
            changed = false
            for (node in plan) if (node.parent in descendants && descendants.add(node.id)) changed = true
        } while (changed)
        val details = plan.filter { it.id in descendants }.map { it.detail }
        assertFalse(details.joinToString("\n"), details.any {
            it.startsWith("MATERIALIZE ") || it.startsWith("CO-ROUTINE ") ||
                Regex("(?:SCAN|SEARCH) (?:raw|artists|names|groups|av|lv|sv|g|aa)\\b").containsMatchIn(it)
        })
    }
}
