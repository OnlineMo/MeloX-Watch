package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.metadata.MetadataAssociation
import io.github.onlinemo.meloxwatch.data.metadata.MetadataProvenance
import io.github.onlinemo.meloxwatch.data.metadata.MetadataPurpose
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.CancellationException

/** Portable associations carry content and provenance, never an old provider UUID or access URI. */
internal object BackupMetadataAssociations {
    val KINDS = setOf(BackupRecordKind.METADATA_LYRICS, BackupRecordKind.METADATA_ARTWORK)
    val FIELDS = setOf("revision", "sha256", "bytes", "provenance", "resourceId", "cueReference")

    fun fields(kind: BackupRecordKind): Set<String> = FIELDS +
        if (purpose(kind) == MetadataPurpose.LYRICS) setOf("primaryLinked") else emptySet()

    fun purpose(kind: BackupRecordKind): MetadataPurpose = when (kind) {
        BackupRecordKind.METADATA_LYRICS -> MetadataPurpose.LYRICS
        BackupRecordKind.METADATA_ARTWORK -> MetadataPurpose.ARTWORK
        else -> throw BackupException(BackupIssue.INVALID_INPUT)
    }

    fun kind(purpose: MetadataPurpose): BackupRecordKind = when (purpose) {
        MetadataPurpose.LYRICS -> BackupRecordKind.METADATA_LYRICS
        MetadataPurpose.ARTWORK -> BackupRecordKind.METADATA_ARTWORK
    }

    fun resourceKind(kind: BackupRecordKind): BackupResourceKind = when (purpose(kind)) {
        MetadataPurpose.LYRICS -> BackupResourceKind.LYRICS
        MetadataPurpose.ARTWORK -> BackupResourceKind.IMAGE
    }

    fun resourceId(row: BackupRecord): String? = row.optionalText("sha256")?.let {
        BackupLogicalSchema.resourceId(resourceKind(row.kind), it)
    }

    fun record(value: MetadataAssociation, cue: BackupCell, includeResourceReferences: Boolean,
        primaryLinked: Boolean = false): BackupRecord {
        val kind = kind(value.purpose)
        require(!primaryLinked || value.purpose == MetadataPurpose.LYRICS && value.assetId != null)
        val resourceId = value.sha256?.takeIf { includeResourceReferences && value.assetId != null }?.let {
            BackupLogicalSchema.resourceId(resourceKind(kind), it)
        }
        return BackupRecord(kind, value.trackKey.trackId, mapOf(
            "revision" to BackupCell.Integer(value.revision), "sha256" to backupText(value.sha256),
            "bytes" to backupNumber(value.byteCount), "provenance" to backupText(value.provenance?.encode()),
            "resourceId" to backupText(resourceId), "cueReference" to cue,
        ) + if (value.purpose == MetadataPurpose.LYRICS) mapOf("primaryLinked" to BackupCell.Flag(primaryLinked)) else emptyMap())
    }

    fun decode(row: BackupRecord, assetId: String? = null,
        trackKey: TrackKey = TrackKey(TrackKey.LOCAL_PROVIDER, row.key)): MetadataAssociation = try {
        if (row.kind !in KINDS || row.values.keys != fields(row.kind) || row.key.length > 128 ||
            BackupLogicalSchema.portableHint(row.key) != row.key || trackKey.providerId != TrackKey.LOCAL_PROVIDER) {
            throw BackupException(BackupIssue.INVALID_INPUT)
        }
        val value = MetadataAssociation(trackKey, purpose(row.kind), row.long("revision"), assetId,
            row.optionalText("sha256"), row.optionalLong("bytes"), row.optionalText("provenance")?.let(MetadataProvenance::decode))
        val suppliedResource = row.optionalText("resourceId")
        if (suppliedResource != null && suppliedResource != resourceId(row)) throw BackupException(BackupIssue.INVALID_REFERENCE)
        if (row.kind == BackupRecordKind.METADATA_LYRICS && row.flag("primaryLinked") && value.sha256 == null) {
            throw BackupException(BackupIssue.INVALID_REFERENCE)
        }
        val cue = row.portableCueReference()
        if (row.key.startsWith("cue-v1-") && cue == null) throw BackupException(BackupIssue.INVALID_REFERENCE)
        value
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: BackupException) { throw failure }
    catch (failure: Exception) { throw BackupException(BackupIssue.INVALID_INPUT, failure) }
}
