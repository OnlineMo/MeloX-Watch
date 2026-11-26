package io.github.onlinemo.meloxwatch.data.metadata

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackEntity

/** Clearing retains the revision row, so a preview from before the clear cannot pass an ABA check. */
@Entity(
    tableName = "local_metadata_associations",
    primaryKeys = ["trackId", "purpose"],
    foreignKeys = [ForeignKey(
        entity = LibraryTrackEntity::class,
        parentColumns = ["trackId"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["trackId"]), Index(value = ["assetId", "purpose"])],
)
data class LocalMetadataAssociationEntity(
    val trackId: String,
    val purpose: String,
    val schemaVersion: Int,
    val revision: Long,
    val assetId: String?,
    val sha256: String?,
    val byteCount: Long?,
    val provenance: String?,
)

/** Bounded projections keep damaged values out of allocations and file-recovery authority. */
data class LocalMetadataAssociationRow(
    val validTypes: Boolean,
    val schemaVersion: Long,
    val revision: Long,
    val assetId: String?,
    val sha256: String?,
    val byteCount: Long?,
    val provenance: String?,
)
