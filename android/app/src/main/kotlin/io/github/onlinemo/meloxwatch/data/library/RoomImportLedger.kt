package io.github.onlinemo.meloxwatch.data.library

import android.net.Uri
import androidx.core.net.toUri
import io.github.onlinemo.meloxwatch.BuildConfig
import io.github.onlinemo.meloxwatch.data.imports.ImportException
import io.github.onlinemo.meloxwatch.data.imports.ImportIntent
import io.github.onlinemo.meloxwatch.data.imports.ImportIssue
import io.github.onlinemo.meloxwatch.data.imports.ImportLedger
import io.github.onlinemo.meloxwatch.data.imports.ImportLimits
import io.github.onlinemo.meloxwatch.data.imports.ImportPhase
import io.github.onlinemo.meloxwatch.data.imports.ImportRecord
import io.github.onlinemo.meloxwatch.data.imports.ImportRecoveryPage
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.imports.TransferImportLedger
import io.github.onlinemo.meloxwatch.data.imports.TransferImportRecord
import io.github.onlinemo.meloxwatch.data.imports.TransferImportRecoveryPage
import io.github.onlinemo.meloxwatch.data.imports.TransferImportSelection
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferCommitClaim
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferPermit
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferRoomLedger
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Shares the library owner and mutex. No paths are opened and no file operation happens here. */
class RoomImportLedger(
    private val repository: RoomLocalLibraryRepository,
    private val ownedAuthority: String = BuildConfig.APPLICATION_ID + ".owned-audio",
) : ImportLedger, TransferImportLedger {
    private val dao get() = repository.dao
    private val transferDao get() = repository.database.transferredImportDao()
    private val transferLedger by lazy { TransferRoomLedger(repository.database) }

    override suspend fun record(operationId: String): ImportRecord? = access {
        validateId(operationId)
        repository.libraryTransaction { dao.importRecord(operationId)?.model() }
    }

    override suspend fun begin(intent: ImportIntent): ImportRecord = access {
        validateIntent(intent)
        repository.libraryTransaction {
            if (transferDao.transferredImportRecord(intent.operationId) != null) fail(ImportIssue.CONFLICT)
            val existing = dao.importRecord(intent.operationId)
            if (existing != null) {
                val record = existing.model()
                if (record.intent != intent) fail(ImportIssue.CONFLICT)
                return@libraryTransaction record
            }
            val value = LibraryImportEntity(intent.operationId, LibraryCodec.source(intent.source), LibraryCodec.candidate(intent.candidate),
                intent.sourceIdentity, intent.providerVersion, intent.fileToken, ImportPhase.COPYING.name, 0L, null, null, null,
                null, null, null, null, null, null)
            dao.insertImport(value)
            value.model()
        }
    }

    override suspend fun recordVerified(operationId: String, actualBytes: Long, sha256: String, metadata: LocalAudioMetadata): ImportRecord = access {
        validateId(operationId)
        if (actualBytes !in 1..ImportLimits.QUOTA_BYTES || !sha256.matches(Regex("[0-9a-f]{64}"))) fail(ImportIssue.INTEGRITY_FAILED)
        repository.libraryTransaction {
            val old = stored(operationId)
            val record = old.model()
            if (record.intent.candidate.sizeBytes != null && record.intent.candidate.sizeBytes != actualBytes) fail(ImportIssue.INTEGRITY_FAILED)
            if (record.phase in setOf(ImportPhase.VERIFIED, ImportPhase.INSTALLED, ImportPhase.COMMITTED)) {
                if (record.actualBytes != actualBytes || record.sha256 != sha256 || record.metadata != metadata) fail(ImportIssue.CONFLICT)
                return@libraryTransaction record
            }
            if (record.phase != ImportPhase.COPYING) fail(ImportIssue.CONFLICT)
            val next = old.copy(phase = ImportPhase.VERIFIED.name, actualBytes = actualBytes, sha256 = sha256,
                metadata = LibraryCodec.metadata(metadata), issue = null)
            dao.updateImport(next)
            next.model()
        }
    }

    override suspend fun markInstalled(operationId: String): ImportRecord = access {
        validateId(operationId)
        repository.libraryTransaction {
            val old = stored(operationId)
            val record = old.model()
            if (record.phase in setOf(ImportPhase.INSTALLED, ImportPhase.COMMITTED)) return@libraryTransaction record
            if (record.phase != ImportPhase.VERIFIED) fail(ImportIssue.CONFLICT)
            val next = old.copy(phase = ImportPhase.INSTALLED.name, issue = null)
            dao.updateImport(next)
            next.model()
        }
    }

    override suspend fun commitInstalled(operationId: String, ownedSpec: LocalSourceSpec, grant: LocalGrantObservation,
        ownedCandidate: ObservedCandidate): ImportRecord = access {
        validateId(operationId)
        repository.libraryTransaction(finalPublication = true, cleanAfter = true) {
            val old = stored(operationId)
            val record = old.model()
            validateOwned(record, ownedSpec, grant, ownedCandidate)
            if (record.phase == ImportPhase.COMMITTED) {
                if (old.ownedSpec?.let(LibraryCodec::source) != ownedSpec || old.ownedGrant?.let(LibraryCodec::grant) != grant ||
                    old.ownedCandidate?.let(LibraryCodec::candidate) != ownedCandidate) fail(ImportIssue.CONFLICT)
                return@libraryTransaction record
            }
            if (record.phase != ImportPhase.INSTALLED) fail(ImportIssue.CONFLICT)
            val track = repository.commitOwnedCopyUnlocked(ownedSpec, grant, ownedCandidate, record.metadata ?: fail(ImportIssue.RECOVERY_REQUIRED))
            val next = old.copy(phase = ImportPhase.COMMITTED.name, issue = null, trackProviderId = track.providerId, trackId = track.trackId,
                ownedSourceId = ownedSpec.sourceId, ownedSpec = LibraryCodec.source(ownedSpec), ownedGrant = LibraryCodec.grant(grant),
                ownedCandidate = LibraryCodec.candidate(ownedCandidate))
            dao.updateImport(next)
            next.model()
        }
    }

    override suspend fun markUnfinished(operationId: String, phase: ImportPhase, issue: ImportIssue): ImportRecord = access {
        validateId(operationId)
        if (phase !in setOf(ImportPhase.CANCELLED, ImportPhase.FAILED, ImportPhase.INSTALLED)) fail(ImportIssue.INVALID_INPUT)
        if (phase == ImportPhase.CANCELLED && issue != ImportIssue.CANCELLED) fail(ImportIssue.INVALID_INPUT)
        if (phase == ImportPhase.INSTALLED && issue != ImportIssue.RECOVERY_REQUIRED) fail(ImportIssue.INVALID_INPUT)
        repository.libraryTransaction {
            val old = stored(operationId)
            val record = old.model()
            if (record.phase in setOf(ImportPhase.COMMITTED, ImportPhase.CANCELLED, ImportPhase.FAILED)) return@libraryTransaction record
            if (record.phase == ImportPhase.INSTALLED && phase != ImportPhase.INSTALLED) fail(ImportIssue.CONFLICT)
            if (record.phase != ImportPhase.INSTALLED && phase == ImportPhase.INSTALLED) fail(ImportIssue.CONFLICT)
            val next = old.copy(phase = phase.name, issue = issue.name)
            dao.updateImport(next)
            next.model()
        }
    }

    override suspend fun pending(afterOperationId: String?, limit: Int): ImportRecoveryPage = access {
        if (afterOperationId != null) validateId(afterOperationId)
        if (limit !in 1..ImportLimits.RECOVERY_PAGE_SIZE) fail(ImportIssue.INVALID_INPUT)
        repository.libraryTransaction {
            val rows = dao.pendingImports(afterOperationId, limit)
            val last = rows.lastOrNull()?.operationId
            val more = last != null && dao.pendingImports(last, 1).isNotEmpty()
            ImportRecoveryPage(rows.map { it.model() }, last.takeIf { more })
        }
    }

    override suspend fun recordTransferred(operationId: String): TransferImportRecord? = transferAccess {
        validateId(operationId)
        repository.libraryTransaction { transferDao.transferredImportRecord(operationId)?.transferModel() }
    }

    override suspend fun beginTransferred(selection: TransferImportSelection): TransferImportRecord = transferAccess {
        validateId(selection.operationId)
        repository.libraryTransaction {
            if (dao.importRecord(selection.operationId) != null) transferFail(TransferIssue.CONFLICT)
            val old = transferDao.transferredImportRecord(selection.operationId)
            if (old != null) return@libraryTransaction matchedTransferred(old, selection)
            val declaration = selection.declaration
            val value = LibraryTransferredImportEntity(
                operationId = selection.operationId, protocolSchema = TransferLimits.SCHEMA,
                operationKind = selection.binding.kind.wire,
                targetFingerprint = selection.binding.target.forLocalDisplay(),
                peerFingerprint = selection.binding.peer.forLocalDisplay(),
                payloadHash = selection.binding.payloadHash.forLocalStorage(),
                displayName = declaration.displayName, mediaType = declaration.mediaType,
                declaredBytes = declaration.declaredBytes, declaredSha256 = declaration.sha256.forLocalStorage(),
                expectedRevision = declaration.expectedRevision, fileToken = selection.fileToken,
                phase = ImportPhase.COPYING.name, actualBytes = 0L, sha256 = null, metadata = null, issue = null,
                trackProviderId = null, trackId = null, ownedSourceId = null, ownedSpec = null,
                ownedGrant = null, ownedCandidate = null, committedRevision = null,
            )
            transferDao.insertTransferredImport(value)
            value.transferModel()
        }
    }

    override suspend fun restartTransferred(selection: TransferImportSelection): TransferImportRecord = transferAccess {
        repository.libraryTransaction {
            val old = storedTransferred(selection.operationId)
            val record = matchedTransferred(old, selection)
            if (record.phase !in setOf(ImportPhase.COPYING, ImportPhase.VERIFIED, ImportPhase.CANCELLED, ImportPhase.FAILED)) {
                transferFail(TransferIssue.CONFLICT)
            }
            val next = old.copy(phase = ImportPhase.COPYING.name, actualBytes = 0L, sha256 = null, metadata = null, issue = null)
            transferDao.updateTransferredImport(next)
            next.transferModel()
        }
    }

    override suspend fun verifyTransferred(selection: TransferImportSelection, actualBytes: Long, sha256: String,
        metadata: LocalAudioMetadata): TransferImportRecord = transferAccess {
        if (actualBytes !in 1..ImportLimits.QUOTA_BYTES || sha256 != selection.declaration.sha256.forLocalStorage() ||
            selection.declaration.declaredBytes != null && selection.declaration.declaredBytes != actualBytes) {
            transferFail(TransferIssue.INTEGRITY_FAILED)
        }
        repository.libraryTransaction {
            val old = storedTransferred(selection.operationId)
            val record = matchedTransferred(old, selection)
            if (record.phase in setOf(ImportPhase.VERIFIED, ImportPhase.INSTALLED, ImportPhase.COMMITTED)) {
                if (record.actualBytes != actualBytes || record.sha256 != sha256 || record.metadata != metadata) transferFail(TransferIssue.CONFLICT)
                return@libraryTransaction record
            }
            if (record.phase != ImportPhase.COPYING) transferFail(TransferIssue.CONFLICT)
            val next = old.copy(phase = ImportPhase.VERIFIED.name, actualBytes = actualBytes, sha256 = sha256,
                metadata = LibraryCodec.metadata(metadata), issue = null)
            transferDao.updateTransferredImport(next)
            next.transferModel()
        }
    }

    override suspend fun installedTransferred(selection: TransferImportSelection): TransferImportRecord = transferAccess {
        repository.libraryTransaction {
            val old = storedTransferred(selection.operationId)
            val record = matchedTransferred(old, selection)
            if (record.phase in setOf(ImportPhase.INSTALLED, ImportPhase.COMMITTED)) return@libraryTransaction record
            if (record.phase != ImportPhase.VERIFIED) transferFail(TransferIssue.CONFLICT)
            val next = old.copy(phase = ImportPhase.INSTALLED.name, issue = null)
            transferDao.updateTransferredImport(next)
            next.transferModel()
        }
    }

    override suspend fun requireCommittedTransferred(selection: TransferImportSelection): TransferReceipt = transferAccess {
        repository.libraryTransaction {
            val original = matchedTransferred(storedTransferred(selection.operationId), selection)
            val receipt = original.receipt() ?: transferFail(TransferIssue.RECOVERY_REQUIRED)
            transferLedger.requireRecordedInCurrentTransaction(receipt)
        }
    }

    override suspend fun commitTransferred(selection: TransferImportSelection, permit: TransferPermit,
        ownedSpec: LocalSourceSpec, grant: LocalGrantObservation, ownedCandidate: ObservedCandidate,
        commitBoundary: () -> TransferCommitClaim): TransferImportRecord = transferAccess {
        repository.libraryTransaction(finalPublication = true, cleanAfter = true) {
            transferLedger.bindInCurrentTransaction(selection.binding, permit)
            val old = storedTransferred(selection.operationId)
            val record = matchedTransferred(old, selection)
            validateTransferredOwned(record, ownedSpec, grant, ownedCandidate)
            if (record.phase == ImportPhase.COMMITTED) {
                permit.checkActive()
                if (old.ownedSpec?.let(LibraryCodec::source) != ownedSpec || old.ownedGrant?.let(LibraryCodec::grant) != grant ||
                    old.ownedCandidate?.let(LibraryCodec::candidate) != ownedCandidate) transferFail(TransferIssue.CONFLICT)
                val receipt = record.receipt() ?: transferFail(TransferIssue.RECOVERY_REQUIRED)
                val persisted = transferLedger.committedInCurrentTransaction(selection.binding, permit)
                    ?: transferFail(TransferIssue.RECOVERY_REQUIRED)
                if (!persisted.binding.sameOperation(receipt.binding) || persisted.result != receipt.result ||
                    persisted.revision != receipt.revision || persisted.resultId != receipt.resultId ||
                    persisted.actualBytes != receipt.actualBytes) transferFail(TransferIssue.RECOVERY_REQUIRED)
                return@libraryTransaction record
            }
            if (record.phase != ImportPhase.INSTALLED) transferFail(TransferIssue.CONFLICT)
            // This claim belongs to the actual Room publication transaction. Revocation before
            // it blocks publication; afterwards the accepted transaction and receipt may finish.
            if (permit.target != selection.binding.target || permit.peer != selection.binding.peer ||
                permit.capability != selection.binding.kind.capability) transferFail(TransferIssue.WRONG_TARGET)
            permit.checkActive()
            val claim = commitBoundary()
            if (!claim.binding.sameOperation(selection.binding)) transferFail(TransferIssue.CONFLICT)
            val track = repository.commitOwnedCopyUnlocked(ownedSpec, grant, ownedCandidate,
                record.metadata ?: transferFail(TransferIssue.RECOVERY_REQUIRED))
            val revision = dao.meta()?.revision ?: transferFail(TransferIssue.RECOVERY_REQUIRED)
            val next = old.copy(phase = ImportPhase.COMMITTED.name, issue = null,
                trackProviderId = track.providerId, trackId = track.trackId, ownedSourceId = ownedSpec.sourceId,
                ownedSpec = LibraryCodec.source(ownedSpec), ownedGrant = LibraryCodec.grant(grant),
                ownedCandidate = LibraryCodec.candidate(ownedCandidate), committedRevision = revision)
            transferDao.updateTransferredImport(next)
            val published = next.transferModel()
            transferLedger.recordInCurrentTransaction(published.receipt() ?: transferFail(TransferIssue.RECOVERY_REQUIRED), claim)
            published
        }
    }

    override suspend fun unfinishedTransferred(selection: TransferImportSelection, phase: ImportPhase,
        issue: TransferIssue): TransferImportRecord = transferAccess {
        if (phase !in setOf(ImportPhase.CANCELLED, ImportPhase.FAILED, ImportPhase.INSTALLED) ||
            phase == ImportPhase.INSTALLED && issue != TransferIssue.RECOVERY_REQUIRED) transferFail(TransferIssue.INVALID_INPUT)
        repository.libraryTransaction {
            val old = storedTransferred(selection.operationId)
            val record = matchedTransferred(old, selection)
            if (record.phase == ImportPhase.COMMITTED) return@libraryTransaction record
            if (record.phase == ImportPhase.INSTALLED && phase != ImportPhase.INSTALLED ||
                record.phase != ImportPhase.INSTALLED && phase == ImportPhase.INSTALLED) transferFail(TransferIssue.CONFLICT)
            val next = old.copy(phase = phase.name, issue = issue.name)
            transferDao.updateTransferredImport(next)
            next.transferModel()
        }
    }

    override suspend fun pendingTransferred(afterOperationId: String?, limit: Int): TransferImportRecoveryPage = transferAccess {
        if (afterOperationId != null) validateId(afterOperationId)
        if (limit !in 1..ImportLimits.RECOVERY_PAGE_SIZE) transferFail(TransferIssue.INVALID_INPUT)
        repository.libraryTransaction {
            val rows = transferDao.pendingTransferredImports(afterOperationId, limit)
            val last = rows.lastOrNull()?.operationId
            val more = last != null && transferDao.pendingTransferredImports(last, 1).isNotEmpty()
            TransferImportRecoveryPage(rows.map { it.transferModel() }, last.takeIf { more })
        }
    }

    private suspend fun storedTransferred(operationId: String): LibraryTransferredImportEntity {
        validateId(operationId)
        return transferDao.transferredImportRecord(operationId) ?: transferFail(TransferIssue.INVALID_INPUT)
    }
    private fun matchedTransferred(value: LibraryTransferredImportEntity, selection: TransferImportSelection): TransferImportRecord {
        val record = value.transferModel()
        if (!record.selection.sameAs(selection)) transferFail(TransferIssue.CONFLICT)
        return record
    }

    private fun validateTransferredOwned(record: TransferImportRecord, spec: LocalSourceSpec,
        grant: LocalGrantObservation, candidate: ObservedCandidate) {
        if (record.phase !in setOf(ImportPhase.INSTALLED, ImportPhase.COMMITTED)) transferFail(TransferIssue.CONFLICT)
        val selection = record.selection
        val uri = Uri.Builder().scheme("content").authority(ownedAuthority).appendPath(selection.fileToken).build().toString()
        val expectedSpec = LocalSourceSpec("transfer:${selection.operationId}", LocalSourceKind.APP_OWNED, uri,
            authority = ownedAuthority, rootObjectId = selection.fileToken)
        val expectedGrant = LocalGrantObservation(appOwned = true, read = ObservedCapability.AVAILABLE,
            offlineRead = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.UNAVAILABLE,
            persistedRead = ObservedCapability.UNAVAILABLE, persistedWrite = ObservedCapability.UNAVAILABLE)
        if (spec != expectedSpec || grant != expectedGrant) transferFail(TransferIssue.INVALID_INPUT)
        val expectedCandidate = ObservedCandidate(spec.sourceId, selection.fileToken, uri, null,
            selection.declaration.displayName, record.metadata?.mimeType, LocalNodeKind.AUDIO, record.actualBytes,
            providerContentToken = "sha256:${record.sha256}", entityProof = "owned:$ownedAuthority:${selection.fileToken}")
        if (candidate != expectedCandidate) transferFail(TransferIssue.INTEGRITY_FAILED)
    }

    private suspend fun <T> transferAccess(action: suspend () -> T): T = try { action() }
    catch (error: CancellationException) { throw error }
    catch (error: TransferException) { throw error }
    catch (error: ImportException) {
        throw TransferException(when (error.issue) {
            ImportIssue.BUSY -> TransferIssue.BUSY
            ImportIssue.INVALID_INPUT -> TransferIssue.INVALID_INPUT
            ImportIssue.CONFLICT -> TransferIssue.CONFLICT
            ImportIssue.INTEGRITY_FAILED -> TransferIssue.INTEGRITY_FAILED
            else -> TransferIssue.RECOVERY_REQUIRED
        })
    }
    catch (error: LibraryException) {
        throw TransferException(when (error.issue) {
            LibraryIssue.BUSY -> TransferIssue.BUSY
            LibraryIssue.CONFLICT, LibraryIssue.SOURCE_CHANGED -> TransferIssue.CONFLICT
            LibraryIssue.STORAGE_FULL -> TransferIssue.STORAGE_FULL
            LibraryIssue.INVALID_INPUT -> TransferIssue.INVALID_INPUT
            else -> TransferIssue.RECOVERY_REQUIRED
        })
    }
    catch (_: IllegalArgumentException) { throw TransferException(TransferIssue.RECOVERY_REQUIRED) }
    private fun transferFail(issue: TransferIssue): Nothing = throw TransferException(issue)

    private suspend fun stored(operationId: String) = dao.importRecord(operationId) ?: fail(ImportIssue.INVALID_INPUT)

    private fun validateIntent(intent: ImportIntent) {
        validateId(intent.operationId)
        if (!contentUri(intent.source.uri) || !contentUri(intent.candidate.uri) || intent.candidate.directory ||
            intent.candidate.displayName.length > 512) fail(ImportIssue.INVALID_INPUT)
    }

    private fun validateOwned(record: ImportRecord, spec: LocalSourceSpec, grant: LocalGrantObservation, candidate: ObservedCandidate) {
        if (record.phase !in setOf(ImportPhase.INSTALLED, ImportPhase.COMMITTED)) fail(ImportIssue.CONFLICT)
        val token = record.intent.fileToken
        val uri = Uri.Builder().scheme("content").authority(ownedAuthority).appendPath(token).build().toString()
        val expectedSpec = LocalSourceSpec("import:${record.intent.operationId}", LocalSourceKind.APP_OWNED, uri,
            authority = ownedAuthority, rootObjectId = token)
        if (spec != expectedSpec || !grant.appOwned || grant.read != ObservedCapability.AVAILABLE ||
            grant.offlineRead != ObservedCapability.AVAILABLE || grant.persistedRead != ObservedCapability.UNAVAILABLE ||
            grant.persistedWrite != ObservedCapability.UNAVAILABLE || grant.acceptedPersistedFlags != 0 || grant.returnedFlags != 0 ||
            grant.write == ObservedCapability.AVAILABLE || grant.delete == ObservedCapability.AVAILABLE ||
            grant.rename == ObservedCapability.AVAILABLE || grant.move == ObservedCapability.AVAILABLE) fail(ImportIssue.INVALID_INPUT)
        if (candidate.sourceId != spec.sourceId || candidate.objectId != token || candidate.uri != uri || candidate.parentObjectId != null ||
            candidate.kind != LocalNodeKind.AUDIO || candidate.displayName != record.intent.candidate.displayName ||
            candidate.sizeBytes != record.actualBytes || candidate.providerContentToken != "sha256:${record.sha256}" ||
            candidate.entityProof != "owned:$ownedAuthority:$token") fail(ImportIssue.INTEGRITY_FAILED)
    }

    private fun contentUri(value: String): Boolean = value.toUri().let { it.scheme == "content" && !it.authority.isNullOrBlank() }
    private fun validateId(value: String) {
        val valid = try { value.length == 36 && UUID.fromString(value).toString() == value } catch (_: IllegalArgumentException) { false }
        if (!valid) fail(ImportIssue.INVALID_INPUT)
    }
    private suspend fun <T> access(action: suspend () -> T): T = try { action() }
    catch (error: CancellationException) { throw error }
    catch (error: ImportException) { throw error }
    catch (error: LibraryException) {
        val issue = when (error.issue) {
            LibraryIssue.BUSY -> ImportIssue.BUSY
            LibraryIssue.CONFLICT -> ImportIssue.CONFLICT
            LibraryIssue.SOURCE_CHANGED -> ImportIssue.SOURCE_CHANGED
            LibraryIssue.PERMISSION_REVOKED -> ImportIssue.PERMISSION_REVOKED
            LibraryIssue.SOURCE_UNAVAILABLE, LibraryIssue.FILE_MISSING -> ImportIssue.SOURCE_UNAVAILABLE
            LibraryIssue.INVALID_INPUT -> ImportIssue.INVALID_INPUT
            LibraryIssue.STORAGE_FULL -> ImportIssue.STORAGE_FULL
            else -> ImportIssue.RECOVERY_REQUIRED
        }
        throw ImportException(issue, error)
    }
    private fun fail(issue: ImportIssue): Nothing = throw ImportException(issue)
}
