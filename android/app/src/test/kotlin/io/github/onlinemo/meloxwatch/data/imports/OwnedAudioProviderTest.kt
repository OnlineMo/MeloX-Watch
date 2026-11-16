package io.github.onlinemo.meloxwatch.data.imports

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class OwnedAudioProviderTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var provider: OwnedAudioProvider

    @Before fun setup() {
        val root = temporary.newFolder("private")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
            override fun getNoBackupFilesDir(): File = root
        }
        provider = OwnedAudioProvider().also {
            it.attachInfo(context, ProviderInfo().apply { authority = OwnedAudioProvider.authority(context); exported = false })
        }
    }

    @Test fun onlyACompletedTokenCanBeReadAndItsActualBytesAreReturned() {
        val token = UUID.randomUUID().toString()
        val bytes = byteArrayOf(1, 2, 3, 4)
        OwnedAudioProvider.stagingFile(context, token).writeBytes(bytes)
        val uri = OwnedAudioProvider.uri(context, token)
        expect<FileNotFoundException> { provider.openFile(uri, "r") }
        OwnedAudioProvider.finalFile(context, token).writeBytes(bytes)
        ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { assertArrayEquals(bytes, it.readBytes()) }
        provider.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null).use {
            assertTrue(it.moveToFirst())
            assertEquals(bytes.size.toLong(), it.getLong(0))
        }
    }

    @Test fun foreignUrisPathTraversalExtraSegmentsAndSymlinksCannotExposeOtherFiles() {
        val token = UUID.randomUUID().toString()
        val authority = OwnedAudioProvider.authority(context)
        val outside = File(temporary.root, "private-secret").apply { writeText("outside") }
        val bad = listOf("file://${outside.absolutePath}", "content://other/$token", "content://$authority/../$token",
            "content://$authority/$token/extra", "content://$authority/$token?path=outside", "content://$authority/$token#outside")
        for (value in bad) expect<FileNotFoundException> { provider.openFile(Uri.parse(value), "r") }
        Files.createSymbolicLink(OwnedAudioProvider.finalFile(context, token).toPath(), outside.toPath())
        expect<FileNotFoundException> { provider.openFile(OwnedAudioProvider.uri(context, token), "r") }
        assertEquals("outside", outside.readText())
    }

    @Test fun noWriteModeMutationOrForeignUidCanReachTheStoredCopy() {
        val token = UUID.randomUUID().toString()
        val file = OwnedAudioProvider.finalFile(context, token).apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val uri = OwnedAudioProvider.uri(context, token)
        for (mode in listOf("w", "rw", "rwt", "wa")) expect<SecurityException> { provider.openFile(uri, mode) }
        expect<UnsupportedOperationException> { provider.insert(uri, ContentValues()) }
        expect<UnsupportedOperationException> { provider.update(uri, ContentValues(), null, null) }
        expect<UnsupportedOperationException> { provider.delete(uri, null, null) }
        ShadowBinder.setCallingUid(Process.myUid() + 1)
        try { expect<SecurityException> { provider.openFile(uri, "r") } }
        finally { ShadowBinder.setCallingUid(Process.myUid()) }
        assertArrayEquals(byteArrayOf(7, 8, 9), file.readBytes())
    }

    private inline fun <reified T : Throwable> expect(action: () -> Any?) {
        try { action(); throw AssertionError("Expected ${T::class.java.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
