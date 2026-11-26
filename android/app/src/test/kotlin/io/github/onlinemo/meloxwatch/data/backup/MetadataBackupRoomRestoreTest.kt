package io.github.onlinemo.meloxwatch.data.backup

import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontBackupAccess
import io.github.onlinemo.meloxwatch.data.library.LibraryArtistEntity
import io.github.onlinemo.meloxwatch.data.library.LibrarySortKeys
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackEntity
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.lyrics.active.OwnedLyricsProvider
import io.github.onlinemo.meloxwatch.data.lyrics.active.RoomLyricsContextResolver
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.decode
import io.github.onlinemo.meloxwatch.data.lyrics.store.entity
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataArtworkFiles
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataRepository
import io.github.onlinemo.meloxwatch.data.metadata.MetadataAssociation
import io.github.onlinemo.meloxwatch.data.metadata.MetadataProvenance
import io.github.onlinemo.meloxwatch.data.metadata.MetadataPurpose
import io.github.onlinemo.meloxwatch.data.metadata.decode
import io.github.onlinemo.meloxwatch.data.queue.QueueEntryEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueGenerationEntity
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.themes.ThemeStorageRecords
import io.github.onlinemo.meloxwatch.model.CueTrackReference
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver

/** Real Room, native image decoding and immutable file providers define cloud regression cases. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class MetadataBackupRoomRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: NativeBackupFixture
    private lateinit var artwork: LocalMetadataArtworkFiles
    private lateinit var resources: BackupNativeResourceBridge
    private val key = TrackKey("local", stableUuid("metadata-room-track"))
    private val source = MetadataProvenance("1024", "Fixture song", "Fixture artist", "Fixture album")
    private val lyricBytes = "[00:01.00]Fixture line\n[00:02.00]Second line\n".toByteArray()

    /** The rollback marker declines coroutine stack copies so identity assertions retain the original fault. */
    private class RestoreCancellation : CancellationException("Cancelled after prospective native digest"),
        CopyableThrowable<RestoreCancellation> {
        override fun createCopy(): RestoreCancellation? = null
    }

    @Before fun open() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fixture = NativeBackupFixture(RuntimeEnvironment.getApplication() as Context, temporary.newFolder("app"))
        check(withTimeout(20_000L) { fixture.coordinator.initializeStorage() } is BackupStartupState.Ready)
        artwork = artwork(fixture)
        resources = bridge(fixture, artwork)
        registerProviders(fixture)
    }

    @After fun close() {
        try { runBlocking { fixture.close() } } finally { Dispatchers.resetMain() }
    }

    @Test(timeout = 60_000L)
    fun SQLCaptureTraversesEveryAssociationAndKeepsBothPurposeSelectionsIndependent() = runBlocking {
        withNative(fixture) {
            fixture.library.libraryTransaction {
                val dao = fixture.database.libraryDao()
                val meta = dao.meta()!!
                for (ordinal in 0 until 257) {
                    val id = stableUuid("metadata-paged-track", ordinal.toString())
                    dao.insertTrack(LibraryTrackEntity(id, "local", BackupTestFixtures.EPOCH_MS, ordinal + 1L))
                    for (purpose in MetadataPurpose.entries) {
                        fixture.database.localMetadataDao().insert(MetadataAssociation(TrackKey("local", id), purpose,
                            ordinal + 1L, null, "1".repeat(64), 128, source).entity())
                    }
                }
                check(dao.updateMeta(meta.copy(revision = meta.revision + 1, nextAddedSequence = 258)) == 1)
            }
            for (scope in listOf(BackupScope.LYRICS, BackupScope.LIBRARY)) {
                val directory = root().newGeneration()
                SqliteBackupStage.create(directory) {}.use { stage ->
                    stage.begin(BackupHeader(scopes = setOf(scope), createdEpochMs = BackupTestFixtures.EPOCH_MS,
                        sourceDatabaseVersion = RoomBackupCapture(fixture.database).sourceDatabaseVersion()))
                    val selected = if (scope == BackupScope.LYRICS) BackupRecordKind.METADATA_LYRICS else BackupRecordKind.METADATA_ARTWORK
                    val other = if (scope == BackupScope.LYRICS) BackupRecordKind.METADATA_ARTWORK else BackupRecordKind.METADATA_LYRICS
                    try { RoomBackupCapture(fixture.database).captureMain(stage, includeResourceReferences = false) }
                    catch (failure: Exception) {
                        val accepted = try { stage.count(selected) }
                        catch (diagnostic: Exception) { failure.addSuppressed(diagnostic); -1L }
                        val acceptedRowGroups = if (accepted >= 0L) (accepted + 99L) / 100L else -1L
                        // Row groups describe accepted data; the byte window may end an SQL page sooner.
                        System.err.println("BACKUP_CAPTURE_FAILURE scope=${scope.name} selectedKind=${selected.name} " +
                            "phase=CAPTURE_BEFORE_SEAL acceptedRows=$accepted acceptedRowGroups=$acceptedRowGroups")
                        throw failure
                    }
                    stage.sealSnapshot()
                    assertEquals(257L, stage.count(selected)); assertEquals(0L, stage.count(other))
                    var after: String? = null
                    var count = 0L
                    while (true) {
                        val page = stage.pageNow(selected, after)
                        if (page.isEmpty()) break
                        assertTrue(page.size <= 100)
                        for (row in page) {
                            assertEquals(source, BackupMetadataAssociations.decode(row).provenance)
                            assertNull(row.optionalText("resourceId"))
                        }
                        count += page.size; after = page.last().key
                    }
                    assertEquals(257L, count)
                }
            }
        }
    }

    @Test(timeout = 60_000L)
    fun realZipRestoresBothAssetsIntoNewLocalStorageAndPreservesTheSourceNotice() = runBlocking {
        val imageBytes = png()
        var oldLyrics = ""
        var oldArtwork = ""
        val archive = withNative(fixture) {
            seedTrack(fixture, key)
            val text = fixture.copies.createConfirmedMetadata(lyricBytes, backupSha256(lyricBytes))
            val image = artwork.installConfirmed(imageBytes, backupSha256(imageBytes))
            try {
                oldLyrics = OwnedLyricsProvider.token(fixture.context, Uri.parse(text.uri))!!
                oldArtwork = image.assetId
                fixture.library.libraryTransaction {
                    fixture.database.localMetadataDao().insert(MetadataAssociation(key, MetadataPurpose.LYRICS, 4,
                        oldLyrics, text.sha256, lyricBytes.size.toLong(), source).entity())
                    fixture.database.localMetadataDao().insert(MetadataAssociation(key, MetadataPurpose.ARTWORK, 5,
                        oldArtwork, image.sha256, image.byteCount, source).entity())
                    val observed = fixture.sources.observe(LocalSourceSpec("metadata-v1:$oldLyrics", LocalSourceKind.DOCUMENT, text.uri))
                    fixture.database.lyricsDao().insert(LyricsTrackPreferences(key, revision = 7, primary = LyricsAssociation(
                        observed.root.objectId, observed.source.sourceId, text.uri, 0, LyricsEncodingChoice.UTF8, text.sha256)).entity())
                }
                captureArchive(fixture, resources, setOf(BackupScope.LIBRARY, BackupScope.LYRICS, BackupScope.RESOURCES))
            } finally { text.release(); image.release() }
        }
        val target = NativeBackupFixture(RuntimeEnvironment.getApplication() as Context, temporary.newFolder("new-install"))
        try {
            check(withTimeout(20_000L) { target.coordinator.initializeStorage() } is BackupStartupState.Ready)
            registerProviders(target)
            val targetArtwork = artwork(target)
            val targetResources = bridge(target, targetArtwork)
            restoreZip(target, targetResources, archive)
            val lyrics = association(target, key, MetadataPurpose.LYRICS)!!
            val image = association(target, key, MetadataPurpose.ARTWORK)!!
            assertNotEquals(oldLyrics, lyrics.assetId); assertNotEquals(oldArtwork, image.assetId)
            assertEquals(5L, lyrics.revision); assertEquals(6L, image.revision)
            assertEquals(source, lyrics.provenance); assertEquals(source, image.provenance)
            assertArrayEquals(lyricBytes, OwnedLyricsProvider.finalFile(target.context, lyrics.assetId!!).readBytes())
            assertArrayEquals(imageBytes, targetArtwork.readVerified(image.assetId!!, image.byteCount!!, image.sha256!!))
            assertArrayEquals(lyricBytes, OwnedLyricsProvider.finalFile(fixture.context, oldLyrics).readBytes())
            assertArrayEquals(imageBytes, artwork.readVerified(oldArtwork, imageBytes.size.toLong(), backupSha256(imageBytes)))
            val primary = target.database.lyricsDao().row(key.trackId)!!.decode(key).primary!!
            assertEquals("metadata-v1:${lyrics.assetId}", primary.sourceId)
            withNative(target) {
                seedCurrentQueue(target, key)
                val metadata = metadata(target, targetArtwork)
                val clear = metadata.captureClear(MetadataPurpose.LYRICS)
                assertTrue(metadata.clearStillCurrent(clear))
                metadata.clear(clear) { true }
            }
            assertNull(target.database.lyricsDao().row(key.trackId)!!.decode(key).primary)
            assertNull(association(target, key, MetadataPurpose.LYRICS)!!.assetId)
        } finally { target.close(); registerProviders(fixture) }
    }

    @Test(timeout = 60_000L)
    fun actualMetadataApplyRoundTripKeepsUtf8PrimaryLinkedAndExplicitClearRemovesIt() = runBlocking {
        lateinit var appliedKey: TrackKey
        var originalAssetId = ""
        val archive = withNative(fixture) {
            val audio = seedLookupAudio(fixture)
            appliedKey = audio.trackKey
            seedCurrentQueue(fixture, appliedKey, audio.location)
            // Original-audio access is a controlled proof; Room capture/apply and native resource owners are real.
            val repository = LocalMetadataRepository(fixture.context, fixture.database, fixture.library,
                RoomLyricsContextResolver(fixture.database, fixture.library), fixture.sources, fixture.queues,
                fixture.copies, artwork, fixture.barrier,
                verifyAudio = { it.audio.copy(contentValidation = LocalContentValidation.VERIFIED) })
            val applied = repository.apply(repository.capture(MetadataPurpose.LYRICS), lyricBytes, source) { true }
            originalAssetId = checkNotNull(applied.association.assetId)
            val primary = fixture.database.lyricsDao().row(appliedKey.trackId)!!.decode(appliedKey).primary!!
            assertEquals(LyricsEncodingChoice.UTF8, primary.encoding)
            assertEquals("metadata-v1:$originalAssetId", primary.sourceId)
            captureArchive(fixture, resources, setOf(BackupScope.LIBRARY, BackupScope.LYRICS, BackupScope.RESOURCES),
                expectedResources = 1, selectedKey = appliedKey)
        }
        val target = NativeBackupFixture(RuntimeEnvironment.getApplication() as Context, temporary.newFolder("apply-new-install"))
        try {
            check(withTimeout(20_000L) { target.coordinator.initializeStorage() } is BackupStartupState.Ready)
            registerProviders(target)
            val targetArtwork = artwork(target)
            restoreZip(target, bridge(target, targetArtwork), archive)
            val restored = association(target, appliedKey, MetadataPurpose.LYRICS)!!
            assertNotEquals(originalAssetId, restored.assetId)
            val primary = target.database.lyricsDao().row(appliedKey.trackId)!!.decode(appliedKey).primary!!
            assertEquals(LyricsEncodingChoice.UTF8, primary.encoding)
            assertEquals("metadata-v1:${restored.assetId}", primary.sourceId)
            assertEquals(restored.sha256, primary.confirmedContentSha256)
            assertArrayEquals(lyricBytes, OwnedLyricsProvider.finalFile(target.context, restored.assetId!!).readBytes())
            withNative(target) {
                seedCurrentQueue(target, appliedKey)
                val repository = metadata(target, targetArtwork)
                repository.clear(repository.captureClear(MetadataPurpose.LYRICS)) { true }
            }
            assertNull(target.database.lyricsDao().row(appliedKey.trackId)!!.decode(appliedKey).primary)
            assertNull(association(target, appliedKey, MetadataPurpose.LYRICS)!!.sha256)
            assertArrayEquals(lyricBytes, OwnedLyricsProvider.finalFile(fixture.context, originalAssetId).readBytes())
        } finally { target.close(); registerProviders(fixture) }
    }

    @Test(timeout = 60_000L)
    fun sameContentFromADifferentOriginalManualCopyRetainsItsPrimaryAfterRestoreAndMetadataClear() = runBlocking {
        var originalMetadataId = ""
        var originalManualId = ""
        val archive = withNative(fixture) {
            seedTrack(fixture, key)
            val associated = fixture.copies.createConfirmedMetadata(lyricBytes, backupSha256(lyricBytes))
            val manual = fixture.copies.createConfirmedMetadata(lyricBytes, backupSha256(lyricBytes))
            try {
                originalMetadataId = OwnedLyricsProvider.token(fixture.context, Uri.parse(associated.uri))!!
                originalManualId = OwnedLyricsProvider.token(fixture.context, Uri.parse(manual.uri))!!
                assertNotEquals(originalMetadataId, originalManualId)
                val observed = fixture.sources.observe(LocalSourceSpec("metadata-v1:$originalManualId",
                    LocalSourceKind.DOCUMENT, manual.uri))
                fixture.library.libraryTransaction {
                    fixture.database.localMetadataDao().insert(MetadataAssociation(key, MetadataPurpose.LYRICS, 4,
                        originalMetadataId, associated.sha256, lyricBytes.size.toLong(), source).entity())
                    fixture.database.lyricsDao().insert(LyricsTrackPreferences(key, revision = 8, primary = LyricsAssociation(
                        observed.root.objectId, observed.source.sourceId, manual.uri, 0, LyricsEncodingChoice.UTF8, manual.sha256)).entity())
                }
                captureArchive(fixture, resources, setOf(BackupScope.LIBRARY, BackupScope.LYRICS, BackupScope.RESOURCES), expectedResources = 1)
            } finally { associated.release(); manual.release() }
        }
        val target = NativeBackupFixture(RuntimeEnvironment.getApplication() as Context, temporary.newFolder("manual-new-install"))
        try {
            check(withTimeout(20_000L) { target.coordinator.initializeStorage() } is BackupStartupState.Ready)
            registerProviders(target)
            val targetArtwork = artwork(target)
            restoreZip(target, bridge(target, targetArtwork), archive)
            val association = association(target, key, MetadataPurpose.LYRICS)!!
            val primary = target.database.lyricsDao().row(key.trackId)!!.decode(key).primary!!
            assertNotEquals(originalMetadataId, association.assetId); assertNotEquals(originalManualId, association.assetId)
            assertEquals(OwnedLyricsProvider.uri(target.context, association.assetId!!).toString(), primary.uri)
            assertFalse(primary.sourceId.startsWith("metadata-v1:"))
            withNative(target) {
                captureArchive(target, bridge(target, targetArtwork),
                    setOf(BackupScope.LIBRARY, BackupScope.LYRICS, BackupScope.RESOURCES), expectedResources = 1)
                seedCurrentQueue(target, key)
                val metadata = metadata(target, targetArtwork)
                metadata.clear(metadata.captureClear(MetadataPurpose.LYRICS)) { true }
            }
            assertEquals(primary, target.database.lyricsDao().row(key.trackId)!!.decode(key).primary)
            assertNull(association(target, key, MetadataPurpose.LYRICS)!!.sha256)
            assertArrayEquals(lyricBytes, OwnedLyricsProvider.finalFile(target.context, association.assetId!!).readBytes())
        } finally { target.close(); registerProviders(fixture) }
    }

    @Test(timeout = 60_000L)
    fun absentResourcesRemainPendingAndMergePreservesClearAndPendingRevisionRows() = runBlocking {
        val incoming = MetadataAssociation(key, MetadataPurpose.LYRICS, 11, null, backupSha256(lyricBytes),
            lyricBytes.size.toLong(), source)
        val archive = logicalArchive(setOf(BackupScope.LYRICS), listOf(BackupMetadataAssociations.record(incoming, BackupCell.Null, false)))
        fixture.archiveInput = archive
        suspend fun restore(mode: BackupMergeMode) {
            fixture.attachClosedPlayback()
            val preview = fixture.coordinator.previewRestore(input(), mode)
            fixture.coordinator.confirmRestore(preview.token)
        }
        restore(BackupMergeMode.REPLACE_SELECTED)
        val pending = association(fixture, key, MetadataPurpose.LYRICS)!!
        assertNull(pending.assetId); assertEquals(incoming.sha256, pending.sha256)
        assertEquals(source, pending.provenance); assertEquals(12L, pending.revision)
        restore(BackupMergeMode.MERGE_KEEP_CURRENT)
        assertEquals(pending, association(fixture, key, MetadataPurpose.LYRICS))
        withNative(fixture) {
            fixture.library.libraryTransaction {
                check(fixture.database.localMetadataDao().updateIfRevision(key.trackId, "LYRICS", 12, 21, null, null, null, null) == 1)
                fixture.database.localMetadataDao().insert(MetadataAssociation(key, MetadataPurpose.ARTWORK, 8, null,
                    "2".repeat(64), 128, source).entity())
                val other = TrackKey("local", stableUuid("metadata-removed-track"))
                seedTrackInsideTransaction(fixture, other)
                fixture.database.localMetadataDao().insert(MetadataAssociation(other, MetadataPurpose.LYRICS, 9, null,
                    "3".repeat(64), 128, source).entity())
            }
        }
        restore(BackupMergeMode.MERGE_KEEP_CURRENT)
        assertEquals(MetadataAssociation(key, MetadataPurpose.LYRICS, 21), association(fixture, key, MetadataPurpose.LYRICS))
        restore(BackupMergeMode.REPLACE_SELECTED)
        assertEquals(22L, association(fixture, key, MetadataPurpose.LYRICS)!!.revision)
        assertEquals(8L, association(fixture, key, MetadataPurpose.ARTWORK)!!.revision)
        val removed = association(fixture, TrackKey("local", stableUuid("metadata-removed-track")), MetadataPurpose.LYRICS)!!
        assertEquals(10L, removed.revision); assertNull(removed.sha256); assertNull(removed.provenance)
        restore(BackupMergeMode.REPLACE_SELECTED)
        assertEquals(23L, association(fixture, key, MetadataPurpose.LYRICS)!!.revision)
    }

    @Test(timeout = 60_000L)
    fun dependentRowsAreClearedBeforeLibraryWritesAndLateFailureRollsBackTheWholeTransaction() = runBlocking {
        withNative(fixture) {
            seedTrack(fixture, key)
            fixture.library.libraryTransaction {
                fixture.database.localMetadataDao().insert(MetadataAssociation(key, MetadataPurpose.ARTWORK, 19, null,
                    "7".repeat(64), 128, source).entity())
            }
        }
        val original = association(fixture, key, MetadataPurpose.ARTWORK)
        val before = RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {})
        val archive = logicalArchive(setOf(BackupScope.LIBRARY), listOf(libraryMeta(), BackupTestFixtures.track(key.trackId, 2),
            BackupMetadataAssociations.record(MetadataAssociation(key, MetadataPurpose.ARTWORK, 3), BackupCell.Null, false)))
        var beforeCacheSize = 0L
        var beforeCacheSpill = 0L
        fixture.database.withTestWriterSql {
            beforeCacheSize = scalarLong("PRAGMA main.cache_size")
            beforeCacheSpill = scalarLong("PRAGMA main.cache_spill")
            assertEquals(1L, scalarLong("SELECT COUNT(*) FROM pragma_database_list WHERE name='main' AND length(file)>0"))
            execute("CREATE TRIGGER metadata_dependants_before_library BEFORE UPDATE ON local_tracks " +
                "WHEN EXISTS(SELECT 1 FROM local_metadata_associations WHERE trackId=NEW.trackId AND purpose='ARTWORK') " +
                "BEGIN SELECT RAISE(ABORT,'metadata-dependants-were-not-cleared'); END")
        }
        val cancellation = RestoreCancellation()
        var reachedCommit = false
        try {
            try { restoreZip(fixture, resources, archive, beforeCommit = {
                fixture.database.withTestWriterSql {
                    assertEquals(-1024L, scalarLong("PRAGMA main.cache_size"))
                    assertTrue(scalarLong("PRAGMA main.cache_spill") > 0L)
                    assertEquals(0L, scalarLong("SELECT COUNT(*) FROM main.sqlite_schema WHERE name LIKE 'backup_%'"))
                    assertEquals(0L, scalarLong("SELECT COUNT(*) FROM sqlite_temp_schema WHERE name LIKE 'backup_%'"))
                }
                reachedCommit = true
                throw cancellation
            }) }
            catch (failure: CancellationException) { assertSame(cancellation, failure) }
            assertTrue(reachedCommit)
            assertEquals(original, association(fixture, key, MetadataPurpose.ARTWORK))
            assertEquals(before, RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {}))
            fixture.database.withTestWriterSql {
                assertEquals(beforeCacheSize, scalarLong("PRAGMA main.cache_size"))
                assertEquals(beforeCacheSpill, scalarLong("PRAGMA main.cache_spill"))
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM main.sqlite_schema WHERE name LIKE 'backup_%'"))
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM sqlite_temp_schema WHERE name LIKE 'backup_%'"))
            }
        } finally { fixture.database.withTestWriterSql { execute("DROP TRIGGER metadata_dependants_before_library") } }
    }

    @Test(timeout = 60_000L)
    fun noResourceRestoreVerifiesTheActualLocalCopyAndCorruptBytesCannotBeReattached() = runBlocking {
        var token = ""
        withNative(fixture) {
            seedTrack(fixture, key)
            val copy = fixture.copies.createConfirmedMetadata(lyricBytes, backupSha256(lyricBytes))
            try {
                token = OwnedLyricsProvider.token(fixture.context, Uri.parse(copy.uri))!!
                fixture.library.libraryTransaction {
                    fixture.database.localMetadataDao().insert(MetadataAssociation(key, MetadataPurpose.LYRICS, 4,
                        token, copy.sha256, lyricBytes.size.toLong(), source).entity())
                }
            } finally { copy.release() }
        }
        val requestedSource = source.copy(title = "Fixture restored title")
        val incoming = MetadataAssociation(key, MetadataPurpose.LYRICS, 7, null, backupSha256(lyricBytes),
            lyricBytes.size.toLong(), requestedSource)
        val archive = logicalArchive(setOf(BackupScope.LYRICS), listOf(BackupMetadataAssociations.record(incoming, BackupCell.Null, false)))
        restoreZip(fixture, resources, archive)
        val reused = association(fixture, key, MetadataPurpose.LYRICS)!!
        assertEquals(token, reused.assetId); assertEquals(requestedSource, reused.provenance)
        assertEquals(8L, reused.revision)
        val corrupt = lyricBytes.copyOf().apply { this[lastIndex] = '!'.code.toByte() }
        OwnedLyricsProvider.finalFile(fixture.context, token).writeBytes(corrupt)
        val applied = restoreZip(fixture, resources, archive)
        val pending = association(fixture, key, MetadataPurpose.LYRICS)!!
        assertNull(pending.assetId); assertEquals(incoming.sha256, pending.sha256)
        assertEquals(requestedSource, pending.provenance); assertEquals(9L, pending.revision)
        assertEquals(1L, applied.missingReferences)
    }

    @Test(timeout = 60_000L)
    fun changedCueBoundsCannotOverwriteAnAssociationWithTheSameTrackKey() = runBlocking {
        val cue = LocalCueReference(CueTrackReference("fixture-cue-source", "fixture-disc", 1, 2,
            0, 75, 1_000_000, null, "sha256:" + "1".repeat(64), audioContentTokenHint = "sha256:" + "2".repeat(64)))
        val cueKey = cue.trackKey
        withNative(fixture) {
            seedTrack(fixture, cueKey, cue)
            fixture.library.libraryTransaction {
                fixture.database.localMetadataDao().insert(MetadataAssociation(cueKey, MetadataPurpose.LYRICS, 6,
                    null, backupSha256(lyricBytes), lyricBytes.size.toLong(), source).entity())
            }
        }
        val original = association(fixture, cueKey, MetadataPurpose.LYRICS)
        val before = RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {})
        val changed = LocalCueReference(cue.identity.copy(endFrameExclusive = 150, endUsExclusive = 2_000_000))
        fixture.archiveInput = logicalArchive(setOf(BackupScope.LYRICS), listOf(BackupMetadataAssociations.record(
            original!!.copy(revision = 9), BackupCell.Text(CueReferenceCodec.encode(changed)), false)))
        fixture.attachClosedPlayback()
        val preview = fixture.coordinator.previewRestore(input(), BackupMergeMode.REPLACE_SELECTED)
        val failure = try { fixture.coordinator.confirmRestore(preview.token); null } catch (failure: BackupException) { failure }
        assertEquals(BackupIssue.CONFLICT, failure?.issue)
        assertEquals(original, association(fixture, cueKey, MetadataPurpose.LYRICS))
        assertEquals(before, RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {}))
        assertEquals(BackupAccessState.READY, fixture.barrier.state.value)
    }

    @Test(timeout = 60_000L)
    fun cleanupProtectsRawCrossPurposeReferencesAndRefusesADamagedOwner() = runBlocking {
        withNative(fixture) { fonts ->
            seedTrack(fixture, key)
            val copy = fixture.copies.createConfirmedMetadata(lyricBytes, backupSha256(lyricBytes))
            val token = OwnedLyricsProvider.token(fixture.context, Uri.parse(copy.uri))!!
            try {
                fixture.library.libraryTransaction {
                    fixture.database.localMetadataDao().insert(MetadataAssociation(key, MetadataPurpose.ARTWORK, 4,
                        token, copy.sha256, lyricBytes.size.toLong(), source).entity())
                }
            } finally { copy.release() }
            val storage = root()
            BackupRestoreJournal.open(storage).use { journal ->
                val incoming = storage.newGeneration(); val before = storage.newGeneration()
                journal.registerGeneration(incoming.ownership()); journal.registerGeneration(before.ownership())
                val versions = fixture.configurations.readAll(fonts)
                val run = BackupRestoreRun(stableUuid("metadata-cleanup-run"), stableUuid("metadata-cleanup-export"),
                    "3".repeat(64), incoming.root.name, before.root.name, setOf(BackupScope.PREFERENCES),
                    BackupMergeMode.REPLACE_SELECTED, "4".repeat(64))
                journal.begin(run, versions, ThemeStorageRecords.encode(fixture.themes.readCatalogForBackup()))
                resources.cleanup(journal, BackupConfigDirection.ROLLBACK, fonts)
                assertArrayEquals(lyricBytes, OwnedLyricsProvider.finalFile(fixture.context, token).readBytes())
                fixture.database.withTestWriterSql {
                    execute("UPDATE local_metadata_associations SET purpose='UNKNOWN' WHERE trackId=? AND purpose='ARTWORK'", key.trackId)
                }
                val failure = try { resources.cleanup(journal, BackupConfigDirection.ROLLBACK, fonts); null }
                    catch (failure: BackupException) { failure }
                assertEquals(BackupIssue.RECOVERY_REQUIRED, failure?.issue)
                assertArrayEquals(lyricBytes, OwnedLyricsProvider.finalFile(fixture.context, token).readBytes())
            }
        }
    }

    private suspend fun restoreZip(runtime: NativeBackupFixture, bridge: BackupNativeResourceBridge, archive: File,
        beforeCommit: suspend () -> Unit = {}): BackupMainApplyResult = withNative(runtime) { fonts ->
        val storage = root()
        val incomingDirectory = storage.newGeneration()
        SqliteBackupStage.create(incomingDirectory) {}.use { incoming ->
            BackupArchiveReader().stage(archive, incoming) {}
            val beforeDirectory = storage.newGeneration()
            SqliteBackupStage.create(beforeDirectory) {}.use { before ->
                val capture = RoomBackupCapture(runtime.database)
                val versions = runtime.configurations.readAll(fonts)
                before.begin(BackupHeader(scopes = setOf(BackupScope.PREFERENCES, BackupScope.RESOURCES) +
                    incoming.header.scopes.intersect(setOf(BackupScope.LYRICS, BackupScope.LIBRARY)),
                    createdEpochMs = BackupTestFixtures.EPOCH_MS, sourceDatabaseVersion = capture.sourceDatabaseVersion()))
                capture.captureMain(before, includeResourceReferences = false)
                val packages = bridge.capture(before, versions, fonts)
                assertTrue(packages.isEmpty())
                for ((_, value) in versions) before.record(value.record)
                before.sealSnapshot()
                BackupRestoreJournal.open(storage).use { journal ->
                    journal.registerGeneration(incomingDirectory.ownership()); journal.registerGeneration(beforeDirectory.ownership())
                    val target = storage.newGeneration(); journal.registerGeneration(target.ownership())
                    val run = BackupRestoreRun(stableUuid("metadata-restore-run", target.root.name), incoming.header.exportId,
                        backupFileSha256(archive) {}, incomingDirectory.root.name, beforeDirectory.root.name, incoming.header.scopes,
                        BackupMergeMode.REPLACE_SELECTED, capture.mainDigest(checkCancelled = {}))
                    journal.begin(run, versions, ThemeStorageRecords.encode(runtime.themes.readCatalogForBackup()))
                    runtime.permissions.prepare(incoming, run.archiveSha256, run.scopes, run.mode)
                    bridge.retainBefore(before, versions, fonts)
                    bridge.validate(incoming, fonts)
                    BackupResourceSession.open(incomingDirectory).use { session ->
                        val prepared = bridge.prepare(run, incoming, before, versions, fonts, session, journal, false)
                        journal.configureTargets(run.id, prepared.targets)
                        for ((key, version) in versions) {
                            val targetRecord = prepared.targets.getValue(key)
                            journal.planConfig(run.id, BackupConfigDirection.FORWARD, version, targetRecord)
                            journal.confirmConfig(run.id, runtime.configurations.compareAndSet(version, targetRecord, fonts))
                        }
                        val applied = RoomBackupMaterializer(runtime.context.packageName, runtime.barrier, runtime.database,
                            runtime.library, runtime.permissions, bridge::restoredLyrics, bridge::restoredMetadataAsset)
                            .apply(run, incoming, journal) { bindings ->
                                journal.prepareMain(run.id, target.root.name, capture.mainDigest(checkCancelled = {}, insideWriter = true),
                                    bindings + ("resourceMissingReferences" to BackupCell.Integer(prepared.missingReferences)))
                                beforeCommit()
                            }
                        journal.finishDirection(run.id, BackupConfigDirection.FORWARD, bridge.cleanupBindings(journal))
                        bridge.cleanup(journal, BackupConfigDirection.FORWARD, fonts)
                        applied
                    }
                }
            }
        }
    }

    private suspend fun captureArchive(runtime: NativeBackupFixture, bridge: BackupNativeResourceBridge, scopes: Set<BackupScope>,
        expectedResources: Int = 2, selectedKey: TrackKey = key): File {
        val directory = root().newGeneration()
        return SqliteBackupStage.create(directory) {}.use { stage ->
            stage.begin(BackupHeader(scopes = scopes, createdEpochMs = BackupTestFixtures.EPOCH_MS,
                sourceDatabaseVersion = RoomBackupCapture(runtime.database).sourceDatabaseVersion()))
            RoomBackupCapture(runtime.database) { assetId -> OwnedLyricsProvider.uri(runtime.context, assetId).toString() }.captureMain(stage)
            bridge.capture(stage, emptyMap(), null)
            stage.sealSnapshot()
            val lyrics = stage.row(BackupRecordKind.METADATA_LYRICS, selectedKey.trackId)!!
            val artwork = stage.row(BackupRecordKind.METADATA_ARTWORK, selectedKey.trackId)
            assertEquals(expectedResources.toLong(), stage.count(BackupRecordKind.RESOURCE))
            for (row in listOfNotNull(lyrics, artwork)) assertFalse(BackupJsonCodec.record(row).toString(Charsets.UTF_8).contains("assetId"))
            val primary = runtime.database.lyricsDao().row(selectedKey.trackId)?.decode(selectedKey)?.primary
            val native = association(runtime, selectedKey, MetadataPurpose.LYRICS)
            val linked = primary != null && native?.assetId != null &&
                primary.uri == OwnedLyricsProvider.uri(runtime.context, native.assetId!!).toString() && primary.sourceId == "metadata-v1:${native.assetId}"
            assertEquals(linked, lyrics.flag("primaryLinked"))
            if (native?.assetId != null && primary != null) assertFalse(BackupJsonCodec.record(stage.row(BackupRecordKind.LYRICS_TRACK, selectedKey.trackId)!!)
                .toString(Charsets.UTF_8).contains(native.assetId!!))
            directory.child("archive.zip").also { BackupArchiveWriter().write(stage, it) {} }
        }
    }

    private fun logicalArchive(scopes: Set<BackupScope>, rows: List<BackupRecord>): File {
        val directory = root().newGeneration()
        return SqliteBackupStage.create(directory) {}.use { stage ->
            stage.begin(BackupHeader(scopes = scopes, createdEpochMs = BackupTestFixtures.EPOCH_MS))
            for (row in rows) stage.record(row)
            stage.sealSnapshot()
            directory.child("archive.zip").also { BackupArchiveWriter().write(stage, it) {} }
        }
    }

    private suspend fun association(runtime: NativeBackupFixture, key: TrackKey, purpose: MetadataPurpose): MetadataAssociation? =
        runtime.database.localMetadataDao().row(key.trackId, purpose.name)?.decode(key, purpose)

    private suspend fun seedTrack(runtime: NativeBackupFixture, key: TrackKey, cue: LocalCueReference? = null) =
        runtime.library.libraryTransaction { seedTrackInsideTransaction(runtime, key, cue) }

    private suspend fun seedTrackInsideTransaction(runtime: NativeBackupFixture, key: TrackKey, cue: LocalCueReference? = null) {
        val dao = runtime.database.libraryDao()
        val meta = dao.meta()!!
        val row = LibraryTrackEntity(key.trackId, key.providerId, BackupTestFixtures.EPOCH_MS, meta.nextAddedSequence)
        dao.insertTrack(row)
        val version = runtime.library.emptyTrackVersion(row, "Fixture track.mp3", meta.revision + 1).copy(
            cueReference = cue?.let(CueReferenceCodec::encode), durationMs = cue?.identity?.durationUs?.div(1000),
            trackNumber = cue?.identity?.trackNumber)
        dao.insertTrackVersion(version)
        dao.insertArtists(listOf(LibraryArtistEntity(version.versionId, 0, LibrarySortKeys.text(null), null)))
        check(dao.updateMeta(meta.copy(revision = meta.revision + 1, nextAddedSequence = meta.nextAddedSequence + 1)) == 1)
    }

    private fun artwork(runtime: NativeBackupFixture) = LocalMetadataArtworkFiles(runtime.context, runtime.io,
        runtime.barrier, freeBytes = { it.usableSpace }, bitmaps = runtime.bitmaps)
    private fun bridge(runtime: NativeBackupFixture, artwork: LocalMetadataArtworkFiles) = BackupNativeResourceBridge(
        runtime.context, runtime.barrier, runtime.database, runtime.io, runtime.sources, runtime.copies,
        runtime.background, runtime.themes, metadataArtwork = artwork)
    private fun metadata(runtime: NativeBackupFixture, artwork: LocalMetadataArtworkFiles) = LocalMetadataRepository(
        runtime.context, runtime.database, runtime.library, RoomLyricsContextResolver(runtime.database, runtime.library),
        runtime.sources, runtime.queues, runtime.copies, artwork, runtime.barrier,
        verifyAudio = { throw AssertionError("Clearing an owned association must not reopen the audio source") })
    private suspend fun seedCurrentQueue(runtime: NativeBackupFixture, key: TrackKey,
        location: LocalLocationReference? = null) = runtime.library.libraryTransaction {
        val queue = runtime.database.queueDao()
        val old = queue.head()!!
        val generation = stableUuid("metadata-clear-generation", old.queueRevision.toString())
        val entry = stableUuid("metadata-clear-entry", generation)
        queue.insertGeneration(QueueGenerationEntity(generation, stableUuid("metadata-clear-operation", generation),
            old.activeGenerationId, old.queueRevision, "metadata-clear-view", 0, 1, false, "OFF", null, 0, 0, false))
        queue.insertEntries(listOf(QueueEntryEntity(generation, entry, "local", key.trackId, 0, 0, "Fixture track", null,
            null, location?.locationId, location?.uri, location?.grantUri, location?.grantFlags,
            location?.access?.name, location?.contentRevision)))
        check(queue.advanceStaging(generation, 0, 1) == 1); check(queue.seal(generation) == 1)
        check(queue.markPublished(generation, old.queueRevision + 1) == 1)
        check(queue.publish(generation, old.activeGenerationId, old.queueRevision, old.queueRevision + 1, entry, 0, 0) == 1)
    }

    private suspend fun seedLookupAudio(runtime: NativeBackupFixture): LocalReadDescriptor {
        val source = runtime.library.registerSource(LocalSourceSpec("metadata-apply-source", LocalSourceKind.DOCUMENT,
            "content://metadata-fixture/document/audio-object", authority = "metadata-fixture", rootObjectId = "audio-object"),
            LocalGrantObservation(read = ObservedCapability.AVAILABLE))
        val scan = runtime.library.beginScan(source.spec.sourceId, source.authEpoch, runtime.library.rules().revision, ScanMode.FULL)
        runtime.library.commitSeen(scan, listOf(ObservedCandidate(source.spec.sourceId, "audio-object", source.spec.uri, null,
            "Fixture audio.mp3", "audio/mpeg", LocalNodeKind.AUDIO, providerContentToken = "native:fixture:1")))
        runtime.library.commitMetadata(scan, listOf(MetadataOutcome("audio-object", LocalAudioMetadata(
            title = "Fixture local title", artist = "Fixture local artist", durationMs = 180_000L))))
        val coverage = ScanCoverage(false, true, true, true, false)
        runtime.library.setCoverage(scan, coverage)
        runtime.library.finishScan(scan, ScanCounters(), coverage)
        val view = runtime.library.openView()
        val key = try { runtime.library.page(view).tracks.single().trackKey }
            finally { runtime.library.closeView(view.viewId) }
        return runtime.library.resolveLocalRead(key, null, null)
    }
    private fun root(): BackupPrivateDirectory = BackupPrivateDirectory.open(temporary.newFolder())
    private fun libraryMeta() = BackupRecord(BackupRecordKind.LIBRARY_META, "library", mapOf("shortAudioFilter" to BackupCell.Flag(false)))
    private fun input() = BackupDocumentInput(Uri.parse("content://fixture.documents/archive"), Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun registerProviders(runtime: NativeBackupFixture) {
        register(runtime, OwnedLyricsProvider.authority(runtime.context), OwnedLyricsProvider())
        register(runtime, runtime.context.packageName + ".backup-resources", BackupResourceProvider())
    }
    private fun register(runtime: NativeBackupFixture, authority: String, provider: ContentProvider) {
        val info = ProviderInfo().apply {
            this.authority = authority; packageName = runtime.context.packageName; name = provider.javaClass.name
            exported = false; grantUriPermissions = false
            applicationInfo = ApplicationInfo().apply { packageName = runtime.context.packageName; flags = 0 }
        }
        shadowOf(runtime.context.packageManager).addOrUpdateProvider(info)
        provider.attachInfo(runtime.context, info)
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.GREEN); return ByteArrayOutputStream().also {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray() } finally { bitmap.recycle() }
    }

    private suspend fun <T> withNative(runtime: NativeBackupFixture, action: suspend (LyricsFontBackupAccess) -> T): T =
        withTimeout(20_000L) { acquireNative(runtime, action) }
    private suspend fun <T> acquireNative(runtime: NativeBackupFixture, action: suspend (LyricsFontBackupAccess) -> T): T {
        while (true) {
            var entered = false
            try { return runtime.barrier.exclusively { entered = true; runtime.fonts.withSettings(action) } }
            catch (failure: BackupException) { if (entered || failure.issue != BackupIssue.BUSY) throw failure }
            delay(5)
        }
    }
}
