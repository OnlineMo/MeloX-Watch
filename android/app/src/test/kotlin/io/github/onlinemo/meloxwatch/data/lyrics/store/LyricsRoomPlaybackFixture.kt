package io.github.onlinemo.meloxwatch.data.lyrics.store

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.TestDatabaseSql
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.data.library.LibraryCodec
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadBinding
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

/** The generated application database is required; these rows do not establish Android grant/device acceptance. */
internal open class LyricsRoomPlaybackFixture {
    @get:Rule val temporaryFolder = TemporaryFolder()
    protected lateinit var file: File
    protected lateinit var database: MeloXDatabase
    protected lateinit var store: RoomLyricsTrackStore
    protected val trackKey = TrackKey(TrackKey.LOCAL_PROVIDER, LyricsRoomRows.TRACK_ID)
    protected val fence = LyricsRoomRows.fence()

    @Before
    fun openLyricsDatabase() = runBlocking {
        file = File(temporaryFolder.root, "lyrics.db")
        database = openDatabase(file)
        database.withTestWriterTransaction {
            LyricsRoomRows.seed { table, values -> insertFixture(table, values) }
        }
        store = RoomLyricsTrackStore(database, database.lyricsDao())
    }

    @After
    fun closeLyricsDatabase() { database.close() }

    protected fun openDatabase(target: File): MeloXDatabase = Room.databaseBuilder(
        RuntimeEnvironment.getApplication() as Context, MeloXDatabase::class.java, target.absolutePath,
    ).setDriver(BundledSQLiteDriver())
        .addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3, MeloXDatabase.MIGRATION_3_4).build()

    protected fun reopen() {
        database.close()
        database = openDatabase(file)
        store = RoomLyricsTrackStore(database, database.lyricsDao())
    }

    protected suspend fun ready(key: TrackKey = trackKey): LyricsTrackPreferences =
        (store.read(key) as LyricsTrackReadResult.Ready).preferences

    protected suspend fun execute(sql: String, vararg args: Any) = database.withTestWriterTransaction {
        execute(sql, *args)
    }

    protected suspend fun scalar(sql: String): Long = database.withTestWriterTransaction { scalarInside(sql) }

    protected suspend fun scalarInside(sql: String): Long = database.withTestWriterSql {
        query(sql) {
            assertTrue(it.step())
            it.getLong(0)
        }
    }

    protected suspend fun assertIntegrity() = database.withTestWriterTransaction {
        query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
        query("PRAGMA integrity_check") {
            assertTrue(it.step())
            assertEquals("ok", it.getText(0))
        }
    }
}

/** Fixed row data is bound through the same public native SQL scope as the assertions. */
private suspend fun TestDatabaseSql.insertFixture(table: String, values: ContentValues) {
    val columns = values.keySet().toList()
    require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
    require(columns.isNotEmpty() && columns.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) })
    val names = columns.joinToString(", ") { "`$it`" }
    val parameters = columns.joinToString(", ") { "?" }
    execute("INSERT INTO `$table` ($names) VALUES ($parameters)", *columns.map(values::get).toTypedArray())
}

internal object LyricsRoomRows {
    const val TRACK_ID = "lyrics-audio-track"
    const val OTHER_TRACK_ID = "lyrics-other-track"
    const val SOURCE_ID = "lyrics-audio-source"
    const val LOCATION_ID = "lyrics-audio-location"
    const val OTHER_LOCATION_ID = "lyrics-other-location"
    const val GENERATION_ID = "lyrics-generation-one"
    const val OTHER_GENERATION_ID = "lyrics-generation-two"
    const val ENTRY_ID = "lyrics-entry-one"
    const val DUPLICATE_ENTRY_ID = "lyrics-entry-duplicate"
    const val OTHER_ENTRY_ID = "lyrics-entry-other"
    const val QUEUE_REVISION = 5L
    const val CONTENT_REVISION = 7L
    const val SOURCE_REVISION = 3L
    const val AUTH_EPOCH = 2L
    private const val EPOCH_MS = 1_794_787_200_000L

    fun fence(
        other: Boolean = false,
        queueRevision: Long = if (other) 6L else QUEUE_REVISION,
    ) = LyricsTrackWriteFence(
        LyricsLoadBinding(TrackKey(TrackKey.LOCAL_PROVIDER, if (other) OTHER_TRACK_ID else TRACK_ID),
            if (other) OTHER_LOCATION_ID else LOCATION_ID, CONTENT_REVISION, SOURCE_ID, SOURCE_REVISION, AUTH_EPOCH,
            requestGeneration = 11L, sourceGeneration = 12L),
        if (other) OTHER_GENERATION_ID else GENERATION_ID, queueRevision, if (other) OTHER_ENTRY_ID else ENTRY_ID,
    )

    fun association(translation: Boolean = false) = LyricsAssociation(
        if (translation) "translation-document" else "primary-document", "manual-lyrics-source",
        "content://lyrics-fixture/document/" + if (translation) "translation.lrc" else "primary.lrc",
        LyricsTrackLimits.READ_PERMISSION_FLAG,
        if (translation) LyricsEncodingChoice.GB18030 else LyricsEncodingChoice.AUTO_BOM_OR_UTF8,
        (if (translation) "b" else "a").repeat(64),
    )

    suspend fun seed(insert: suspend (String, ContentValues) -> Unit) {
        suspend fun row(table: String, vararg fields: Pair<String, Any?>) {
            val values = ContentValues().apply {
                for ((name, value) in fields) when (value) {
                    null -> putNull(name)
                    is String -> put(name, value)
                    is Int -> put(name, value)
                    is Long -> put(name, value)
                    else -> throw AssertionError("Unsupported fixture storage type")
                }
            }
            insert(table, values)
        }
        val source = LocalSourceSpec(SOURCE_ID, LocalSourceKind.TREE, "content://lyrics-fixture/tree/audio",
            grantFlags = 1, authority = "lyrics-fixture", rootObjectId = "audio-root", providerIncarnation = "fixture-instance")
        val grant = LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE,
            persistedRead = ObservedCapability.AVAILABLE, returnedFlags = 1, acceptedPersistedFlags = 1, persistenceAttempted = true)
        val spec = LibraryCodec.source(source)
        row("library_meta", "id" to 1, "revision" to 1L, "rulesRevision" to 0L, "nextAddedSequence" to 3L,
            "nextScanSequence" to 1L, "shortAudioFilter" to 0, "statisticsEpoch" to 0L)
        row("local_sources", "sourceId" to SOURCE_ID, "spec" to spec, "grantId" to "lyrics-audio-grant",
            "revision" to SOURCE_REVISION, "authEpoch" to AUTH_EPOCH, "state" to "ACTIVE", "activeScanId" to null,
            "latestScanId" to null, "absenceScanId" to null, "absenceScanSequence" to null, "watermark" to null)
        row("local_grants", "grantId" to "lyrics-audio-grant", "sourceId" to SOURCE_ID, "uri" to source.uri,
            "observation" to LibraryCodec.grant(grant), "readCapability" to "AVAILABLE", "acceptedPersistedFlags" to 1, "appOwned" to 0)
        row("local_source_versions", "sourceId" to SOURCE_ID, "validFrom" to 1L, "validTo" to null, "state" to "ACTIVE",
            "spec" to spec, "grantId" to "lyrics-audio-grant", "absenceScanId" to null, "absenceScanSequence" to null,
            "rootObjectId" to "audio-root")
        for ((index, other) in listOf(false, true).withIndex()) {
            val track = if (other) OTHER_TRACK_ID else TRACK_ID
            val location = if (other) OTHER_LOCATION_ID else LOCATION_ID
            val suffix = if (other) "other" else "audio"
            row("local_locations", "locationId" to location, "identityKey" to "lyrics-$suffix-identity",
                "contentRevision" to CONTENT_REVISION, "contentToken" to "native:$suffix:7", "verifiedContentToken" to "verified:$suffix:7",
                "previousVerifiedContentToken" to null, "sizeBytes" to 1024L, "modifiedEpochMs" to EPOCH_MS, "appOwned" to 0)
            row("local_tracks", "trackId" to track, "providerId" to TrackKey.LOCAL_PROVIDER,
                "firstAddedEpochMs" to EPOCH_MS, "firstAddedSequence" to index + 1L)
            row("local_track_locations", "relationId" to "lyrics-$suffix-relation", "trackId" to track,
                "locationId" to location, "validFrom" to 1L, "validTo" to null)
            row("local_nodes", "nodeId" to "lyrics-$suffix-node", "sourceId" to SOURCE_ID,
                "objectId" to "$suffix-object", "lastKnownName" to "$suffix.mp3")
            row("local_access_aliases", "aliasId" to "lyrics-$suffix-alias", "sourceId" to SOURCE_ID,
                "objectId" to "$suffix-object", "locationId" to location)
            row("local_alias_versions", "versionId" to "lyrics-$suffix-alias-version", "aliasId" to "lyrics-$suffix-alias",
                "sourceId" to SOURCE_ID, "locationId" to location, "nodeId" to "lyrics-$suffix-node",
                "uri" to "content://lyrics-fixture/document/$suffix-object", "filename" to "$suffix.mp3", "readIssue" to null,
                "lastSeenSequence" to 0L, "contentRevision" to CONTENT_REVISION, "providerContentToken" to "native:$suffix:7",
                "sizeBytes" to 1024L, "validFrom" to 1L, "validTo" to null)
        }
        for (other in listOf(false, true)) {
            val generation = if (other) OTHER_GENERATION_ID else GENERATION_ID
            val count = if (other) 1L else 2L
            row("queue_generations", "generationId" to generation, "operationId" to "$generation-operation",
                "expectedGenerationId" to null, "expectedQueueRevision" to 0L, "viewId" to "$generation-view",
                "viewRevision" to 1L, "totalCount" to count, "shuffle" to 0, "repeatMode" to "OFF", "cycleLimit" to null,
                "startOriginalOrder" to 0L, "initialPositionMs" to 0L, "allowEmpty" to 0, "phase" to 2,
                "writtenCount" to count, "committedQueueRevision" to if (other) 6L else QUEUE_REVISION)
            repeat(count.toInt()) { ordinal ->
                val suffix = if (other) "other" else "audio"
                row("queue_entries", "generationId" to generation,
                    "queueEntryId" to if (other) OTHER_ENTRY_ID else if (ordinal == 0) ENTRY_ID else DUPLICATE_ENTRY_ID,
                    "providerId" to TrackKey.LOCAL_PROVIDER, "trackId" to if (other) OTHER_TRACK_ID else TRACK_ID,
                    "originalOrder" to ordinal.toLong(), "playbackOrder" to ordinal.toLong(), "displayName" to "$suffix.mp3",
                    "durationMs" to 180_000L, "accountId" to null, "locationId" to if (other) OTHER_LOCATION_ID else LOCATION_ID,
                    "locationUri" to "content://lyrics-fixture/document/$suffix-object", "grantUri" to source.uri,
                    "grantFlags" to 1, "access" to "PERSISTED", "contentRevision" to CONTENT_REVISION)
            }
        }
        row("queue_head", "id" to 1, "activeGenerationId" to GENERATION_ID, "queueRevision" to QUEUE_REVISION,
            "checkpointRevision" to 2L, "currentEntryId" to ENTRY_ID, "positionMs" to 1200L, "completedCycles" to 0)
    }
}
