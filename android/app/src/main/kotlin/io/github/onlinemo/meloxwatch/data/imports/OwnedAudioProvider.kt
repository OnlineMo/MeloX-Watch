package io.github.onlinemo.meloxwatch.data.imports

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.UUID

/** Internal completed-copy access. It accepts neither filesystem paths nor write operations. */
class OwnedAudioProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireInternalCaller()
        if (mode != "r") throw SecurityException("Imported audio is read-only")
        return ParcelFileDescriptor.open(completedFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        requireInternalCaller()
        completedFile(uri)
        return "audio/*"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        requireInternalCaller()
        val file = completedFile(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val values: List<Any?> = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply {
            addRow(values)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only provider")

    private fun requireInternalCaller() {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Internal audio access only")
    }

    private fun completedFile(uri: Uri): File {
        val app = context ?: throw FileNotFoundException("Provider unavailable")
        if (uri.scheme != "content" || uri.authority != authority(app) || uri.pathSegments.size != 1 ||
            uri.query != null || uri.fragment != null
        ) throw FileNotFoundException("Invalid audio reference")
        val file = finalFile(app, uri.pathSegments.single())
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) throw FileNotFoundException("Audio unavailable")
        return file
    }

    companion object {
        const val DIRECTORY = "imported-audio"
        fun authority(context: Context): String = context.packageName + ".owned-audio"
        fun root(context: Context): File {
            val parent = context.noBackupFilesDir.canonicalFile
            val raw = File(parent, DIRECTORY)
            if (Files.isSymbolicLink(raw.toPath())) throw FileNotFoundException("Import storage unavailable")
            val result = raw.canonicalFile
            check(result.parentFile == parent)
            if ((!result.exists() && !result.mkdirs()) || !result.isDirectory || Files.isSymbolicLink(result.toPath())) {
                throw FileNotFoundException("Import storage unavailable")
            }
            return result
        }
        fun finalFile(context: Context, token: String): File = child(context, token, ".audio")
        internal fun stagingFile(context: Context, token: String): File = child(context, token, ".part")
        fun uri(context: Context, token: String): Uri {
            validateToken(token)
            return Uri.Builder().scheme("content").authority(authority(context)).appendPath(token).build()
        }
        internal fun validateToken(token: String) {
            if (token.length != 36 || UUID.fromString(token).toString() != token) throw IllegalArgumentException("Invalid audio token")
        }
        private fun child(context: Context, token: String, suffix: String): File {
            validateToken(token)
            val parent = root(context)
            val raw = File(parent, token + suffix)
            if (Files.isSymbolicLink(raw.toPath())) throw FileNotFoundException("Invalid audio reference")
            val result = raw.canonicalFile
            check(result.parentFile == parent)
            return result
        }
    }
}
