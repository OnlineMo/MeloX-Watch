package io.github.onlinemo.meloxwatch.ui.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import io.github.onlinemo.meloxwatch.data.backup.BackupDocumentDestination
import io.github.onlinemo.meloxwatch.data.backup.BackupDocumentInput

/** 保留真实返回 flags；Unit 输入不会在重组或 Activity 重建时自动再发起选择。 */
class BackupInputContract : ActivityResultContract<Unit, BackupDocumentInput?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): BackupDocumentInput? {
        if (resultCode != Activity.RESULT_OK || intent?.data == null) return null
        return BackupDocumentInput(checkNotNull(intent.data), intent.flags and RETURNED_GRANT_FLAGS)
    }
}

class BackupDestinationContract : ActivityResultContract<Unit, BackupDocumentDestination?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): BackupDocumentDestination? {
        if (resultCode != Activity.RESULT_OK || intent?.data == null) return null
        return BackupDocumentDestination(checkNotNull(intent.data), intent.flags and RETURNED_GRANT_FLAGS)
    }
}

private const val RETURNED_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
