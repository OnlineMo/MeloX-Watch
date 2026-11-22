package io.github.onlinemo.meloxwatch.data.backup

import android.content.Context
import android.net.Uri
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactDocumentDestination
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactDocumentInput
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactDocuments
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactException
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactIssue
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactSource
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** 选择器返回 URI/flags 只属于本次本地任务，不进入便携备份或诊断。 */
class BackupDocumentInput(internal val uri: Uri, internal val returnedFlags: Int) {
    override fun toString(): String = "BackupDocumentInput"
}

/** 目录里的输出由本次操作新建，不能把指定的已有空文件当作本次暂存。 */
class BackupDocumentDestination(internal val uri: Uri, internal val returnedFlags: Int) {
    override fun toString(): String = "BackupDocumentDestination"
}

/** 备份格式/完整标志在实际 I/O 内核里校验；文件发布与诊断共用同一个真实 owner。 */
class BackupDocuments internal constructor(private val artifacts: LocalArtifactDocuments) {
    constructor(context: Context, io: LocalProviderIo) : this(LocalArtifactDocuments(context, io))

    internal suspend fun copyInput(input: BackupDocumentInput, directory: BackupPrivateDirectory): File = artifactBoundary {
        artifacts.copyBackupInput(LocalArtifactDocumentInput(input.uri, input.returnedFlags), directory.root)
    }

    suspend fun writeVerified(destination: BackupDocumentDestination, file: File,
        receipt: BackupExportReceipt): BackupExportReceipt = artifactBoundary {
        val canonical = try { UUID.fromString(receipt.exportId).toString() } catch (_: IllegalArgumentException) { null }
        if (canonical != receipt.exportId || receipt.bytes !in 1..(BackupLimits.LOGICAL_ARCHIVE_BYTES + BackupLimits.RESOURCE_ARCHIVE_BYTES) ||
            !receipt.sha256.matches(Regex("[0-9a-f]{64}"))) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        val source = LocalArtifactSource.backup(file, receipt.exportId, receipt.bytes, receipt.sha256) { archive, checkCancelled ->
            val header = BackupArchiveReader().verify(archive, checkCancelled)
            if (header.exportId != receipt.exportId) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
        val written = artifacts.writeArtifact(LocalArtifactDocumentDestination(destination.uri, destination.returnedFlags), source)
        if (written.artifactId != receipt.exportId || written.sha256 != receipt.sha256 || written.bytes != receipt.bytes) {
            throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
        receipt
    }

    /** 外部 .part 的清理失败只阻当前导出；不把它升级为全库 Startup 不可用。 */
    suspend fun recoverPendingOutput(): Unit = artifactBoundary { artifacts.recoverPendingOutput() }

    private suspend fun <T> artifactBoundary(action: suspend () -> T): T {
        try { return action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LocalArtifactException) {
            // provider I/O 可能包一层源错误，仍保留真实格式失败及其收尾异常。
            var cause: Throwable? = failure
            var levels = 0
            while (cause != null && levels++ < 16) {
                if (cause is BackupException) throw cause
                cause = cause.cause
            }
            throw BackupException(when (failure.issue) {
                LocalArtifactIssue.INVALID_INPUT -> BackupIssue.INVALID_INPUT
                LocalArtifactIssue.LOCAL_PROVIDER_REQUIRED -> BackupIssue.LOCAL_DESTINATION_REQUIRED
                LocalArtifactIssue.TOO_LARGE -> BackupIssue.TOO_LARGE
                LocalArtifactIssue.PERMISSION_DENIED -> BackupIssue.PERMISSION_DENIED
                LocalArtifactIssue.STORAGE_FULL -> BackupIssue.STORAGE_FULL
                LocalArtifactIssue.IO -> BackupIssue.IO
                LocalArtifactIssue.BUSY -> BackupIssue.BUSY
                LocalArtifactIssue.INTEGRITY_MISMATCH -> BackupIssue.INTEGRITY_MISMATCH
                LocalArtifactIssue.CONFLICT -> BackupIssue.CONFLICT
                LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED -> BackupIssue.OUTPUT_CLEANUP_REQUIRED
                LocalArtifactIssue.UNSUPPORTED_RESOURCE -> BackupIssue.UNSUPPORTED_RESOURCE
                LocalArtifactIssue.PATH_ESCAPE -> BackupIssue.PATH_ESCAPE
            }, failure)
        }
    }
}
