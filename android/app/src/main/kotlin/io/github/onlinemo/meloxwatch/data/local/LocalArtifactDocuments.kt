package io.github.onlinemo.meloxwatch.data.local

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.StatFs
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import io.github.onlinemo.meloxwatch.data.imports.ImportLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 只对已核对的本地 provider 执行文档协议。未知 provider 在 query/open 前拒绝；
 * 目录里的随机 .part 经完整读回后才重命名，清理必须同时匹配已记录的实体身份。
 */
internal class LocalArtifactDocuments internal constructor(
    context: Context,
    private val io: LocalProviderIo,
    private val requireLocalProvider: (Uri) -> Unit,
) {
    constructor(context: Context, io: LocalProviderIo) : this(context, io, { uri ->
        requirePlatformLocalProvider(context.applicationContext, uri)
    })

    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val occupied = AtomicBoolean(false)

    internal suspend fun copyBackupInput(input: LocalArtifactDocumentInput, directory: File): File = exclusive {
        val copied = AtomicReference<CopiedDocument?>(null)
        var delivered = false
        var failure: Throwable? = null
        try {
            val result = physical { signal ->
                requireDocumentInput(input, signal)
                copyDocument(input.uri, ArtifactPrivateDirectory.open(requireBackupGeneration(directory)), signal).also { copied.set(it) }
            }
            delivered = true
            result.file
        } catch (error: Throwable) {
            failure = error
            throw error
        }
        finally {
            // 私有副本的放弃清理由当前 owner 完成；不在 I/O 放槽后的 resume 回调另开写入。
            if (!delivered && copied.get() != null) withContext(NonCancellable) {
                try {
                    physical {
                        copied.get()?.delete()
                        Unit
                    }
                }
                catch (closing: Throwable) {
                    val outgoing = failure
                    if (outgoing == null) throw closing
                    if (outgoing !== closing) outgoing.addSuppressed(closing)
                }
            }
        }
    }

    suspend fun writeArtifact(
        destination: LocalArtifactDocumentDestination,
        source: LocalArtifactSource,
    ): LocalArtifactReceipt = exclusive {
        physical { signal ->
            requireDestination(destination)
            val journal = documentJournal()
            recoverPendingActual(journal, signal, finishFinal = true)
            val sourceFile = requireSourceScope(source)
            val sourceIdentity = ArtifactFileIdentity.capture(sourceFile)
            verifySource(source, sourceFile, signal)
            sourceIdentity.requireSame(sourceFile)
            val receipt = LocalArtifactReceipt(source.artifactId, source.sha256, source.expectedBytes)
            val parent = DocumentsContract.buildDocumentUriUsingTree(destination.uri,
                DocumentsContract.getTreeDocumentId(destination.uri))
            val folder = readDocument(parent, signal) ?: throw LocalArtifactException(LocalArtifactIssue.PERMISSION_DENIED)
            if (folder.mimeType != DocumentsContract.Document.MIME_TYPE_DIR ||
                (folder.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) {
                throw LocalArtifactException(LocalArtifactIssue.PERMISSION_DENIED)
            }
            val nonce = UUID.randomUUID().toString()
            val planned = PendingDocument(destination.uri,
                "${source.kind.prefix}-${receipt.artifactId}-$nonce.part", source.finalName, receipt, source.kind)
            if (findChild(destination.uri, planned.finalName, signal) != null) throw LocalArtifactException(LocalArtifactIssue.CONFLICT)
            journal.save(planned)
            var pending = planned
            try {
                signal.throwIfCanceled()
                val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", planned.partName)
                    ?: throw LocalArtifactException(LocalArtifactIssue.IO)
                requireUri(created)
                requireLocalProvider(created)
                val document = readDocument(created, signal) ?: throw LocalArtifactException(LocalArtifactIssue.IO)
                requireReadableFile(document)
                if (document.displayName != planned.partName || document.sizeBytes != 0L ||
                    (document.flags and DOCUMENT_WRITE_FLAGS) != DOCUMENT_WRITE_FLAGS ||
                    !DocumentsContract.isChildDocument(resolver, parent, created)) {
                    throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
                }
                val descriptor = openDescriptor(created, "rw", signal)
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    val identity = DocumentFileIdentity.capture(descriptor)
                    if (Os.fstat(descriptor.fileDescriptor).st_size != 0L) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
                    pending = planned.copy(partUri = created, identity = identity, phase = DocumentPhase.CREATED)
                    journal.save(pending)
                    FileInputStream(sourceFile).use { input ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var bytes = 0L
                        val digest = MessageDigest.getInstance("SHA-256")
                        while (true) {
                            signal.throwIfCanceled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            bytes = boundedAdd(bytes, count.toLong(), receipt.bytes)
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                        if (bytes != receipt.bytes || digest.hex() != receipt.sha256) throw LocalArtifactException(LocalArtifactIssue.INTEGRITY_MISMATCH)
                    }
                    sourceIdentity.requireSame(sourceFile)
                    output.flush()
                    Os.fsync(descriptor.fileDescriptor)
                    identity.requireSame(descriptor)
                    if (Os.fstat(descriptor.fileDescriptor).st_size != receipt.bytes) throw LocalArtifactException(LocalArtifactIssue.INTEGRITY_MISMATCH)
                }
                // 来源格式由真实生产者在写前校验；严格等字节读回也包含备份的最后完整标志。
                verifyDocumentBytes(destination.uri, created, pending, signal, finalName = false)
                pending = pending.copy(phase = DocumentPhase.RENAMING)
                journal.save(pending)
                signal.throwIfCanceled()
                val renamed = DocumentsContract.renameDocument(resolver, created, planned.finalName)
                    ?: throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
                requireUri(renamed)
                requireLocalProvider(renamed)
                verifyDocumentBytes(destination.uri, renamed, pending, signal, finalName = true)
                journal.clear()
                receipt
            } catch (error: Throwable) {
                try { recoverPendingActual(journal, CancellationSignal(), finishFinal = false) }
                catch (cleanup: Throwable) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                }
                throw error
            }
        }
    }

    /** 外部暂存失败属于导出恢复域，不阻止本地库/播放的启动 Ready。 */
    suspend fun recoverPendingOutput() = exclusive {
        physical { signal -> recoverPendingActual(documentJournal(), signal, finishFinal = true) }
    }

    private fun requireDocumentInput(input: LocalArtifactDocumentInput, signal: CancellationSignal) {
        requireGrant(input.uri, input.returnedFlags, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        requireLocalProvider(input.uri)
        if (!DocumentsContract.isDocumentUri(context, input.uri)) throw LocalArtifactException(LocalArtifactIssue.INVALID_INPUT)
        val document = readDocument(input.uri, signal) ?: throw LocalArtifactException(LocalArtifactIssue.IO)
        requireReadableFile(document)
    }

    private fun requireDestination(destination: LocalArtifactDocumentDestination) {
        requireGrant(destination.uri, destination.returnedFlags, DIRECTORY_GRANTS)
        requireLocalProvider(destination.uri)
        if (!DocumentsContract.isTreeUri(destination.uri) || destination.uri.pathSegments.size != 2) {
            throw LocalArtifactException(LocalArtifactIssue.INVALID_INPUT)
        }
        requireIdentifier(DocumentsContract.getTreeDocumentId(destination.uri))
    }

    private fun requireGrant(uri: Uri, returnedFlags: Int, required: Int) {
        requireUri(uri)
        if ((returnedFlags and required) != required || context.checkUriPermission(uri,
                Process.myPid(), Process.myUid(), required) != PackageManager.PERMISSION_GRANTED) {
            throw LocalArtifactException(LocalArtifactIssue.PERMISSION_DENIED)
        }
    }

    private fun requireReadableFile(document: DocumentMetadata) {
        if (document.mimeType == DocumentsContract.Document.MIME_TYPE_DIR ||
            (document.flags and (DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT or DocumentsContract.Document.FLAG_PARTIAL)) != 0) {
            throw LocalArtifactException(LocalArtifactIssue.INVALID_INPUT)
        }
        if (document.sizeBytes != null && document.sizeBytes > MAX_ARCHIVE_BYTES) throw LocalArtifactException(LocalArtifactIssue.TOO_LARGE)
    }

    private fun openDescriptor(uri: Uri, mode: String, signal: CancellationSignal): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri, mode, signal) ?: throw LocalArtifactException(LocalArtifactIssue.IO)

    private fun copyDocument(
        uri: Uri,
        directory: ArtifactPrivateDirectory,
        signal: CancellationSignal,
    ): CopiedDocument {
        val metadata = readDocument(uri, signal) ?: throw LocalArtifactException(LocalArtifactIssue.IO)
        requireReadableFile(metadata)
        val created = ArtifactCreatedFile.create(directory.child("input.zip"))
        try {
            openDescriptor(uri, "r", signal).let { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val identity = DocumentFileIdentity.capture(descriptor)
                    val initialSize = Os.fstat(descriptor.fileDescriptor).st_size
                    if (initialSize < 0L || initialSize > MAX_ARCHIVE_BYTES) {
                        throw LocalArtifactException(if (initialSize > MAX_ARCHIVE_BYTES) LocalArtifactIssue.TOO_LARGE else LocalArtifactIssue.INTEGRITY_MISMATCH)
                    }
                    requirePrivateInputSpace(directory.root, initialSize)
                    val output = Channels.newOutputStream(created.channel)
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var bytes = 0L
                    while (true) {
                        signal.throwIfCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (count.toLong() > initialSize - bytes) throw LocalArtifactException(LocalArtifactIssue.INTEGRITY_MISMATCH)
                        requirePrivateInputSpace(directory.root, count.toLong())
                        bytes += count.toLong()
                        output.write(buffer, 0, count)
                    }
                    identity.requireSame(descriptor)
                    if (bytes != initialSize || Os.fstat(descriptor.fileDescriptor).st_size != initialSize ||
                        metadata.sizeBytes?.let { it != bytes } == true) throw LocalArtifactException(LocalArtifactIssue.INTEGRITY_MISMATCH)
                    created.channel.force(true)
                    created.requireSame()
                }
            }
            created.close()
            syncArtifactDirectory(artifactParent(created.file))
            return CopiedDocument(created)
        } catch (error: Throwable) {
            closeWithFailure(error) { created.close() }
            closeWithFailure(error) { created.deleteCreated() }
            throw error
        }
    }

    private fun requireBackupGeneration(directory: File): File {
        val root = ArtifactPrivateDirectory.open(File(context.noBackupFilesDir.canonicalFile, "logical-backups-v1"))
        val canonical = directory.canonicalFile
        val id = try { UUID.fromString(directory.name).toString() } catch (_: IllegalArgumentException) { null }
        if (id == null || directory.name != id || canonical.parentFile != root.root ||
            directory.absoluteFile != canonical || java.nio.file.Files.isSymbolicLink(directory.toPath())) {
            throw LocalArtifactException(LocalArtifactIssue.PATH_ESCAPE)
        }
        return canonical
    }

    private fun requireSourceScope(source: LocalArtifactSource): File {
        val sourceFile = source.file.canonicalFile
        if (java.nio.file.Files.isSymbolicLink(source.file.toPath())) throw LocalArtifactException(LocalArtifactIssue.PATH_ESCAPE)
        when (source.kind) {
            LocalArtifactKind.BACKUP -> {
                val generation = requireBackupGeneration(artifactParent(source.file))
                if (source.file.name != "export.zip" || sourceFile.parentFile != generation) throw LocalArtifactException(LocalArtifactIssue.PATH_ESCAPE)
            }
            LocalArtifactKind.DIAGNOSTIC -> {
                val directory = ArtifactPrivateDirectory.open(File(context.cacheDir.canonicalFile, "diagnostic-exports"))
                val id = source.file.name.removeSuffix(".json")
                val canonicalId = try { UUID.fromString(id).toString() } catch (_: IllegalArgumentException) { null }
                if (sourceFile.parentFile != directory.root ||
                    (source.file.name != "active.json" && (canonicalId == null || source.file.name != "$canonicalId.json"))) {
                    throw LocalArtifactException(LocalArtifactIssue.PATH_ESCAPE)
                }
            }
        }
        return sourceFile
    }

    private fun verifySource(source: LocalArtifactSource, file: File, signal: CancellationSignal) {
        if (file.length() != source.expectedBytes || artifactFileSha256(file, source.expectedBytes, signal::throwIfCanceled) != source.sha256) {
            throw LocalArtifactException(LocalArtifactIssue.INTEGRITY_MISMATCH)
        }
        source.verifyBackupFormat?.invoke(file, signal::throwIfCanceled)
    }

    private fun verifyDocumentBytes(tree: Uri, uri: Uri, pending: PendingDocument, signal: CancellationSignal, finalName: Boolean) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val metadata = readDocument(uri, signal) ?: throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
        requireReadableFile(metadata)
        if (metadata.displayName != (if (finalName) pending.finalName else pending.partName) || metadata.sizeBytes != pending.receipt.bytes ||
            !DocumentsContract.isChildDocument(resolver, parent, uri)) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
        openDescriptor(uri, "r", signal).let { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val identity = checkNotNull(pending.identity)
                identity.requireSame(descriptor)
                if (Os.fstat(descriptor.fileDescriptor).st_size != pending.receipt.bytes) throw LocalArtifactException(LocalArtifactIssue.INTEGRITY_MISMATCH)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var bytes = 0L
                while (true) {
                    signal.throwIfCanceled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    bytes = boundedAdd(bytes, count.toLong(), pending.receipt.bytes)
                    digest.update(buffer, 0, count)
                }
                identity.requireSame(descriptor)
                // RENAMING 只在完整标志/条目/摘要读回通过后落盘；同摘要即同一完整包，
                // 不再为重命名确认另建可能在进程中断后累积的全包暂存目录。
                if (bytes != pending.receipt.bytes || digest.hex() != pending.receipt.sha256 ||
                    Os.fstat(descriptor.fileDescriptor).st_size != pending.receipt.bytes) throw LocalArtifactException(LocalArtifactIssue.INTEGRITY_MISMATCH)
            }
        }
    }

    private fun recoverPendingActual(journal: DocumentOutputJournal, signal: CancellationSignal, finishFinal: Boolean) {
        try {
            val pending = journal.read() ?: return
            requireGrant(pending.tree, DIRECTORY_GRANTS, DIRECTORY_GRANTS)
            requireLocalProvider(pending.tree)
            val part = findChild(pending.tree, pending.partName, signal)
            if (pending.phase == DocumentPhase.PLANNED) {
                if (part != null) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
                journal.clear()
                return
            }
            if (part != null) {
                if (pending.partUri == null || part.id != DocumentsContract.getDocumentId(pending.partUri)) {
                    throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
                }
                val uri = DocumentsContract.buildDocumentUriUsingTree(pending.tree, part.id)
                openDescriptor(uri, "r", signal).let { descriptor ->
                    descriptor.use {
                        checkNotNull(pending.identity).requireSame(descriptor)
                        signal.throwIfCanceled()
                        if (!DocumentsContract.deleteDocument(resolver, uri)) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
                    }
                }
                journal.clear()
                return
            }
            if (pending.phase == DocumentPhase.RENAMING) {
                val final = findChild(pending.tree, pending.finalName, signal)
                if (final != null) {
                    if (!finishFinal) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
                    verifyDocumentBytes(pending.tree,
                        DocumentsContract.buildDocumentUriUsingTree(pending.tree, final.id), pending, signal, finalName = true)
                }
            }
            journal.clear()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cancelled: OperationCanceledException) { throw CancellationException("Backup document operation cancelled", cancelled) }
        catch (failure: Throwable) {
            if (failure is LocalArtifactException && failure.issue == LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED) throw failure
            throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED, failure)
        }
    }

    private fun readDocument(uri: Uri, signal: CancellationSignal): DocumentMetadata? {
        signal.throwIfCanceled()
        val cursor = try { resolver.query(uri, DOCUMENT_COLUMNS, null, null, null, signal) }
        catch (_: FileNotFoundException) { return null }
        cursor?.use {
            if (!it.moveToFirst()) return null
            val result = documentMetadata(it)
            if (it.moveToNext()) throw LocalArtifactException(LocalArtifactIssue.INVALID_INPUT)
            return result
        }
        throw LocalArtifactException(LocalArtifactIssue.IO)
    }

    private fun findChild(tree: Uri, name: String, signal: CancellationSignal): DocumentMetadata? {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(children, DOCUMENT_COLUMNS, null, null, null, signal)?.use { cursor ->
            var found: DocumentMetadata? = null
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                signal.throwIfCanceled()
                if (cursor.getString(nameIndex) != name) continue
                if (found != null) throw LocalArtifactException(LocalArtifactIssue.CONFLICT)
                found = documentMetadata(cursor)
            }
            return found
        }
        throw LocalArtifactException(LocalArtifactIssue.IO)
    }

    private fun documentMetadata(cursor: Cursor): DocumentMetadata {
        fun text(column: String): String {
            val index = cursor.getColumnIndexOrThrow(column)
            if (cursor.isNull(index)) throw LocalArtifactException(LocalArtifactIssue.INVALID_INPUT)
            return cursor.getString(index)
        }
        val id = text(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        requireIdentifier(id)
        val name = text(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (name.length > LocalInputLimits.NAME_CHARACTERS || name.any(Char::isISOControl)) throw LocalArtifactException(LocalArtifactIssue.INVALID_INPUT)
        val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        val size = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex)
        if (size != null && size < 0L) throw LocalArtifactException(LocalArtifactIssue.INVALID_INPUT)
        return DocumentMetadata(id, name, text(DocumentsContract.Document.COLUMN_MIME_TYPE),
            cursor.getInt(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)), size)
    }

    private fun documentJournal(): DocumentOutputJournal = DocumentOutputJournal(
        ArtifactPrivateDirectory.createBelow(context.noBackupFilesDir, "local-artifact-output-v1"),
    )

    private suspend fun <T> exclusive(action: suspend () -> T): T {
        if (!occupied.compareAndSet(false, true)) throw LocalArtifactException(LocalArtifactIssue.BUSY)
        try { return action() }
        finally { occupied.set(false) }
    }

    private suspend fun <T> physical(action: (CancellationSignal) -> T): T {
        val admitted = AtomicBoolean(false)
        try {
            return io.read(admitted = { admitted.set(true) }, action = action)
        } catch (error: Throwable) {
            throw documentFailure(error)
        } finally {
            // admission 在占槽时确定；取消不能以“尚未进入 action”为由提前释放 owner。
            if (admitted.get()) withContext(NonCancellable) { io.awaitIdle() }
        }
    }
}

private class DocumentMetadata(val id: String, val displayName: String, val mimeType: String, val flags: Int, val sizeBytes: Long?) {
    override fun toString(): String = "DocumentMetadata"
}

private class CopiedDocument(private val created: ArtifactCreatedFile) {
    val file: File get() = created.file
    fun delete() = created.deleteCreated()
    override fun toString(): String = "CopiedDocument"
}

private class DocumentFileIdentity(val device: Long, val inode: Long) {
    fun requireSame(descriptor: ParcelFileDescriptor) {
        val actual = capture(descriptor)
        if (actual.device != device || actual.inode != inode) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
    }
    override fun toString(): String = "DocumentFileIdentity"
    companion object {
        fun capture(descriptor: ParcelFileDescriptor): DocumentFileIdentity {
            val facts = Os.fstat(descriptor.fileDescriptor)
            if (!OsConstants.S_ISREG(facts.st_mode) || facts.st_dev < 0L || facts.st_ino <= 0L) throw LocalArtifactException(LocalArtifactIssue.UNSUPPORTED_RESOURCE)
            return DocumentFileIdentity(facts.st_dev, facts.st_ino)
        }
    }
}

private enum class DocumentPhase { PLANNED, CREATED, RENAMING }
private data class PendingDocument(
    val tree: Uri,
    val partName: String,
    val finalName: String,
    val receipt: LocalArtifactReceipt,
    val kind: LocalArtifactKind,
    val phase: DocumentPhase = DocumentPhase.PLANNED,
    val partUri: Uri? = null,
    val identity: DocumentFileIdentity? = null,
) {
    override fun toString(): String = "PendingDocument"
}

/** 私有、固定字段的有界二进制日志；损坏或未知版本不补成空状态。 */
private class DocumentOutputJournal(val directory: ArtifactPrivateDirectory) {
    private val file get() = directory.child("pending.bin")
    private val atomic get(): AtomicFile {
        directory.child("pending.bin.bak")
        directory.child("pending.bin.new")
        return AtomicFile(file)
    }

    fun read(): PendingDocument? {
        val backup = directory.child("pending.bin.bak")
        val unfinished = directory.child("pending.bin.new")
        if (!file.exists() && !backup.exists()) {
            if (unfinished.exists()) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
            return null
        }
        val bytes = atomic.openRead().use { input ->
            val output = ByteArrayOutputStream(4096)
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > JOURNAL_BYTES) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.size < 32) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(bytes.size - 32, bytes.size))) {
            throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            if (input.readInt() != JOURNAL_MAGIC || input.readInt() != 1) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
            val kind = LocalArtifactKind.entries.getOrNull(input.readInt()) ?: throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
            val phase = DocumentPhase.entries.getOrNull(input.readInt()) ?: throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
            val tree = Uri.parse(input.text(LocalInputLimits.URI_CHARACTERS))
            val partName = input.text(128)
            val finalName = input.text(128)
            val receipt = LocalArtifactReceipt(input.text(36), input.text(64), input.readLong())
            val uri = if (input.canonicalBoolean()) Uri.parse(input.text(LocalInputLimits.URI_CHARACTERS)) else null
            val identity = if (input.canonicalBoolean()) DocumentFileIdentity(input.readLong(), input.readLong()) else null
            if (input.read() != -1) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
            requireUri(tree)
            if (!DocumentsContract.isTreeUri(tree) || tree.pathSegments.size != 2) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
            requireReceipt(receipt, kind)
            val prefix = "${kind.prefix}-${receipt.artifactId}-"
            val nonce = partName.removePrefix(prefix).removeSuffix(".part")
            val canonicalNonce = try { UUID.fromString(nonce).toString() } catch (_: IllegalArgumentException) { null }
            if (canonicalNonce == null || partName != "$prefix$canonicalNonce.part" || finalName != "${kind.prefix}-${receipt.artifactId}.${kind.suffix}" ||
                (phase == DocumentPhase.PLANNED) != (uri == null && identity == null) ||
                (phase != DocumentPhase.PLANNED && (uri == null || identity == null))) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
            if (uri != null) {
                requireUri(uri)
                if (uri.authority != tree.authority || DocumentsContract.getTreeDocumentId(uri) != DocumentsContract.getTreeDocumentId(tree)) {
                    throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
                }
                requireIdentifier(DocumentsContract.getDocumentId(uri))
            }
            if (identity != null && (identity.device < 0L || identity.inode <= 0L)) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
            PendingDocument(tree, partName, finalName, receipt, kind, phase, uri, identity)
        }
    }

    fun save(value: PendingDocument) {
        val payload = ByteArrayOutputStream(4096)
        DataOutputStream(payload).use { output ->
            output.writeInt(JOURNAL_MAGIC)
            output.writeInt(1)
            output.writeInt(value.kind.ordinal)
            output.writeInt(value.phase.ordinal)
            output.text(value.tree.toString())
            output.text(value.partName)
            output.text(value.finalName)
            output.text(value.receipt.artifactId)
            output.text(value.receipt.sha256)
            output.writeLong(value.receipt.bytes)
            output.writeBoolean(value.partUri != null)
            value.partUri?.let { output.text(it.toString()) }
            output.writeBoolean(value.identity != null)
            value.identity?.let { identity ->
                output.writeLong(identity.device)
                output.writeLong(identity.inode)
            }
        }
        val bytes = payload.toByteArray()
        if (bytes.size + 32 > JOURNAL_BYTES) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
        val active = atomic
        val stream = active.startWrite()
        try {
            stream.write(bytes)
            stream.write(MessageDigest.getInstance("SHA-256").digest(bytes))
            stream.fd.sync()
            active.finishWrite(stream)
            syncArtifactDirectory(directory.root)
        } catch (error: Throwable) {
            closeWithFailure(error) { active.failWrite(stream) }
            throw error
        }
    }

    fun clear() {
        directory.child("pending.bin.bak")
        atomic.delete()
        syncArtifactDirectory(directory.root)
        if (file.exists() || directory.child("pending.bin.bak").exists() || directory.child("pending.bin.new").exists()) {
            throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
        }
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(maximumCharacters: Int): String {
        val size = readInt()
        if (size !in 1..(maximumCharacters * 4)) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
        val bytes = ByteArray(size)
        readFully(bytes)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        if (text.length > maximumCharacters || text.any(Char::isISOControl)) throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
        return text
    }

    private fun DataInputStream.canonicalBoolean(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw LocalArtifactException(LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED)
    }
}

internal fun requirePlatformLocalProvider(context: Context, uri: Uri) {
    requireUri(uri)
    // AOSP 本地存储文档提供者是首个明确支持路径；别的本地/云 provider 必须单独核验。
    if (uri.authority != "com.android.externalstorage.documents") throw LocalArtifactException(LocalArtifactIssue.LOCAL_PROVIDER_REQUIRED)
    val provider = context.packageManager.resolveContentProvider(checkNotNull(uri.authority), 0)
        ?: throw LocalArtifactException(LocalArtifactIssue.LOCAL_PROVIDER_REQUIRED)
    if (provider.packageName != "com.android.externalstorage" ||
        (provider.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
        throw LocalArtifactException(LocalArtifactIssue.LOCAL_PROVIDER_REQUIRED)
    }
}

private fun requireUri(uri: Uri) {
    val text = uri.toString()
    if (text.length > LocalInputLimits.URI_CHARACTERS || text.any(Char::isISOControl) || uri.scheme != "content" ||
        uri.authority.isNullOrBlank() || uri.authority?.contains('@') == true || uri.fragment != null || uri.query != null) {
        throw LocalArtifactException(LocalArtifactIssue.INVALID_INPUT)
    }
    try {
        Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
    } catch (failure: java.nio.charset.CharacterCodingException) { throw LocalArtifactException(LocalArtifactIssue.INVALID_INPUT, failure) }
}

private fun requireIdentifier(value: String) {
    if (value.isBlank() || value.length > LocalInputLimits.URI_CHARACTERS || value.any(Char::isISOControl)) throw LocalArtifactException(LocalArtifactIssue.INVALID_INPUT)
}

private fun requireReceipt(receipt: LocalArtifactReceipt, kind: LocalArtifactKind) {
    val exportId = try { UUID.fromString(receipt.artifactId).toString() } catch (_: IllegalArgumentException) { null }
    if (exportId != receipt.artifactId || !receipt.sha256.matches(Regex("[0-9a-f]{64}")) || receipt.bytes !in 1..kind.maximumBytes) {
        throw LocalArtifactException(LocalArtifactIssue.INTEGRITY_MISMATCH)
    }
}

private fun boundedAdd(bytes: Long, next: Long, maximum: Long = MAX_ARCHIVE_BYTES): Long {
    if (next < 0L || bytes > maximum - next) throw LocalArtifactException(LocalArtifactIssue.TOO_LARGE)
    return bytes + next
}

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

private inline fun closeWithFailure(failure: Throwable?, close: () -> Unit) {
    try { close() }
    catch (closing: Throwable) {
        if (failure == null) throw closing
        if (failure !== closing) failure.addSuppressed(closing)
    }
}

private fun documentFailure(error: Throwable): Throwable {
    if (error is CancellationException || error is LocalArtifactException) return error
    var cause: Throwable? = error
    var levels = 0
    while (cause != null && levels++ < 16) {
        if (cause is LocalArtifactException) return cause
        if (cause is ErrnoException && (cause.errno == OsConstants.ENOSPC || cause.errno == OsConstants.EDQUOT)) {
            return LocalArtifactException(LocalArtifactIssue.STORAGE_FULL, error)
        }
        cause = cause.cause
    }
    return when (error) {
        is SourceReadException -> LocalArtifactException(when (error.issue) {
            SourceReadIssue.BUSY -> LocalArtifactIssue.BUSY
            SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> LocalArtifactIssue.PERMISSION_DENIED
            SourceReadIssue.STORAGE_FULL -> LocalArtifactIssue.STORAGE_FULL
            SourceReadIssue.INVALID_SOURCE -> LocalArtifactIssue.INVALID_INPUT
            else -> LocalArtifactIssue.IO
        }, error)
        is SecurityException -> LocalArtifactException(LocalArtifactIssue.PERMISSION_DENIED, error)
        is IOException -> LocalArtifactException(LocalArtifactIssue.IO, error)
        is Exception -> LocalArtifactException(LocalArtifactIssue.IO, error)
        else -> error
    }
}

private const val DIRECTORY_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
private const val DOCUMENT_WRITE_FLAGS = DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
private const val MAX_ARCHIVE_BYTES = 300L * 1024 * 1024
private const val COPY_BUFFER_BYTES = 64 * 1024
private const val JOURNAL_BYTES = 80 * 1024
private const val JOURNAL_MAGIC = 0x4c503131
private val DOCUMENT_COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE)

/** 私有文件创建/删除均核对最初实体；归属信息不进入外部产物。 */
private class ArtifactFileIdentity private constructor(private val key: Any, private val directory: Boolean) {
    fun requireSame(file: File) {
        val actual = capture(file, directory)
        if (actual.key != key) throw LocalArtifactException(LocalArtifactIssue.INTEGRITY_MISMATCH)
    }
    companion object {
        fun capture(file: File, directory: Boolean = false): ArtifactFileIdentity {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink || (if (directory) !attributes.isDirectory else !attributes.isRegularFile)) {
                throw LocalArtifactException(LocalArtifactIssue.PATH_ESCAPE)
            }
            return ArtifactFileIdentity(attributes.fileKey() ?: throw LocalArtifactException(LocalArtifactIssue.IO), directory)
        }
    }
}

private fun artifactParent(file: File): File =
    file.parentFile ?: throw LocalArtifactException(LocalArtifactIssue.PATH_ESCAPE)

private class ArtifactCreatedFile private constructor(
    val file: File,
    val channel: FileChannel,
    private val identity: ArtifactFileIdentity,
    private val parentIdentity: ArtifactFileIdentity,
) : AutoCloseable {
    fun requireSame() {
        parentIdentity.requireSame(artifactParent(file))
        identity.requireSame(file)
    }
    fun deleteCreated() {
        requireSame()
        Files.delete(file.toPath())
        syncArtifactDirectory(artifactParent(file))
    }
    override fun close() = channel.close()
    companion object {
        fun create(file: File): ArtifactCreatedFile {
            val parent = ArtifactFileIdentity.capture(artifactParent(file), directory = true)
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            try {
                val identity = ArtifactFileIdentity.capture(file)
                parent.requireSame(artifactParent(file))
                return ArtifactCreatedFile(file, channel, identity, parent)
            } catch (error: Throwable) {
                closeWithFailure(error) { channel.close() }
                throw error
            }
        }
    }
}

private class ArtifactPrivateDirectory private constructor(val root: File, private val identity: ArtifactFileIdentity) {
    fun child(name: String): File {
        if (!name.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}")) || name.endsWith('.')) throw LocalArtifactException(LocalArtifactIssue.PATH_ESCAPE)
        identity.requireSame(root)
        val child = File(root, name)
        if (child.canonicalFile.parentFile != root || Files.isSymbolicLink(child.toPath())) throw LocalArtifactException(LocalArtifactIssue.PATH_ESCAPE)
        return child
    }
    companion object {
        fun open(root: File): ArtifactPrivateDirectory {
            val file = root.absoluteFile
            if (file != file.canonicalFile) throw LocalArtifactException(LocalArtifactIssue.PATH_ESCAPE)
            return ArtifactPrivateDirectory(file, ArtifactFileIdentity.capture(file, directory = true))
        }
        fun createBelow(trustedRoot: File, name: String): ArtifactPrivateDirectory {
            val parent = open(trustedRoot.canonicalFile)
            val child = parent.child(name)
            if (!Files.exists(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(child.toPath())
                syncArtifactDirectory(parent.root)
            }
            return open(child)
        }
    }
}

private fun artifactFileSha256(file: File, expectedBytes: Long, checkCancelled: () -> Unit): String {
    val identity = ArtifactFileIdentity.capture(file)
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    FileInputStream(file).use { input ->
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            bytes = boundedAdd(bytes, count.toLong(), expectedBytes)
            digest.update(buffer, 0, count)
        }
    }
    identity.requireSame(file)
    if (bytes != expectedBytes) throw LocalArtifactException(LocalArtifactIssue.INTEGRITY_MISMATCH)
    return digest.hex()
}

private fun syncArtifactDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
    try { Os.fsync(descriptor) }
    finally { Os.close(descriptor) }
}

/** 私有输入副本使用既有实际空间预留；不把其它应用可回收缓存当作可用字节。 */
private fun requirePrivateInputSpace(directory: File, incomingBytes: Long) {
    if (incomingBytes < 0L) throw LocalArtifactException(LocalArtifactIssue.INVALID_INPUT)
    val available = StatFs(directory.absolutePath).availableBytes
    if (available < 0L) throw LocalArtifactException(LocalArtifactIssue.IO)
    if (available < ImportLimits.FREE_RESERVE_BYTES || incomingBytes > available - ImportLimits.FREE_RESERVE_BYTES) {
        throw LocalArtifactException(LocalArtifactIssue.STORAGE_FULL)
    }
}
