package io.github.onlinemo.meloxwatch.data.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.onlinemo.meloxwatch.data.imports.ImportIntent
import io.github.onlinemo.meloxwatch.data.imports.ImportIssue
import io.github.onlinemo.meloxwatch.data.imports.ImportPhase
import io.github.onlinemo.meloxwatch.data.imports.ImportRecord
import io.github.onlinemo.meloxwatch.model.TrackKey

/** Immutable selection and verified bytes survive the file/Room commit boundary. */
@Entity(
    tableName = "import_operations",
    foreignKeys = [ForeignKey(entity = LibraryTrackEntity::class, parentColumns = ["trackId"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)],
    indices = [
        Index(value = ["fileToken"], unique = true, name = "index_import_operations_fileToken"),
        Index(value = ["phase", "operationId"], name = "index_import_operations_phase_operationId"),
        Index(value = ["ownedSourceId"], unique = true, name = "index_import_operations_ownedSourceId"),
        Index(value = ["trackId"], name = "index_import_operations_trackId"),
    ],
)
data class LibraryImportEntity(
    @PrimaryKey val operationId: String,
    val sourceSpec: String,
    val sourceCandidate: String,
    val sourceIdentity: String,
    val providerVersion: String?,
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
)

internal fun LibraryImportEntity.model(): ImportRecord {
    require(fileToken == operationId)
    val phase = ImportPhase.valueOf(phase)
    require((phase == ImportPhase.COMMITTED) == (trackId != null))
    val track = trackId?.let {
        require(trackProviderId == TrackKey.LOCAL_PROVIDER && ownedSourceId != null && ownedSpec != null && ownedGrant != null && ownedCandidate != null)
        TrackKey(TrackKey.LOCAL_PROVIDER, it)
    }
    return ImportRecord(
        ImportIntent(operationId, LibraryCodec.source(sourceSpec), LibraryCodec.candidate(sourceCandidate), sourceIdentity, providerVersion),
        phase, actualBytes, sha256, metadata?.let(LibraryCodec::metadata), issue?.let(ImportIssue::valueOf), track,
    )
}
