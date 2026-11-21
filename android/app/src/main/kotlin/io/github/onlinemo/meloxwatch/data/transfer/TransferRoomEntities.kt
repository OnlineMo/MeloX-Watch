package io.github.onlinemo.meloxwatch.data.transfer

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local binding and original receipt; no bearer, address, file location or external URI. */
@Entity(tableName = "transfer_room_operations")
data class TransferRoomOperationEntity(
    @PrimaryKey val operationId: String,
    val protocolSchema: Int,
    val operationKind: Int,
    val targetFingerprint: String,
    val peerFingerprint: String,
    val payloadHash: String,
    val resultKind: Int?,
    val appliedRevision: Long?,
    val resultId: String?,
    val actualBytes: Long?,
)

/** The immutable preview is a bounded proposal, not permission to reuse a later grant. */
@Entity(tableName = "transfer_display_previews", indices = [
    Index(value = ["peerFingerprint", "sessionId"], name = "index_transfer_display_previews_peerFingerprint_sessionId"),
    Index(value = ["processId", "expiresElapsedMs"], name = "index_transfer_display_previews_processId_expiresElapsedMs"),
])
data class TransferDisplayPreviewEntity(
    @PrimaryKey val previewId: String,
    val protocolSchema: Int,
    val targetFingerprint: String,
    val peerFingerprint: String,
    val sessionId: String,
    val grantId: String,
    val grantEpoch: Long,
    val processId: String,
    val expiresElapsedMs: Long,
    val kind: Int,
    val entityId: String,
    val libraryRevision: Long,
    val rulesRevision: Long,
    val currentName: String,
    val proposedName: String,
    val currentTrackCount: Long,
    val patchKind: Int,
    val patchName: String?,
    val patchTrackIds: String,
    val payloadHash: String,
)
