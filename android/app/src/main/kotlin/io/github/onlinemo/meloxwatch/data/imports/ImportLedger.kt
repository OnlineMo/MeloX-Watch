package io.github.onlinemo.meloxwatch.data.imports

import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID

object ImportLimits {
    const val QUOTA_BYTES = 1024L * 1024 * 1024
    const val FREE_RESERVE_BYTES = 512L * 1024 * 1024
    const val BUFFER_BYTES = 64 * 1024
    const val RECOVERY_PAGE_SIZE = 100
}

enum class ImportPhase { COPYING, VERIFIED, INSTALLED, COMMITTED, CANCELLED, FAILED }
enum class ImportIssue {
    BUSY, INVALID_INPUT, CONFLICT, PERMISSION_DENIED, PERMISSION_REVOKED,
    SOURCE_UNAVAILABLE, SOURCE_CHANGED, NOT_AUDIO, METADATA_UNREADABLE, UNSUPPORTED_FORMAT,
    STORAGE_FULL, QUOTA_EXCEEDED, INTEGRITY_FAILED, INTERRUPTED, CANCELLED, RECOVERY_REQUIRED,
}

class ImportException(val issue: ImportIssue, cause: Throwable? = null) : Exception(issue.name, cause)

/** A retry binds to the same selected object and operation, never just its filename or hash. */
data class ImportIntent(
    val operationId: String,
    val source: LocalSourceSpec,
    val candidate: ObservedCandidate,
    val sourceIdentity: String,
    val providerVersion: String? = null,
) {
    init {
        require(operationId.length == 36 && UUID.fromString(operationId).toString() == operationId)
        require(candidate.sourceId == source.sourceId && !candidate.directory)
        require(sourceIdentity.isNotBlank() && sourceIdentity.length <= 24_576)
    }

    val fileToken: String get() = operationId
}

/** Extra space is unknown when the provider cannot supply a reliable length. */
data class ImportPreview(
    val intent: ImportIntent,
    val declaredBytes: Long?,
    val ownedBytes: Long,
    val availableBytes: Long,
    val quotaBytes: Long = ImportLimits.QUOTA_BYTES,
    val reserveBytes: Long = ImportLimits.FREE_RESERVE_BYTES,
)

data class ImportRecord(
    val intent: ImportIntent,
    val phase: ImportPhase,
    val actualBytes: Long = 0,
    val sha256: String? = null,
    val metadata: LocalAudioMetadata? = null,
    val issue: ImportIssue? = null,
    val trackKey: TrackKey? = null,
) {
    init {
        require(actualBytes in 0..ImportLimits.QUOTA_BYTES)
        require(sha256 == null || sha256.matches(Regex("[0-9a-f]{64}")))
        require(phase !in setOf(ImportPhase.VERIFIED, ImportPhase.INSTALLED, ImportPhase.COMMITTED) ||
            (actualBytes > 0 && sha256 != null && metadata != null))
        require(phase != ImportPhase.COMMITTED || trackKey != null)
    }
}

data class ImportRecoveryPage(val records: List<ImportRecord>, val nextOperationId: String?)

/** File I/O stays outside Room. Installation and library publication have separate durable phases. */
interface ImportLedger {
    suspend fun record(operationId: String): ImportRecord?
    suspend fun begin(intent: ImportIntent): ImportRecord
    suspend fun recordVerified(operationId: String, actualBytes: Long, sha256: String, metadata: LocalAudioMetadata): ImportRecord
    suspend fun markInstalled(operationId: String): ImportRecord
    suspend fun commitInstalled(
        operationId: String,
        ownedSpec: LocalSourceSpec,
        grant: LocalGrantObservation,
        ownedCandidate: ObservedCandidate,
    ): ImportRecord

    /** CANCELLED/FAILED may retire staging only. INSTALLED may only retain a recovery issue. */
    suspend fun markUnfinished(operationId: String, phase: ImportPhase, issue: ImportIssue): ImportRecord
    suspend fun pending(afterOperationId: String? = null, limit: Int = ImportLimits.RECOVERY_PAGE_SIZE): ImportRecoveryPage
}
