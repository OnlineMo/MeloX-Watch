package io.github.onlinemo.meloxwatch.data.themes

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 接收包是持久本机候选，不能作为远端直接应用的选择。 */
@Entity(
    tableName = "transferred_theme_candidates",
    indices = [Index(value = ["state", "operationId"], name = "index_transferred_theme_candidates_state_operationId")],
)
data class TransferredThemeCandidateEntity(
    @PrimaryKey val operationId: String,
    val protocolSchema: Int,
    val targetFingerprint: String,
    val peerFingerprint: String,
    val payloadHash: String,
    val packageSha256: String,
    val packageBytes: Long,
    val manifestSha256: String,
    val themeId: String,
    val displayName: String,
    val receivedThemeRevision: Long,
    val state: String,
    val localApplyOperationId: String?,
    val localPreviewToken: String?,
    val localExpectedRevision: Long?,
    val localReplacementId: String?,
    val localReplacementThemeId: String?,
    val localReplacementManifestSha256: String?,
    val fontLicenseAcknowledgmentVersion: Int?,
    val appliedInstallationId: String?,
    val appliedRevision: Long?,
) {
    override fun toString(): String = "<local-received-theme-record>"
}

internal enum class TransferredThemeState { RECEIVED, APPLYING, APPLIED, DISCARDED }
