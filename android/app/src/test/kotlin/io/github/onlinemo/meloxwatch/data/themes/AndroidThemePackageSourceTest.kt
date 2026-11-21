package io.github.onlinemo.meloxwatch.data.themes

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class AndroidThemePackageSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun selectedContentUriOpensOneReadOnlyDescriptorAndNeverQueriesOrWritesTheOriginal() {
        val provider = provider()
        val original = provider.file.readBytes()
        val source = AndroidThemePackageSource(context().contentResolver)
        val opened = source.open("content://selected-theme/package", CancellationSignal())
        assertEquals(original.size.toLong(), opened.declaredBytes)
        assertArrayEquals(original, opened.input.readBytes())
        assertEquals(1, provider.opens.get())
        assertEquals("r", provider.lastMode)
        opened.close()
        opened.close()
        assertFalse(checkNotNull(provider.lastDescriptor).fileDescriptor.valid())
        assertEquals(0, provider.queries.get())
        assertEquals(0, provider.writes.get())
        assertArrayEquals(original, provider.file.readBytes())
    }

    @Test fun networkFileOpaqueEmptyAndFragmentUrisAreRejectedBeforeProviderAccess() {
        val provider = provider()
        val source = AndroidThemePackageSource(context().contentResolver)
        for (uri in listOf("", "https://example.invalid/package.zip", "file:///package.zip", "content:/package",
            "content://selected-theme/package#fragment", "content://selected-theme/\npackage")) {
            Fixture.expect(ThemeIssue.INVALID_INPUT) { source.open(uri, CancellationSignal()) }
        }
        assertEquals(0, provider.opens.get())
        assertEquals(0, provider.queries.get())
        assertEquals(0, provider.writes.get())
    }

    @Test fun cancelledSignalDoesNotAcquireANewDescriptor() {
        val provider = provider()
        val signal = CancellationSignal().apply { cancel() }
        try {
            AndroidThemePackageSource(context().contentResolver).open("content://selected-theme/package", signal)
            throw AssertionError("Expected cancellation")
        } catch (_: OperationCanceledException) {
            assertEquals(0, provider.opens.get())
        }
    }

    @Test fun realProviderDenialHasAPermissionIssueAndKeepsTheSavedTheme() = runBlocking {
        val provider = provider().apply { denied = true }
        val directory = temporary.newFolder()
        val repository = Fixture.repository(directory, AndroidThemePackageSource(context().contentResolver))
        repository.read()
        val before = File(directory, "catalog.bin").readBytes()
        Fixture.expectSuspending(ThemeIssue.PERMISSION_DENIED) {
            repository.prepareImport(ThemeImportRequest(Fixture.id(), "content://selected-theme/package"))
        }
        assertEquals(1, provider.opens.get())
        assertEquals(0, provider.writes.get())
        assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
        assertEquals(ThemeBuiltins.defaultChoice, repository.state.value.selected)
    }

    @Test fun inputAndDescriptorCloseFailuresKeepTheFirstErrorAndCloseOnlyOnce() {
        val primary = IOException("input close")
        val secondary = IOException("descriptor close")
        val inputCalls = AtomicInteger()
        val descriptorCalls = AtomicInteger()
        val input = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() {
                inputCalls.incrementAndGet()
                throw primary
            }
        }
        val opened = OpenedThemePackage(input, 1) {
            descriptorCalls.incrementAndGet()
            throw secondary
        }
        try {
            opened.close()
            throw AssertionError("Expected input close failure")
        } catch (actual: IOException) {
            assertSame(primary, actual)
            assertTrue(actual.suppressed.any { it === secondary })
        }
        opened.close()
        assertEquals(1, inputCalls.get())
        assertEquals(1, descriptorCalls.get())
    }

    @Test fun aFatalInputCloseStillClosesTheDescriptorAndPreservesItsErrorIdentity() {
        val primary = AssertionError("controlled fatal close")
        val descriptors = AtomicInteger()
        val opened = OpenedThemePackage(object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() = throw primary
        }, null) { descriptors.incrementAndGet() }
        try {
            opened.close()
            throw AssertionError("Expected controlled fatal close")
        } catch (actual: AssertionError) {
            assertSame(primary, actual)
            assertEquals(1, descriptors.get())
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun provider(): SelectedThemeTestProvider {
        val provider = SelectedThemeTestProvider()
        provider.file = temporary.newFile().apply { writeBytes(Fixture.packageOf().zip()) }
        val info = ProviderInfo().apply { authority = "selected-theme" }
        provider.attachInfo(context(), info)
        ShadowContentResolver.registerProviderInternal("selected-theme", provider)
        return provider
    }
}

private class SelectedThemeTestProvider : ContentProvider() {
    lateinit var file: File
    val opens = AtomicInteger()
    val queries = AtomicInteger()
    val writes = AtomicInteger()
    var denied = false
    var lastMode: String? = null
    var lastDescriptor: ParcelFileDescriptor? = null

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = "application/zip"

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        opens.incrementAndGet()
        lastMode = mode
        if (denied) throw SecurityException("Controlled selected-document denial")
        check(mode == "r")
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        lastDescriptor = descriptor
        return AssetFileDescriptor(descriptor, 0, file.length())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?): Cursor? {
        queries.incrementAndGet()
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = rejectWrite()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = rejectWrite()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = rejectWrite()

    private fun rejectWrite(): Nothing {
        writes.incrementAndGet()
        throw AssertionError("Theme import must not modify its selected source")
    }
}
