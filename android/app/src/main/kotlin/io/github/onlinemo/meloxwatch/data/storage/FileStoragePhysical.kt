package io.github.onlinemo.meloxwatch.data.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import io.github.onlinemo.meloxwatch.data.fileoperations.WrittenDocument
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** 这里只观察容量；不读完整音频摘要、不取写 FD、不枚举外部目录或删除文件。 */
internal object FileStoragePhysical {
    fun available(context: Context): Long? = try { StatFs(context.noBackupFilesDir.absolutePath).availableBytes.takeIf { it >= 0L } }
        catch (_: Exception) { null }

    fun reference(context: Context, descriptor: LocalReadDescriptor, signal: CancellationSignal): Long? {
        val uri = descriptor.location.uri.toUri()
        if (uri.scheme != "content" || uri.query != null || uri.fragment != null ||
            descriptor.source.spec.authority != null && descriptor.source.spec.authority != uri.authority) {
            throw StorageException(StorageIssue.SOURCE_CHANGED)
        }
        if (descriptor.source.spec.kind == LocalSourceKind.TREE && (!DocumentsContract.isTreeUri(uri) ||
            DocumentsContract.getTreeDocumentId(uri) != descriptor.source.spec.rootObjectId ||
            DocumentsContract.getDocumentId(uri) != descriptor.objectId)) throw StorageException(StorageIssue.SOURCE_CHANGED)
        return openedBytes(context, uri, signal)
    }

    fun recovery(context: Context, document: WrittenDocument, signal: CancellationSignal): Long? {
        val uri = document.proof.uri.toUri()
        if (uri.scheme != "content" || uri.authority != document.treeUri.toUri().authority ||
            DocumentsContract.getDocumentId(uri) != document.proof.objectId) throw StorageException(StorageIssue.SOURCE_CHANGED)
        return openedBytes(context, uri, signal, document)
    }

    private fun openedBytes(context: Context, uri: Uri, signal: CancellationSignal, document: WrittenDocument? = null): Long? {
        signal.throwIfCanceled()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r", signal) ?: throw StorageException(StorageIssue.UNAVAILABLE)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            val before = Os.fstat(descriptor.fileDescriptor)
            if (document != null && (before.st_dev != document.proof.device || before.st_ino != document.proof.inode)) {
                throw StorageException(StorageIssue.SOURCE_CHANGED)
            }
            // 管道及虚拟 FD 的完整容量未知；不把前缀当完整文件，也不宣称内容验证。
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_size < 0L) return@use null
            signal.throwIfCanceled()
            val after = Os.fstat(descriptor.fileDescriptor)
            if (before.st_dev != after.st_dev || before.st_ino != after.st_ino || before.st_size != after.st_size) {
                throw StorageException(StorageIssue.SOURCE_CHANGED)
            }
            before.st_size
        }
    }

    /** 仅统计私有直接子项；链接、未知名或不可读项保留未知状态。 */
    fun privateAudio(context: Context, directoryName: String, suffix: String, signal: CancellationSignal): StorageCategory {
        val parent = context.noBackupFilesDir.canonicalFile
        val directory = File(parent, directoryName)
        try {
            val root = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!root.isDirectory || root.isSymbolicLink || directory.canonicalFile.parentFile != parent) throw StorageException(StorageIssue.UNAVAILABLE)
        } catch (_: NoSuchFileException) {
            val parentFacts = Files.readAttributes(parent.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!parentFacts.isDirectory || parentFacts.isSymbolicLink) throw StorageException(StorageIssue.UNAVAILABLE)
            return StorageCategory(0L, 0L)
        }
        var bytes = 0L
        var files = 0L
        var unknown = 0L
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                signal.throwIfCanceled()
                val name = entry.fileName.toString()
                if (!name.endsWith(suffix)) continue
                try {
                    val token = name.removeSuffix(suffix)
                    if (token.length != 36 || UUID.fromString(token).toString() != token) throw StorageException(StorageIssue.INCOMPLETE)
                    val facts = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (!facts.isRegularFile || facts.isSymbolicLink) throw StorageException(StorageIssue.INCOMPLETE)
                    bytes = Math.addExact(bytes, facts.size())
                    files = Math.addExact(files, 1L)
                } catch (_: Exception) { unknown = Math.addExact(unknown, 1L) }
            }
        }
        return StorageCategory(bytes, files, unknown, StorageIssue.INCOMPLETE.takeIf { unknown > 0L })
    }
}
