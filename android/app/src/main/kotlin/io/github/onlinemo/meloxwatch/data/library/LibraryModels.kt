package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.TrackKey

object LibraryLimits {
    const val PAGE_SIZE = 100
    const val UI_VIEWS = 3
    const val QUEUE_VIEWS = 1
    const val TRANSFER_VIEWS = 4
    const val VIEW_LEASE_MS = 10 * 60 * 1000L
    const val SORT_RULE_VERSION = 1
    const val SHORT_AUDIO_MS = 60_000L
}

/** Parsed values stay separate from display fallbacks and later user overrides. */
data class LocalAudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val compilation: Boolean = false,
    val mimeType: String? = null,
    val parserVersion: Int = 1,
    val codecParameters: LocalCodecParameters? = null,
) {
    init {
        require(durationMs == null || durationMs > 0L)
        require(discNumber == null || discNumber >= 0)
        require(trackNumber == null || trackNumber >= 0)
        require(parserVersion > 0)
        require(artists.size <= 128)
        require(listOf(title, artist, album, albumArtist, mimeType).all { it == null || it.length <= 4096 })
        require(artists.all { it.length <= 4096 })
    }
}

/** Observed input and converted PCM parameters; this does not declare a verified device/backend. */
data class LocalCodecParameters(
    val container: String,
    val encoding: String,
    val inputSampleRateHz: Int,
    val inputBitsPerSample: Int,
    val channels: Int,
    val pcmSampleRateHz: Int,
    val pcmBitsPerSample: Int,
    val version: Int? = null,
    val compressionLevel: Int? = null,
) {
    init {
        require(container.isNotBlank() && container.length <= 64 && encoding.isNotBlank() && encoding.length <= 64)
        require(inputSampleRateHz > 0 && pcmSampleRateHz > 0 && inputBitsPerSample in 1..64 && pcmBitsPerSample in 1..64)
        require(channels in 1..8 && (version == null || version > 0) && (compressionLevel == null || compressionLevel > 0))
    }
}

enum class LibrarySourceState { ACTIVE, NEEDS_AUTHORIZATION, UNAVAILABLE, REMOVED }
enum class LibraryAvailability { AVAILABLE, UNKNOWN, NEEDS_AUTHORIZATION, UNAVAILABLE, MISSING, REMOVED }
enum class LibraryViewOwner { UI, QUEUE, TRANSFER }
typealias LibrarySortField = io.github.onlinemo.meloxwatch.model.LibrarySortField
typealias LibrarySort = io.github.onlinemo.meloxwatch.model.LibrarySort
enum class LibraryGroupKind { ALBUM, ARTIST }

data class LibrarySource(
    val spec: LocalSourceSpec,
    val grant: LocalGrantObservation,
    val grantId: String,
    val revision: Long,
    val authEpoch: Long,
    val state: LibrarySourceState,
    val activeScanId: String?,
    val latestScanId: String?,
    val displayName: String? = null,
)

data class LibrarySourcePage(val sources: List<LibrarySource>, val nextSourceId: String?)

data class LibraryOverview(
    val revision: Long,
    val rulesRevision: Long,
    val totalTracks: Long,
    val visibleTracks: Long,
    val availableTracks: Long,
    val sourceCount: Long,
    val shortAudioFilter: Boolean,
)

data class LibraryQuery(
    val search: String = "",
    val sourceId: String? = null,
    val folderNodeId: String? = null,
    val albumKey: String? = null,
    val artistKey: String? = null,
    val addedSinceEpochMs: Long? = null,
    val includeHidden: Boolean = false,
    val folderSubtree: Boolean = false,
    val relativePathKeyword: String? = null,
    val readableOnly: Boolean = false,
    // 内部歌单输入可纳入待逐文件验证的TREE；默认只读筛选仍只含AVAILABLE。
    internal val allowUnknownTreeReadValidation: Boolean = false,
) {
    init {
        require(search.length <= 4096)
        require(folderNodeId == null || sourceId != null)
        require(!folderSubtree || folderNodeId != null)
        require(!allowUnknownTreeReadValidation || readableOnly)
        require(relativePathKeyword == null || relativePathKeyword.isNotBlank() && relativePathKeyword.length <= 4096)
    }
}

data class LibraryViewToken(
    val viewId: String,
    val revision: Long,
    val rulesRevision: Long,
    val totalCount: Long,
    val query: LibraryQuery,
    val sort: LibrarySort,
    val shortAudioFilter: Boolean,
    val owner: LibraryViewOwner,
    val sortRuleVersion: Int = LibraryLimits.SORT_RULE_VERSION,
    val statisticsEpoch: Long = 0L,
)

/** Every key belongs to one view. A stale cursor cannot silently become a new page. */
data class LibraryCursor(
    val viewId: String,
    val revision: Long,
    val sortRuleVersion: Int,
    val missingRank: Int,
    val nullRank: Int,
    val sortKey: String,
    val providerId: String,
    val trackId: String,
)

data class LibraryTrack(
    val trackKey: TrackKey,
    val title: String,
    val filename: String,
    val metadata: LocalAudioMetadata,
    val availability: LibraryAvailability,
    val metadataIssue: String?,
    val firstAddedEpochMs: Long,
    val firstAddedSequence: Long,
    val albumKey: String,
    val location: LocalLocationReference?,
    val sourceId: String?,
    val aliasId: String?,
    val nodeId: String?,
    val playCount: Long,
    val listeningMs: Long,
    val displayArtist: String? = metadata.artists.firstOrNull() ?: metadata.artist,
    val displayAlbum: String? = metadata.album,
    val cueReference: LocalCueReference? = null,
)

data class LibraryPage(
    val view: LibraryViewToken,
    val tracks: List<LibraryTrack>,
    val nextCursor: LibraryCursor?,
    val previousCursor: LibraryCursor? = null,
)

data class LibraryGroup(val key: String, val title: String?, val subtitle: String?, val trackCount: Long)
data class LibraryGroupPage(val view: LibraryViewToken, val groups: List<LibraryGroup>, val nextKey: String?)
data class LibraryFolder(val nodeId: String, val sourceId: String, val name: String?, val parentNodeId: String?)
data class LibraryFolderPage(val view: LibraryViewToken, val folders: List<LibraryFolder>, val nextNodeId: String?)
data class LibrarySection(val key: String, val count: Long, val cursorBefore: LibraryCursor?)
data class LibraryLocation(val track: LibraryTrack, val ordinal: Long, val cursorBefore: LibraryCursor?)

/** A descriptor is an access snapshot; Android must still open and validate the resource. */
enum class LocalContentValidation { UNKNOWN, VERIFIED }

data class LocalReadDescriptor(
    val trackKey: TrackKey,
    val source: LibrarySource,
    val aliasId: String,
    val location: LocalLocationReference,
    val objectId: String,
    val providerContentToken: String?,
    val sizeBytes: Long?,
    val verifiedContentToken: String? = null,
    val contentValidation: LocalContentValidation = LocalContentValidation.UNKNOWN,
    val cueReference: LocalCueReference? = null,
)

data class LibraryExclusion(
    val ruleId: String,
    val sourceId: String,
    val nodeId: String,
    val subtree: Boolean,
    val displayName: String? = null,
    val sourceDisplayName: String? = null,
)
data class LibraryExclusionPage(val exclusions: List<LibraryExclusion>, val nextRuleId: String?)
data class LibraryRules(val revision: Long, val shortAudioFilter: Boolean)
data class SeenBatchCommit(val revision: Long, val seen: Long, val added: Long)
data class MetadataBatchCommit(val revision: Long, val processed: Long, val failed: Long)
data class AbsencePage(val nextAliasId: String?, val stagedCount: Long, val complete: Boolean)

enum class LibraryIssue {
    BUSY, CONFLICT, INVALID_INPUT, VIEW_EXPIRED, SOURCE_CHANGED, SOURCE_UNAVAILABLE,
    PERMISSION_REVOKED, FILE_MISSING, INCOMPLETE, STORAGE_FULL, STORAGE_UNAVAILABLE, RECOVERY_REQUIRED,
}

class LibraryException(val issue: LibraryIssue, cause: Throwable? = null) : Exception(issue.name, cause)
