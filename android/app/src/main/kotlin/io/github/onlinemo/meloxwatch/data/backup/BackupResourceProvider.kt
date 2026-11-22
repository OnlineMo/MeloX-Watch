package io.github.onlinemo.meloxwatch.data.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.FileNotFoundException
import java.util.UUID

/** Gives existing native importers a read-only URI for this operation's sealed local resource. */
class BackupResourceProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireInternalCaller()
        if (mode != "r") throw SecurityException("Backup resources are read-only")
        val resource = BackupResourceSession.resolve(requireContext(), uri)
        val descriptor = Os.open(resource.file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val facts = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(facts.st_mode) || facts.st_size != resource.descriptor.bytes) throw FileNotFoundException("Resource unavailable")
            return ParcelFileDescriptor.dup(descriptor)
        } finally { Os.close(descriptor) }
    }

    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor {
        requireInternalCaller()
        signal?.throwIfCanceled()
        val resource = BackupResourceSession.resolve(requireContext(), uri)
        val descriptor = openFile(uri, mode)
        try { signal?.throwIfCanceled(); return AssetFileDescriptor(descriptor, 0L, resource.descriptor.bytes) }
        catch (failure: Throwable) { descriptor.close(); throw failure }
    }

    override fun getType(uri: Uri): String {
        requireInternalCaller()
        return when (BackupResourceSession.resolve(requireContext(), uri).descriptor.kind) {
            BackupResourceKind.LYRICS -> "text/plain"
            BackupResourceKind.FONT -> "font/sfnt"
            BackupResourceKind.IMAGE -> "application/octet-stream"
            BackupResourceKind.THEME_PACKAGE -> "application/zip"
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        requireInternalCaller()
        val resource = BackupResourceSession.resolve(requireContext(), uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply { addRow(columns.map { column -> when (column) {
            OpenableColumns.DISPLAY_NAME -> "resource.bin"
            OpenableColumns.SIZE -> resource.descriptor.bytes
            else -> null
        } }) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")
    private fun requireContext(): Context = context ?: throw FileNotFoundException("Provider unavailable")
    private fun requireInternalCaller() { if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Internal resource access only") }
}

/** A single opaque process lease, retired only after the real shared physical IO has exited. */
internal class BackupResourceSession private constructor(val directory: BackupPrivateDirectory, private val token: String) : AutoCloseable {
    fun uri(context: Context, resourceId: String): String {
        if (!resourceId.matches(DIGEST)) throw BackupException(BackupIssue.INVALID_RESOURCE)
        synchronized(lock) { if (current !== this) throw BackupException(BackupIssue.BUSY) }
        return Uri.Builder().scheme("content").authority(authority(context)).appendPath(token).appendPath(directory.root.name).appendPath(resourceId).build().toString()
    }
    override fun close() { synchronized(lock) { if (current === this) current = null } }

    companion object {
        private val lock = Any()
        private var current: BackupResourceSession? = null
        private val DIGEST = Regex("[0-9a-f]{64}")
        fun open(directory: BackupPrivateDirectory): BackupResourceSession = synchronized(lock) {
            if (current != null) throw BackupException(BackupIssue.BUSY)
            BackupResourceSession(directory, UUID.randomUUID().toString()).also { current = it }
        }
        private fun authority(context: Context): String = context.packageName + ".backup-resources"
        fun resolve(context: Context, uri: Uri): ResolvedBackupResource {
            if (uri.scheme != "content" || uri.authority != authority(context) || !uri.isHierarchical || uri.query != null || uri.fragment != null ||
                uri.pathSegments.size != 3 || !uri.pathSegments[2].matches(DIGEST)) throw FileNotFoundException("Invalid resource reference")
            val session = synchronized(lock) { current?.takeIf { it.token == uri.pathSegments[0] && it.directory.root.name == uri.pathSegments[1] } }
                ?: throw FileNotFoundException("Resource operation ended")
            val database = session.directory.child("rows.db")
            val identity = BackupFileIdentity.capture(database)
            val connection = BundledSQLiteDriver().open(database.absolutePath)
            val descriptor = try {
                connection.execute("PRAGMA query_only=ON")
                connection.execute("PRAGMA cache_size=-256")
                connection.prepare("SELECT r.kind,r.bytes,r.sha256,r.licenseRequired FROM resources r JOIN stage_meta m ON m.id=1 WHERE r.resourceId=? AND r.sealed=1 AND m.phase='VERIFIED'").use {
                    it.bindText(1, uri.pathSegments[2])
                    if (!it.step()) throw FileNotFoundException("Resource unavailable")
                    val ordinal = it.getLong(0).takeIf { value -> value in 0L..BackupResourceKind.entries.lastIndex.toLong() }
                        ?: throw FileNotFoundException("Resource unavailable")
                    val kind = BackupResourceKind.entries[ordinal.toInt()]
                    BackupResource(uri.pathSegments[2], kind, it.getLong(1), it.getText(2), it.getLong(3) == 1L)
                }
            } finally { connection.close() }
            identity.requireSame(database)
            val file = session.directory.child(descriptor.id + ".bin")
            BackupFileIdentity.capture(file)
            synchronized(lock) { if (current !== session) throw FileNotFoundException("Resource operation ended") }
            return ResolvedBackupResource(file, descriptor)
        }
    }
}

internal class ResolvedBackupResource(val file: java.io.File, val descriptor: BackupResource) {
    override fun toString(): String = "ResolvedBackupResource"
}
