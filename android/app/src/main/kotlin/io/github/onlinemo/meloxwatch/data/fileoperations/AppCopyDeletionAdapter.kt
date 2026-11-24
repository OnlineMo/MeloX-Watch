package io.github.onlinemo.meloxwatch.data.fileoperations

import android.content.Context
import androidx.core.net.toUri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import io.github.onlinemo.meloxwatch.data.imports.OwnedAudioProvider
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** 副本删除票据只允许删除已完成的确切私有副本，不能作用外部 URI。 */
class AppCopyDeletionAdapter(private val context: Context) : FileOperationPhysicalAdapter {
    override fun inspect(target: FileOperationTarget, kind: FileOperationKind, cancellation: CancellationSignal): PhysicalFileProof =
        SafDocumentFileAccess.providerCall {
            if (kind != FileOperationKind.DELETE_APP_COPY) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
            prove(target, cancellation)
        }

    override fun apply(item: FileOperationItem, command: FileOperationCommand, sourceDeletionConfirmed: Boolean,
        cancellation: CancellationSignal, save: (FileOperationItem) -> Unit): FileOperationItem = SafDocumentFileAccess.providerCall {
        if (command.kind != FileOperationKind.DELETE_APP_COPY || sourceDeletionConfirmed) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        if (item.phase in setOf(FileItemPhase.EXTERNAL_APPLIED, FileItemPhase.INDEX_RECONCILED)) return@providerCall item
        if (item.phase != FileItemPhase.REQUESTED) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        val current = prove(item.target, cancellation)
        SafDocumentFileAccess.requireExact(item.target.proof ?: throw FileOperationException(FileOperationIssue.INVALID_INPUT), current)
        val state = FileUnitWriter(item, save)
        state.change(phase = FileItemPhase.APPLYING)
        val file = file(item.target)
        cancellation.throwIfCanceled()
        Files.delete(file.toPath())
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = item.artifacts.copy(originalDeleted = true))
        state.item
    }

    override fun recover(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit): FileOperationItem = SafDocumentFileAccess.providerCall {
        if (command.kind != FileOperationKind.DELETE_APP_COPY) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        if (item.phase in setOf(FileItemPhase.REQUESTED, FileItemPhase.CANCELLED, FileItemPhase.FAILED, FileItemPhase.INDEX_RECONCILED)) return@providerCall item
        val state = FileUnitWriter(item, save)
        val file = file(item.target)
        cancellation.throwIfCanceled()
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = item.artifacts.copy(originalDeleted = true), issue = null)
        } else {
            val proof = prove(item.target, cancellation)
            SafDocumentFileAccess.requireExact(item.target.proof ?: throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED), proof)
            state.change(phase = FileItemPhase.CANCELLED, issue = FileOperationIssue.CANCELLED)
        }
        state.item
    }

    override fun restoreRetainedOriginal(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit): FileOperationItem = throw FileOperationException(FileOperationIssue.INVALID_INPUT)
    override fun discardStaging(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit): FileOperationItem = throw FileOperationException(FileOperationIssue.INVALID_INPUT)

    override fun finishArtifacts(item: FileOperationItem, cancellation: CancellationSignal): FileOperationItem {
        if (item.phase != FileItemPhase.INDEX_RECONCILED || item.artifacts.published != null || item.artifacts.staged != null ||
            item.artifacts.originalBackup != null) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        return item
    }

    override fun verifyPublishedRoute(source: LibrarySource, document: WrittenDocument, cancellation: CancellationSignal): FilePublishedRoute =
        throw FileOperationException(FileOperationIssue.INVALID_INPUT)

    private fun file(target: FileOperationTarget): File {
        val descriptor = target.descriptor ?: throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        if (target.ownership != FileResourceOwnership.APP_COPY || descriptor.source.spec.kind != LocalSourceKind.APP_OWNED) {
            throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        }
        val uri = descriptor.location.uri.toUri()
        if (uri.scheme != "content" || uri.authority != OwnedAudioProvider.authority(context) || uri.pathSegments.size != 1 ||
            uri.query != null || uri.fragment != null || descriptor.objectId != uri.pathSegments.single()) {
            throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
        }
        val result = OwnedAudioProvider.finalFile(context, uri.pathSegments.single())
        if (Files.isSymbolicLink(result.toPath()) || result.parentFile != OwnedAudioProvider.root(context)) {
            throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
        }
        return result
    }

    private fun prove(target: FileOperationTarget, cancellation: CancellationSignal): PhysicalFileProof {
        val file = file(target)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw FileOperationException(FileOperationIssue.FILE_MISSING)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val before = Os.fstat(descriptor.fileDescriptor)
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_ino <= 0L || before.st_size < 0L) {
                throw FileOperationException(FileOperationIssue.UNSUPPORTED_PROVIDER)
            }
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(FileOperationLimits.BUFFER_BYTES)
            var count = 0L
            while (true) {
                cancellation.throwIfCanceled()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) throw FileOperationException(FileOperationIssue.SOURCE_UNAVAILABLE)
                hash.update(buffer, 0, read)
                count = Math.addExact(count, read.toLong())
            }
            val after = Os.fstat(descriptor.fileDescriptor)
            if (before.st_dev != after.st_dev || before.st_ino != after.st_ino || before.st_ctime != after.st_ctime ||
                before.st_mtime != after.st_mtime || before.st_size != after.st_size || count != before.st_size || Files.isSymbolicLink(file.toPath())) {
                throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
            }
            PhysicalFileProof(target.descriptor!!.location.uri, target.descriptor.objectId, file.name, "audio/*",
                before.st_dev, before.st_ino, before.st_ctime, count, fileOperationHex(hash.digest()))
        }
    }
}

internal class RoutingFileOperationAdapter(
    private val originals: FileOperationPhysicalAdapter,
    private val copies: FileOperationPhysicalAdapter,
) : FileOperationPhysicalAdapter {
    private fun owner(target: FileOperationTarget) = when (target.ownership) {
        FileResourceOwnership.EXTERNAL_ORIGINAL -> originals
        FileResourceOwnership.APP_COPY -> copies
        null -> throw FileOperationException(FileOperationIssue.INVALID_INPUT)
    }
    override fun inspect(target: FileOperationTarget, kind: FileOperationKind, cancellation: CancellationSignal) = owner(target).inspect(target, kind, cancellation)
    override fun readTagFields(target: FileOperationTarget, cancellation: CancellationSignal) = owner(target).readTagFields(target, cancellation)
    override fun apply(item: FileOperationItem, command: FileOperationCommand, sourceDeletionConfirmed: Boolean, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit) = owner(item.target).apply(item, command, sourceDeletionConfirmed, cancellation, save)
    override fun recover(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit) = owner(item.target).recover(item, command, cancellation, save)
    override fun restoreRetainedOriginal(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit) = owner(item.target).restoreRetainedOriginal(item, command, cancellation, save)
    override fun discardStaging(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit) = owner(item.target).discardStaging(item, command, cancellation, save)
    override fun compensateInterruptedTag(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit) = owner(item.target).compensateInterruptedTag(item, command, cancellation, save)
    override fun finishArtifacts(item: FileOperationItem, cancellation: CancellationSignal) = owner(item.target).finishArtifacts(item, cancellation)
    override fun verifyPublishedRoute(source: LibrarySource, document: WrittenDocument, cancellation: CancellationSignal) =
        originals.verifyPublishedRoute(source, document, cancellation)
}
