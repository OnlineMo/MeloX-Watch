package io.github.onlinemo.meloxwatch.data.storage

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import io.github.onlinemo.meloxwatch.data.artwork.LocalArtworkRepository
import io.github.onlinemo.meloxwatch.data.backup.BackupStartupState
import io.github.onlinemo.meloxwatch.data.backup.NativeBackupFixture
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationException
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationIssue
import io.github.onlinemo.meloxwatch.data.lyrics.online.DiskOnlineLyricsCache
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsCacheKey
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsText
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataArtworkFiles
import io.github.onlinemo.meloxwatch.data.metadata.metadataSha256
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Real owners, private files, Room and LP-10 admission; audio/device acceptance is separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class FileStorageRepositoryIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: NativeBackupFixture

    @Before fun open() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fixture = NativeBackupFixture(RuntimeEnvironment.getApplication() as Context, temporary.newFolder("app"))
    }

    @After fun close() {
        try { runBlocking { withTimeout(20_000L) { fixture.close() } } }
        finally { Dispatchers.resetMain() }
    }

    @Test(timeout = 60_000L)
    fun actualOwnerNamespacesRemainByteExactWhenTheRealCacheOwnersClearTheirDerivedFiles() = runBlocking {
        ready()
        val metadataFiles = LocalMetadataArtworkFiles(fixture.context, fixture.io, fixture.barrier,
            freeBytes = File::getUsableSpace, bitmaps = fixture.bitmaps)
        val lyricBytes = "[00:00.00]Saved local text\n".toByteArray(Charsets.UTF_8)
        val lyric = fixture.copies.createConfirmedMetadata(lyricBytes, metadataSha256(lyricBytes))
        val imageBytes = png()
        val image = metadataFiles.installConfirmed(imageBytes, metadataSha256(imageBytes))
        val specs = fixture.io.read { signal -> signal.throwIfCanceled(); specifications(metadataFiles) }
        val audio = specs.single { it.area == StorageArea.AUDIO }.directory
        val incoming = specs.first { it.area == StorageArea.RECOVERY }.directory
        val lyrics = specs.single { it.area == StorageArea.LYRICS }.directory
        val savedArtwork = specs.single { it.area == StorageArea.ARTWORK }.directory
        val fonts = specs.single { it.area == StorageArea.FONTS }.directory
        val themes = specs.single { it.area == StorageArea.THEMES }.directory
        val backgrounds = specs.single { it.area == StorageArea.BACKGROUNDS }.directory
        val recovery = specs.last { it.area == StorageArea.RECOVERY }.directory
        write(audio, "${UUID.randomUUID()}.audio", byteArrayOf(1, 2, 3, 4, 5, 6))
        write(fonts, "${UUID.randomUUID()}.font", byteArrayOf(7, 8, 9))
        write(themes, "owner.lock", byteArrayOf())
        write(themes, "catalog.bin", byteArrayOf(10, 11))
        write(themes, "installed/${UUID.randomUUID()}/assets/raw.bin", byteArrayOf(12, 13, 14, 15))
        write(backgrounds, "owner.lock", byteArrayOf())
        write(backgrounds, "settings.bin", byteArrayOf(16, 17))
        write(backgrounds, "${UUID.randomUUID()}.image", imageBytes)
        val stages = listOf(
            write(audio, "${UUID.randomUUID()}.part", byteArrayOf(18, 19)),
            write(incoming, "${UUID.randomUUID()}.partial", byteArrayOf(20, 21, 22)),
            write(lyrics, "${UUID.randomUUID()}.part", byteArrayOf(23, 24, 25)),
            write(savedArtwork, "${UUID.randomUUID()}.part", byteArrayOf(26, 27)),
            write(fonts, "${UUID.randomUUID()}.part", byteArrayOf(28, 29)),
            write(themes, "received/${UUID.randomUUID()}/input.zip", byteArrayOf(30, 31, 32)),
            write(backgrounds, "${UUID.randomUUID()}.part", byteArrayOf(33, 34)),
        )
        val external = write(temporary.newFolder("original"), "preserved.audio", byteArrayOf(35, 36, 37))
        fixture.playlists.apply(PlaylistMutationRequest("storage-create", "storage-preserved", 0L,
            PlaylistMutation.Create("Preserved playlist")))
        val playlistBefore = fixture.playlists.snapshot("storage-preserved")
        val themeBefore = fixture.themes.state.value
        val backgroundBefore = fixture.background.state.value
        val retained = snapshot(specs.map { it.directory } + external.parentFile)
        val expectedRecovery = stages.sumOf(File::length) + snapshot(listOf(recovery)).values.sumOf { it.size.toLong() }
        val artwork = LocalArtworkRepository(fixture.context, fixture.io,
            validateRead = { throw AssertionError("Storage must not request an original audio read") }, bitmaps = fixture.bitmaps)
        val onlineCache = DiskOnlineLyricsCache(File(fixture.context.noBackupFilesDir, "online-lyrics-cache-fixture"))
        val key = OnlineLyricsCacheKey("a".repeat(64), "b".repeat(64))
        assertTrue(onlineCache.write(key, OnlineLyricsText(lyricBytes, null)) { true })
        val displayedArtwork = checkNotNull(artwork.derived("c".repeat(64), 96, imageBytes))
        val inventory = StoragePrivateInventory(fixture.context, fixture.io)
        val repository = FileStorageRepository(fixture.context, fixture.io, fixture.fileOperations,
            artwork::measureDiskCache, artwork::clearDiskCache, onlineCache::measureStorage, onlineCache::clearStorage,
            privateMeasure = {
                fixture.coordinator.requireReady()
                fixture.barrier.mutation({ StorageException(StorageIssue.BUSY) }) {
                    inventory.measure { specifications(metadataFiles) }
                }
            })
        try {
            val before = repository.measure()
            assertEquals(6L, before.audioCopies.bytes)
            assertEquals(lyricBytes.size.toLong(), before.lyricsCopies.bytes)
            assertEquals(imageBytes.size.toLong(), before.savedArtwork.bytes)
            assertEquals(3L, before.fonts.bytes)
            assertEquals(6L, before.themes.bytes)
            assertEquals(2L + imageBytes.size, before.backgrounds.bytes)
            assertEquals(expectedRecovery, before.recoveryStaging.observedBytes)
            assertFalse(before.recoveryStaging.complete)
            assertTrue(listOf(before.audioCopies, before.lyricsCopies, before.savedArtwork, before.fonts,
                before.themes, before.backgrounds).all { it.complete })
            assertTrue(before.artworkCache.complete && before.lyricsCache.complete)
            assertEquals(themeBefore, fixture.themes.state.value)
            assertEquals(backgroundBefore, fixture.background.state.value)
            assertSnapshot(retained, specs.map { it.directory } + external.parentFile)
            val cleared = repository.clearCaches()
            assertTrue(checkNotNull(cleared.artworkAfter.bytes) < checkNotNull(cleared.artworkBefore.bytes))
            assertEquals(0L, cleared.lyricsAfter.bytes)
            assertFalse(displayedArtwork.bitmap.isRecycled)
            assertSnapshot(retained, specs.map { it.directory } + external.parentFile)
            assertEquals(playlistBefore, fixture.playlists.snapshot("storage-preserved"))
            assertArrayEquals(imageBytes, metadataFiles.readVerified(image.assetId, image.byteCount, image.sha256))
            val after = repository.measure()
            assertEquals(before.audioCopies, after.audioCopies)
            assertEquals(before.lyricsCopies, after.lyricsCopies)
            assertEquals(before.savedArtwork, after.savedArtwork)
            assertEquals(before.fonts, after.fonts)
            assertEquals(before.themes, after.themes)
            assertEquals(before.backgrounds, after.backgrounds)
            assertEquals(before.recoveryStaging, after.recoveryStaging)
            assertEquals(cleared.artworkAfter, after.artworkCache)
            assertEquals(cleared.lyricsAfter, after.lyricsCache)
        } finally { displayedArtwork.close(); lyric.release(); image.release() }
    }

    @Test(timeout = 60_000L)
    fun cancelledMeasurementKeepsTheRepositoryAndOriginalMutationGuardUntilItsActualReadExits() = runBlocking {
        ready()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = AtomicBoolean(false)
        val blockNext = AtomicBoolean(true)
        val zero = StorageCategory(0L, 0L)
        val repository = FileStorageRepository(fixture.context, fixture.io, fixture.fileOperations,
            artworkMeasure = { zero }, artworkClear = { zero }, lyricsMeasure = { zero }, lyricsClear = { zero },
            privateMeasure = {
                fixture.io.read {
                    if (blockNext.getAndSet(false)) {
                        entered.countDown()
                        try { check(release.await(10, TimeUnit.SECONDS)) }
                        finally { exited.set(true) }
                    }
                    StoragePrivateReport(zero, zero, zero, zero, zero, zero, zero, null)
                }
            })
        val reading = async(Dispatchers.Default) { repository.measure() }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            reading.cancel()
            assertFalse(reading.isCompleted)
            assertFalse(exited.get())
            try { repository.measure(); throw AssertionError("Cancelled command was released before its actual read") }
            catch (failure: StorageException) { assertEquals(StorageIssue.BUSY, failure.issue) }
            try {
                fixture.fileOperations.withExclusiveLibraryMutation(requireNoPending = false) {
                    throw AssertionError("Original mutation guard was released before its actual read")
                }
                throw AssertionError("Busy original mutation guard was accepted")
            } catch (failure: FileOperationException) { assertEquals(FileOperationIssue.BUSY, failure.issue) }
            release.countDown()
            withTimeout(15_000L) { reading.join() }
            assertTrue(exited.get())
            var admitted = false
            fixture.fileOperations.withExclusiveLibraryMutation(requireNoPending = false) { admitted = true }
            assertTrue(admitted)
            assertEquals(zero, repository.measure().audioCopies)
        } finally { release.countDown(); reading.cancel(); reading.join() }
    }

    private suspend fun ready() {
        assertTrue(withTimeout(20_000L) { fixture.coordinator.initializeStorage() } is BackupStartupState.Ready)
        fixture.fileOperations.initialize()
    }

    private fun specifications(metadataFiles: LocalMetadataArtworkFiles): List<StorageDirectorySpec> =
        FileStoragePhysical.privateAudioSpecifications(fixture.context) + listOf(
            fixture.copies.storageDirectorySpecOnWorker(), metadataFiles.storageDirectorySpecOnWorker(),
            fixture.fontFiles.storageDirectorySpecOnWorker(), fixture.themes.storageDirectorySpecOnWorker(),
            fixture.background.storageDirectorySpecOnWorker(), fixture.coordinator.storageDirectorySpecOnWorker())

    private fun write(root: File, name: String, bytes: ByteArray): File = File(root, name).apply {
        checkNotNull(parentFile).mkdirs(); writeBytes(bytes)
    }

    private fun snapshot(roots: List<File>): Map<File, ByteArray> = roots.flatMap { root ->
        if (root.exists()) root.walkTopDown().filter { it.isFile }.toList() else emptyList()
    }.associateWith(File::readBytes)

    private fun assertSnapshot(before: Map<File, ByteArray>, roots: List<File>) {
        val after = snapshot(roots)
        assertEquals(before.keys, after.keys)
        for ((file, bytes) in before) assertArrayEquals(bytes, after[file])
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
