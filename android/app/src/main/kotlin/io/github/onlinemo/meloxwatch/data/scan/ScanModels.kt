package io.github.onlinemo.meloxwatch.data.scan

import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue

enum class ScanMode { FULL, INCREMENTAL }

data class ScanToken(
    val scanId: String,
    val sourceId: String,
    val sourceRevision: Long,
    val authEpoch: Long,
    val rulesRevision: Long,
    val mode: ScanMode,
)

data class ScanCounters(
    val seen: Long = 0,
    val processed: Long = 0,
    val added: Long = 0,
    val skipped: Long = 0,
    val failed: Long = 0,
)

/** Absence reconciliation requires all facts, not merely a COMPLETED label. */
data class ScanCoverage(
    val enumerationComplete: Boolean = false,
    val authorizationValid: Boolean = false,
    val rulesUnchanged: Boolean = false,
    val sourceUnchanged: Boolean = false,
    val excludedSubtrees: Boolean = false,
) {
    val permitsAbsence: Boolean
        get() = enumerationComplete && authorizationValid && rulesUnchanged && sourceUnchanged && !excludedSubtrees
}

enum class ScanPhase {
    PREPARING, RUNNING, STOPPING, STAGING_ABSENCE, COMPLETED,
    CANCELLED, PAUSED_BACKGROUND, FAILED, INTERRUPTED,
}

data class ScanState(
    val token: ScanToken,
    val phase: ScanPhase,
    val counters: ScanCounters = ScanCounters(),
    val coverage: ScanCoverage = ScanCoverage(),
    val issue: SourceReadIssue? = null,
)

data class MetadataOutcome(
    val objectId: String,
    val metadata: LocalAudioMetadata? = null,
    val issue: SourceReadIssue? = null,
    val verifiedContentToken: String? = null,
) {
    init {
        require(objectId.isNotBlank())
        require((metadata == null) != (issue == null))
        require(verifiedContentToken == null || (metadata != null && verifiedContentToken.matches(Regex("sha256:[0-9a-f]{64}"))))
    }
}
