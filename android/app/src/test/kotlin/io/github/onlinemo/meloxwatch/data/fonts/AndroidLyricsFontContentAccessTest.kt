package io.github.onlinemo.meloxwatch.data.fonts

import android.content.ClipDescription
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/** Real content resolver/asset descriptors and native decoding; no audio-route or MIME shortcut. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidLyricsFontContentAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realSafReadClosesItsDescriptorAndPrivateCopySurvivesOriginalPermissionWithdrawal() = runBlocking {
        val original = Fixture.file(temporary.root)
        val before = Fixture.sha256(original.readBytes())
        val provider = provider("selected-font-normal", original)
        val files = AndroidLyricsFontFiles(context(), LocalProviderIo())
        val request = Fixture.request().copy(uri = "content://selected-font-normal/font")
        val prepared = files.importFont(request, emptySet())
        assertEquals(listOf("r"), provider.modes)
        assertFalse(checkNotNull(provider.lastDescriptor).fileDescriptor.valid())
        assertEquals(before, prepared.asset.sha256)
        provider.denied = true
        val restored = AndroidLyricsFontFiles(context(), LocalProviderIo())
            .loadFont(LyricsFontSelection(prepared.asset, 600f), "MeloX Watch")
        assertNotNull(restored.typeface)
        assertEquals(FontWeightSupport.AVAILABLE, restored.capability.support)
        assertEquals(600f, restored.configuredAxisWeight)
        assertEquals(1, provider.modes.size)
        assertEquals(before, Fixture.sha256(original.readBytes()))
        assertTrue(original.isFile)
    }

    @Test fun deniedRealSelectedDocumentPreservesTheOriginalAndFallsBackWithoutNativeImport() = runBlocking {
        val original = Fixture.file(temporary.root, Fixture.STATIC)
        val before = Fixture.sha256(original.readBytes())
        val provider = provider("selected-font-denied", original).apply { denied = true }
        val files = AndroidLyricsFontFiles(context(), LocalProviderIo())
        try {
            files.importFont(Fixture.request(Fixture.STATIC).copy(uri = "content://selected-font-denied/font"), emptySet())
            throw AssertionError("Expected revoked read permission")
        } catch (error: LyricsFontException) { assertEquals(LyricsFontIssue.PERMISSION_DENIED, error.issue) }
        assertEquals(listOf("r"), provider.modes)
        assertTrue(checkNotNull(File(context().filesDir, "lyrics-fonts-v1").list()).isEmpty())
        assertEquals(before, Fixture.sha256(original.readBytes()))
        assertTrue(original.isFile)
    }

    @Test fun descriptorOffsetAndDeclaredLengthLimitImportToTheUserSelectedFontRegion() = runBlocking {
        val font = Fixture.bytes(Fixture.STATIC)
        val original = File(temporary.root, "wrapped-font").apply { writeBytes(ByteArray(17) + font + ByteArray(23)) }
        val provider = provider("selected-font-region", original).apply { startOffset = 17L; length = font.size.toLong() }
        val files = AndroidLyricsFontFiles(context(), LocalProviderIo())
        val prepared = files.importFont(Fixture.request(Fixture.STATIC).copy(uri = "content://selected-font-region/font"), emptySet())
        assertEquals(font.size.toLong(), prepared.asset.byteCount)
        assertEquals(Fixture.sha256(font), prepared.asset.sha256)
        assertFalse(checkNotNull(provider.lastDescriptor).fileDescriptor.valid())
        assertEquals(SfntOutline.CFF, prepared.info.outline)
    }

    @Test fun networkAndRawFilesystemUrisAreRejectedBeforeAnyResolverAccess() = runBlocking {
        val original = Fixture.file(temporary.root)
        val provider = provider("selected-font-uri", original)
        val files = AndroidLyricsFontFiles(context(), LocalProviderIo())
        for (uri in listOf("https://example.invalid/font.ttf", original.toURI().toString())) {
            try {
                files.importFont(Fixture.request().copy(uri = uri), emptySet())
                throw AssertionError("Expected document route only")
            } catch (error: LyricsFontException) { assertEquals(LyricsFontIssue.INVALID_INPUT, error.issue) }
        }
        assertTrue(provider.modes.isEmpty())
        assertTrue(original.isFile)
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()
    private fun provider(authority: String, original: File): FontDocumentProvider = FontDocumentProvider().apply {
        file = original
        length = original.length()
        attachInfo(context(), ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, this)
    }
}

class FontDocumentProvider : ContentProvider() {
    lateinit var file: File
    var denied = false
    var startOffset = 0L
    var length = 0L
    val modes = ArrayList<String>()
    var lastDescriptor: ParcelFileDescriptor? = null
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
    // Read-only resolver calls use the typed route; forward cancellation to the same file slice.
    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor {
        if (mimeTypeFilter != "*/*" && !ClipDescription.compareMimeTypes(getType(uri), mimeTypeFilter)) {
            throw FileNotFoundException("Controlled selected-font type mismatch")
        }
        return openAssetFile(uri, "r", signal)
    }

    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor {
        modes += mode
        if (denied) throw SecurityException("Controlled selected-font permission withdrawal")
        check(mode == "r")
        signal?.throwIfCanceled()
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        lastDescriptor = descriptor
        return AssetFileDescriptor(descriptor, startOffset, length)
    }
}
