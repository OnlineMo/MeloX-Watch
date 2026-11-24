package io.github.onlinemo.meloxwatch.data.queue

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withTestReaderSql
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.library.LibraryCursor
import io.github.onlinemo.meloxwatch.data.library.LibraryDao
import io.github.onlinemo.meloxwatch.data.library.LibraryLimits
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
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
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueLimits
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Production Room projection over native SQLite and a synthetic library; not device or UI acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QueueArtistReaderNativeTest {
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository
    private lateinit var source: LibrarySource
    private lateinit var reader: QueueArtistReader
    private val queries = ArrayList<LabelQuery>()
    private var nextObject = 0

    @Before
    fun openDatabase() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context, MeloXDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3).build()
        library = RoomLocalLibraryRepository(database, { 1000L }, { 1_790_000_000_000L })
        library.initialize()
        source = library.registerSource(LocalSourceSpec("queue-label-fixture", LocalSourceKind.TREE,
            "content://queue-label-fixture/tree/root", authority = "queue-label-fixture", rootObjectId = "root",
            providerIncarnation = "synthetic-instance"),
            LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE))
        // Only counts calls; every query still executes the real generated Room DAO.
        val dao = database.libraryDao()
        val counting = Proxy.newProxyInstance(LibraryDao::class.java.classLoader, arrayOf(LibraryDao::class.java)) { _, method, arguments ->
            val args = arguments ?: emptyArray()
            if (method.name == "artistLabels") {
                queries += LabelQuery(args[0] as String, (args[1] as List<*>).map { it as String }, args[2] as Int)
            }
            try { method.invoke(dao, *args) }
            catch (error: InvocationTargetException) { throw error.targetException }
        } as LibraryDao
        reader = QueueArtistReader(counting)
    }

    @After
    fun closeDatabase() { database.close() }

    @Test(timeout = 30000L)
    fun sameTitlesAndRepeatedInstancesUseExactLocalKeysInOneBatch() = runBlocking {
        val tracks = addTracks(listOf("Artist A", "Artist B"))
        val entries = listOf(entry(tracks[0].trackKey, 0), entry(tracks[1].trackKey, 1),
            entry(tracks[0].trackKey, 2), entry(TrackKey("synthetic-online", tracks[0].trackKey.trackId), 3))
        val labels = reader.read(entries)
        assertEquals(tracks.associate { it.trackKey to it.metadata.artist }, labels)
        assertEquals(setOf("Artist A", "Artist B"), labels.values.toSet())
        assertEquals(2, labels.size)
        assertEquals(1, queries.size)
        assertEquals(TrackKey.LOCAL_PROVIDER, queries.single().provider)
        assertEquals(tracks.map { it.trackKey.trackId }.toSet(), queries.single().trackIds.toSet())
        assertEquals(2, queries.single().limit)
    }

    @Test(timeout = 30000L)
    fun absentTagsAndLiteralSeparatorsStayDistinctFromMissingTracks() = runBlocking {
        val tracks = addTracks(listOf(null, "AC/DC; A / B & 日本語 🎵"))
        val missing = TrackKey(TrackKey.LOCAL_PROVIDER, "not-in-library")
        val labels = reader.read(tracks.mapIndexed { index, track -> entry(track.trackKey, index) } + entry(missing, 2))
        assertEquals(tracks.associate { it.trackKey to it.metadata.artist }, labels)
        assertEquals(setOf(null, "AC/DC; A / B & 日本語 🎵"), labels.values.toSet())
        val withoutTag = tracks.single { it.metadata.artist == null }.trackKey
        assertTrue(labels.containsKey(withoutTag))
        assertNull(labels[withoutTag])
        assertFalse(labels.containsKey(missing))
    }

    @Test(timeout = 30000L)
    fun aFullPageAndItsFinalRemainderQueryOnlyTheirOwnKeys() = runBlocking {
        val tracks = addTracks(List(QueueLimits.PAGE_SIZE + 1) { "Synthetic artist $it" })
        val first = tracks.take(QueueLimits.PAGE_SIZE)
        val remainder = tracks.last()
        val labels = reader.read(first.mapIndexed { index, track -> entry(track.trackKey, index) })
        assertEquals(first.associate { it.trackKey to it.metadata.artist }, labels)
        assertFalse(labels.containsKey(remainder.trackKey))
        assertEquals(mapOf(remainder.trackKey to remainder.metadata.artist), reader.read(listOf(entry(remainder.trackKey, 100))))
        assertEquals(listOf(100, 1), queries.map { it.limit })
        assertEquals(listOf(100, 1), queries.map { it.trackIds.size })
    }

    @Test(timeout = 30000L)
    fun oversizedInputIsRejectedBeforeAnyDatabaseQuery() = runBlocking {
        try {
            reader.read(List(QueueLimits.PAGE_SIZE + 1) { entry(TrackKey(TrackKey.LOCAL_PROVIDER, "synthetic-$it"), it) })
            fail("Expected the page read budget to be enforced")
        } catch (_: IllegalArgumentException) { }
        assertTrue(queries.isEmpty())
    }

    @Test(timeout = 30000L)
    fun emptyAndNonLocalPagesDoNotQueryTheLocalLibrary() = runBlocking {
        assertTrue(reader.read(emptyList()).isEmpty())
        assertTrue(reader.read(listOf(entry(TrackKey("synthetic-online", "same-id"), 0))).isEmpty())
        assertTrue(queries.isEmpty())
    }

    @Test(timeout = 30000L)
    fun cachedLabelsDoNotTurnAnUnindexedKeyIntoAnArtist() = runBlocking {
        val labels = reader.read(listOf(entry(TrackKey(TrackKey.LOCAL_PROVIDER, "missing-tag-record"), 0)))
        assertTrue(labels.isEmpty())
        assertEquals(1, queries.single().limit)
    }

    @Test(timeout = 30000L)
    fun currentLabelsDoNotReadAClosedHistoricalVersion() = runBlocking {
        val track = addTracks(listOf("Before")).single()
        val oldView = library.openView(LibraryQuery())
        try {
            val token = begin()
            library.commitSeen(token, listOf(root(), audio(0)))
            library.commitMetadata(token, listOf(MetadataOutcome("synthetic-0", metadata("After"))))
            finish(token)
            val historical = database.withTestReaderSql {
                scalarLong("SELECT COUNT(*) FROM local_track_versions WHERE trackId = ? AND validTo IS NOT NULL", track.trackKey.trackId)
            }
            assertTrue(historical > 0L)
            assertEquals(mapOf(track.trackKey to "After"), reader.read(listOf(entry(track.trackKey, 0))))
        } finally { library.closeView(oldView.viewId) }
    }

    @Test(timeout = 30000L)
    fun damagedOversizedLabelsRespectBothSqlAndUtf16Bounds() = runBlocking {
        val tracks = addTracks(listOf("First", "Second", "Third"))
        database.withTestWriterSql {
            execute("UPDATE local_track_versions SET artist = ? WHERE trackId = ? AND validTo IS NULL", "x".repeat(4097), tracks[0].trackKey.trackId)
            execute("UPDATE local_track_versions SET artist = ? WHERE trackId = ? AND validTo IS NULL", "🎵".repeat(2049), tracks[1].trackKey.trackId)
            execute("UPDATE local_track_versions SET artist = ? WHERE trackId = ? AND validTo IS NULL", "x".repeat(4096), tracks[2].trackKey.trackId)
        }
        val labels = reader.read(tracks.mapIndexed { index, track -> entry(track.trackKey, index) })
        assertEquals(3, labels.size)
        assertNull(labels[tracks[0].trackKey])
        assertNull(labels[tracks[1].trackKey])
        assertEquals("x".repeat(4096), labels[tracks[2].trackKey])
    }

    @Test(timeout = 30000L)
    fun duplicatedCurrentRecordsFailInsteadOfPublishingAnAmbiguousMap() = runBlocking {
        val tracks = addTracks(listOf("A", "B")).sortedBy { it.trackKey.trackId }
        // Ambiguity at the final key must not be concealed by the result-row limit.
        val current = checkNotNull(database.libraryDao().currentTrack(tracks.last().trackKey.trackId))
        database.libraryDao().insertTrackVersion(current.copy(versionId = "synthetic-corrupt-version", validFrom = current.validFrom + 1L))
        try {
            reader.read(tracks.mapIndexed { index, track -> entry(track.trackKey, index) })
            fail("Expected an ambiguous current record to be rejected")
        } catch (_: IllegalStateException) { }
        assertEquals(1, queries.size)
    }

    private fun entry(key: TrackKey, index: Int) = QueueEntry("synthetic-entry-$index", key,
        index.toLong(), index.toLong(), displayName = "Same title", durationMs = 180_000L)

    private fun metadata(artist: String?) = LocalAudioMetadata(title = "Same title", artist = artist,
        durationMs = 180_000L, mimeType = "audio/mpeg")

    private fun root() = ObservedCandidate(source.spec.sourceId, "root", source.spec.uri, null,
        "Synthetic library", "vnd.android.document/directory", LocalNodeKind.DIRECTORY)

    private fun audio(index: Int) = ObservedCandidate(source.spec.sourceId, "synthetic-$index",
        "content://queue-label-fixture/document/synthetic-$index", "root", "same.mp3", "audio/mpeg",
        LocalNodeKind.AUDIO, sizeBytes = 128L, providerContentToken = "synthetic-content-$index:1")

    private suspend fun begin(): ScanToken = library.beginScan(source.spec.sourceId,
        checkNotNull(library.source(source.spec.sourceId)).authEpoch, library.rules().revision, ScanMode.INCREMENTAL)

    private suspend fun finish(token: ScanToken) {
        library.nextDirectory(token)?.let { library.completeDirectory(token, it.objectId, true) }
        check(library.nextDirectory(token) == null)
        val coverage = ScanCoverage(true, true, true, true, false)
        library.setCoverage(token, coverage)
        library.finishScan(token, ScanCounters(), coverage)
    }

    private suspend fun addTracks(artists: List<String?>): List<LibraryTrack> {
        check(artists.size in 1..101) // This fixture is deliberately small; no product queue cap is introduced.
        val token = begin()
        library.commitSeen(token, listOf(root()))
        val candidates = artists.map { audio(nextObject++) }
        candidates.chunked(LibraryLimits.PAGE_SIZE).forEach { library.commitSeen(token, it) }
        candidates.zip(artists).map { (candidate, artist) -> MetadataOutcome(candidate.objectId, metadata(artist)) }
            .chunked(LibraryLimits.PAGE_SIZE).forEach { library.commitMetadata(token, it) }
        finish(token)
        val view = library.openView(LibraryQuery())
        try {
            val tracks = ArrayList<LibraryTrack>()
            var cursor: LibraryCursor? = null
            do {
                val page = library.page(view, cursor)
                tracks += page.tracks
                cursor = page.nextCursor
            } while (cursor != null)
            assertEquals(artists.size, tracks.size)
            return tracks
        } finally { library.closeView(view.viewId) }
    }

    private data class LabelQuery(val provider: String, val trackIds: List<String>, val limit: Int)
}
