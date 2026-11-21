package io.github.onlinemo.meloxwatch.data.library

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import java.io.File
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

/** Real production Room mutations/queries; synthetic identities do not claim provider or device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomLocalLibraryExclusionRegressionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository

    @Before fun openDatabase() = runBlocking {
        file = File(temporaryFolder.root, "library-regression.db")
        open()
        assertEquals(0L, library.initialize().totalTracks)
    }

    @After fun closeDatabase() { database.close() }

    @Test(timeout = 30000L)
    fun excludedSubtreeDoesNotIndexNewChildrenAndRemovalAllowsRediscovery() = runBlocking {
        val source = source("exclusion")
        val music = directory(source, "music", "Music")
        val backup = directory(source, "music-backup", "MusicBackup")
        val existing = audio(source, "existing", "Existing.mp3", music.objectId)
        val seed = begin(source)
        library.commitSeen(seed, listOf(root(source), music, backup, existing))
        finish(seed)
        val original = rows().single()
        val folder = database.libraryDao().node(source.spec.sourceId, music.objectId) ?: throw AssertionError("real folder")
        library.addExclusion(library.rules().revision, source.spec.sourceId, folder.nodeId, true)
        val rule = library.exclusionPage().exclusions.single()
        assertTrue(rows().isEmpty())
        assertEquals(original.trackKey, rows(LibraryQuery(includeHidden = true)).single().trackKey)

        val blocked = audio(source, "blocked", "Blocked.mp3", music.objectId)
        val allowed = audio(source, "allowed", "Allowed.mp3", backup.objectId)
        val scan = begin(source)
        val batch = library.commitSeen(scan, listOf(blocked, allowed))
        assertEquals(2L, batch.seen)
        assertEquals(1L, batch.added)
        val counters = library.latestScan(source.spec.sourceId)?.counters ?: throw AssertionError("scan counters")
        assertEquals(2L, counters.seen)
        assertEquals(1L, counters.skipped)
        assertEquals(1L, counters.added)
        assertNull(database.libraryDao().alias(source.spec.sourceId, blocked.objectId))
        val seen = database.libraryDao().seen(scan.scanId, blocked.objectId) ?: throw AssertionError("seen excluded node")
        assertNull(seen.aliasId)
        assertEquals(2L, library.overview().totalTracks)
        assertEquals(listOf("Allowed.mp3"), rows().map { it.filename })
        assertEquals(setOf("Existing.mp3", "Allowed.mp3"), rows(LibraryQuery(includeHidden = true)).map { it.filename }.toSet())
        finish(scan)

        library.removeExclusion(library.rules().revision, rule.ruleId)
        val retry = begin(source)
        assertEquals(1L, library.commitSeen(retry, listOf(blocked)).added)
        finish(retry)
        assertEquals(3L, library.overview().totalTracks)
        assertEquals(original.trackKey, rows().first { it.filename == "Existing.mp3" }.trackKey)
        database.close()
        open()
        assertEquals(3L, library.initialize().totalTracks)
        assertTrue(library.exclusionPage().exclusions.isEmpty())
        assertEquals(setOf("Existing.mp3", "Allowed.mp3", "Blocked.mp3"), rows().map { it.filename }.toSet())
        assertEquals(original.trackKey, rows().first { it.filename == "Existing.mp3" }.trackKey)
    }

    @Test(timeout = 30000L)
    fun previouslyCreatedParentPlaceholderStillRejectsCyclesBeforeItsFirstVersion() = runBlocking {
        val source = source("placeholder")
        val child = audio(source, "child", "Child.mp3", "later-folder")
        val scan = begin(source)
        library.commitSeen(scan, listOf(root(source), child))
        val placeholder = database.libraryDao().node(source.spec.sourceId, "later-folder") ?: throw AssertionError("placeholder")
        assertNull(database.libraryDao().currentNode(placeholder.nodeId))
        val invalid = directory(source, "later-folder", "Later").copy(parentObjectId = child.objectId)
        try {
            library.commitSeen(scan, listOf(invalid))
            throw AssertionError("existing parent placeholder must not form a cycle")
        } catch (error: LibraryException) { assertEquals(LibraryIssue.SOURCE_CHANGED, error.issue) }
        assertNull(database.libraryDao().currentNode(placeholder.nodeId))
        assertNull(database.libraryDao().seen(scan.scanId, invalid.objectId))
        assertEquals(2L, library.latestScan(source.spec.sourceId)?.counters?.seen)
        assertEquals(1L, library.overview().totalTracks)

        library.commitSeen(scan, listOf(directory(source, "later-folder", "Later")))
        assertEquals("Later", database.libraryDao().currentNode(placeholder.nodeId)?.name)
        assertEquals("Later", database.libraryDao().node(source.spec.sourceId, "later-folder")?.lastKnownName)
        finish(scan)
        assertEquals(1L, library.overview().totalTracks)
        assertEquals("Child.mp3", rows().single().filename)
    }

    @Test(timeout = 30000L)
    fun bestAccessUsesFrozenVersionsAndChoosesWithinTheRequestedScope() = runBlocking {
        val first = source("first")
        val second = source("second")
        val left = audio(first, "left", "Shared.mp3", proof = "document:shared", content = "native:shared")
        val right = audio(second, "right", "Shared.mp3", proof = "document:shared", content = "native:shared")
        val firstScan = begin(first)
        library.commitSeen(firstScan, listOf(root(first), left, audio(first, "other", "Other.mp3")))
        finish(firstScan)
        val secondScan = begin(second)
        assertEquals(0L, library.commitSeen(secondScan, listOf(root(second), right)).added)
        finish(secondScan)
        val leftAlias = database.libraryDao().alias(first.spec.sourceId, left.objectId) ?: throw AssertionError("left alias")
        val rightAlias = database.libraryDao().alias(second.spec.sourceId, right.objectId) ?: throw AssertionError("right alias")
        assertEquals(leftAlias.locationId, rightAlias.locationId)
        val winner = if (leftAlias.aliasId < rightAlias.aliasId) leftAlias else rightAlias
        val alternate = if (winner == leftAlias) rightAlias else leftAlias
        val winnerSource = if (winner == leftAlias) first else second
        val alternateSource = if (winner == leftAlias) second else first
        val frozen = library.openView()
        try {
            assertEquals(2L, frozen.totalCount)
            val original = library.page(frozen).tracks.first { it.location?.locationId == leftAlias.locationId }
            assertEquals(winner.aliasId, original.aliasId)
            assertEquals(winnerSource.spec.sourceId, original.sourceId)
            library.setSourceIssue(winnerSource.spec.sourceId, winnerSource.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
            val current = rows().first { it.trackKey == original.trackKey }
            assertEquals(alternate.aliasId, current.aliasId)
            assertEquals(alternateSource.spec.sourceId, current.sourceId)
            assertEquals(LibraryAvailability.AVAILABLE, current.availability)
            val scoped = rows(LibraryQuery(sourceId = winnerSource.spec.sourceId)).first { it.trackKey == original.trackKey }
            assertEquals(winner.aliasId, scoped.aliasId)
            assertEquals(LibraryAvailability.NEEDS_AUTHORIZATION, scoped.availability)
            assertEquals(original, library.page(frozen).tracks.first { it.trackKey == original.trackKey })

            val rootId = alternateSource.spec.rootObjectId ?: throw AssertionError("root identity")
            val folder = database.libraryDao().node(alternateSource.spec.sourceId, rootId) ?: throw AssertionError("root node")
            library.addExclusion(library.rules().revision, alternateSource.spec.sourceId, folder.nodeId, true)
            val visible = rows().first { it.trackKey == original.trackKey }
            assertEquals(winner.aliasId, visible.aliasId)
            assertEquals(LibraryAvailability.NEEDS_AUTHORIZATION, visible.availability)
            assertTrue(rows(LibraryQuery(sourceId = alternateSource.spec.sourceId)).isEmpty())
            val hidden = rows(LibraryQuery(sourceId = alternateSource.spec.sourceId, includeHidden = true))
                .first { it.trackKey == original.trackKey }
            assertEquals(alternate.aliasId, hidden.aliasId)
            assertEquals(LibraryAvailability.AVAILABLE, hidden.availability)
            assertEquals(original, library.page(frozen).tracks.first { it.trackKey == original.trackKey })
        } finally { library.closeView(frozen.viewId) }
    }

    private fun open() {
        database = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context,
            MeloXDatabase::class.java, file.absolutePath).setDriver(BundledSQLiteDriver())
            .addMigrations(MeloXDatabase.MIGRATION_1_2).build()
        library = RoomLocalLibraryRepository(database, { 1000L }, { 1_790_000_000_000L })
    }

    private suspend fun source(id: String): LibrarySource = library.registerSource(LocalSourceSpec(id, LocalSourceKind.TREE,
        "content://library-regression/tree/$id", authority = "library-regression", rootObjectId = "root-$id",
        providerIncarnation = "fixture-instance"), LocalGrantObservation(read = ObservedCapability.AVAILABLE,
        enumerate = ObservedCapability.AVAILABLE))

    private fun root(source: LibrarySource) = ObservedCandidate(source.spec.sourceId,
        source.spec.rootObjectId ?: throw AssertionError("root"), source.spec.uri, null, source.spec.sourceId,
        "vnd.android.document/directory", LocalNodeKind.DIRECTORY)

    private fun directory(source: LibrarySource, id: String, name: String) = ObservedCandidate(source.spec.sourceId, id,
        "content://library-regression/document/${source.spec.sourceId}/$id", source.spec.rootObjectId, name,
        "vnd.android.document/directory", LocalNodeKind.DIRECTORY)

    private fun audio(source: LibrarySource, id: String, name: String, parent: String? = source.spec.rootObjectId,
        proof: String? = null, content: String = "native:$id:1") = ObservedCandidate(source.spec.sourceId, id,
        "content://library-regression/document/${source.spec.sourceId}/$id", parent, name, "audio/mpeg", LocalNodeKind.AUDIO,
        sizeBytes = 128L, providerContentToken = content, entityProof = proof)

    private suspend fun begin(source: LibrarySource): ScanToken = library.beginScan(source.spec.sourceId,
        library.source(source.spec.sourceId)?.authEpoch ?: throw AssertionError("source"), library.rules().revision, ScanMode.INCREMENTAL)

    private suspend fun finish(token: ScanToken) {
        while (true) {
            val next = library.nextDirectory(token) ?: break
            library.completeDirectory(token, next.objectId, true)
        }
        val coverage = ScanCoverage(true, true, true, true, false)
        library.setCoverage(token, coverage)
        library.finishScan(token, ScanCounters(), coverage)
    }

    private suspend fun rows(query: LibraryQuery = LibraryQuery()): List<LibraryTrack> {
        val view = library.openView(query)
        try { return library.page(view).tracks }
        finally { library.closeView(view.viewId) }
    }
}
