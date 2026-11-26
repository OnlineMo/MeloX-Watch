package io.github.onlinemo.meloxwatch.data.metadata

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.onlinemo.meloxwatch.data.artwork.LocalArtworkRepository
import io.github.onlinemo.meloxwatch.data.artwork.embeddedPng
import io.github.onlinemo.meloxwatch.data.artwork.png
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocumentCopies
import io.github.onlinemo.meloxwatch.data.lyrics.active.OwnedLyricsProvider
import io.github.onlinemo.meloxwatch.data.lyrics.active.RoomLyricsContextResolver
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsRoomPlaybackFixture
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsRoomRows
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.entity
import io.github.onlinemo.meloxwatch.data.lyrics.store.update
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver

/** Real Room/native files and association CAS; the original-audio proof is a controlled boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class LocalMetadataRepositoryTest : LyricsRoomPlaybackFixture() {
    private lateinit var appContext: Context
    private lateinit var io: LocalProviderIo
    private lateinit var copies: LyricsDocumentCopies
    private lateinit var repository: LocalMetadataRepository
    private lateinit var bitmaps: ThemeBitmapOwner
    private var originalVerified = true

    @Before fun prepareMetadataOwners() = runBlocking {
        appContext = MetadataRoomContext(RuntimeEnvironment.getApplication(), File(temporaryFolder.root, "metadata-private"))
        io = LocalProviderIo()
        bitmaps = ThemeBitmapOwner()
        val barrier = BackupMutationBarrier()
        barrier.initializeStorage { }
        copies = LyricsDocumentCopies(appContext, io, freeBytes = { 1_000_000_000L }, backupBarrier = barrier)
        val sources = AndroidLocalSources(appContext, io)
        val library = RoomLocalLibraryRepository(database)
        val artwork = LocalMetadataArtworkFiles(appContext, io, barrier, freeBytes = { 1_000_000_000L }, bitmaps = bitmaps)
        val provider = OwnedLyricsProvider().apply {
            attachInfo(appContext, ProviderInfo().apply {
                authority = OwnedLyricsProvider.authority(appContext)
                exported = false
            })
        }
        ShadowContentResolver.registerProviderInternal(OwnedLyricsProvider.authority(appContext), provider)
        database.withTestWriterTransaction {
            database.libraryDao().insertTrackVersion(LibraryTrackVersionEntity(
                versionId = "metadata-native-version", trackId = trackKey.trackId, providerId = "local",
                validFrom = 1L, validTo = null, member = true, firstAddedEpochMs = 1_794_787_200_000L,
                firstAddedSequence = 1L, filename = "Fixture.mp3", displayTitle = "Fixture title", title = "Fixture title",
                artist = "Fixture artist", artists = "[]", album = "Fixture album", albumArtist = null, durationMs = 180_000L,
                discNumber = null, trackNumber = null, compilation = false, mimeType = "audio/mpeg", parserVersion = 1,
                metadataIssue = null, titleKey = "fixture title", artistKey = "fixture artist", albumSortKey = "fixture album",
                filenameKey = "fixture.mp3", albumKey = "metadata-fixture-album", searchText = "fixture title fixture artist",
                titleSection = "F", artistSection = "F", albumSection = "F", filenameSection = "F", playCount = 0L, listeningMs = 0L,
            ))
        }
        repository = LocalMetadataRepository(appContext, database, library, RoomLyricsContextResolver(database, library), sources,
            RoomQueueStore(database), copies, artwork, barrier, verifyAudio = { context ->
                if (!originalVerified) throw MetadataStoreException(MetadataStoreIssue.SOURCE_CHANGED)
                context.audio.copy(contentValidation = LocalContentValidation.VERIFIED)
            })
    }

    @Test(timeout = 30_000L)
    fun confirmationInstallsRealLyricsAndCommitsProvenanceAndPrimaryTogetherWhileKeepingTranslationAndOffset() = runBlocking {
        val before = LyricsTrackPreferences(trackKey, revision = 3L, primary = LyricsRoomRows.association(),
            translation = LyricsRoomRows.association(translation = true), userDelayMs = 500)
        assertTrue(database.lyricsDao().insert(before.entity()) >= 0L)
        val target = repository.capture(MetadataPurpose.LYRICS)
        val bytes = "[00:01]Confirmed fixture lyric".toByteArray()
        val result = repository.apply(target, bytes, source()) { true }
        val association = result.association
        val preferences = ready()
        assertEquals(1L, association.revision)
        assertEquals(4L, preferences.revision)
        assertEquals(before.translation, preferences.translation)
        assertEquals(500, preferences.userDelayMs)
        val assetId = checkNotNull(association.assetId)
        assertArrayEquals(bytes, OwnedLyricsProvider.finalFile(appContext, assetId).readBytes())
        assertEquals("metadata-v1:$assetId", preferences.primary?.sourceId)
        assertEquals(LyricsEncodingChoice.UTF8, preferences.primary?.encoding)
        assertEquals(metadataSha256(bytes), preferences.primary?.confirmedContentSha256)
        assertEquals(source(), repository.read(trackKey, MetadataPurpose.LYRICS).provenance)
        assertFalse(repository.isCurrent(target))
        assertTrue(repository.isCurrent(result.target))
        assertIntegrity()
    }

    @Test(timeout = 30_000L)
    fun clearRemovesOnlyTheMatchingNetworkPrimaryAndKeepsARevisionTombstone() = runBlocking {
        val bytes = "[00:01]Network fixture".toByteArray()
        val applied = repository.apply(repository.capture(MetadataPurpose.LYRICS), bytes, source()) { true }
        val clear = repository.captureClear(MetadataPurpose.LYRICS)
        val result = repository.clear(clear) { true }
        assertEquals(applied.association.revision + 1L, result.association.revision)
        assertNull(result.association.assetId)
        assertNull(ready().primary)
        assertFalse(repository.clearStillCurrent(clear))
        assertFalse(repository.isCurrent(applied.target))
        repository.recoverLyrics()
        assertFalse(OwnedLyricsProvider.finalFile(appContext, checkNotNull(applied.association.assetId)).exists())
        assertIntegrity()
    }

    @Test(timeout = 30_000L)
    fun manualPrimaryReplacementKeepsItsOwnFileAndTheMetadataAssetRemainsReferencedUntilExplicitClear() = runBlocking {
        val bytes = "[00:01]Network fixture".toByteArray()
        val applied = repository.apply(repository.capture(MetadataPurpose.LYRICS), bytes, source()) { true }
        val current = ready()
        val manual = current.copy(revision = current.revision + 1L, primary = LyricsRoomRows.association(),
            translation = LyricsRoomRows.association(true), userDelayMs = 700)
        assertEquals(1, database.lyricsDao().update(current.revision, manual.entity()))
        repository.recoverLyrics()
        assertTrue(OwnedLyricsProvider.finalFile(appContext, checkNotNull(applied.association.assetId)).isFile)
        repository.clear(repository.captureClear(MetadataPurpose.LYRICS)) { true }
        assertEquals(manual, ready())
        repository.recoverLyrics()
        assertFalse(OwnedLyricsProvider.finalFile(appContext, checkNotNull(applied.association.assetId)).exists())
        assertIntegrity()
    }

    @Test(timeout = 30_000L)
    fun aLateOwnerChangeRollsBackBothTablesAndDoesNotConsumeTheOldAssociation() = runBlocking {
        val applied = repository.apply(repository.capture(MetadataPurpose.LYRICS), "[00:01]Fixture".toByteArray(), source()) { true }
        val beforeLyrics = ready()
        val beforeMeta = repository.read(trackKey, MetadataPurpose.LYRICS)
        val revision = database.libraryDao().meta()?.revision
        var checks = 0
        assertEquals(MetadataStoreIssue.SOURCE_CHANGED, failure {
            repository.clear(repository.captureClear(MetadataPurpose.LYRICS)) { ++checks < 2 }
        })
        assertEquals(beforeLyrics, ready())
        assertEquals(beforeMeta, repository.read(trackKey, MetadataPurpose.LYRICS))
        assertEquals(revision, database.libraryDao().meta()?.revision)
        assertTrue(repository.isCurrent(applied.target))
        assertIntegrity()
    }

    @Test(timeout = 30_000L)
    fun revokedAudioDoesNotPreventLogicalClearAndADifferentQueueInstanceCannotClearAnOldTicket() = runBlocking {
        repository.apply(repository.capture(MetadataPurpose.LYRICS), "[00:01]Fixture".toByteArray(), source()) { true }
        val old = repository.captureClear(MetadataPurpose.LYRICS)
        execute("UPDATE queue_head SET currentEntryId=? WHERE id=1", LyricsRoomRows.DUPLICATE_ENTRY_ID)
        assertEquals(MetadataStoreIssue.SOURCE_CHANGED, failure { repository.clear(old) { true } })
        execute("UPDATE queue_head SET currentEntryId=? WHERE id=1", LyricsRoomRows.ENTRY_ID)
        execute("UPDATE local_sources SET state='REMOVED',authEpoch=authEpoch+1 WHERE sourceId=?", LyricsRoomRows.SOURCE_ID)
        repository.clear(repository.captureClear(MetadataPurpose.LYRICS)) { true }
        assertNull(ready().primary)
        assertIntegrity()
    }

    @Test(timeout = 30_000L)
    fun confirmedArtworkUsesRealOwnedBytesSurvivesCacheClearAndReturnsToEmbeddedArtworkAfterClear() = runBlocking {
        val original = File(temporaryFolder.root, "original-art.mp3").apply { writeBytes(embeddedPng(png(40, 20))) }
        val provider = MetadataOriginalProvider(original).apply {
            attachInfo(appContext, ProviderInfo().apply { authority = "lyrics-fixture"; exported = false })
        }
        ShadowContentResolver.registerProviderInternal("lyrics-fixture", provider)
        val target = repository.capture(MetadataPurpose.ARTWORK)
        val downloaded = png(8, 4)
        val result = repository.apply(target, downloaded, source()) { true }
        lateinit var images: LocalArtworkRepository
        images = LocalArtworkRepository(appContext, io, validateRead = { it.copy(contentValidation = LocalContentValidation.VERIFIED) },
            bitmaps = bitmaps, confirmedArtwork = { descriptor, edge -> repository.acquireArtwork(descriptor, edge, images) })
        images.acquireOwned(target.context.audio, ArtworkReadPath.LOCAL_EXTRACT)!!.use {
            assertEquals(8, it.bitmap.width); assertEquals(4, it.bitmap.height)
        }
        images.clearDiskCache()
        images.trimUnused()
        images.acquireOwned(target.context.audio, ArtworkReadPath.LOCAL_EXTRACT)!!.use { assertEquals(8, it.bitmap.width) }
        repository.clear(repository.captureClear(MetadataPurpose.ARTWORK)) { true }
        images.acquireOwned(target.context.audio, ArtworkReadPath.LOCAL_EXTRACT)!!.use {
            assertEquals(40, it.bitmap.width); assertEquals(20, it.bitmap.height)
        }
        assertNull(repository.read(trackKey, MetadataPurpose.ARTWORK).assetId)
        assertFalse(repository.isCurrent(result.target))
        assertIntegrity()
    }

    @Test(timeout = 30_000L)
    fun anUnverifiedSourceOrStaleContentCannotInstallAnAssociation() = runBlocking {
        val target = repository.capture(MetadataPurpose.ARTWORK)
        originalVerified = false
        assertEquals(MetadataStoreIssue.SOURCE_CHANGED, failure { repository.apply(target, png(8, 4), source()) { true } })
        assertNull(repository.read(trackKey, MetadataPurpose.ARTWORK).sha256)
        val directory = File(appContext.noBackupFilesDir, "local-metadata-artwork-v1")
        assertTrue(!directory.exists() || checkNotNull(directory.list()).isEmpty())
        originalVerified = true
        execute("UPDATE local_locations SET contentRevision=contentRevision+1 WHERE locationId=?", LyricsRoomRows.LOCATION_ID)
        assertEquals(MetadataStoreIssue.SOURCE_CHANGED, failure { repository.apply(target, png(8, 4), source()) { true } })
        assertNull(repository.read(trackKey, MetadataPurpose.ARTWORK).sha256)
        assertIntegrity()
    }

    @Test(timeout = 30_000L)
    fun rawCrossPurposeAndDamagedOwnerRowsBlockAccidentalFileRecovery() = runBlocking {
        val applied = repository.apply(repository.capture(MetadataPurpose.LYRICS), "[00:01]Fixture".toByteArray(), source()) { true }
        val current = ready()
        assertEquals(1, database.lyricsDao().update(current.revision,
            current.copy(revision = current.revision + 1L, primary = LyricsRoomRows.association()).entity()))
        execute("UPDATE local_metadata_associations SET purpose='UNKNOWN' WHERE trackId=?", trackKey.trackId)
        assertTrue(database.localMetadataDao().isAnyAssetReferenced(checkNotNull(applied.association.assetId)))
        assertTrue(database.localMetadataDao().recoveryBlocked(MetadataPurpose.LYRICS.name))
        repository.recoverLyrics()
        assertTrue(OwnedLyricsProvider.finalFile(appContext, checkNotNull(applied.association.assetId)).isFile)
    }

    private suspend fun failure(action: suspend () -> Any?): MetadataStoreIssue {
        try { action() } catch (failure: MetadataStoreException) { return failure.issue }
        throw AssertionError("A stale metadata operation was accepted")
    }
    private fun source() = MetadataProvenance("201", "Fixture song", "Fixture artist", "Fixture album")
}

private class MetadataRoomContext(base: Context, private val root: File) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
}

/** The source path is fixed by the historical queue fixture; only its cover bytes are supplied here. */
private class MetadataOriginalProvider(private val file: File) : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "audio/mpeg"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r" && uri.path == "/document/audio-object")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only fixture")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only fixture")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only fixture")
}
