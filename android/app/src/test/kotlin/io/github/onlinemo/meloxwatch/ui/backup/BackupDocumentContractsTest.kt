package io.github.onlinemo.meloxwatch.ui.backup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 选择器结果保留真实 flags；测试不授予 URI 权限、不访问 provider、不写文档。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class BackupDocumentContractsTest {
    @Test
    fun inputAsksForOneLocalOpenableDocumentAndDoesNotRequestWrite() {
        val intent = BackupInputContract().createIntent(RuntimeEnvironment.getApplication(), Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
    }

    @Test
    fun outputChoosesATreeInsteadOfTreatingAnExistingEmptyFileAsOwned() {
        val intent = BackupDestinationContract().createIntent(RuntimeEnvironment.getApplication(), Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        val needed = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertEquals(needed, intent.flags and needed)
    }

    @Test
    fun returnedGrantBitsAreNotInventedAndOpaqueInputsDoNotStringifyTheirUri() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Agenerated.zip")
        val result = checkNotNull(BackupInputContract().parseResult(Activity.RESULT_OK,
            Intent().setData(uri).setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
        assertEquals(uri, result.uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, result.returnedFlags)
        assertEquals("BackupDocumentInput", result.toString())
        assertNull(BackupInputContract().parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
        assertNull(BackupDestinationContract().parseResult(Activity.RESULT_OK, Intent()))
    }
}
