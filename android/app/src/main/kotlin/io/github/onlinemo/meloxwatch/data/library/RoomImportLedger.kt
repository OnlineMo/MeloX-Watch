package io.github.onlinemo.meloxwatch.data.library

import android.net.Uri
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
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Shares the library owner and mutex. No paths are opened and no file operation happens here. */
class RoomImportLedger(
    private val repository: RoomLocalLibraryRepository,
    private val ownedAuthority: String = BuildConfig.APPLICATION_ID + ".owned-audio",
) : ImportLedger {
    private val dao get() = repository.dao

    override suspend fun record(operationId: String): ImportRecord? = access {
        validateId(operationId)
        repository.libraryTransaction { dao.importRecord(operationId)?.model() }
    }

    override suspend fun begin(intent: ImportIntent): ImportRecord = access {
        validateIntent(intent)
        repository.libraryTransaction {
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

    private fun contentUri(value: String): Boolean = Uri.parse(value).let { it.scheme == "content" && !it.authority.isNullOrBlank() }
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
