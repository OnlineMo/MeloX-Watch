package io.github.onlinemo.meloxwatch.data.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.onlinemo.meloxwatch.data.imports.ImportPhase
import io.github.onlinemo.meloxwatch.data.imports.TransferImportRecord
import io.github.onlinemo.meloxwatch.data.imports.TransferImportSelection
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadDeclaration
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import io.github.onlinemo.meloxwatch.model.TrackKey

/** Local durable identity; tokens, session credentials and network addresses are deliberately absent. */
@Entity(
    tableName = "transferred_import_operations",
    foreignKeys = [ForeignKey(entity = LibraryTrackEntity::class, parentColumns = ["trackId"],
        childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)],
    indices = [
        Index(value = ["fileToken"], unique = true, name = "index_transferred_import_operations_fileToken"),
        Index(value = ["phase", "operationId"], name = "index_transferred_import_operations_phase_operationId"),
        Index(value = ["ownedSourceId"], unique = true, name = "index_transferred_import_operations_ownedSourceId"),
        Index(value = ["trackId"], name = "index_transferred_import_operations_trackId"),
    ],
)
data class LibraryTransferredImportEntity(
    @PrimaryKey val operationId: String,
    val protocolSchema: Int,
    val operationKind: Int,
    val targetFingerprint: String,
    val peerFingerprint: String,
    val payloadHash: String,
    val displayName: String,
    val mediaType: String,
    val declaredBytes: Long?,
    val declaredSha256: String,
    val expectedRevision: Long,
    val fileToken: String,
    val phase: String,
    val actualBytes: Long,
    val sha256: String?,
    val metadata: String?,
    val issue: String?,
    val trackProviderId: String?,
    val trackId: String?,
    val ownedSourceId: String?,
    val ownedSpec: String?,
    val ownedGrant: String?,
    val ownedCandidate: String?,
    val committedRevision: Long?,
)

internal fun LibraryTransferredImportEntity.transferModel(): TransferImportRecord {
    // 升级保留旧 payload 摘要，已有音乐无需重传。
    require(protocolSchema in 1..TransferLimits.SCHEMA && operationKind == TransferOperationKind.AUDIO.wire)
    require(fileToken == operationId)
    val selection = TransferImportSelection(
        TransferOperationBinding(operationId, TransferOperationKind.AUDIO,
            TransferFingerprint.fromWire(transferHex(targetFingerprint)),
            TransferFingerprint.fromWire(transferHex(peerFingerprint)), TransferHash.fromWire(transferHex(payloadHash))),
        TransferUploadDeclaration(TransferUploadKind.AUDIO, displayName, mediaType, declaredBytes,
            TransferHash.fromWire(transferHex(declaredSha256)), expectedRevision),
    )
    val currentPhase = ImportPhase.valueOf(phase)
    require((currentPhase == ImportPhase.COMMITTED) == (trackId != null))
    val track = trackId?.let {
        require(trackProviderId == TrackKey.LOCAL_PROVIDER && ownedSourceId != null && ownedSpec != null &&
            ownedGrant != null && ownedCandidate != null && committedRevision != null)
        TrackKey(TrackKey.LOCAL_PROVIDER, it)
    }
    return TransferImportRecord(selection, currentPhase, actualBytes, sha256, metadata?.let(LibraryCodec::metadata),
        issue?.let(TransferIssue::valueOf), track, committedRevision)
}

private fun transferHex(value: String): ByteArray {
    require(value.matches(Regex("[0-9a-f]{64}")))
    return ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
