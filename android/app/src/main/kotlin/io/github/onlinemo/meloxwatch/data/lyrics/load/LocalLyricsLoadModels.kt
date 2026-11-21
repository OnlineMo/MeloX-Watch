package io.github.onlinemo.meloxwatch.data.lyrics.load

import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsCandidate
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsDiagnostic
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsDocument
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseIssue
import io.github.onlinemo.meloxwatch.model.TrackKey

/** The caller must invalidate both generations when its track or selected lyric source changes. */
data class LyricsLoadBinding(
    val trackKey: TrackKey,
    val locationId: String,
    val contentRevision: Long,
    val sourceId: String,
    val sourceRevision: Long,
    val authorizationEpoch: Long,
    val requestGeneration: Long,
    val sourceGeneration: Long,
    val documentIdentity: String? = null,
) {
    init {
        require(trackKey.providerId == TrackKey.LOCAL_PROVIDER)
        require(locationId.isNotBlank() && locationId.length <= LocalInputLimits.URI_CHARACTERS)
        require(sourceId.isNotBlank() && sourceId.length <= 128)
        require(listOf(contentRevision, sourceRevision, authorizationEpoch, requestGeneration, sourceGeneration).all { it >= 0L })
        require(documentIdentity == null || documentIdentity.isNotBlank() && documentIdentity.length <= LocalInputLimits.URI_CHARACTERS)
    }
}

/** A picker preview or saved association supplies this input; loading never saves its URI grant. */
data class LyricsDocumentInput(
    val documentId: String,
    val sourceGeneration: Long,
    val source: LocalSourceSpec,
    val candidate: ObservedCandidate,
    val encoding: LyricsEncodingChoice = LyricsEncodingChoice.AUTO_BOM_OR_UTF8,
    val expectedContentSha256: String? = null,
    val requirePersistedRead: Boolean = false,
) {
    init {
        require(documentId.isNotBlank() && documentId.length <= LocalInputLimits.URI_CHARACTERS && sourceGeneration >= 0L)
        require(candidate.sourceId == source.sourceId && !candidate.directory)
        require(candidate.displayName.length <= LocalInputLimits.NAME_CHARACTERS)
        require(candidate.mimeType == null || candidate.mimeType.length <= LocalInputLimits.TAG_CHARACTERS)
        require(candidate.providerContentToken == null || candidate.providerContentToken.length <= LocalInputLimits.URI_CHARACTERS)
        require(expectedContentSha256 == null || expectedContentSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Parent identity is an explicit enumeration edge, never inferred from an audio URI or title. */
data class LyricsSiblingScope(val parentObjectId: String, val audioFileName: String) {
    init {
        require(parentObjectId.isNotBlank() && parentObjectId.length <= LocalInputLimits.URI_CHARACTERS)
        require(audioFileName.isNotBlank() && audioFileName.length < LocalInputLimits.NAME_CHARACTERS)
    }
}

sealed interface LyricsLoadSelection {
    data class Automatic(
        val associatedDocument: LyricsDocumentInput? = null,
        val siblingScope: LyricsSiblingScope? = null,
    ) : LyricsLoadSelection

    data class TextDocument(val input: LyricsDocumentInput) : LyricsLoadSelection
    data class Embedded(val candidateId: String? = null) : LyricsLoadSelection {
        init { require(candidateId == null || candidateId.isNotBlank() && candidateId.length <= 512) }
    }
}

/** The owner reserves already-retained documents and validation scratch before each serial load. */
data class LyricsLoadRequest(
    val binding: LyricsLoadBinding,
    val selection: LyricsLoadSelection,
    val maxAccountedBytes: Long = LyricsLimits.ACCOUNTED_BYTES,
) {
    init { require(maxAccountedBytes in 1L..LyricsLimits.ACCOUNTED_BYTES) }
}

data class LocalLyricsLoadLimits(
    val lyrics: LyricsLimits = LyricsLimits(),
    val embedded: EmbeddedLyricsLimits = EmbeddedLyricsLimits(lyrics = lyrics),
    val maxSiblingEntries: Int = 4_096,
    val ioChunkBytes: Int = 8_192,
    val maxReadOperations: Int = 65_536,
) {
    init {
        require(embedded.lyrics == lyrics)
        require(maxSiblingEntries in 1..4_096 && ioChunkBytes in 1..8_192 && maxReadOperations in 1..65_536)
    }
}

enum class LyricsSourceOrigin { EXPLICIT_DOCUMENT, SAME_NAME_LRC, EMBEDDED_SYNCHRONIZED, EMBEDDED_PLAIN_TEXT }
enum class LyricsSiblingStatus {
    NOT_REQUESTED, SCOPE_UNAVAILABLE, NO_MATCH, MATCHED, AMBIGUOUS,
    DIRECTORY_TRUNCATED, INCOMPLETE_PROVIDER, AUDIO_SCOPE_MISMATCH,
}

data class LyricsLoadedSource(
    val origin: LyricsSourceOrigin,
    val sourceId: String,
    val objectId: String,
    val documentId: String,
    val sourceGeneration: Long,
    val contentSha256: String,
    val embeddedCandidateId: String? = null,
)

enum class LyricsLoadIssue {
    BUSY, STALE_REQUEST, DOCUMENT_MISMATCH, SOURCE_CHANGED, CONTENT_UNKNOWN,
    PERMISSION_DENIED, PERMISSION_REVOKED, SOURCE_UNAVAILABLE, FILE_MISSING, INVALID_INPUT,
    INPUT_TOO_LARGE, READ_LIMIT, INVALID_READ, MEMORY_LIMIT, IO_FAILURE,
    PARSE_FAILED, EMBEDDED_FAILED, NO_LYRICS,
}

/** Stable diagnostics contain no exception messages, source text, URI or file path. */
data class LyricsLoadFailure(
    val issue: LyricsLoadIssue,
    val sourceIssue: SourceReadIssue? = null,
    val libraryIssue: LibraryIssue? = null,
    val parseIssue: LyricsParseIssue? = null,
)

sealed interface LocalLyricsLoadResult {
    val binding: LyricsLoadBinding
    val siblingStatus: LyricsSiblingStatus

    class Loaded internal constructor(
        override val binding: LyricsLoadBinding,
        val audio: LocalReadDescriptor,
        val source: LyricsLoadedSource,
        val document: LyricsDocument,
        val embeddedCandidates: List<EmbeddedLyricsCandidate>,
        val embeddedDiagnostics: List<EmbeddedLyricsDiagnostic>,
        override val siblingStatus: LyricsSiblingStatus,
        /** A conservative representation bound, not measured heap, PSS or an acceptance result. */
        val accountedBytes: Long,
    ) : LocalLyricsLoadResult

    class Unavailable internal constructor(
        override val binding: LyricsLoadBinding,
        val failure: LyricsLoadFailure,
        override val siblingStatus: LyricsSiblingStatus,
        val embeddedDiagnostics: List<EmbeddedLyricsDiagnostic>,
    ) : LocalLyricsLoadResult
}

internal class LyricsLoadException(val failure: LyricsLoadFailure) : Exception(failure.issue.name)
internal fun lyricsLoadFail(issue: LyricsLoadIssue): Nothing = throw LyricsLoadException(LyricsLoadFailure(issue))

// Bound the retained request/descriptor/source/result envelope separately from parser-owned storage.
internal const val LYRICS_LOAD_ENVELOPE_BYTES = 65_536L
