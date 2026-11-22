package io.github.onlinemo.meloxwatch.data.fileoperations

import android.content.Context
import androidx.core.net.toUri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LibrarySource

/**
 * 对独立授权的 SAF 树执行真实原件操作；完整标签候选与保留原件均记录持久身份。
 * 原件不以截断模式打开；跨提供方复制不等于同意删除源文件。
 */
class SafOriginalFileAdapter(context: Context, private val tags: AudioTagFileCodec = AudioTagFileCodec()) : FileOperationPhysicalAdapter {
    private val files = SafDocumentFileAccess(context)

    override fun readTagFields(target: FileOperationTarget, cancellation: CancellationSignal): Map<AudioTagField, List<String>?> =
        SafDocumentFileAccess.providerCall {
            val original = original(target)
            val read = files.read(original.treeUri, original.parentObjectId, original.proof.uri, cancellation) { tags.inspect(it, cancellation) }
            SafDocumentFileAccess.requireExact(original.proof, read.proof)
            read.result.values
        }

    override fun inspect(target: FileOperationTarget, kind: FileOperationKind, cancellation: CancellationSignal): PhysicalFileProof =
        SafDocumentFileAccess.providerCall {
            val original = originalRoute(target)
            files.requireParent(original.treeUri, original.parentObjectId, cancellation, create = kind == FileOperationKind.WRITE_TAGS)
            val row = files.document(original.uri.toUri(), cancellation)
            when (kind) {
                FileOperationKind.DELETE_ORIGINAL -> files.requireFlags(row, DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
                FileOperationKind.WRITE_TAGS -> files.requireFlags(row, DocumentsContract.Document.FLAG_SUPPORTS_WRITE, DocumentsContract.Document.FLAG_SUPPORTS_RENAME,
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
                FileOperationKind.RENAME -> files.requireFlags(row, DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
                FileOperationKind.MOVE -> Unit // 根据实际目标选择移动或完整校验复制，不能由来源能力推定。
                else -> throw FileOperationException(FileOperationIssue.INVALID_INPUT)
            }
            if (kind == FileOperationKind.WRITE_TAGS) {
                val read = files.read(original.treeUri, original.parentObjectId, original.uri, cancellation) { tags.inspect(it, cancellation) }
                files.requireWritableAccess(WrittenDocument(original.sourceId, original.treeUri, original.parentObjectId, read.proof), cancellation)
                read.proof
            } else files.proof(original.treeUri, original.parentObjectId, original.uri, cancellation)
        }

    override fun apply(item: FileOperationItem, command: FileOperationCommand, sourceDeletionConfirmed: Boolean,
        cancellation: CancellationSignal, save: (FileOperationItem) -> Unit): FileOperationItem = SafDocumentFileAccess.providerCall {
        val state = FileUnitWriter(item, save)
        when (item.phase) {
            FileItemPhase.EXTERNAL_APPLIED, FileItemPhase.INDEX_RECONCILED, FileItemPhase.COPY_VALIDATED -> return@providerCall item
            FileItemPhase.COPY_INDEX_RECONCILED -> {
                if (command.kind != FileOperationKind.MOVE) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
                if (!sourceDeletionConfirmed) return@providerCall item
                deleteCompletedCopySource(state, cancellation)
            }
            FileItemPhase.REQUESTED -> when (command.kind) {
                FileOperationKind.DELETE_ORIGINAL -> deleteOriginal(state, cancellation)
                FileOperationKind.WRITE_TAGS -> writeTags(state, checkNotNull(command.tags), cancellation)
                FileOperationKind.RENAME -> rename(state, checkNotNull(command.displayName), cancellation)
                FileOperationKind.MOVE -> move(state, checkNotNull(command.destination), cancellation)
                else -> throw FileOperationException(FileOperationIssue.INVALID_INPUT)
            }
            else -> throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        }
        state.item
    }

    override fun recover(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit): FileOperationItem = SafDocumentFileAccess.providerCall {
        if (item.phase in setOf(FileItemPhase.REQUESTED, FileItemPhase.CANCELLED, FileItemPhase.FAILED)) return@providerCall item
        if (item.phase == FileItemPhase.INDEX_RECONCILED && item.artifacts.originalBackup == null) return@providerCall item
        val state = FileUnitWriter(item, save)
        val original = original(item.target)
        val artifacts = item.artifacts
        val published = artifacts.published?.let { document ->
            val interruptedMoveRename = command.kind == FileOperationKind.MOVE && !isCrossProvider(original, document) &&
                item.phase in setOf(FileItemPhase.APPLYING, FileItemPhase.NEEDS_RECOVERY)
            observe(document, cancellation, allowRename = interruptedMoveRename)
        }
        if (published != null) {
            if (command.kind == FileOperationKind.WRITE_TAGS && !artifacts.restoredOriginal) verifyTags(published, artifacts, cancellation)
            if (command.kind == FileOperationKind.MOVE && !artifacts.originalDeleted && isCrossProvider(original, published)) {
                val sourceGone = artifacts.sourceDeletionStarted && files.absent(original.treeUri, original.parentObjectId,
                    original.proof.objectId, cancellation) && files.findRetained(original, cancellation) == null
                state.change(phase = if (sourceGone) FileItemPhase.EXTERNAL_APPLIED else if (item.phase == FileItemPhase.COPY_INDEX_RECONCILED)
                    item.phase else FileItemPhase.COPY_VALIDATED,
                    artifacts = artifacts.copy(published = published, originalDeleted = sourceGone),
                    issue = if (sourceGone || !artifacts.sourceDeletionStarted) null else item.issue)
            } else {
                state.change(phase = if (item.phase == FileItemPhase.INDEX_RECONCILED) item.phase else FileItemPhase.EXTERNAL_APPLIED,
                    artifacts = artifacts.copy(published = published,
                        actualNameDiffers = if (command.kind == FileOperationKind.MOVE)
                            published.proof.displayName != (command.destination?.displayName ?: original.proof.displayName)
                        else artifacts.actualNameDiffers), issue = item.issue)
            }
            return@providerCall state.item
        }
        when (command.kind) {
            FileOperationKind.DELETE_ORIGINAL -> {
                if (files.absent(original.treeUri, original.parentObjectId, original.proof.objectId, cancellation) &&
                    files.findRetained(original, cancellation) == null) {
                    state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = artifacts.copy(originalDeleted = true), issue = null)
                } else state.change(phase = FileItemPhase.CANCELLED, issue = FileOperationIssue.CANCELLED)
            }
            FileOperationKind.RENAME -> {
                val moved = files.findRetained(original, cancellation)
                if (moved != null && (moved.proof.uri != original.proof.uri || moved.proof.displayName != original.proof.displayName)) {
                    state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = artifacts.copy(published = moved,
                        actualNameDiffers = moved.proof.displayName != command.displayName), issue = null)
                } else state.change(phase = FileItemPhase.CANCELLED, issue = FileOperationIssue.CANCELLED)
            }
            FileOperationKind.MOVE -> observeMove(state, original, checkNotNull(command.destination), cancellation)
            FileOperationKind.WRITE_TAGS -> {
                val retained = artifacts.originalBackup?.let { observe(it, cancellation, allowRename = true) }
                    ?: files.findRetained(original, cancellation)?.takeIf { it.proof.displayName != original.proof.displayName }
                val staged = observeStaging(item, cancellation)
                if (retained != null && retained.proof.displayName == original.proof.displayName) {
                    state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = artifacts.copy(originalBackup = null,
                        published = retained, restoredOriginal = true, tagValues = null, audioSha256 = null), issue = FileOperationIssue.CANCELLED)
                } else if (staged != null && staged.proof.displayName == original.proof.displayName && artifacts.tagValues != null) {
                    verifyTags(staged, artifacts, cancellation)
                    state.change(phase = FileItemPhase.EXTERNAL_APPLIED,
                        artifacts = artifacts.copy(originalBackup = retained, published = staged), issue = null)
                } else if (staged == null && artifacts.staged != null && retained == null) {
                    checkedOriginal(item.target, cancellation)
                    state.change(phase = FileItemPhase.CANCELLED, artifacts = artifacts.copy(staged = null), issue = FileOperationIssue.CANCELLED)
                } else state.change(phase = FileItemPhase.NEEDS_RECOVERY,
                    artifacts = artifacts.copy(originalBackup = retained ?: artifacts.originalBackup, staged = staged ?: artifacts.staged), issue = FileOperationIssue.RECOVERY_REQUIRED)
            }
            else -> throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        }
        state.item
    }

    override fun discardStaging(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit): FileOperationItem = SafDocumentFileAccess.providerCall {
        if (item.phase != FileItemPhase.NEEDS_RECOVERY || item.artifacts.originalBackup != null ||
            item.artifacts.published != null && !item.artifacts.restoredOriginal || command.kind !in setOf(FileOperationKind.WRITE_TAGS, FileOperationKind.MOVE)) {
            throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        }
        val staged = item.artifacts.staged ?: throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        if (staged.proof.displayName != item.artifacts.stagingName) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        if (item.artifacts.restoredOriginal) {
            val restored = item.artifacts.published?.let { observe(it, cancellation) }
                ?: throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
            SafDocumentFileAccess.requireSameObjectContents(original(item.target).proof, restored.proof)
        } else checkedOriginal(item.target, cancellation)
        val state = FileUnitWriter(item, save)
        state.change(phase = FileItemPhase.APPLYING)
        files.delete(staged, cancellation)
        state.change(phase = FileItemPhase.CANCELLED, artifacts = item.artifacts.copy(staged = null), issue = FileOperationIssue.CANCELLED)
        state.item
    }

    override fun compensateInterruptedTag(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit): FileOperationItem {
        if (command.kind != FileOperationKind.WRITE_TAGS || item.phase != FileItemPhase.NEEDS_RECOVERY ||
            item.artifacts.published != null || item.artifacts.originalBackup == null) return item
        // 观察已排除验证发布的候选；恢复确切备份只补偿当前确认的中断替换。
        return restoreRetainedOriginal(item, command, cancellation, save)
    }

    /** 明确恢复使用独立保留原件票据；当前标签操作的补偿也须先证明未发布成功。 */
    override fun restoreRetainedOriginal(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit): FileOperationItem = SafDocumentFileAccess.providerCall {
        if (command.kind != FileOperationKind.WRITE_TAGS || item.phase != FileItemPhase.NEEDS_RECOVERY || item.artifacts.published != null) {
            throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        }
        val original = original(item.target)
        val retained = item.artifacts.originalBackup?.let { observe(it, cancellation) }
            ?: throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        SafDocumentFileAccess.requireSameObjectContents(original.proof, retained.proof)
        files.requireNameFree(original.treeUri, original.parentObjectId, original.proof.displayName, cancellation)
        val state = FileUnitWriter(item, save)
        state.change(phase = FileItemPhase.APPLYING)
        val restored = files.rename(retained, original.proof.displayName, cancellation)
        if (restored.proof.displayName != original.proof.displayName) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = item.artifacts.copy(originalBackup = null,
            published = restored, tagValues = null, audioSha256 = null, restoredOriginal = true), issue = FileOperationIssue.CANCELLED)
        state.item
    }

    override fun finishArtifacts(item: FileOperationItem, cancellation: CancellationSignal): FileOperationItem =
        SafDocumentFileAccess.providerCall {
            if (item.phase != FileItemPhase.INDEX_RECONCILED) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
            val backup = item.artifacts.originalBackup
            if (backup != null) {
                val published = item.artifacts.published ?: throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
                if (backup.proof.device == published.proof.device && backup.proof.inode == published.proof.inode) {
                    throw FileOperationException(FileOperationIssue.INTEGRITY_FAILED)
                }
                // 只有完整独立验证并已协调的替换文件，才允许清理本次确切保留原件；
                // 移动目标上的已完成副本继续保留。
                verifyTags(observe(published, cancellation) ?: throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED), item.artifacts, cancellation)
                if (!files.absent(backup.treeUri, backup.parentObjectId, backup.proof.objectId, cancellation)) files.delete(backup, cancellation)
            }
            val leftover = item.artifacts.staged?.takeIf { it.proof.inode != item.artifacts.published?.proof?.inode ||
                it.proof.device != item.artifacts.published?.proof?.device }
            item.copy(phase = if (leftover != null) FileItemPhase.NEEDS_RECOVERY else if (item.artifacts.restoredOriginal) FileItemPhase.CANCELLED else item.phase,
                artifacts = item.artifacts.copy(originalBackup = null, staged = leftover),
                issue = if (leftover != null) FileOperationIssue.RECOVERY_REQUIRED else item.issue)
        }

    override fun verifyPublishedRoute(source: LibrarySource, document: WrittenDocument, cancellation: CancellationSignal): FilePublishedRoute = try {
        SafDocumentFileAccess.providerCall {
            if (source.spec.kind != LocalSourceKind.TREE || source.state != LibrarySourceState.ACTIVE ||
                source.spec.uri.toUri().authority != document.treeUri.toUri().authority) {
                throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
            }
            files.requireReadableDescendant(source.spec.uri, document.proof.objectId, cancellation)
            val uri = files.route(source.spec.uri, document.proof.objectId)
            val proof = files.proof(source.spec.uri, document.parentObjectId, uri.toString(), cancellation)
            SafDocumentFileAccess.requireSameObjectContents(document.proof, proof)
            FilePublishedRoute(source.spec.sourceId, source.authEpoch, source.grantId, proof, null)
        }
    } catch (failure: FileOperationException) {
        FilePublishedRoute(source.spec.sourceId, source.authEpoch, source.grantId, null, failure.issue)
    }

    private fun deleteOriginal(state: FileUnitWriter, cancellation: CancellationSignal) {
        val original = checkedOriginal(state.item.target, cancellation)
        state.change(phase = FileItemPhase.APPLYING)
        files.delete(original, cancellation)
        state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = state.item.artifacts.copy(originalDeleted = true))
    }

    private fun rename(state: FileUnitWriter, name: String, cancellation: CancellationSignal) {
        val original = checkedOriginal(state.item.target, cancellation)
        files.requireNameFree(original.treeUri, original.parentObjectId, name, cancellation, original.proof.objectId)
        state.change(phase = FileItemPhase.APPLYING)
        val result = if (name == original.proof.displayName) original else files.rename(original, name, cancellation)
        state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = state.item.artifacts.copy(published = result,
            actualNameDiffers = result.proof.displayName != name))
    }

    private fun writeTags(state: FileUnitWriter, patch: AudioTagPatch, cancellation: CancellationSignal) {
        val original = checkedOriginal(state.item.target, cancellation)
        files.requireParent(original.treeUri, original.parentObjectId, cancellation, create = true)
        files.requireFlags(files.document(original.proof.uri.toUri(), cancellation), DocumentsContract.Document.FLAG_SUPPORTS_RENAME,
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE, DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
        files.requireWritableAccess(original, cancellation)
        val stagedName = privateName(state.item, "part")
        val backupName = privateName(state.item, "recovery")
        files.requireNameFree(original.treeUri, original.parentObjectId, stagedName, cancellation)
        files.requireNameFree(original.treeUri, original.parentObjectId, backupName, cancellation)
        state.change(phase = FileItemPhase.STAGING, artifacts = state.item.artifacts.copy(stagingName = stagedName, backupName = backupName))
        val created = files.create(original.sourceId, original.treeUri, original.parentObjectId, stagedName, original.proof.mimeType, cancellation)
        requireDifferentObject(original, created)
        state.change(artifacts = state.item.artifacts.copy(staged = created))
        val rewritten = files.writeEmpty(created, cancellation) { output, _, beforeWrite ->
            files.read(original.treeUri, original.parentObjectId, original.proof.uri, cancellation) {
                tags.rewrite(it, output, patch, cancellation, beforeWrite)
            }.also { SafDocumentFileAccess.requireExact(original.proof, it.proof) }.result
        }
        val staged = created.copy(proof = files.proof(created.treeUri, created.parentObjectId, created.proof.uri, cancellation))
        val artifacts = state.item.artifacts.copy(staged = staged, tagValues = rewritten.values, audioSha256 = rewritten.audioSha256)
        verifyTags(staged, artifacts, cancellation)
        state.change(phase = FileItemPhase.VALIDATED, artifacts = artifacts)
        // 长流式工作后、改变原件前再次校验，防止期间的外部变更被旧确认覆盖。
        checkedOriginal(state.item.target, cancellation)
        files.requireNameFree(original.treeUri, original.parentObjectId, backupName, cancellation)
        state.change(phase = FileItemPhase.APPLYING)
        val retained = files.rename(original, backupName, cancellation)
        if (retained.proof.displayName != backupName) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        state.change(artifacts = state.item.artifacts.copy(originalBackup = retained))
        files.requireNameFree(original.treeUri, original.parentObjectId, original.proof.displayName, cancellation)
        val published = files.rename(staged, original.proof.displayName, cancellation)
        if (published.proof.displayName != original.proof.displayName) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        verifyTags(published, state.item.artifacts, cancellation)
        state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = state.item.artifacts.copy(published = published))
    }

    private fun move(state: FileUnitWriter, requestedDestination: FileOperationDestination, cancellation: CancellationSignal) {
        val original = checkedOriginal(state.item.target, cancellation)
        val destination = requestedDestination.copy(displayName = requestedDestination.displayName ?: original.proof.displayName)
        files.requireParent(destination.treeUri, destination.parentObjectId, cancellation, create = true)
        val sameAuthority = original.treeUri.toUri().authority == destination.treeUri.toUri().authority
        if (sameAuthority) {
            val sameParent = original.parentObjectId == destination.parentObjectId
            files.requireNameFree(destination.treeUri, destination.parentObjectId, checkNotNull(destination.displayName), cancellation,
                original.proof.objectId.takeIf { sameParent })
            if (!sameParent) files.requireFlags(files.document(original.proof.uri.toUri(), cancellation), DocumentsContract.Document.FLAG_SUPPORTS_MOVE)
            state.change(phase = FileItemPhase.APPLYING)
            val moved = if (sameParent) original.copy(sourceId = destination.sourceId, treeUri = destination.treeUri)
                else files.move(original, destination, cancellation)
            // 可选改名前先保存实际移动结果；取消不能把已完成目标当垃圾删除。
            state.change(artifacts = state.item.artifacts.copy(published = moved))
            val result = if (moved.proof.displayName == destination.displayName) moved
                else files.rename(moved, checkNotNull(destination.displayName), cancellation)
            state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = state.item.artifacts.copy(published = result,
                actualNameDiffers = result.proof.displayName != destination.displayName))
        } else copyToAnotherProvider(state, original, destination, cancellation)
    }

    private fun copyToAnotherProvider(state: FileUnitWriter, original: WrittenDocument, destination: FileOperationDestination,
        cancellation: CancellationSignal) {
        files.requireNameFree(destination.treeUri, destination.parentObjectId, checkNotNull(destination.displayName), cancellation)
        val name = privateName(state.item, "part")
        state.change(phase = FileItemPhase.STAGING, artifacts = state.item.artifacts.copy(stagingName = name))
        val created = files.create(destination.sourceId, destination.treeUri, destination.parentObjectId, name, original.proof.mimeType, cancellation)
        requireDifferentObject(original, created)
        state.change(artifacts = state.item.artifacts.copy(staged = created))
        val copied = files.writeEmpty(created, cancellation) { output, _, beforeWrite ->
            files.read(original.treeUri, original.parentObjectId, original.proof.uri, cancellation) { input ->
                val buffer = ByteArray(FileOperationLimits.BUFFER_BYTES)
                while (true) {
                    cancellation.throwIfCanceled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) throw FileOperationException(FileOperationIssue.SOURCE_UNAVAILABLE)
                    beforeWrite(count)
                    output.write(buffer, 0, count)
                }
            }.proof
        }
        SafDocumentFileAccess.requireExact(original.proof, copied)
        val staged = created.copy(proof = files.proof(created.treeUri, created.parentObjectId, created.proof.uri, cancellation))
        if (staged.proof.bytes != original.proof.bytes || staged.proof.sha256 != original.proof.sha256) {
            throw FileOperationException(FileOperationIssue.INTEGRITY_FAILED)
        }
        state.change(phase = FileItemPhase.VALIDATED, artifacts = state.item.artifacts.copy(staged = staged))
        checkedOriginal(state.item.target, cancellation)
        files.requireNameFree(destination.treeUri, destination.parentObjectId, checkNotNull(destination.displayName), cancellation)
        state.change(phase = FileItemPhase.APPLYING)
        val published = files.rename(staged, checkNotNull(destination.displayName), cancellation)
        state.change(phase = FileItemPhase.COPY_VALIDATED, artifacts = state.item.artifacts.copy(published = published,
            actualNameDiffers = published.proof.displayName != destination.displayName))
    }

    private fun deleteCompletedCopySource(state: FileUnitWriter, cancellation: CancellationSignal) {
        val published = state.item.artifacts.published ?: throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        // 二次删源票据绑定确切已验证目标；执行时目标仍须实际存在且字节相同。
        observe(published, cancellation) ?: throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
        val original = checkedOriginal(state.item.target, cancellation)
        if (!isCrossProvider(original, published)) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        state.change(phase = FileItemPhase.APPLYING, artifacts = state.item.artifacts.copy(sourceDeletionStarted = true))
        files.delete(original, cancellation)
        state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = state.item.artifacts.copy(originalDeleted = true), reconcileAfter = null)
    }

    private fun observeMove(state: FileUnitWriter, original: WrittenDocument, destination: FileOperationDestination, cancellation: CancellationSignal) {
        val expectedName = destination.displayName ?: original.proof.displayName
        if (original.treeUri.toUri().authority == destination.treeUri.toUri().authority) {
            val candidate = original.copy(sourceId = destination.sourceId, treeUri = destination.treeUri, parentObjectId = destination.parentObjectId)
            val moved = files.findRetained(candidate, cancellation)
            if (moved != null && (moved.proof.uri != original.proof.uri || moved.parentObjectId != original.parentObjectId ||
                    moved.proof.displayName != original.proof.displayName)) {
                state.change(phase = FileItemPhase.EXTERNAL_APPLIED, artifacts = state.item.artifacts.copy(published = moved,
                    actualNameDiffers = moved.proof.displayName != expectedName), issue = null)
            } else state.change(phase = FileItemPhase.CANCELLED, issue = FileOperationIssue.CANCELLED)
        } else {
            val staged = observeStaging(state.item, cancellation)
            if (staged != null && staged.proof.displayName != state.item.artifacts.stagingName &&
                staged.proof.bytes == original.proof.bytes && staged.proof.sha256 == original.proof.sha256) {
                state.change(phase = FileItemPhase.COPY_VALIDATED, artifacts = state.item.artifacts.copy(published = staged,
                    actualNameDiffers = staged.proof.displayName != expectedName), issue = null)
            } else state.change(phase = FileItemPhase.NEEDS_RECOVERY,
                artifacts = state.item.artifacts.copy(staged = staged ?: state.item.artifacts.staged), issue = FileOperationIssue.RECOVERY_REQUIRED)
        }
    }

    private fun observeStaging(item: FileOperationItem, cancellation: CancellationSignal): WrittenDocument? {
        val staged = item.artifacts.staged ?: return null
        if (files.absent(staged.treeUri, staged.parentObjectId, staged.proof.objectId, cancellation)) {
            // 完整候选已有完整摘要，只允许用它查找改名后的相同 inode。
            return if (item.artifacts.tagValues != null || item.phase in setOf(FileItemPhase.VALIDATED, FileItemPhase.APPLYING))
                files.findRetained(staged, cancellation) else null
        }
        val current = files.proof(staged.treeUri, staged.parentObjectId, staged.proof.uri, cancellation)
        if (staged.proof.device != current.device || staged.proof.inode != current.inode) throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
        if (current.displayName != item.artifacts.stagingName) SafDocumentFileAccess.requireSameObjectContents(staged.proof, current)
        // 部分暂存只供独立清理预览；不能升级为有效候选，
        // 也不由启动或无关缓存操作自动删除。
        return staged.copy(proof = current)
    }

    private fun verifyTags(document: WrittenDocument, artifacts: FileOperationArtifacts, cancellation: CancellationSignal) {
        val expected = artifacts.tagValues ?: throw FileOperationException(FileOperationIssue.INTEGRITY_FAILED)
        val actual = files.read(document.treeUri, document.parentObjectId, document.proof.uri, cancellation) { tags.inspect(it, cancellation) }
        SafDocumentFileAccess.requireExact(document.proof, actual.proof)
        if (actual.result.values != expected || actual.result.audioSha256 != artifacts.audioSha256) {
            throw FileOperationException(FileOperationIssue.INTEGRITY_FAILED)
        }
    }

    private fun observe(document: WrittenDocument, cancellation: CancellationSignal, allowRename: Boolean = false): WrittenDocument? {
        if (allowRename) return files.findRetained(document, cancellation)
        if (files.absent(document.treeUri, document.parentObjectId, document.proof.objectId, cancellation)) {
            return files.findRetained(document, cancellation)
        }
        val proof = files.proof(document.treeUri, document.parentObjectId, document.proof.uri, cancellation)
        SafDocumentFileAccess.requireExact(document.proof, proof)
        return document.copy(proof = proof)
    }

    private fun checkedOriginal(target: FileOperationTarget, cancellation: CancellationSignal): WrittenDocument {
        val original = original(target)
        val proof = files.proof(original.treeUri, original.parentObjectId, original.proof.uri, cancellation)
        SafDocumentFileAccess.requireExact(original.proof, proof)
        return original.copy(proof = proof)
    }

    private fun original(target: FileOperationTarget): WrittenDocument {
        val route = originalRoute(target)
        return WrittenDocument(route.sourceId, route.treeUri, route.parentObjectId,
            target.proof ?: throw FileOperationException(FileOperationIssue.INVALID_INPUT))
    }

    private fun originalRoute(target: FileOperationTarget): SafOriginalRoute {
        val descriptor = target.descriptor ?: throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        if (target.ownership != FileResourceOwnership.EXTERNAL_ORIGINAL || descriptor.source.spec.kind != LocalSourceKind.TREE ||
            descriptor.source.state != LibrarySourceState.ACTIVE) throw FileOperationException(FileOperationIssue.UNSUPPORTED_PROVIDER)
        files.requireTree(descriptor.source.spec.uri, write = true)
        files.requireRoute(descriptor.source.spec.uri, descriptor.location.uri, descriptor.objectId)
        return SafOriginalRoute(descriptor.source.spec.sourceId, descriptor.source.spec.uri,
            target.parentObjectId ?: throw FileOperationException(FileOperationIssue.INCOMPLETE_SCOPE), descriptor.location.uri)
    }

    private fun isCrossProvider(left: WrittenDocument, right: WrittenDocument) = left.treeUri.toUri().authority != right.treeUri.toUri().authority
    private fun requireDifferentObject(left: WrittenDocument, right: WrittenDocument) {
        if (left.proof.device == right.proof.device && left.proof.inode == right.proof.inode) throw FileOperationException(FileOperationIssue.INTEGRITY_FAILED)
    }
    private fun privateName(item: FileOperationItem, suffix: String) = ".melox-${item.operationId}-${item.ordinal}.$suffix"
}

private data class SafOriginalRoute(val sourceId: String, val treeUri: String, val parentObjectId: String, val uri: String)

internal class FileUnitWriter(initial: FileOperationItem, private val save: (FileOperationItem) -> Unit) {
    var item: FileOperationItem = initial
        private set

    fun change(phase: FileItemPhase = item.phase, artifacts: FileOperationArtifacts = item.artifacts,
        issue: FileOperationIssue? = item.issue, reconcileAfter: String? = item.reconcileAfter) {
        val changed = item.copy(phase = phase, artifacts = artifacts, issue = issue, reconcileAfter = reconcileAfter)
        save(changed)
        item = changed
    }
}
