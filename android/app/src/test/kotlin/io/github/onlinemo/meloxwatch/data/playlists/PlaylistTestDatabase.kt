package io.github.onlinemo.meloxwatch.data.playlists

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.model.TrackKey
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

/** 独立SQLite的关系表和测试库/来源/扫描表；不注册应用曲库或队列。 */
@Database(entities = [
    PlaylistMetaEntity::class, PlaylistHeadEntity::class, PlaylistGenerationEntity::class,
    PlaylistMemberEntity::class, PlaylistViewEntity::class, PlaylistReceiptEntity::class,
    PlaylistFenceLibraryEntity::class, PlaylistFenceSourceEntity::class, PlaylistFenceScanEntity::class,
], version = 1, exportSchema = false)
abstract class PlaylistTestDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun fenceDao(): PlaylistFenceDao
}

@Entity(tableName = "playlist_fixture_library")
data class PlaylistFenceLibraryEntity(
    @PrimaryKey val id: Int = 1,
    val catalogRevision: Long,
    val rulesRevision: Long,
)

@Entity(tableName = "playlist_fixture_sources")
data class PlaylistFenceSourceEntity(
    @PrimaryKey val sourceId: String,
    val sourceRevision: Long,
    val authEpoch: Long,
    val authorization: String = "VALID",
)

@Entity(tableName = "playlist_fixture_scans")
data class PlaylistFenceScanEntity(
    @PrimaryKey val scanId: String,
    val sourceId: String,
    val sourceRevision: Long,
    val authEpoch: Long,
    val rulesRevision: Long,
    val status: String = "COMPLETE",
    val full: Boolean = true,
)

@Dao
interface PlaylistFenceDao {
    @Query("SELECT * FROM playlist_fixture_library WHERE id = 1")
    suspend fun library(): PlaylistFenceLibraryEntity?

    @Query("SELECT * FROM playlist_fixture_sources WHERE sourceId = :id")
    suspend fun source(id: String): PlaylistFenceSourceEntity?

    @Query("SELECT * FROM playlist_fixture_scans WHERE scanId = :id")
    suspend fun scan(id: String): PlaylistFenceScanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLibrary(row: PlaylistFenceLibraryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSource(row: PlaylistFenceSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putScan(row: PlaylistFenceScanEntity)
}

/** 每次最终提交读取同SQLite事务中的持久行；不会缓存调用前的“通过”状态。 */
class PlaylistRoomFenceVerifier(private val database: PlaylistTestDatabase) :
    PlaylistTransactionFenceVerifier, PlaylistLibraryTransactionFenceVerifier {
    var folderChecks = 0
        private set
    var libraryChecks = 0
        private set

    override suspend fun verify(revision: PlaylistLibraryRevision) {
        assertTrue(database.hasCurrentWriterTransaction())
        libraryChecks += 1
        val actual = database.fenceDao().library() ?: throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
        if (actual.catalogRevision != revision.catalogRevision || actual.rulesRevision != revision.rulesRevision) {
            throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        }
    }

    override suspend fun verify(fence: PlaylistCatalogFence) {
        assertTrue(database.hasCurrentWriterTransaction())
        folderChecks += 1
        val dao = database.fenceDao()
        val library = dao.library() ?: throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
        if (library.catalogRevision != fence.catalogRevision || library.rulesRevision != fence.rulesRevision) {
            throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        }
        val source = dao.source(fence.sourceId) ?: throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
        if (source.authorization != "VALID") throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
        if (source.sourceRevision != fence.sourceRevision || source.authEpoch != fence.authEpoch) {
            throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        }
        val scan = dao.scan(fence.scanId) ?: throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        if (scan.sourceId != fence.sourceId || scan.sourceRevision != fence.sourceRevision ||
            scan.authEpoch != fence.authEpoch || scan.rulesRevision != fence.rulesRevision) {
            throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        }
        if (scan.status != "COMPLETE" || !scan.full) throw PlaylistException(PlaylistIssue.INCOMPLETE)
    }
}

abstract class PlaylistRoomFixture {
    @get:Rule val folder = TemporaryFolder()
    protected lateinit var file: File
    protected lateinit var database: PlaylistTestDatabase
    protected lateinit var repository: RoomPlaylistRepository
    protected var elapsed = 1_000L
    protected var epoch = 1_794_787_200_000L
    protected var process = "playlist-fixture-process"

    @Before fun openPlaylistDatabase() = runBlocking {
        file = File(folder.root, "playlists.db")
        database = connect()
        repository = newRepository()
        assertEquals(PlaylistOverview(0L, 0L, 0L), repository.initialize())
    }

    @After fun closePlaylistDatabase() {
        database.close()
    }

    protected fun newRepository(
        dao: PlaylistDao = database.playlistDao(),
        folderFenceVerifier: PlaylistTransactionFenceVerifier? = null,
        libraryFenceVerifier: PlaylistLibraryTransactionFenceVerifier? = null,
    ) = RoomPlaylistRepository(
        database, dao, elapsedMs = { elapsed }, epochMs = { epoch }, processId = process,
        folderFenceVerifier = folderFenceVerifier, libraryFenceVerifier = libraryFenceVerifier,
    )

    protected fun useFenceVerifier(dao: PlaylistDao = database.playlistDao()): PlaylistRoomFenceVerifier {
        val verifier = PlaylistRoomFenceVerifier(database)
        repository = newRepository(dao, verifier, verifier)
        return verifier
    }

    protected suspend fun prepareFence(
        sourceId: String = "folder-source",
        scanId: String = "complete-scan",
        catalogRevision: Long = 7L,
        rulesRevision: Long = 3L,
        sourceRevision: Long = 4L,
        authEpoch: Long = 2L,
    ): PlaylistCatalogFence = database.withPreservedTransaction {
        val dao = database.fenceDao()
        dao.putLibrary(PlaylistFenceLibraryEntity(catalogRevision = catalogRevision, rulesRevision = rulesRevision))
        dao.putSource(PlaylistFenceSourceEntity(sourceId, sourceRevision, authEpoch))
        dao.putScan(PlaylistFenceScanEntity(scanId, sourceId, sourceRevision, authEpoch, rulesRevision))
        PlaylistCatalogFence(catalogRevision, rulesRevision, sourceId, sourceRevision, authEpoch, scanId)
    }

    protected suspend fun createFolder(
        id: String,
        synchronization: PlaylistFolderSynchronization,
        source: PlaylistPageSource,
        name: String = id,
    ): PlaylistSnapshot = checkNotNull(repository.apply(PlaylistMutationRequest("create-$id", id, 0L,
        PlaylistMutation.CreateFolder(name, synchronization)), source).snapshot)

    protected suspend fun reopen(nextProcess: String = process) {
        database.close()
        process = nextProcess
        database = connect()
        repository = newRepository()
        repository.initialize()
    }

    protected suspend fun create(
        id: String,
        name: String = id,
        references: List<PlaylistReference> = emptyList(),
    ): PlaylistSnapshot {
        val input = references.takeIf { it.isNotEmpty() }?.let { FrozenPlaylistInput("input-$id", 0L, it.size.toLong()) }
        return checkNotNull(repository.apply(PlaylistMutationRequest("create-$id", id, 0L,
            PlaylistMutation.Create(name, input)), input?.let { listSource(it, references) }).snapshot)
    }

    protected fun request(operation: String, snapshot: PlaylistSnapshot, mutation: PlaylistMutation) =
        PlaylistMutationRequest(operation, snapshot.playlistId, snapshot.revision, mutation)

    protected fun reference(index: Long) = PlaylistReference(
        TrackKey(TrackKey.LOCAL_PROVIDER, "track-$index"), titleHint = "Saved title $index",
    )

    protected fun listSource(input: FrozenPlaylistInput, references: List<PlaylistReference>): PlaylistPageSource {
        val frozen = references.toList()
        return PlaylistPageSource { offset, limit ->
            assertFalse(database.hasCurrentWriterTransaction())
            assertTrue(limit in 1..PlaylistLimits.PAGE_SIZE)
            val end = minOf(frozen.size.toLong(), offset + limit).toInt()
            PlaylistInputPage(input, offset, frozen.subList(offset.toInt(), end).toList(), end.toLong() == input.totalCount)
        }
    }

    protected fun generatedSource(input: FrozenPlaylistInput): PlaylistPageSource = PlaylistPageSource { offset, limit ->
        assertFalse(database.hasCurrentWriterTransaction())
        assertTrue(limit in 1..PlaylistLimits.PAGE_SIZE)
        val count = minOf(limit.toLong(), input.totalCount - offset).toInt()
        PlaylistInputPage(input, offset, List(count) { reference(offset + it) }, offset + count == input.totalCount)
    }

    protected suspend fun allMembers(snapshot: PlaylistSnapshot): List<PlaylistMember> {
        val view = repository.openView(snapshot.playlistId)
        assertEquals(snapshot, view.snapshot)
        try { return allMembers(view) } finally { repository.closeView(view.viewId) }
    }

    protected suspend fun allMembers(view: PlaylistViewToken): List<PlaylistMember> {
        val members = ArrayList<PlaylistMember>()
        var first = 0L
        do {
            val page = repository.page(view, first)
            assertTrue(page.members.size <= PlaylistLimits.PAGE_SIZE)
            members.addAll(page.members)
            first = page.nextOrdinal ?: break
        } while (true)
        assertEquals(view.snapshot.memberCount, members.size.toLong())
        return members
    }

    protected suspend fun scalar(sql: String, vararg args: Any): Long = database.withTestWriterTransaction {
        scalarLong(sql, *args)
    }

    protected suspend fun execute(sql: String) = database.withTestWriterTransaction {
        execute(sql)
    }

    protected suspend fun integrity() = database.withTestWriterTransaction {
        query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
        query("PRAGMA integrity_check") {
            assertTrue(it.step())
            assertEquals("ok", it.getText(0))
        }
    }

    protected suspend fun expectIssue(issue: PlaylistIssue, action: suspend () -> Unit) {
        try { action() } catch (error: PlaylistException) { assertEquals(issue, error.issue); return }
        throw AssertionError("expected $issue")
    }

    private fun connect() = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context,
        PlaylistTestDatabase::class.java, file.absolutePath).setDriver(BundledSQLiteDriver()).build()
}
