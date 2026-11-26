package io.github.onlinemo.meloxwatch.data.lyrics.active

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
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.UUID

/** 私有歌词副本只向本应用开放；URI 只接受随机对象 ID，不接受路径或写操作。 */
class OwnedLyricsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireInternalCaller()
        if (mode != "r") throw SecurityException("Lyrics copies are read-only")
        val file = completedFile(uri)
        val descriptor = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) throw FileNotFoundException("Lyrics unavailable")
            return ParcelFileDescriptor.dup(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    override fun getType(uri: Uri): String {
        requireInternalCaller()
        completedFile(uri)
        return "text/plain"
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
                OpenableColumns.DISPLAY_NAME -> "lyrics.lrc"
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(values) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only provider")

    private fun requireInternalCaller() {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Internal lyrics access only")
    }

    private fun completedFile(uri: Uri): File {
        val app = context ?: throw FileNotFoundException("Provider unavailable")
        val token = token(app, uri) ?: throw FileNotFoundException("Invalid lyrics reference")
        return finalFile(app, token).also {
            if (!it.isFile || Files.isSymbolicLink(it.toPath())) throw FileNotFoundException("Lyrics unavailable")
        }
    }

    companion object {
        private const val DIRECTORY = "lyrics-documents"
        fun authority(context: Context): String = context.packageName + ".owned-lyrics"

        fun uri(context: Context, token: String): Uri {
            validateToken(token)
            return Uri.Builder().scheme("content").authority(authority(context)).appendPath(token).build()
        }

        fun token(context: Context, uri: Uri): String? {
            if (uri.scheme != "content" || uri.authority != authority(context) || !uri.isHierarchical ||
                uri.pathSegments.size != 1 || uri.query != null || uri.fragment != null) return null
            val candidate = uri.pathSegments.single()
            return try {
                validateToken(candidate)
                candidate
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        internal fun root(context: Context): File {
            val parent = context.noBackupFilesDir.canonicalFile
            val raw = File(parent, DIRECTORY)
            if (Files.isSymbolicLink(raw.toPath())) throw FileNotFoundException("Lyrics storage unavailable")
            val result = raw.canonicalFile
            if (result.parentFile != parent || !result.exists() && !result.mkdirs() ||
                !result.isDirectory || Files.isSymbolicLink(result.toPath())) {
                throw FileNotFoundException("Lyrics storage unavailable")
            }
            return result
        }

        internal fun storageDirectory(context: Context): File = File(context.noBackupFilesDir.canonicalFile, DIRECTORY)

        internal fun finalFile(context: Context, token: String): File = child(context, token, ".lrc")
        internal fun stagingFile(context: Context, token: String): File = child(context, token, ".part")

        private fun child(context: Context, token: String, suffix: String): File {
            validateToken(token)
            val parent = root(context)
            val raw = File(parent, token + suffix)
            if (Files.isSymbolicLink(raw.toPath())) throw FileNotFoundException("Invalid lyrics reference")
            return raw.canonicalFile.also { check(it.parentFile == parent) }
        }

        private fun validateToken(value: String) {
            require(value.length == 36 && UUID.fromString(value).toString() == value)
        }
    }
}
