package io.github.onlinemo.meloxwatch.data.themes

import android.database.sqlite.SQLiteFullException
import androidx.sqlite.SQLiteException
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.toPlatformSQLiteException
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferPermit
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceiptKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferRoomLedger
import io.github.onlinemo.meloxwatch.data.transfer.TransferVerifiedUpload
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class ReceivedThemeSummary(val operationId: String, val displayName: String, val receivedThemeRevision: Long)

/**
 * 单个应用实例协调真实 Room 收据和 LP-12 私有主题包。
 * 远端收据不改变外观选择；只有本机预览的明确批准才写入应用意图。
 * 文件 guard 防止后续编辑抹除该意图的恢复证据。
 */
class TransferredThemeCoordinator(
    private val database: MeloXDatabase,
    private val themes: LocalThemeRepository,
    private val transferQuota: ThemeTransferQuota,
) : ThemeSelectionStore by themes {
    private val dao = database.transferredThemeCandidateDao()
    private val ledger = TransferRoomLedger(database)
    private val occupied = AtomicBoolean(false)
    private val mutablePending = MutableStateFlow<ReceivedThemeSummary?>(null)
    val pending: StateFlow<ReceivedThemeSummary?> = mutablePending.asStateFlow()
    private val mutableIssue = MutableStateFlow<ThemeIssue?>(null)
    val issue: StateFlow<ThemeIssue?> = mutableIssue.asStateFlow()

    suspend fun load(): ReceivedThemeSummary? = localOperation {
        recoverLocked()
        publishPending()
    }

    /** 编辑器先协调已有批准；读取不会重放任何应用操作。 */
    override suspend fun read(): ThemeSnapshot = localOperation {
        recoverLocked()
        publishPending()
        themes.read()
    }

    override suspend fun prepareImport(request: ThemeImportRequest): ThemePreview = localOperation {
        recoverLocked()
        themes.prepareImport(request)
    }

    override suspend fun previewChoice(choice: ThemeChoice): ThemePreview = localOperation {
        recoverLocked()
        themes.previewChoice(choice)
    }

    /** 同一编辑器的本机确认按精确 token 路由，收到的包不能绕过持久批准记录。 */
    override suspend fun apply(request: ThemeApplyRequest): ThemeApplyResult = localOperation {
        recoverLocked()
        val receivedId = themes.receivedPreviewId(request.previewToken)
        if (receivedId == null) themes.apply(request) else approveLocked(receivedId, request)
    }

    internal suspend fun committed(binding: TransferOperationBinding, permit: TransferPermit): TransferReceipt? = operation {
        database.withPreservedTransaction {
            val receipt = ledger.committedInCurrentTransaction(binding, permit)
            if (receipt == null) {
                // 候选与 canonical 收据必须同事务存在；缺收据不能变成未完成上传再补造。
                if (dao.candidate(binding.operationId) != null) transferFail(TransferIssue.RECOVERY_REQUIRED)
                return@withPreservedTransaction null
            }
            val row = dao.candidate(binding.operationId) ?: transferFail(TransferIssue.RECOVERY_REQUIRED)
            requireCandidate(row)
            if (!row.record().matches(binding)) transferFail(TransferIssue.CONFLICT)
            ledger.requireRecordedInCurrentTransaction(row.receipt())
            receipt
        }
    }

    internal suspend fun receive(binding: TransferOperationBinding, upload: TransferVerifiedUpload, permit: TransferPermit): TransferReceipt = operation {
        if (binding.kind != TransferOperationKind.THEME) transferFail(TransferIssue.INVALID_INPUT)
        val caller = currentCoroutineContext()
        recoverLocked()
        val replay = database.withPreservedTransaction {
            ledger.bindInCurrentTransaction(binding, permit)
            ledger.committedInCurrentTransaction(binding, permit)?.also {
                val original = dao.candidate(binding.operationId) ?: transferFail(TransferIssue.RECOVERY_REQUIRED)
                requireCandidate(original)
                ledger.requireRecordedInCurrentTransaction(original.receipt())
            }
        }
        if (replay != null) return@operation replay
        val inputOwner = upload.beginBackendCommit()
        try {
            val record = upload.readOnPhysicalWorker { input, signal ->
                try { themes.receiveTransferredOnWorker(binding, upload, input, signal, permit, transferQuota) }
                catch (error: ThemeException) { throw transferThemeFailure(error) }
            }
            caller.ensureActive()
            inputOwner.checkActive()
            withContext(NonCancellable) {
                val receipt = database.withPreservedTransaction {
                    ledger.bindInCurrentTransaction(binding, permit)
                    ledger.committedInCurrentTransaction(binding, permit)?.let { original ->
                        val row = dao.candidate(binding.operationId) ?: transferFail(TransferIssue.RECOVERY_REQUIRED)
                        if (row.record() != record) transferFail(TransferIssue.CONFLICT)
                        requireCandidate(row)
                        ledger.requireRecordedInCurrentTransaction(row.receipt())
                        return@withPreservedTransaction original
                    }
                    if (dao.candidate(binding.operationId) != null) transferFail(TransferIssue.RECOVERY_REQUIRED)
                    if (dao.unresolved().isNotEmpty()) transferFail(TransferIssue.BUSY)
                    // Room 查询可能挂起；NonCancellable 只保护已认领提交后的落盘，不能掩盖原调用者取消。
                    caller.ensureActive()
                    val claim = inputOwner.claimCommit(permit, binding)
                    val row = record.receivedEntity()
                    dao.insert(row)
                    ledger.recordInCurrentTransaction(row.receipt(), claim)
                }
                upload.recordCommitted(receipt)
                publishPending()
                receipt
            }
        } finally { inputOwner.complete() }
    }

    suspend fun preview(operationId: String): ThemePreview = localOperation {
        recoverLocked()
        val candidate = readCandidate(operationId)
        if (candidate.state != TransferredThemeState.RECEIVED.name) themeFail(ThemeIssue.PREVIEW_NOT_FOUND)
        themes.previewTransferred(candidate.record())
    }

    suspend fun cancelPreview(token: String) = localOperation { themes.discardPreview(token) }

    /** 调用方必须是手表本机确认操作，不能是远端请求处理器。 */
    suspend fun approve(operationId: String, request: ThemeApplyRequest): ThemeApplyResult = localOperation {
        recoverLocked()
        approveLocked(operationId, request)
    }

    private suspend fun approveLocked(operationId: String, request: ThemeApplyRequest): ThemeApplyResult {
        val candidate = readCandidate(operationId)
        if (candidate.state != TransferredThemeState.RECEIVED.name) themeFail(ThemeIssue.PREVIEW_NOT_FOUND)
        val intent = themes.prepareTransferredApply(candidate.record(), request)
        database.withPreservedTransaction {
            val current = checkedCandidate(operationId)
            if (current != candidate) themeFail(ThemeIssue.REVISION_CONFLICT)
            val replacement = request.replaces
            update(current.copy(state = TransferredThemeState.APPLYING.name,
                localApplyOperationId = request.operationId, localPreviewToken = request.previewToken,
                localExpectedRevision = request.expectedRevision, localReplacementId = replacement?.installationId,
                localReplacementThemeId = replacement?.themeId, localReplacementManifestSha256 = replacement?.manifestSha256,
                fontLicenseAcknowledgmentVersion = request.fontLicenseAcknowledgmentVersion))
        }
        // 提交标记后 waiter 仍可能取消；保留精确 guard 与 APPLYING 行。
        // load() 读取实际落盘字节进行协调，不重复用户批准。
        themes.apply(request)
        val result = settleIntent(intent) ?: themeFail(ThemeIssue.RECOVERY_REQUIRED)
        publishPending()
        return result
    }

    /** 只丢弃接收的应用副本，不移除来源或已安装主题。 */
    suspend fun discard(operationId: String) = localOperation {
        recoverLocked()
        database.withPreservedTransaction {
            val current = checkedCandidate(operationId)
            when (current.state) {
                TransferredThemeState.RECEIVED.name -> update(current.copy(state = TransferredThemeState.DISCARDED.name))
                TransferredThemeState.DISCARDED.name -> Unit
                else -> themeFail(ThemeIssue.IN_USE)
            }
        }
        themes.discardTransferred(operationId)
        publishPending()
        Unit
    }

    private suspend fun recoverLocked() {
        val storage = themes.transferredStorage()
        storage.intent?.let { intent ->
            val candidate = readCandidate(intent.candidate.operationId)
            if (candidate.record() != intent.candidate) themeFail(ThemeIssue.RECOVERY_REQUIRED)
            when (candidate.state) {
                TransferredThemeState.RECEIVED.name -> {
                    // guard 已落盘但 Room 意图没有提交，应用操作尚未执行。
                    if (themes.transferredApplyOutcome(intent).applied != null) themeFail(ThemeIssue.RECOVERY_REQUIRED)
                    themes.releaseTransferredApply(intent)
                }
                TransferredThemeState.APPLYING.name, TransferredThemeState.APPLIED.name -> settleIntent(intent)
                else -> themeFail(ThemeIssue.RECOVERY_REQUIRED)
            }
        }
        val current = themes.transferredStorage()
        if (current.intent != null) themeFail(ThemeIssue.RECOVERY_REQUIRED)
        for (id in current.receivedIds) {
            val row = database.withPreservedTransaction {
                val candidate = dao.candidate(id)
                if (candidate != null) { requireCandidate(candidate)
                    ledger.requireRecordedInCurrentTransaction(candidate.receipt()) }
                else if (database.transferRoomDao().operation(id)?.resultKind != null) themeFail(ThemeIssue.RECOVERY_REQUIRED)
                candidate
            }
            when (row?.state) {
                null, TransferredThemeState.DISCARDED.name, TransferredThemeState.APPLIED.name -> themes.discardTransferred(id)
                TransferredThemeState.RECEIVED.name -> themes.validateTransferred(row.record())
                else -> themeFail(ThemeIssue.RECOVERY_REQUIRED)
            }
        }
        val unresolved = database.withPreservedTransaction { dao.unresolved() }
        if (unresolved.size > 1 || unresolved.any { it.state != TransferredThemeState.RECEIVED.name || it.operationId !in current.receivedIds }) {
            themeFail(ThemeIssue.RECOVERY_REQUIRED)
        }
    }

    private suspend fun settleIntent(intent: TransferredThemeApplyIntent): ThemeApplyResult? {
        val candidate = readCandidate(intent.candidate.operationId)
        if (candidate.record() != intent.candidate || candidate.intent() != intent) themeFail(ThemeIssue.RECOVERY_REQUIRED)
        val result = themes.transferredApplyOutcome(intent).applied
        database.withPreservedTransaction {
            val current = checkedCandidate(candidate.operationId)
            if (current != candidate) themeFail(ThemeIssue.RECOVERY_REQUIRED)
            when (current.state) {
                TransferredThemeState.APPLYING.name -> {
                    if (result == null) update(current.withoutApproval())
                    else update(current.copy(state = TransferredThemeState.APPLIED.name,
                        appliedInstallationId = result.installedRef?.installationId ?: themeFail(ThemeIssue.RECOVERY_REQUIRED),
                        appliedRevision = result.snapshot.revision ?: themeFail(ThemeIssue.RECOVERY_REQUIRED)))
                }
                TransferredThemeState.APPLIED.name -> {
                    if (result == null || current.appliedInstallationId != result.installedRef?.installationId ||
                        current.appliedRevision != result.snapshot.revision) themeFail(ThemeIssue.RECOVERY_REQUIRED)
                }
                else -> themeFail(ThemeIssue.RECOVERY_REQUIRED)
            }
        }
        themes.releaseTransferredApply(intent)
        return result
    }

    private suspend fun readCandidate(id: String): TransferredThemeCandidateEntity = database.withPreservedTransaction { checkedCandidate(id) }

    private suspend fun checkedCandidate(id: String): TransferredThemeCandidateEntity {
        if (!isThemeOperationId(id)) themeFail(ThemeIssue.INVALID_INPUT)
        val row = dao.candidate(id) ?: themeFail(ThemeIssue.THEME_NOT_FOUND)
        requireCandidate(row)
        ledger.requireRecordedInCurrentTransaction(row.receipt())
        return row
    }

    private suspend fun update(row: TransferredThemeCandidateEntity) {
        requireCandidate(row)
        if (dao.update(row) != 1) themeFail(ThemeIssue.RECOVERY_REQUIRED)
    }

    private suspend fun publishPending(): ReceivedThemeSummary? {
        val rows = database.withPreservedTransaction {
            dao.unresolved().also { candidates -> candidates.forEach { requireCandidate(it)
                    ledger.requireRecordedInCurrentTransaction(it.receipt()) } }
        }
        if (rows.size > 1 || rows.any { it.state != TransferredThemeState.RECEIVED.name }) themeFail(ThemeIssue.RECOVERY_REQUIRED)
        return rows.singleOrNull()?.let { ReceivedThemeSummary(it.operationId, it.displayName, it.receivedThemeRevision) }.also {
            mutablePending.value = it
            mutableIssue.value = null
        }
    }

    private suspend fun <T> operation(action: suspend () -> T): T {
        if (!occupied.compareAndSet(false, true)) themeFail(ThemeIssue.BUSY)
        try { return action() }
        catch (cancelled: CancellationException) { mutableIssue.value = ThemeIssue.RECOVERY_REQUIRED
            throw cancelled }
        catch (error: ThemeException) { mutableIssue.value = error.issue
            throw error }
        catch (error: TransferException) { mutableIssue.value = localThemeIssue(error.issue)
            throw error }
        catch (error: SQLiteException) {
            val platform = error.toPlatformSQLiteException()
            val issue = if (platform is SQLiteFullException) ThemeIssue.STORAGE_FULL else ThemeIssue.RECOVERY_REQUIRED
            mutableIssue.value = issue
            throw ThemeException(issue, platform)
        }
        finally { occupied.set(false) }
    }

    private suspend fun <T> localOperation(action: suspend () -> T): T = try { operation(action) }
    catch (error: TransferException) { throw ThemeException(localThemeIssue(error.issue), error) }
}

private fun localThemeIssue(issue: TransferIssue): ThemeIssue = when (issue) {
    TransferIssue.BUSY -> ThemeIssue.BUSY
    TransferIssue.STORAGE_FULL -> ThemeIssue.STORAGE_FULL
    TransferIssue.STORAGE_UNAVAILABLE -> ThemeIssue.STORAGE_UNAVAILABLE
    TransferIssue.QUOTA_EXCEEDED -> ThemeIssue.STORAGE_QUOTA_EXCEEDED
    TransferIssue.CONFLICT -> ThemeIssue.OPERATION_CONFLICT
    TransferIssue.INTEGRITY_FAILED -> ThemeIssue.INTEGRITY_FAILED
    else -> ThemeIssue.RECOVERY_REQUIRED
}

private fun TransferredThemeCandidateEntity.record() = TransferredThemeRecord(protocolSchema, operationId,
    targetFingerprint, peerFingerprint, payloadHash, packageSha256, packageBytes, manifestSha256, themeId, displayName, receivedThemeRevision)

private fun TransferredThemeCandidateEntity.receipt() = TransferReceipt(record().binding(), TransferReceiptKind.THEME_PENDING_CONFIRMATION,
    receivedThemeRevision, operationId, packageBytes)

private fun TransferredThemeRecord.receivedEntity() = TransferredThemeCandidateEntity(operationId, protocolSchema,
    targetFingerprint, peerFingerprint, payloadHash, packageSha256, packageBytes, manifestSha256, themeId, displayName,
    receivedThemeRevision, TransferredThemeState.RECEIVED.name, null, null, null, null, null, null, null, null, null)

private fun TransferredThemeCandidateEntity.withoutApproval() = copy(state = TransferredThemeState.RECEIVED.name,
    localApplyOperationId = null, localPreviewToken = null, localExpectedRevision = null,
    localReplacementId = null, localReplacementThemeId = null, localReplacementManifestSha256 = null,
    fontLicenseAcknowledgmentVersion = null, appliedInstallationId = null, appliedRevision = null)

private fun TransferredThemeCandidateEntity.intent(): TransferredThemeApplyIntent {
    val replacementParts = listOf(localReplacementId, localReplacementThemeId, localReplacementManifestSha256)
    if (replacementParts.any { it != null } && replacementParts.any { it == null }) themeFail(ThemeIssue.RECOVERY_REQUIRED)
    val replacement = localReplacementId?.let { ThemeRef(it, checkNotNull(localReplacementThemeId), checkNotNull(localReplacementManifestSha256)) }
    return TransferredThemeApplyIntent(record(), ThemeApplyRequest(localApplyOperationId ?: themeFail(ThemeIssue.RECOVERY_REQUIRED),
        localPreviewToken ?: themeFail(ThemeIssue.RECOVERY_REQUIRED), localExpectedRevision ?: themeFail(ThemeIssue.RECOVERY_REQUIRED),
        replacement, fontLicenseAcknowledgmentVersion))
}

private fun requireCandidate(row: TransferredThemeCandidateEntity) {
    row.record()
    when (row.state) {
        TransferredThemeState.RECEIVED.name, TransferredThemeState.DISCARDED.name -> if (row != row.withoutApproval().copy(state = row.state)) themeFail(ThemeIssue.RECOVERY_REQUIRED)
        TransferredThemeState.APPLYING.name -> { row.intent()
            if (row.appliedRevision != null || row.appliedInstallationId != null) themeFail(ThemeIssue.RECOVERY_REQUIRED) }
        TransferredThemeState.APPLIED.name -> {
            row.intent()
            if (row.appliedRevision == null || row.localExpectedRevision == Long.MAX_VALUE || row.appliedRevision != checkNotNull(row.localExpectedRevision) + 1 ||
                row.appliedInstallationId == null || !isThemeOperationId(row.appliedInstallationId)) themeFail(ThemeIssue.RECOVERY_REQUIRED)
        }
        else -> themeFail(ThemeIssue.RECOVERY_REQUIRED)
    }
}

internal fun transferThemeFailure(error: ThemeException): TransferException = TransferException(when (error.issue) {
    ThemeIssue.BUSY, ThemeIssue.IN_USE -> TransferIssue.BUSY
    ThemeIssue.STORAGE_FULL -> TransferIssue.STORAGE_FULL
    ThemeIssue.PACKAGE_TOO_LARGE, ThemeIssue.EXPANDED_TOO_LARGE, ThemeIssue.TOO_MANY_RESOURCES,
    ThemeIssue.STORAGE_QUOTA_EXCEEDED, ThemeIssue.FONT_QUOTA_EXCEEDED -> TransferIssue.QUOTA_EXCEEDED
    ThemeIssue.STORAGE_UNAVAILABLE, ThemeIssue.SOURCE_UNAVAILABLE, ThemeIssue.SAVE_FAILED -> TransferIssue.STORAGE_UNAVAILABLE
    ThemeIssue.INTEGRITY_FAILED, ThemeIssue.CORRUPT_STORAGE -> TransferIssue.INTEGRITY_FAILED
    ThemeIssue.RECOVERY_REQUIRED, ThemeIssue.CLEANUP_REQUIRED, ThemeIssue.FONT_BUDGET_UNAVAILABLE -> TransferIssue.RECOVERY_REQUIRED
    ThemeIssue.UNSUPPORTED_SCHEMA -> TransferIssue.INCOMPATIBLE_SCHEMA
    else -> TransferIssue.INVALID_INPUT
})

private fun transferFail(issue: TransferIssue): Nothing = throw TransferException(issue)
