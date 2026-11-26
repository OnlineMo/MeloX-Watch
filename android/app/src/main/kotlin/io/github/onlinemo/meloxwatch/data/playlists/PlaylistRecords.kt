package io.github.onlinemo.meloxwatch.data.playlists

import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal fun playlistName(raw: String): String {
    require(raw.length <= PlaylistLimits.MAX_FIELD_CHARACTERS)
    val name = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
    require(name.codePointCount(0, name.length) in 1..PlaylistLimits.MAX_NAME_CODE_POINTS)
    var position = 0
    while (position < name.length) {
        val character = name[position]
        require(!Character.isISOControl(character))
        if (Character.isHighSurrogate(character)) {
            require(position + 1 < name.length && Character.isLowSurrogate(name[position + 1]))
            position += 2
        } else {
            require(!Character.isLowSurrogate(character))
            position += 1
        }
    }
    return name
}

internal fun playlistNameKey(name: String): String = name.lowercase(Locale.ROOT)

internal fun playlistDigest(fields: List<String?>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (field in fields) {
        if (field == null) {
            digest.update(byteArrayOf(-1, -1, -1, -1))
        } else {
            val bytes = field.toByteArray(Charsets.UTF_8)
            val size = bytes.size
            digest.update(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(),
                (size ushr 8).toByte(), size.toByte()))
            digest.update(bytes)
        }
    }
    return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

internal fun PlaylistReference.identityKey(): String = playlistDigest(
    if (trackKey != null) listOf("track", trackKey.providerId, trackKey.trackId)
    else listOf("placeholder", placeholderId),
)

internal fun PlaylistReference.record(generation: String, memberId: String, ordinal: Long) = PlaylistMemberEntity(
    generation, memberId, ordinal, identityKey(), trackKey?.providerId, trackKey?.trackId, placeholderId,
    issue?.name, titleHint, artistHint, albumHint, relativePathHint, descriptionHint, durationMs,
    cueReference = cueReference?.let(CueReferenceCodec::encode),
)

internal fun PlaylistMemberEntity.model(): PlaylistMember = decodePlaylistRecord {
    require(ordinal >= 0L)
    requirePlaylistIdentifier(memberId)
    require((providerId == null) == (trackId == null))
    val reference = PlaylistReference(
        trackKey = providerId?.let { TrackKey(it, checkNotNull(trackId)) },
        placeholderId = placeholderId,
        issue = issue?.let(PlaylistReferenceIssue::valueOf),
        titleHint = titleHint, artistHint = artistHint, albumHint = albumHint,
        relativePathHint = relativePathHint, descriptionHint = descriptionHint, durationMs = durationMs,
        cueReference = cueReference?.let { CueReferenceCodec.decode(it, portableOnly = true) },
    )
    require(reference.identityKey() == referenceKey)
    PlaylistMember(memberId, ordinal, reference)
}

internal fun PlaylistGenerationEntity.model(): PlaylistSnapshot = decodePlaylistRecord {
    require(state == "PUBLISHED" && revision >= 0L && memberCount >= 0L)
    require(validFrom != null && validFrom >= 0L && (validTo == null || validTo >= validFrom))
    requirePlaylistIdentifier(playlistId)
    requirePlaylistIdentifier(generationId)
    requirePlaylistIdentifier(dataGenerationId)
    val playlistKind = PlaylistKind.valueOf(kind)
    require((playlistKind == PlaylistKind.FAVORITES) == (playlistId == PlaylistLimits.FAVORITES_ID))
    require(playlistName(name) == name && playlistNameKey(name) == nameKey)
    val binding = if (playlistKind == PlaylistKind.FOLDER) PlaylistFolderBinding(
        checkNotNull(bindingSourceId), checkNotNull(bindingNodeId), checkNotNull(bindingSubtree),
    ) else {
        require(bindingSourceId == null && bindingNodeId == null && bindingSubtree == null)
        null
    }
    PlaylistSnapshot(playlistId, generationId, revision, name, playlistKind, memberCount,
        createdEpochMs, updatedEpochMs, binding)
}

private inline fun <T> decodePlaylistRecord(read: () -> T): T {
    try {
        return read()
    } catch (error: IllegalArgumentException) {
        throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED, error)
    } catch (error: IllegalStateException) {
        throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED, error)
    }
}
