package io.github.onlinemo.meloxwatch.data.transfer

import android.content.Context
import io.github.onlinemo.meloxwatch.data.database.TestDatabaseSql
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.data.library.LibraryCursor
import io.github.onlinemo.meloxwatch.data.library.LibrarySortKeys
import io.github.onlinemo.meloxwatch.data.library.LibraryViewOwner
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewOwner
import java.io.File
import kotlinx.coroutines.runBlocking
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

/** Native Room paging beyond the old 10000 boundary; these definitions are not device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomCompanionLargePagesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: CompanionRoomFixture
    private var stageCase = ""
    private var stageStartNanos = 0L
    private var stageEvents = 0

    @Before fun open() = runBlocking {
        fixture = CompanionRoomFixture(RuntimeEnvironment.getApplication() as Context,
            File(temporary.root, "large-pages-room.db"))
        fixture.initialize()
    }

    @After fun close() { fixture.close() }

    @Test(timeout = 30_000L) fun actualRoomCatalogContainsAll20001TracksAcross201PagesAndRevocationStopsReads() = runBlocking {
        startStages("catalog")
        val firstTrackId = seedLargeLibrary()
        stage("catalog.begin")
        val first = fixture.firstPage()
        assertEquals(COUNT, first.totalCount)
        var page = first
        var ordinal = 0L
        var pages = 0
        var lastTrackId: String? = null
        while (true) {
            assertPage(first, page, ordinal)
            for (row in page.rows) {
                assertTrue(row is TransferCatalogRow.Track)
                val track = row as TransferCatalogRow.Track
                assertEquals(expectedTrackId(firstTrackId, ordinal), track.trackId)
                assertEquals("Shared track", track.title)
                assertEquals("Large Artist", track.artist)
                assertEquals("Large Album", track.album)
                assertTrue(track.available)
                lastTrackId = track.trackId
                ordinal++
            }
            pages++
            if (pages % 50 == 0) stage("catalog.pages", rows = ordinal, pages = pages)
            if (page.nextCursor == null) break
            page = fixture.nextPage(page)
        }
        assertEquals(201, pages)
        assertEquals(COUNT, ordinal)
        assertEquals("large-track:20000", lastTrackId)
        assertNull(page.nextCursor)
        assertNativeLibraryCount()
        stage("catalog.end", rows = ordinal, pages = pages)

        stage("revoke.begin")
        val active = fixture.permit(TransferCapability.READ_LIBRARY)
        val oldScope = fixture.scope
        val reread = TransferRequest.ReadPage(CompanionRoomFixture.uuid(), oldScope, first.kind,
            first.viewId, first.revision, first.libraryRevision, null, null, "", 100)
        fixture.authority.revokeForLocalUi(fixture.peer)
        expectTransferIssue(TransferIssue.NOT_APPROVED) { fixture.backend.readPage(reread, active) }
        fixture.backend.closePeerViews(fixture.peer, oldScope.sessionId)
        fixture.newSession(setOf(TransferCapability.READ_LIBRARY))
        val freshScope = TransferRequest.ReadPage(CompanionRoomFixture.uuid(), fixture.scope, first.kind,
            first.viewId, first.revision, first.libraryRevision, null, null, "", 100)
        expectTransferIssue(TransferIssue.EXPIRED) {
            fixture.backend.readPage(freshScope, fixture.permit(TransferCapability.READ_LIBRARY))
        }
        fixture.database.withTestWriterSql {
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM library_views WHERE owner = 'TRANSFER'"))
        }
        assertNativeLibraryCount()
        stage("revoke.end")
    }

    @Test(timeout = 30_000L) fun actualNativePlaylistContainsAll20001OrdinalsAcross201PagesAndCloseRetainsUiViews() = runBlocking {
        startStages("playlist")
        val firstTrackId = seedLargeLibrary()
        stage("playlist.begin")
        val sourceView = fixture.library.openView(owner = LibraryViewOwner.UI)
        val input = FrozenPlaylistInput(CompanionRoomFixture.uuid(), sourceView.revision, COUNT)
        var cursor: LibraryCursor? = null
        var consumed = 0L
        var sourcePages = 0
        val source = PlaylistPageSource { offset, limit ->
            assertEquals(consumed, offset)
            assertTrue(limit in 1..100)
            val page = fixture.library.page(sourceView, cursor, limit)
            assertTrue(page.tracks.size <= 100)
            val references = page.tracks.map { track ->
                assertEquals(expectedTrackId(firstTrackId, consumed++), track.trackKey.trackId)
                PlaylistReference(trackKey = track.trackKey, titleHint = track.title,
                    artistHint = track.displayArtist, albumHint = track.displayAlbum)
            }
            cursor = page.nextCursor
            sourcePages++
            if (sourcePages % 50 == 0) stage("source.pages", rows = consumed, pages = sourcePages)
            if (cursor == null) stage("source.end", rows = consumed, pages = sourcePages)
            PlaylistInputPage(input, offset, references, cursor == null)
        }
        val created = try {
            fixture.playlists.apply(PlaylistMutationRequest(CompanionRoomFixture.uuid(), "large-playlist", 0L,
                PlaylistMutation.Create("Large native playlist", input)), source)
        } finally { fixture.library.closeView(sourceView.viewId) }
        val native = checkNotNull(created.snapshot)
        assertEquals(COUNT, consumed)
        assertEquals(201, sourcePages)
        assertEquals(COUNT, native.memberCount)
        fixture.database.withTestWriterSql {
            assertEquals(COUNT, scalarLong("SELECT COUNT(*) FROM playlist_members WHERE generationId = ?", native.generationId))
            assertEquals(0L, scalarLong("SELECT MIN(ordinal) FROM playlist_members WHERE generationId = ?", native.generationId))
            assertEquals(COUNT - 1L, scalarLong("SELECT MAX(ordinal) FROM playlist_members WHERE generationId = ?", native.generationId))
        }

        stage("playlist.end", rows = consumed, pages = sourcePages)
        stage("members.setup")
        val uiLibrary = fixture.library.openView()
        val uiPlaylist = fixture.playlists.openView(native.playlistId, PlaylistViewOwner.UI)
        try {
            stage("members.begin")
            val first = fixture.firstPage(TransferCatalogKind.PLAYLIST_MEMBERS, native.playlistId)
            var page = first
            var ordinal = 0L
            var pages = 0
            var lastTrackId: String? = null
            while (true) {
                assertPage(first, page, ordinal)
                for (row in page.rows) {
                    assertTrue(row is TransferCatalogRow.Member)
                    val member = row as TransferCatalogRow.Member
                    assertEquals(ordinal, member.ordinal)
                    assertEquals(expectedTrackId(firstTrackId, ordinal), member.trackId)
                    assertEquals("Shared track", member.titleHint)
                    assertEquals("Large Artist", member.artistHint)
                    assertTrue(member.available)
                    lastTrackId = member.trackId
                    ordinal++
                }
                pages++
                if (pages % 50 == 0) stage("members.pages", rows = ordinal, pages = pages)
                if (page.nextCursor == null) break
                page = fixture.nextPage(page, native.playlistId)
            }
            assertEquals(201, pages)
            assertEquals(COUNT, ordinal)
            assertEquals("large-track:20000", lastTrackId)
            assertNull(page.nextCursor)
            fixture.backend.closePeerViews(fixture.peer, fixture.scope.sessionId)
            val closed = TransferRequest.ReadPage(CompanionRoomFixture.uuid(), fixture.scope, first.kind,
                first.viewId, first.revision, first.libraryRevision, null, native.playlistId, "", 100)
            expectTransferIssue(TransferIssue.EXPIRED) {
                fixture.backend.readPage(closed, fixture.permit(TransferCapability.READ_LIBRARY))
            }
            assertEquals(firstTrackId, fixture.library.page(uiLibrary).tracks.first().trackKey.trackId)
            val retained = fixture.playlists.page(uiPlaylist, 0L)
            assertEquals(COUNT, retained.view.snapshot.memberCount)
            assertEquals(100, retained.members.size)
            assertEquals(0L, retained.members.first().ordinal)
            fixture.database.withTestWriterSql {
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM library_views WHERE owner = 'TRANSFER'"))
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM playlist_views WHERE owner = 'TRANSFER'"))
                assertEquals(1L, scalarLong("SELECT COUNT(*) FROM library_views WHERE owner = 'UI'"))
                assertEquals(1L, scalarLong("SELECT COUNT(*) FROM playlist_views WHERE owner = 'UI'"))
            }
            assertNativeLibraryCount()
            stage("members.end", rows = ordinal, pages = pages)
        } finally {
            fixture.library.closeView(uiLibrary.viewId)
            fixture.playlists.closeView(uiPlaylist.viewId)
        }
    }

    private fun assertPage(first: TransferCatalogPage, page: TransferCatalogPage, ordinal: Long) {
        assertEquals(first.kind, page.kind)
        assertEquals(first.viewId, page.viewId)
        assertEquals(first.revision, page.revision)
        assertTrue(first.libraryRevision.sameAs(page.libraryRevision))
        assertEquals(COUNT, page.totalCount)
        assertEquals(ordinal, page.firstOrdinal)
        assertTrue(page.rows.size <= 100)
        assertEquals(minOf(100L, COUNT - ordinal).toInt(), page.rows.size)
        assertEquals(ordinal + page.rows.size < COUNT, page.nextCursor != null)
    }

    /**
     * Seed stored native rows, then exercise production queries and native playlist creation.
     * The one real scan supplies complete Room payloads. Each copied track has its own location,
     * alias and child node; only ordinary metadata values are shared. The new versions are
     * future-dated until all bounded fixture batches exist, then one meta revision exposes them.
     */
    private suspend fun seedLargeLibrary(): String {
        val source = fixture.source()
        fixture.append(source, listOf("seed" to LocalAudioMetadata(title = "Shared track",
            artist = "Large Artist", album = "Large Album", durationMs = 120_000L)))
        val first = fixture.tracks().single()
        val dao = fixture.database.libraryDao()
        val meta = checkNotNull(dao.meta())
        val revision = Math.addExact(meta.revision, 1L)
        val sequence = meta.nextAddedSequence
        val track = checkNotNull(dao.currentTrack(first.trackKey.trackId))
        val alias = checkNotNull(dao.currentAlias(checkNotNull(first.aliasId)))
        val location = checkNotNull(dao.currentLocationVersion(checkNotNull(first.location).locationId))
        val node = checkNotNull(dao.currentNode(checkNotNull(first.nodeId)))
        val relation = fixture.database.withTestWriterSql {
            checkNotNull(scalarText("SELECT relationId FROM local_track_locations WHERE trackId = ? AND validTo IS NULL",
                first.trackKey.trackId))
        }
        val suffix = "printf('%05d', batch.n)"
        fun id(prefix: String) = "'$prefix' || $suffix"
        val identity = "'" + LibrarySortKeys.identity("SOURCE", source.spec.sourceId) + "18:large-object:' || $suffix"
        val content = "'native-fixture:large-object:' || $suffix || ':1'"
        val uri = "'content://companion-room-fixture/document/room-source/large-object:' || $suffix"
        val plans = fixture.database.withTestWriterSql {
            listOf(
                copySql("local_nodes", "nodeId", mapOf("nodeId" to id("large-node:"),
                    "objectId" to id("large-object:"))) to node.nodeId,
                copySql("local_node_versions", "versionId", mapOf("versionId" to id("large-node-version:"),
                    "nodeId" to id("large-node:"), "uri" to uri, "validFrom" to revision.toString())) to node.versionId,
                copySql("local_locations", "locationId", mapOf("locationId" to id("large-location:"),
                    "identityKey" to identity, "contentToken" to content)) to location.locationId,
                copySql("local_location_versions", "versionId", mapOf("versionId" to id("large-location-version:"),
                    "locationId" to id("large-location:"), "contentToken" to content, "validFrom" to revision.toString())) to location.versionId,
                copySql("local_access_aliases", "aliasId", mapOf("aliasId" to id("large-alias:"),
                    "objectId" to id("large-object:"), "locationId" to id("large-location:"))) to alias.aliasId,
                copySql("local_alias_versions", "versionId", mapOf("versionId" to id("large-alias-version:"),
                    "aliasId" to id("large-alias:"), "locationId" to id("large-location:"), "nodeId" to id("large-node:"),
                    "uri" to uri, "providerContentToken" to content, "validFrom" to revision.toString())) to alias.versionId,
                copySql("local_tracks", "trackId", mapOf("trackId" to id("large-track:"),
                    "firstAddedSequence" to "$sequence + batch.n - 1")) to track.trackId,
                copySql("local_track_versions", "versionId", mapOf("versionId" to id("large-track-version:"),
                    "trackId" to id("large-track:"), "firstAddedSequence" to "$sequence + batch.n - 1",
                    "validFrom" to revision.toString())) to track.versionId,
                copySql("local_artist_values", "versionId", mapOf("versionId" to id("large-track-version:"))) to track.versionId,
                copySql("local_track_locations", "relationId", mapOf("relationId" to id("large-relation:"),
                    "trackId" to id("large-track:"), "locationId" to id("large-location:"), "validFrom" to revision.toString())) to relation,
            )
        }
        var firstOrdinal = 1L
        var batches = 0
        while (firstOrdinal < COUNT) {
            val lastOrdinal = minOf(firstOrdinal + 99L, COUNT - 1L)
            fixture.database.withTestWriterTransaction {
                // Ten fixed copies share these 100 ordinals on this borrowed writer connection.
                execute("CREATE TEMP TABLE companion_large_page_batch(n INTEGER PRIMARY KEY)")
                execute("""WITH RECURSIVE numbers(n) AS (
                    SELECT ? UNION ALL SELECT n + 1 FROM numbers WHERE n < ?)
                    INSERT INTO temp.companion_large_page_batch(n) SELECT n FROM numbers
                """.trimIndent(), firstOrdinal, lastOrdinal)
                for ((sql, template) in plans) execute(sql, template)
                execute("DROP TABLE temp.companion_large_page_batch")
            }
            firstOrdinal = lastOrdinal + 1L
            batches++
            if (batches % 50 == 0) stage("seed.batch", nextOrdinal = firstOrdinal)
        }
        stage("seed.publish")
        fixture.database.withTestWriterTransaction {
            assertEquals(meta, dao.meta())
            assertEquals(1, dao.updateMeta(meta.copy(revision = revision,
                nextAddedSequence = Math.addExact(sequence, COUNT - 1L))))
        }
        stage("seed.validate")
        assertNativeLibraryCount()
        assertEquals(COUNT, fixture.library.overview().visibleTracks)
        stage("seed.end", rows = COUNT)
        return first.trackKey.trackId
    }

    /** All names/expressions are fixed test declarations; no provider or peer input enters SQL. */
    private suspend fun TestDatabaseSql.copySql(table: String, key: String, overrides: Map<String, String>): String {
        val columns = query("PRAGMA table_info(" + quoted(table) + ")") { rows ->
            buildList { while (rows.step()) add(rows.getText(1)) }
        }
        assertTrue(columns.isNotEmpty())
        assertTrue(key in columns && overrides.keys.all { it in columns })
        val names = columns.joinToString(",", transform = ::quoted)
        val values = columns.joinToString(",") { column -> overrides[column] ?: "original." + quoted(column) }
        return """INSERT INTO $table ($names) SELECT $values
            FROM $table original CROSS JOIN temp.companion_large_page_batch batch
            WHERE original.$key = ?
        """.trimIndent()
    }

    private suspend fun assertNativeLibraryCount() = fixture.database.withTestWriterSql {
        assertEquals(COUNT, scalarLong("SELECT COUNT(*) FROM local_tracks"))
        assertEquals(COUNT, scalarLong("SELECT COUNT(*) FROM local_track_versions WHERE member = 1 AND validTo IS NULL"))
        assertEquals(COUNT, scalarLong("SELECT COUNT(*) FROM local_locations"))
        assertEquals(COUNT, scalarLong("SELECT COUNT(*) FROM local_access_aliases"))
        query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
    }

    /** Fixed labels and counters only; at most 32 records, with no identities, URIs or paths. */
    private fun startStages(case: String) {
        stageCase = case
        stageStartNanos = System.nanoTime()
        stageEvents = 0
        stage("seed.begin")
    }

    private fun stage(name: String, rows: Long? = null, pages: Int? = null, nextOrdinal: Long? = null) {
        if (stageEvents >= 32) return
        stageEvents++
        val elapsedNanos = System.nanoTime() - stageStartNanos
        println("MELOX_LARGE_PAGE case=$stageCase stage=$name elapsedNanos=$elapsedNanos " +
            "rows=$rows pages=$pages nextOrdinal=$nextOrdinal")
    }

    private fun expectedTrackId(first: String, ordinal: Long) =
        if (ordinal == 0L) first else "large-track:" + ordinal.toString().padStart(5, '0')

    private fun quoted(name: String) = "\"" + name + "\""

    private companion object { const val COUNT = 20_001L }
}
