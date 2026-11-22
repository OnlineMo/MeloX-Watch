package io.github.onlinemo.meloxwatch.data.fileoperations

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal data class SafFileDocument(val uri: Uri, val objectId: String, val name: String,
    val mime: String?, val bytes: Long?, val flags: Int)
internal data class CheckedFileRead<T>(val result: T, val proof: PhysicalFileProof)

/**
 * 仅已授权 SAF 树中的真实 regular、seekable 描述符进入此写路径。
 * 不由 content URI 猜磁盘路径；能力声明只准入请求，实际文档、FD 和字节结果才是证据。
 */
internal class SafDocumentFileAccess(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun requireTree(treeText: String, write: Boolean) {
        val tree = Uri.parse(treeText)
        if (tree.scheme != "content" || !DocumentsContract.isTreeUri(tree) || tree.authority.isNullOrBlank() ||
            tree.query != null || tree.fragment != null) fail(FileOperationIssue.UNSUPPORTED_PROVIDER)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        val persisted = resolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && (!write || it.isWritePermission)
        }
        if (!persisted && context.checkUriPermission(tree, Process.myPid(), Process.myUid(), flags) != PackageManager.PERMISSION_GRANTED) {
            fail(FileOperationIssue.PERMISSION_REVOKED)
        }
    }

    fun route(treeText: String, objectId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeText), objectId)

    fun requireRoute(treeText: String, uriText: String, objectId: String) {
        val tree = Uri.parse(treeText)
        val uri = Uri.parse(uriText)
        if (uri.scheme != "content" || uri.authority != tree.authority || uri.query != null || uri.fragment != null ||
            !DocumentsContract.isDocumentUri(context, uri) || DocumentsContract.getDocumentId(uri) != objectId) {
            fail(FileOperationIssue.SOURCE_CHANGED)
        }
    }

    fun requireReadableDescendant(treeText: String, objectId: String, cancellation: CancellationSignal) {
        requireTree(treeText, write = false)
        cancellation.throwIfCanceled()
        val tree = Uri.parse(treeText)
        val root = route(treeText, DocumentsContract.getTreeDocumentId(tree))
        val child = route(treeText, objectId)
        if (root != child && !DocumentsContract.isChildDocument(resolver, root, child)) fail(FileOperationIssue.SOURCE_CHANGED)
    }

    fun document(uri: Uri, cancellation: CancellationSignal): SafFileDocument {
        cancellation.throwIfCanceled()
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)
        val cursor = resolver.query(uri, projection, null, null, null, cancellation)
            ?: fail(FileOperationIssue.SOURCE_UNAVAILABLE)
        return cursor.use {
            if (!it.moveToFirst()) fail(FileOperationIssue.FILE_MISSING)
            val id = it.getString(0) ?: fail(FileOperationIssue.SOURCE_CHANGED)
            if (id != DocumentsContract.getDocumentId(uri)) fail(FileOperationIssue.SOURCE_CHANGED)
            val name = it.getString(1) ?: fail(FileOperationIssue.UNSUPPORTED_PROVIDER)
            requireFileDisplayName(name)
            val row = SafFileDocument(uri, id, name, if (it.isNull(2)) null else it.getString(2),
                if (it.isNull(3)) null else it.getLong(3).also { size -> if (size < 0L) fail(FileOperationIssue.SOURCE_CHANGED) },
                if (it.isNull(4)) 0 else it.getInt(4))
            if (it.moveToNext()) fail(FileOperationIssue.SOURCE_CHANGED)
            row
        }
    }

    fun requireParent(treeText: String, parentId: String, cancellation: CancellationSignal, create: Boolean): SafFileDocument {
        requireTree(treeText, write = true)
        val tree = Uri.parse(treeText)
        val parentUri = route(treeText, parentId)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        if (parentUri != root && !DocumentsContract.isChildDocument(resolver, root, parentUri)) fail(FileOperationIssue.UNSUPPORTED_PROVIDER)
        val parent = document(parentUri, cancellation)
        if (parent.mime != DocumentsContract.Document.MIME_TYPE_DIR ||
            create && parent.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) fail(FileOperationIssue.UNSUPPORTED_PROVIDER)
        return parent
    }

    /** 只持一个游标；不物化完整目录，也不设产品条数上限。 */
    private fun <T> children(treeText: String, parentId: String, cancellation: CancellationSignal,
        action: (String, String?) -> T?): T? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(treeText), parentId)
        val cursor = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null, cancellation)
            ?: fail(FileOperationIssue.SOURCE_UNAVAILABLE)
        return cursor.use {
            while (it.moveToNext()) {
                cancellation.throwIfCanceled()
                val id = it.getString(0) ?: fail(FileOperationIssue.SOURCE_CHANGED)
                action(id, if (it.isNull(1)) null else it.getString(1))?.let { result -> return@use result }
            }
            null
        }
    }

    fun requireChild(treeText: String, parentId: String, objectId: String, cancellation: CancellationSignal) {
        if (children(treeText, parentId, cancellation) { id, _ -> true.takeIf { id == objectId } } != true) {
            fail(FileOperationIssue.SOURCE_CHANGED)
        }
    }

    fun absent(treeText: String, parentId: String, objectId: String, cancellation: CancellationSignal): Boolean {
        requireParent(treeText, parentId, cancellation, create = false)
        return children(treeText, parentId, cancellation) { id, _ -> true.takeIf { id == objectId } } != true
    }

    fun requireNameFree(treeText: String, parentId: String, name: String, cancellation: CancellationSignal, exceptId: String? = null) {
        requireFileDisplayName(name)
        if (children(treeText, parentId, cancellation) { id, actual ->
                true.takeIf { id != exceptId && actual != null && equivalentName(actual, name) }
            } == true) fail(FileOperationIssue.TARGET_EXISTS)
    }

    fun requireWritableAccess(resource: WrittenDocument, cancellation: CancellationSignal) {
        val descriptor = resolver.openFileDescriptor(Uri.parse(resource.proof.uri), "rw", cancellation)
            ?: fail(FileOperationIssue.PERMISSION_DENIED)
        descriptor.use {
            val stat = regular(it)
            val mode = Os.fcntlInt(it.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE
            if (mode != OsConstants.O_RDWR) fail(FileOperationIssue.PERMISSION_DENIED)
            if (stat.st_dev != resource.proof.device || stat.st_ino != resource.proof.inode || stat.st_size != resource.proof.bytes ||
                stat.st_ctime != resource.proof.changedTime) fail(FileOperationIssue.SOURCE_CHANGED)
        }
    }

    fun requireFlags(document: SafFileDocument, vararg flags: Int) {
        if (flags.any { document.flags and it == 0 }) fail(FileOperationIssue.UNSUPPORTED_PROVIDER)
    }

    fun proof(treeText: String, parentId: String, uriText: String, cancellation: CancellationSignal): PhysicalFileProof =
        read(treeText, parentId, uriText, cancellation) { input ->
            val buffer = ByteArray(FileOperationLimits.BUFFER_BYTES)
            while (true) {
                cancellation.throwIfCanceled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) fail(FileOperationIssue.SOURCE_UNAVAILABLE)
            }
        }.proof

    /** 回调须消耗完整文件；证明来自同一打开的描述符，不是前缀摘要。 */
    fun <T> read(treeText: String, parentId: String, uriText: String, cancellation: CancellationSignal,
        action: (InputStream) -> T): CheckedFileRead<T> {
        requireTree(treeText, write = false)
        val uri = Uri.parse(uriText)
        val row = document(uri, cancellation)
        requireRoute(treeText, uriText, row.objectId)
        requireChild(treeText, parentId, row.objectId, cancellation)
        val descriptor = resolver.openFileDescriptor(uri, "r", cancellation) ?: fail(FileOperationIssue.SOURCE_UNAVAILABLE)
        val observed = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { raw ->
            val before = regular(descriptor)
            val hash = MessageDigest.getInstance("SHA-256")
            val counted = CountingFileInput(DigestInputStream(raw, hash), cancellation)
            val result = action(counted)
            if (counted.read() != -1) fail(FileOperationIssue.INTEGRITY_FAILED)
            val after = regular(descriptor)
            if (!sameStat(before, after) || counted.count != before.st_size || row.bytes != null && row.bytes != counted.count) {
                fail(FileOperationIssue.SOURCE_CHANGED)
            }
            CheckedFileRead(result, PhysicalFileProof(uriText, row.objectId, row.name, row.mime,
                before.st_dev, before.st_ino, before.st_ctime, counted.count, fileOperationHex(hash.digest())))
        }
        val after = document(uri, cancellation)
        if (after.objectId != row.objectId || after.name != row.name || after.bytes != null && after.bytes != observed.proof.bytes) {
            fail(FileOperationIssue.SOURCE_CHANGED)
        }
        requireChild(treeText, parentId, row.objectId, cancellation)
        return observed
    }

    fun create(sourceId: String, treeText: String, parentId: String, name: String, mime: String?, cancellation: CancellationSignal): WrittenDocument {
        val parent = requireParent(treeText, parentId, cancellation, create = true)
        requireNameFree(treeText, parentId, name, cancellation)
        cancellation.throwIfCanceled()
        val uri = DocumentsContract.createDocument(resolver, parent.uri, mime ?: "application/octet-stream", name)
            ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        val proof = proof(treeText, parentId, uri.toString(), cancellation)
        if (proof.displayName != name || proof.bytes != 0L) fail(FileOperationIssue.RECOVERY_REQUIRED)
        return WrittenDocument(sourceId, treeText, parentId, proof)
    }

    /** 唯一可写 FD 是已经记录的确切空候选，原件不能进入此写方法。 */
    fun <T> writeEmpty(candidate: WrittenDocument, cancellation: CancellationSignal, action: (OutputStream, () -> Unit, (Int) -> Unit) -> T): T {
        if (candidate.proof.bytes != 0L) fail(FileOperationIssue.INTEGRITY_FAILED)
        requireTree(candidate.treeUri, write = true)
        val descriptor = resolver.openFileDescriptor(Uri.parse(candidate.proof.uri), "rw", cancellation)
            ?: fail(FileOperationIssue.SOURCE_UNAVAILABLE)
        return ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            val before = regular(descriptor)
            if (before.st_dev != candidate.proof.device || before.st_ino != candidate.proof.inode ||
                before.st_ctime != candidate.proof.changedTime || before.st_size != 0L) fail(FileOperationIssue.SOURCE_CHANGED)
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
            val result = action(output, { Os.fsync(descriptor.fileDescriptor) }, { next -> requireRoom(descriptor, next.toLong()) })
            cancellation.throwIfCanceled()
            output.flush()
            Os.fsync(descriptor.fileDescriptor)
            val after = regular(descriptor)
            if (before.st_dev != after.st_dev || before.st_ino != after.st_ino) fail(FileOperationIssue.SOURCE_CHANGED)
            result
        }
    }

    fun rename(resource: WrittenDocument, name: String, cancellation: CancellationSignal): WrittenDocument {
        requireParent(resource.treeUri, resource.parentObjectId, cancellation, create = false)
        val current = proof(resource.treeUri, resource.parentObjectId, resource.proof.uri, cancellation)
        requireExact(resource.proof, current)
        val row = document(Uri.parse(current.uri), cancellation)
        requireFlags(row, DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
        requireNameFree(resource.treeUri, resource.parentObjectId, name, cancellation, current.objectId)
        cancellation.throwIfCanceled()
        val uri = DocumentsContract.renameDocument(resolver, row.uri, name) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        val renamed = proof(resource.treeUri, resource.parentObjectId, uri.toString(), cancellation)
        requireSameObjectContents(current, renamed)
        return resource.copy(proof = renamed)
    }

    fun move(resource: WrittenDocument, destination: FileOperationDestination, cancellation: CancellationSignal): WrittenDocument {
        val oldParent = requireParent(resource.treeUri, resource.parentObjectId, cancellation, create = false)
        val newParent = requireParent(destination.treeUri, destination.parentObjectId, cancellation, create = true)
        if (oldParent.uri.authority != newParent.uri.authority) fail(FileOperationIssue.UNSUPPORTED_PROVIDER)
        val current = proof(resource.treeUri, resource.parentObjectId, resource.proof.uri, cancellation)
        requireExact(resource.proof, current)
        val row = document(Uri.parse(current.uri), cancellation)
        requireFlags(row, DocumentsContract.Document.FLAG_SUPPORTS_MOVE)
        requireNameFree(destination.treeUri, destination.parentObjectId, checkNotNull(destination.displayName), cancellation)
        requireNameFree(destination.treeUri, destination.parentObjectId, current.displayName, cancellation)
        cancellation.throwIfCanceled()
        val uri = DocumentsContract.moveDocument(resolver, row.uri, oldParent.uri, newParent.uri)
            ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        val moved = proof(destination.treeUri, destination.parentObjectId, uri.toString(), cancellation)
        requireSameObjectContents(current, moved)
        return WrittenDocument(destination.sourceId, destination.treeUri, destination.parentObjectId, moved)
    }

    fun delete(resource: WrittenDocument, cancellation: CancellationSignal) {
        requireParent(resource.treeUri, resource.parentObjectId, cancellation, create = false)
        val current = proof(resource.treeUri, resource.parentObjectId, resource.proof.uri, cancellation)
        requireExact(resource.proof, current)
        requireFlags(document(Uri.parse(current.uri), cancellation), DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
        cancellation.throwIfCanceled()
        if (!DocumentsContract.deleteDocument(resolver, Uri.parse(current.uri))) fail(FileOperationIssue.RECOVERY_REQUIRED)
        // API 成功回应不证明实际删除，也不能授权删除另一同名对象。
        if (!absent(resource.treeUri, resource.parentObjectId, current.objectId, cancellation)) fail(FileOperationIssue.RECOVERY_REQUIRED)
    }

    /** 仅在原授权且已证明的父对象中查找改名后的已知 inode。 */
    fun findRetained(resource: WrittenDocument, cancellation: CancellationSignal): WrittenDocument? {
        requireParent(resource.treeUri, resource.parentObjectId, cancellation, create = false)
        var foundId: String? = null
        children<Unit>(resource.treeUri, resource.parentObjectId, cancellation) { id, _ ->
            val uri = route(resource.treeUri, id)
            val row = document(uri, cancellation)
            if (row.mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                val descriptor = resolver.openFileDescriptor(uri, "r", cancellation)
                    ?: fail(FileOperationIssue.SOURCE_UNAVAILABLE)
                val same = descriptor.use {
                    val stat = regular(it)
                    stat.st_dev == resource.proof.device && stat.st_ino == resource.proof.inode
                }
                if (same) {
                    if (foundId != null) fail(FileOperationIssue.CONFLICT)
                    foundId = id
                }
            }
            null
        }
        val id = foundId ?: return null
        val proof = proof(resource.treeUri, resource.parentObjectId, route(resource.treeUri, id).toString(), cancellation)
        requireSameObjectContents(resource.proof, proof)
        return resource.copy(proof = proof)
    }

    private fun regular(descriptor: ParcelFileDescriptor): StructStat {
        val stat = Os.fstat(descriptor.fileDescriptor)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_ino <= 0L || stat.st_size < 0L || stat.st_ctime < 0L) {
            fail(FileOperationIssue.UNSUPPORTED_PROVIDER)
        }
        Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
        return stat
    }

    private fun requireRoom(descriptor: ParcelFileDescriptor, next: Long) {
        val stats = Os.fstatvfs(descriptor.fileDescriptor)
        if (stats.f_frsize <= 0L || stats.f_bavail < 0L) fail(FileOperationIssue.UNSUPPORTED_PROVIDER)
        val available = if (stats.f_bavail > Long.MAX_VALUE / stats.f_frsize) Long.MAX_VALUE else stats.f_bavail * stats.f_frsize
        if (next < 0L || available < FileOperationLimits.FREE_RESERVE_BYTES ||
            next > available - FileOperationLimits.FREE_RESERVE_BYTES) fail(FileOperationIssue.STORAGE_FULL)
    }

    companion object {
        fun requireExact(expected: PhysicalFileProof, current: PhysicalFileProof) {
            if (expected.uri != current.uri || expected.objectId != current.objectId || expected.displayName != current.displayName ||
                expected.device != current.device || expected.inode != current.inode || expected.changedTime != current.changedTime ||
                expected.bytes != current.bytes || expected.sha256 != current.sha256) fail(FileOperationIssue.SOURCE_CHANGED)
        }

        fun requireSameObjectContents(expected: PhysicalFileProof, current: PhysicalFileProof) {
            // 获准改名/移动可改变 URI、DocID 或 ctime；完整字节和原生身份仍须相同。
            if (expected.device != current.device || expected.inode != current.inode || expected.bytes != current.bytes ||
                expected.sha256 != current.sha256) fail(FileOperationIssue.INTEGRITY_FAILED)
        }

        private fun sameStat(left: StructStat, right: StructStat) = left.st_dev == right.st_dev && left.st_ino == right.st_ino &&
            left.st_size == right.st_size && left.st_ctime == right.st_ctime && left.st_mtime == right.st_mtime
        private fun fail(issue: FileOperationIssue): Nothing = throw FileOperationException(issue)
        private fun equivalentName(left: String, right: String) = Normalizer.normalize(left, Normalizer.Form.NFC).lowercase(Locale.ROOT) ==
            Normalizer.normalize(right, Normalizer.Form.NFC).lowercase(Locale.ROOT)

        fun <T> providerCall(action: () -> T): T = try { action() }
        catch (failure: FileOperationException) { throw failure }
        catch (failure: SecurityException) { throw FileOperationException(FileOperationIssue.PERMISSION_DENIED, failure) }
        catch (failure: FileNotFoundException) { throw FileOperationException(FileOperationIssue.SOURCE_UNAVAILABLE, failure) }
        catch (failure: ErrnoException) {
            throw FileOperationException(when (failure.errno) {
                OsConstants.ENOSPC, OsConstants.EDQUOT -> FileOperationIssue.STORAGE_FULL
                OsConstants.EACCES, OsConstants.EPERM -> FileOperationIssue.PERMISSION_DENIED
                OsConstants.ENOENT -> FileOperationIssue.FILE_MISSING
                OsConstants.ESPIPE, OsConstants.EINVAL -> FileOperationIssue.UNSUPPORTED_PROVIDER
                else -> FileOperationIssue.SOURCE_UNAVAILABLE
            }, failure)
        }
        catch (failure: UnsupportedOperationException) { throw FileOperationException(FileOperationIssue.UNSUPPORTED_PROVIDER, failure) }
        catch (failure: java.io.IOException) { throw FileOperationException(FileOperationIssue.SOURCE_UNAVAILABLE, failure) }
    }
}

private class CountingFileInput(private val input: InputStream, private val cancellation: CancellationSignal) : InputStream() {
    var count = 0L
        private set
    override fun read(): Int {
        cancellation.throwIfCanceled()
        return input.read().also { if (it >= 0) count = Math.addExact(count, 1L) }
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        cancellation.throwIfCanceled()
        return input.read(bytes, offset, length).also { if (it > 0) count = Math.addExact(count, it.toLong()) }
    }
}
