package io.github.onlinemo.meloxwatch.ui.metadata

internal enum class LocalMetadataKind { LYRICS, ARTWORK }

internal enum class LocalMetadataIssue {
    DISABLED, NO_TRACK, NOT_LOCAL, INVALID_QUERY, OFFLINE, SERVICE_UNAVAILABLE,
    TIMEOUT, CERTIFICATE, RATE_LIMITED, BUSY, NO_RESULTS, NO_LYRICS, NO_ARTWORK,
    INVALID_RESPONSE, INPUT_LIMIT, INVALID_IMAGE, LOW_MEMORY, LOW_SPACE,
    STORAGE, SOURCE_CHANGED, CLOSED,
}

internal enum class LocalMetadataNotice { SAVED, CLEARED }

/** The local track identity and temporary service URL never enter a candidate's display fields. */
internal data class LocalMetadataCandidate(
    val id: String,
    val title: String?,
    val artist: String?,
    val album: String?,
) {
    override fun toString(): String = "LocalMetadataCandidate(redacted)"
}

/** Text is already paged; artworkKey names a validated image in the shared bitmap owner. */
internal data class LocalMetadataPreview(
    val id: String,
    val candidate: LocalMetadataCandidate,
    val lyricsText: String? = null,
    val textPage: Int = 0,
    val textPages: Int = 1,
    val artworkKey: String? = null,
) {
    override fun toString(): String = "LocalMetadataPreview(redacted)"
}

internal data class LocalMetadataLookupUiState(
    val enabled: Boolean = false,
    val initializing: Boolean = true,
    val lookupTargetReady: Boolean = false,
    val kind: LocalMetadataKind = LocalMetadataKind.LYRICS,
    val targetTitle: String? = null,
    val targetArtist: String? = null,
    val title: String = "",
    val artist: String = "",
    val sendTitle: Boolean = true,
    val sendArtist: Boolean = true,
    val working: Boolean = false,
    val candidates: List<LocalMetadataCandidate> = emptyList(),
    val more: Boolean = false,
    val preview: LocalMetadataPreview? = null,
    val associated: LocalMetadataCandidate? = null,
    val associationPending: Boolean = false,
    val canClear: Boolean = false,
    val issue: LocalMetadataIssue? = null,
    val notice: LocalMetadataNotice? = null,
) {
    override fun toString(): String = "LocalMetadataLookupUiState(redacted)"
}
