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
import io.github.onlinemo.meloxwatch.data.imports.AudioCopyQuota
import io.github.onlinemo.meloxwatch.data.imports.OwnedAudioProvider
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import java.io.File
import java.util.UUID

/** 这里只观察容量；不读完整音频摘要、不取写 FD、不枚举外部目录或删除文件。 */
internal object FileStoragePhysical {
    fun available(context: Context): Long? = try { StatFs(context.noBackupFilesDir.absolutePath).availableBytes.takeIf { it >= 0L } }
        catch (_: Exception) { null }

    /** Incoming audio and theme bytes share one directory and are observed exactly once. */
    fun privateAudioSpecifications(context: Context): List<StorageDirectorySpec> {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val parent = context.noBackupFilesDir.canonicalFile
        fun valid(name: String, suffix: String): Boolean {
            if (!name.endsWith(suffix)) return false
            val token = name.removeSuffix(suffix)
            return token.length == 36 && try { UUID.fromString(token).toString() == token } catch (_: IllegalArgumentException) { false }
        }
        return listOf(
            StorageDirectorySpec(File(parent, OwnedAudioProvider.DIRECTORY), StorageArea.AUDIO, 1, null,
                classify = { parts, directory ->
                    val name = parts.singleOrNull()
                    when {
                        directory || name == null -> null
                        valid(name, ".audio") -> StorageArea.AUDIO
                        valid(name, ".part") -> StorageArea.RECOVERY
                        else -> null
                    }
                }, mutableFile = { it.last().endsWith(".part") }, requireNonEmpty = { it.last().endsWith(".audio") }),
            StorageDirectorySpec(File(parent, AudioCopyQuota.TRANSFER_DIRECTORY), StorageArea.RECOVERY, 1,
                TransferLimits.PAGE_SIZE.toLong(), classify = { parts, directory ->
                    if (!directory && parts.size == 1 && valid(parts.single(), ".partial")) StorageArea.RECOVERY else null
                }, mutableFile = { true }),
        )
    }

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

}
