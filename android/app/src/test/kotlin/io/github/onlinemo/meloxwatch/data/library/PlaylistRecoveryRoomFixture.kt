package io.github.onlinemo.meloxwatch.data.library

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistDao
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReferenceIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.RoomPlaylistRepository
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistRecoveryAction
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistRecoveryTarget
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment

/** 生产MeloXDatabase、索引扫描和真实歌单仓库；无provider/播放器或网络替身验收。 */
abstract class PlaylistRecoveryRoomFixture {
    @get:Rule val temporaryFolder = TemporaryFolder()
    protected lateinit var file: File
    protected lateinit var database: MeloXDatabase
    protected lateinit var library: RoomLocalLibraryRepository
    protected lateinit var playlists: RoomPlaylistRepository
    protected lateinit var controller: RoomPlaylistRecoveryController
    protected lateinit var source: LibrarySource
    protected lateinit var replacement: TrackKey
    protected lateinit var sameNamedDecoy: TrackKey
    protected var elapsed = 1_000L
    protected var epoch = 1_794_787_200_000L
    protected var process = "recovery-fixture-process"

    @Before fun openRecoveryDatabase() = runBlocking {
        file = File(temporaryFolder.root, "playlist-recovery.db")
        database = open()
        library = RoomLocalLibraryRepository(database, { elapsed }, { epoch })
        library.initialize()
        playlists = repository()
        playlists.initialize()
        source = library.registerSource(LocalSourceSpec("selected-source", LocalSourceKind.TREE,
            "content://recovery-fixture/tree/music", authority = "recovery-fixture", rootObjectId = "root-object",
            providerIncarnation = "fixture-instance"),
            LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE))
        val token = library.beginScan(source.spec.sourceId, source.authEpoch, library.rules().revision, ScanMode.FULL)
        val root = ObservedCandidate(source.spec.sourceId, "root-object", source.spec.uri, null, "Music",
            "vnd.android.document/directory", LocalNodeKind.DIRECTORY)
        val candidates = listOf("selected-object", "decoy-object").map { objectId ->
            ObservedCandidate(source.spec.sourceId, objectId, "content://recovery-fixture/document/$objectId", "root-object",
                "Same.mp3", "audio/mpeg", LocalNodeKind.AUDIO, sizeBytes = 128L,
                providerContentToken = "native:$objectId:1")
        }
        library.commitSeen(token, listOf(root) + candidates)
        library.commitMetadata(token, listOf(
            MetadataOutcome("selected-object", LocalAudioMetadata(title = "Selected actual title", artist = "Actual artist",
                album = "Actual album", durationMs = 120_000L)),
            MetadataOutcome("decoy-object", LocalAudioMetadata(title = "Different same-named audio", durationMs = 90_000L)),
        ))
        while (true) {
            val directory = library.nextDirectory(token) ?: break
            library.completeDirectory(token, directory.objectId, true)
        }
        val coverage = ScanCoverage(true, true, true, true, false)
        library.setCoverage(token, coverage)
        var after: String? = null
        while (true) {
            val page = library.stageAbsence(token, after)
            if (page.complete) break
            after = page.nextAliasId
        }
        library.finishScan(token, ScanCounters(), coverage)
        val view = library.openView()
        try {
            val tracks = library.page(view).tracks
            assertEquals(2, tracks.size)
            val selectedAlias = checkNotNull(database.libraryDao().alias(source.spec.sourceId, "selected-object"))
            replacement = tracks.single { it.aliasId == selectedAlias.aliasId }.trackKey
            sameNamedDecoy = tracks.single { it.trackKey != replacement }.trackKey
            assertTrue(tracks.all { it.availability == LibraryAvailability.AVAILABLE })
        } finally { library.closeView(view.viewId) }
        controller = recovery()
    }

    @After fun closeRecoveryDatabase() { database.close() }

    protected fun repository(dao: PlaylistDao = database.playlistDao()): RoomPlaylistRepository {
        val fences = RoomPlaylistFences(database)
        return RoomPlaylistRepository(database, dao, { elapsed }, { epoch }, process, fences, fences)
    }

    protected fun recovery(
        playlistPort: LocalPlaylistRepository = playlists,
        libraryPort: LocalLibraryRepository = library,
        closeLibraryView: suspend (String) -> Unit = { library.closePlaylistView(it, 5_000L) },
    ) = RoomPlaylistRecoveryController(database, playlistPort, libraryPort, { elapsed }, closeLibraryView)

    protected suspend fun target(id: String = "manual-playlist", references: List<PlaylistReference> = listOf(
        PlaylistReference(sameNamedDecoy, titleHint = "Unrelated first member"),
        PlaylistReference(placeholderId = "missing-description", issue = PlaylistReferenceIssue.REQUIRES_REASSOCIATION,
            titleHint = "Different same-named audio", descriptionHint = "content://untrusted/descriptor-only"),
        PlaylistReference(TrackKey(TrackKey.LOCAL_PROVIDER, "retained-missing-track"), titleHint = "Retained tail"),
    )): PlaylistRecoveryTarget {
        val input = FrozenPlaylistInput("input-$id", 0L, references.size.toLong())
        val snapshot = checkNotNull(playlists.apply(PlaylistMutationRequest("create-$id", id, 0L,
            PlaylistMutation.Create("Manual playlist", input)), PlaylistPageSource { offset, limit ->
                assertFalse(database.inTransaction())
                val end = minOf(references.size.toLong(), offset + limit).toInt()
                PlaylistInputPage(input, offset, references.subList(offset.toInt(), end).toList(), end.toLong() == input.totalCount)
            }).snapshot)
        return targetFrom(snapshot, minOf(1L, snapshot.memberCount - 1L))
    }

    protected suspend fun targetFrom(snapshot: PlaylistSnapshot, ordinal: Long = 1L): PlaylistRecoveryTarget {
        val view = playlists.openView(snapshot.playlistId)
        assertEquals(snapshot, view.snapshot)
        val member = playlists.page(view, ordinal, 1).members.single()
        return PlaylistRecoveryTarget(view, member, PlaylistQueueAvailability.UNRESOLVED, PlaylistRecoveryAction.REASSOCIATE)
    }

    protected suspend fun members(snapshot: PlaylistSnapshot): List<PlaylistMember> {
        val view = playlists.openView(snapshot.playlistId)
        try {
            val result = mutableListOf<PlaylistMember>()
            var first = 0L
            do {
                val page = playlists.page(view, first)
                result += page.members
                first = page.nextOrdinal ?: break
            } while (true)
            assertEquals(snapshot.memberCount, result.size.toLong())
            return result
        } finally { playlists.closeView(view.viewId) }
    }

    protected suspend fun reopen() {
        database.close()
        process = "next-recovery-process"
        database = open()
        library = RoomLocalLibraryRepository(database, { elapsed }, { epoch })
        library.initialize()
        playlists = repository()
        playlists.initialize()
        controller = recovery()
    }

    protected suspend fun scalar(sql: String): Long = database.withPreservedTransaction {
        database.libraryDao().scalar(SimpleSQLiteQuery(sql))
    }

    protected suspend fun integrity() = database.withPreservedTransaction {
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        database.openHelper.writableDatabase.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
    }

    protected suspend fun expectIssue(expected: PlaylistIssue, operation: suspend () -> Unit) {
        try { operation() }
        catch (error: PlaylistException) {
            assertEquals(expected, error.issue)
            return
        }
        throw AssertionError("Expected $expected")
    }

    private fun open(): MeloXDatabase = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context,
        MeloXDatabase::class.java, file.absolutePath).addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3).build()
}
