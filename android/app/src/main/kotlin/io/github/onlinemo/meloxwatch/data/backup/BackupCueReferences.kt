package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.TrackKey

internal val LEGACY_CUE_BACKUP_KINDS = setOf(BackupRecordKind.TRACK, BackupRecordKind.QUEUE_ENTRY,
    BackupRecordKind.FAVORITES_MEMBER, BackupRecordKind.PLAYLIST_MEMBER)
internal val CUE_BACKUP_KINDS = LEGACY_CUE_BACKUP_KINDS + BackupMetadataAssociations.KINDS

/** 旧逻辑行没有此字段；若有值，必须包含标识且不授予访问能力。 */
internal fun BackupRecord.portableCueReference(): LocalCueReference? {
    if (kind !in CUE_BACKUP_KINDS) return null
    val value = when (val cell = values["cueReference"]) {
        null, BackupCell.Null -> return null
        is BackupCell.Text -> cell.value
        else -> throw BackupException(BackupIssue.INVALID_INPUT)
    }
    val reference = try { CueReferenceCodec.decode(value, portableOnly = true) }
    catch (failure: IllegalArgumentException) { throw BackupException(BackupIssue.INVALID_INPUT, failure) }
    val key = when {
        kind == BackupRecordKind.TRACK -> TrackKey(text("providerId"), this.key)
        kind in BackupMetadataAssociations.KINDS -> TrackKey(TrackKey.LOCAL_PROVIDER, this.key)
        else -> optionalText("providerId")?.let { TrackKey(it, text("trackId")) }
    }
    if (key == null || key != reference.trackKey) throw BackupException(BackupIssue.INVALID_REFERENCE)
    return reference
}

internal fun portableCueCell(native: String?): BackupCell = if (native == null) BackupCell.Null else {
    val portable = try { CueReferenceCodec.decode(native).portable() }
    catch (failure: IllegalArgumentException) { throw BackupException(BackupIssue.RECOVERY_REQUIRED, failure) }
    BackupCell.Text(CueReferenceCodec.encode(portable))
}
