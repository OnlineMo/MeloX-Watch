package io.github.onlinemo.meloxwatch.data.lyrics.store

import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsUserOffset
import io.github.onlinemo.meloxwatch.model.TrackKey

internal fun LyricsTrackPreferences.entity() = LyricsTrackPreferencesEntity(
    trackKey.trackId, LyricsTrackLimits.SCHEMA_VERSION, revision,
    primary?.documentId, primary?.sourceId, primary?.uri, primary?.readFlags, primary?.encoding?.name,
    primary?.confirmedContentSha256,
    translation?.documentId, translation?.sourceId, translation?.uri, translation?.readFlags, translation?.encoding?.name,
    translation?.confirmedContentSha256, userDelayMs,
)

internal fun LyricsTrackRow.decode(trackKey: TrackKey): LyricsTrackPreferences {
    if (!validTypes) lyricsTrackFail(LyricsTrackIssue.CORRUPT_RECORD)
    if (schemaVersion != LyricsTrackLimits.SCHEMA_VERSION.toLong()) lyricsTrackFail(LyricsTrackIssue.UNSUPPORTED_SCHEMA)
    if (revision < 0L || userDelayMs !in -LyricsUserOffset.MAX_DELAY_MS.toLong()..LyricsUserOffset.MAX_DELAY_MS.toLong() ||
        userDelayMs % LyricsUserOffset.STEP_MS != 0L) lyricsTrackFail(LyricsTrackIssue.CORRUPT_RECORD)
    return LyricsTrackPreferences(
        trackKey, revision,
        decodeAssociation(primaryDocumentId, primarySourceId, primaryUri, primaryReadFlags,
            primaryEncoding, primaryConfirmedContentSha256),
        decodeAssociation(translationDocumentId, translationSourceId, translationUri, translationReadFlags,
            translationEncoding, translationConfirmedContentSha256),
        userDelayMs.toInt(),
    )
}

private fun decodeAssociation(
    documentId: String?, sourceId: String?, uri: String?, readFlags: Long?, encodingName: String?, sha256: String?,
): LyricsAssociation? {
    if (documentId == null && sourceId == null && uri == null && readFlags == null && encodingName == null && sha256 == null) {
        return null
    }
    if (documentId == null || sourceId == null || uri == null || readFlags == null || encodingName == null || sha256 == null) {
        lyricsTrackFail(LyricsTrackIssue.CORRUPT_RECORD)
    }
    if (readFlags != 0L && readFlags != LyricsTrackLimits.READ_PERMISSION_FLAG.toLong()) {
        lyricsTrackFail(LyricsTrackIssue.CORRUPT_RECORD)
    }
    val encoding = LyricsEncodingChoice.entries.firstOrNull { it.name == encodingName }
        ?: lyricsTrackFail(LyricsTrackIssue.CORRUPT_RECORD)
    return try {
        LyricsAssociation(documentId, sourceId, uri, readFlags.toInt(), encoding, sha256)
    } catch (_: IllegalArgumentException) { lyricsTrackFail(LyricsTrackIssue.CORRUPT_RECORD) }
}

internal suspend fun LyricsDao.update(expectedRevision: Long, row: LyricsTrackPreferencesEntity): Int = updateIfRevision(
    trackId = row.trackId, expectedRevision = expectedRevision, nextRevision = row.revision,
    primaryDocumentId = row.primaryDocumentId, primarySourceId = row.primarySourceId,
    primaryUri = row.primaryUri, primaryReadFlags = row.primaryReadFlags,
    primaryEncoding = row.primaryEncoding, primaryConfirmedContentSha256 = row.primaryConfirmedContentSha256,
    translationDocumentId = row.translationDocumentId, translationSourceId = row.translationSourceId,
    translationUri = row.translationUri, translationReadFlags = row.translationReadFlags,
    translationEncoding = row.translationEncoding, translationConfirmedContentSha256 = row.translationConfirmedContentSha256,
    userDelayMs = row.userDelayMs,
)
