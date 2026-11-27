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
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalCodecParametersPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = LocalCodecParameters("APE", "PCM", 48_000, 16, 2, 48_000, 16, 3990, 1000)
    private val second = first.copy(inputSampleRateHz = 44_100, pcmSampleRateHz = 44_100, compressionLevel = 2000)

    @Test(timeout = 30_000L)
    fun actualScanProjectionAndFrozenViewRetainTheirOwnParametersAcrossReopenAndFailedMetadataRefresh() = runBlocking {
        val file = File(temporary.root, "codec-projection.db")
        var database = open(file)
        try {
            var library = RoomLocalLibraryRepository(database)
            library.initialize()
            val source = library.registerSource(LocalSourceSpec("codec-source", LocalSourceKind.TREE,
                "content://codec-fixture/tree/root", authority = "codec-fixture", rootObjectId = "root"),
                LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE))
            val metadata = LocalAudioMetadata(title = "Observed codec", durationMs = 61_000L, parserVersion = 2, codecParameters = first)
            scan(library, source, MetadataOutcome("audio", metadata))
            val frozen = library.openView()
            val key = library.page(frozen).tracks.single().trackKey
            assertEquals(first, library.page(frozen).tracks.single().metadata.codecParameters)
            try {
                scan(library, source, MetadataOutcome("audio", metadata.copy(codecParameters = second)))
                assertEquals(first, library.page(frozen).tracks.single().metadata.codecParameters)
                assertEquals(second, current(library).codecParameters)
            } finally { library.closeView(frozen.viewId) }
            database.close()
            database = open(file)
            library = RoomLocalLibraryRepository(database)
            library.initialize()
            assertEquals(second, current(library).codecParameters)
            assertEquals(second, checkNotNull(database.libraryDao().currentTrack(key.trackId)).metadata().codecParameters)
            scan(library, checkNotNull(library.source(source.spec.sourceId)),
                MetadataOutcome("audio", issue = SourceReadIssue.METADATA_UNREADABLE))
            assertEquals(second, current(library).codecParameters)
            // 新一次成功解析没有自定义解码参数时，不能沿用已失效的 APE 参数。
            scan(library, checkNotNull(library.source(source.spec.sourceId)), MetadataOutcome("audio", metadata.copy(codecParameters = null)))
            assertNull(current(library).codecParameters)
        } finally { database.close() }
    }

    @Test
    fun typedRecordRejectsDuplicateKeysCoercionUnknownFieldsNewerSchemaAndExcessBytes() {
        val raw = LocalCodecParametersCodec.encode(first)
        assertEquals(first, LocalCodecParametersCodec.decode(raw))
        val invalid = listOf(
            raw.replace("\"schema\":1", "\"schema\":2"),
            raw.replace("\"channels\":2", "\"channels\":\"2\""),
            raw.replace("\"channels\":2", "\"channels\":2.0"),
            raw.replace("\"channels\":2", "\"channels\":2e0"),
            raw.replace("\"channels\":2", "\"channels\":true"),
            raw.replace("\"channels\":2", "\"channels\":null"),
            raw.replace("\"channels\":2", "\"channels\":2147483648"),
            raw.replace("\"channels\":2", "\"channels\":2,\"channels\":1"),
            raw.replace("\"channels\":2", "\"channels\":2,\"extra\":0"),
            raw.replace("\"container\":\"APE\"", "\"container\":\"\\ud800\""),
            raw + " ".repeat(LocalCodecParametersCodec.MAX_BYTES),
        )
        for (value in invalid) {
            assertTrue("The malformed fixture must differ", value != raw)
            try {
                LocalCodecParametersCodec.decode(value)
                throw AssertionError("Malformed codec record accepted")
            }
            catch (failure: LibraryException) { assertEquals(LibraryIssue.RECOVERY_REQUIRED, failure.issue) }
        }
    }

    private fun open(file: File): MeloXDatabase = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context,
        MeloXDatabase::class.java, file.absolutePath).setDriver(BundledSQLiteDriver()).build()

    private suspend fun current(library: RoomLocalLibraryRepository): LocalAudioMetadata {
        val view = library.openView()
        try { return library.page(view).tracks.single().metadata }
        finally { library.closeView(view.viewId) }
    }

    private suspend fun scan(library: RoomLocalLibraryRepository, source: LibrarySource, outcome: MetadataOutcome) {
        val token = library.beginScan(source.spec.sourceId, checkNotNull(library.source(source.spec.sourceId)).authEpoch,
            library.rules().revision, ScanMode.FULL)
        library.commitSeen(token, listOf(
            ObservedCandidate(source.spec.sourceId, "root", source.spec.uri, null, "Root", "vnd.android.document/directory", LocalNodeKind.DIRECTORY),
            ObservedCandidate(source.spec.sourceId, "audio", "content://codec-fixture/document/audio", "root", "Track.ape",
                "audio/ape", LocalNodeKind.AUDIO, sizeBytes = 1024L, providerContentToken = "native:audio:1"),
        ))
        library.commitMetadata(token, listOf(outcome))
        while (true) {
            val next = library.nextDirectory(token) ?: break
            library.completeDirectory(token, next.objectId, true)
        }
        val coverage = ScanCoverage(true, true, true, true, false)
        library.setCoverage(token, coverage)
        var after: String? = null
        while (true) {
            val page = library.stageAbsence(token, after)
            if (page.complete) break
            after = checkNotNull(page.nextAliasId)
        }
        library.finishScan(token, ScanCounters(), coverage)
    }
}
