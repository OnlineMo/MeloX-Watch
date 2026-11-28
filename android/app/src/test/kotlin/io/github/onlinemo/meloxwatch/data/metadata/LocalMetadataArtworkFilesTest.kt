package io.github.onlinemo.meloxwatch.data.metadata

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkException
import io.github.onlinemo.meloxwatch.data.artwork.LocalArtworkRepository
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Immutable assets use actual private files and decode; these tests do not attest device fsync/heap metrics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalMetadataArtworkFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context() = MetadataFileContext(RuntimeEnvironment.getApplication(), temporary.newFolder())

    @Test(timeout = 30_000L)
    fun confirmedBytesSurviveRestartAndRealCacheClearUntilTheirAssociationIsExplicitlyRemoved() = runBlocking {
        val context = context()
        val io = LocalProviderIo()
        val bitmaps = ThemeBitmapOwner()
        val files = LocalMetadataArtworkFiles(context, io, freeBytes = { 1_000_000_000L }, bitmaps = bitmaps)
        val png = png()
        val asset = files.installConfirmed(png, metadataSha256(png))
        val token = asset.assetId
        asset.release()
        val images = LocalArtworkRepository(context, io, validateRead = { throw AssertionError("No original audio read") }, bitmaps = bitmaps)
        val derived = images.derived("a".repeat(64), 96, png)
        assertTrue(derived != null)
        derived?.close()
        images.clearDiskCache()
        assertArrayEquals(png, files.readVerified(token, png.size.toLong(), metadataSha256(png)))
        val restarted = LocalMetadataArtworkFiles(context, io, freeBytes = { 1_000_000_000L }, bitmaps = bitmaps)
        restarted.recover { it == token }
        assertArrayEquals(png, restarted.readVerified(token, png.size.toLong(), metadataSha256(png)))
        restarted.recover { false }
        assertTrue(checkNotNull(assetRoot(context).list()).isEmpty())
    }

    @Test(timeout = 30_000L)
    fun malformedImagesAndInsufficientSpaceNeverReplaceOrEvictAConfirmedAsset() = runBlocking {
        val context = context()
        val io = LocalProviderIo()
        var available = 1_000_000_000L
        val files = LocalMetadataArtworkFiles(context, io, freeBytes = { available })
        val valid = png()
        val asset = files.installConfirmed(valid, metadataSha256(valid))
        asset.release()
        val before = checkNotNull(assetRoot(context).list()).toSet()
        val invalid = byteArrayOf(1, 2, 3, 4)
        try { files.installConfirmed(invalid, metadataSha256(invalid)); throw AssertionError("Malformed image was installed") }
        catch (_: ArtworkException) { }
        assertEquals(before, checkNotNull(assetRoot(context).list()).toSet())
        available = 0L
        assertEquals(MetadataStoreIssue.LOW_SPACE, failure { files.installConfirmed(valid, metadataSha256(valid)) })
        assertEquals(before, checkNotNull(assetRoot(context).list()).toSet())
        assertArrayEquals(valid, files.readVerified(asset.assetId, asset.byteCount, asset.sha256))
    }

    @Test(timeout = 30_000L)
    fun aReferencedDamagedFileIsReportedAndRetainedDuringRecovery() = runBlocking {
        val context = context()
        val files = LocalMetadataArtworkFiles(context, LocalProviderIo(), freeBytes = { 1_000_000_000L })
        val original = png()
        val asset = files.installConfirmed(original, metadataSha256(original))
        asset.release()
        val installed = File(assetRoot(context), "${asset.assetId}.image")
        installed.writeBytes(byteArrayOf(1))
        assertEquals(MetadataStoreIssue.SOURCE_CHANGED, failure { files.readVerified(asset.assetId, asset.byteCount, asset.sha256) })
        files.recover { it == asset.assetId }
        assertTrue(installed.isFile)
        assertArrayEquals(byteArrayOf(1), installed.readBytes())
    }

    @Test(timeout = 30_000L)
    fun cancellationKeepsThePhysicalAndReferenceOwnerUntilTheActualDirectorySyncRetires() = runBlocking {
        val context = context()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val files = LocalMetadataArtworkFiles(context, LocalProviderIo(), freeBytes = { 1_000_000_000L },
            syncDirectory = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) })
        val bytes = png()
        val writing = async(Dispatchers.Default) { files.installConfirmed(bytes, metadataSha256(bytes)) }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            writing.cancel()
            assertEquals(MetadataStoreIssue.BUSY, failure { files.withReferenceWrite { Unit } })
            assertFalse(writing.isCompleted)
            release.countDown()
            writing.join()
            files.recover { false }
            assertTrue(checkNotNull(assetRoot(context).list()).isEmpty())
        } finally { release.countDown(); writing.cancel(); writing.join() }
    }

    @Test(timeout = 30_000L)
    fun aLinkedAssetRootCannotWriteOrRemoveAnythingOutsideItsPrivateDirectory() = runBlocking {
        val context = context()
        val other = temporary.newFolder()
        Files.createSymbolicLink(assetRoot(context).toPath(), other.toPath())
        val files = LocalMetadataArtworkFiles(context, LocalProviderIo(), freeBytes = { 1_000_000_000L })
        val bytes = png()
        assertEquals(MetadataStoreIssue.INVALID_RECORD, failure { files.installConfirmed(bytes, metadataSha256(bytes)) })
        assertTrue(checkNotNull(other.list()).isEmpty())
    }

    private suspend fun failure(action: suspend () -> Any?): MetadataStoreIssue {
        try { action() } catch (failure: MetadataStoreException) { return failure.issue }
        throw AssertionError("The invalid asset operation was accepted")
    }
    private fun assetRoot(context: Context) = File(context.noBackupFilesDir, "local-metadata-artwork-v1")
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            return ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        } finally { bitmap.recycle() }
    }
}

private class MetadataFileContext(base: Context, private val root: File) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
}
