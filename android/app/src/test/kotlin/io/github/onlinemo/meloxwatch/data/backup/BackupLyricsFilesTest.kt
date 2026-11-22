package io.github.onlinemo.meloxwatch.data.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackEntity
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.active.OwnedLyricsProvider
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.entity
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver

/** Local real-file content providers; fixture package flags do not declare device support. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class BackupLyricsFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: NativeBackupFixture
    @Before fun open() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fixture = NativeBackupFixture(RuntimeEnvironment.getApplication() as Context, temporary.newFolder("app"))
        check(fixture.coordinator.initializeStorage() is BackupStartupState.Ready)
    }
    @After fun close() {
        try { runBlocking { fixture.close() } } finally { Dispatchers.resetMain() }
    }

    private fun registerLocalProvider(authority: String, provider: ContentProvider, system: Boolean) {
        val info = ProviderInfo().apply {
            this.authority = authority
            packageName = if (system) "com.android.externalstorage" else fixture.context.packageName
            name = provider.javaClass.name
            exported = system
            grantUriPermissions = true
            applicationInfo = ApplicationInfo().apply {
                packageName = infoPackage(system)
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        }
        shadowOf(fixture.context.packageManager).addOrUpdateProvider(info)
        provider.attachInfo(fixture.context, info)
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    private fun infoPackage(system: Boolean) = if (system) "com.android.externalstorage" else fixture.context.packageName

    @Test(timeout = 60_000L)
    fun realExternalLocalAndOwnedLyricBytesBothSurvivePortableCaptureAndZipRestore() = runBlocking {
        val externalBytes = "[00:01.00]External fixture line\n".toByteArray()
        val ownedBytes = "[00:01.00]Owned fixture line\n[00:02.00]Second line\n".toByteArray()
        val externalFile = temporary.newFile("external.lrc").apply { writeBytes(externalBytes) }
        val ownedInputFile = temporary.newFile("owned-input.lrc").apply { writeBytes(ownedBytes) }
        val externalUri = Uri.Builder().scheme("content").authority("com.android.externalstorage.documents")
            .appendPath("document").appendPath("primary:Music/external.lrc").build()
        val ownedInputUri = Uri.Builder().scheme("content").authority("com.android.externalstorage.documents")
            .appendPath("document").appendPath("primary:Music/owned-input.lrc").build()
        registerLocalProvider("com.android.externalstorage.documents", FixtureTextProvider(mapOf(externalUri to externalFile, ownedInputUri to ownedInputFile)), true)
        registerLocalProvider(OwnedLyricsProvider.authority(fixture.context), OwnedLyricsProvider(), false)
        fixture.context.contentResolver.takePersistableUriPermission(externalUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val captured = BackupPrivateDirectory.open(temporary.newFolder("capture"))
        fixture.barrier.exclusively {
            val external = fixture.sources.observe(LocalSourceSpec("fixture-external-lyrics", LocalSourceKind.DOCUMENT,
                externalUri.toString(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
            val ownedInput = fixture.sources.observe(LocalSourceSpec("fixture-owned-input", LocalSourceKind.DOCUMENT, ownedInputUri.toString()))
            val copy = fixture.copies.create(ownedInput.root, backupSha256(ownedBytes))
            try {
                val owned = fixture.sources.observe(LocalSourceSpec("fixture-owned-lyrics", LocalSourceKind.DOCUMENT, copy.uri))
                fixture.library.libraryTransaction {
                    val dao = fixture.database.libraryDao()
                    dao.insertTrack(LibraryTrackEntity("fixture-external-track", "local", BackupTestFixtures.EPOCH_MS, 1L))
                    dao.insertTrack(LibraryTrackEntity("fixture-owned-track", "local", BackupTestFixtures.EPOCH_MS, 2L))
                    val meta = dao.meta()!!
                    dao.updateMeta(meta.copy(nextAddedSequence = 3L, revision = meta.revision + 1L))
                    fixture.database.lyricsDao().insert(LyricsTrackPreferences(TrackKey("local", "fixture-external-track"),
                        primary = LyricsAssociation(external.root.objectId, external.source.sourceId, externalUri.toString(), 1,
                            LyricsEncodingChoice.UTF8, backupSha256(externalBytes)), userDelayMs = 100).entity())
                    fixture.database.lyricsDao().insert(LyricsTrackPreferences(TrackKey("local", "fixture-owned-track"),
                        primary = LyricsAssociation(owned.root.objectId, owned.source.sourceId, copy.uri, 0,
                            LyricsEncodingChoice.UTF8, copy.sha256)).entity())
                }
                SqliteBackupStage.create(captured) {}.use { stage ->
                    stage.begin(BackupTestFixtures.header(BackupScope.LYRICS, BackupScope.RESOURCES))
                    RoomBackupCapture(fixture.database).captureMain(stage)
                    fixture.resources.capture(stage, emptyMap(), null)
                    stage.sealSnapshot()
                    assertNull(stage.row(BackupRecordKind.LYRICS_TRACK, "fixture-owned-track")!!.optionalText("primaryDocumentIdHint"))
                    assertEquals(backupSha256(ownedBytes), stage.row(BackupRecordKind.LYRICS_TRACK, "fixture-owned-track")!!.text("primarySha256"))
                    assertEquals(2L, stage.count(BackupRecordKind.RESOURCE))
                    val archive = captured.child("export.zip")
                    BackupArchiveWriter().write(stage, archive) {}
                    val target = BackupPrivateDirectory.open(temporary.newFolder("roundtrip"))
                    SqliteBackupStage.create(target) {}.use { restored ->
                        BackupArchiveReader().stage(archive, restored) {}
                        val first = restored.row(BackupRecordKind.LYRICS_TRACK, "fixture-external-track")!!
                        val second = restored.row(BackupRecordKind.LYRICS_TRACK, "fixture-owned-track")!!
                        assertEquals(100L, first.long("userDelayMs"))
                        assertArrayEquals(externalBytes, restored.openResource(first.text("primaryResourceId")).use { it.readBytes() })
                        assertArrayEquals(ownedBytes, restored.openResource(second.text("primaryResourceId")).use { it.readBytes() })
                    }
                }
            } finally { copy.release() }
        }
        assertArrayEquals(externalBytes, externalFile.readBytes())
        assertArrayEquals(ownedBytes, ownedInputFile.readBytes())
    }

    @Test(timeout = 30_000L)
    fun unknownProviderIsRejectedBeforeAnyExternalQueryOrOpen() = runBlocking {
        val file = temporary.newFile("unknown.lrc").apply { writeText("Fixture line") }
        val uri = Uri.parse("content://fixture.unknown/lyrics")
        val provider = FixtureTextProvider(mapOf(uri to file))
        registerLocalProvider("fixture.unknown", provider, false)
        val root = BackupPrivateDirectory.open(temporary.newFolder("capture"))
        fixture.barrier.exclusively {
            fixture.library.libraryTransaction {
                fixture.database.libraryDao().insertTrack(LibraryTrackEntity("fixture-unknown-track", "local", BackupTestFixtures.EPOCH_MS, 1L))
                fixture.database.lyricsDao().insert(LyricsTrackPreferences(TrackKey("local", "fixture-unknown-track"),
                    primary = LyricsAssociation("uri:$uri", "fixture-unknown-source", uri.toString(), 1,
                        LyricsEncodingChoice.UTF8, backupSha256(file.readBytes()))).entity())
            }
            SqliteBackupStage.create(root) {}.use { stage ->
                stage.begin(BackupTestFixtures.header(BackupScope.LYRICS, BackupScope.RESOURCES))
                RoomBackupCapture(fixture.database).captureMain(stage)
                try {
                    fixture.resources.capture(stage, emptyMap(), null)
                    throw AssertionError("Unknown provider must be rejected")
                } catch (failure: BackupException) { assertEquals(BackupIssue.UNSUPPORTED_RESOURCE, failure.issue) }
            }
        }
        assertEquals(0, provider.queries); assertEquals(0, provider.opens)
        assertTrue(file.isFile)
    }

    private class FixtureTextProvider(private val files: Map<Uri, File>) : ContentProvider() {
        var queries = 0
        var opens = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r"); opens++
            return ParcelFileDescriptor.open(files.getValue(uri), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            val file = files.getValue(uri)
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply { addRow(columns.map { column -> when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> uri.lastPathSegment
                DocumentsContract.Document.COLUMN_MIME_TYPE -> "text/plain"
                DocumentsContract.Document.COLUMN_FLAGS -> 0
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L
                else -> null
            } }) }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    }
}
