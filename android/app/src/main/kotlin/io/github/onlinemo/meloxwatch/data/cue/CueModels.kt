package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Initial input/work budgets, not measured device limits or library/queue limits. */
object CueLimits {
    const val RAW_BYTES = 1024 * 1024
    const val LINES = 10_000
    const val LINE_CHARACTERS = 8192
    const val FIELD_CHARACTERS = 4096
    const val PATH_CHARACTERS = 4096
    const val PATH_COMPONENT_CHARACTERS = 512
    const val DIRECTORY_DEPTH = 16 // Includes the authorized root; final audio name is not a directory.
    const val FILES = 99
    const val TRACKS = 99
    const val LOOKUP_CANDIDATES = 100_000
    const val BOUNDARY_VERSION = 1
    const val FRAMES_PER_SECOND = 75L

    // Quotient/remainder arithmetic leaves every admitted frame convertible to Long microseconds.
    const val MAX_FRAME_COUNT = Long.MAX_VALUE / 1_000_000L * FRAMES_PER_SECOND +
        Long.MAX_VALUE % 1_000_000L * FRAMES_PER_SECOND / 1_000_000L
}

enum class CueEncoding { UTF8, GB18030 }

enum class CueIssue {
    INVALID_INPUT, LIMIT_EXCEEDED, INVALID_ENCODING, UNSUPPORTED_ENCODING,
    INVALID_SYNTAX, UNSUPPORTED_DIRECTIVE, UNSUPPORTED_FILE_TYPE, UNSUPPORTED_GAP,
    INVALID_TRACK, INVALID_INDEX, INVALID_TIME, INVALID_PATH, DUPLICATE_REFERENCE,
    AMBIGUOUS_PATH, AUTHORIZATION_REQUIRED, SOURCE_CHANGED, INCOMPLETE_ENUMERATION,
    MISSING_AUDIO, INVALID_AUDIO, UNVERIFIED_CONTENT, INVALID_BOUNDARIES,
}

/** Error messages contain a code/line, never CUE contents, private paths or provider URIs. */
class CueException(val issue: CueIssue, val lineNumber: Int? = null, cause: Throwable? = null) :
    Exception(if (lineNumber == null) issue.name else "${issue.name}:$lineNumber", cause)

data class CueFrameTime(val frames: Long) {
    init { require(frames in 0L..CueLimits.MAX_FRAME_COUNT) }

    /** Floor each endpoint independently; the original 75 fps value remains authoritative. */
    fun toMicroseconds(): Long = frames / CueLimits.FRAMES_PER_SECOND * 1_000_000L +
        frames % CueLimits.FRAMES_PER_SECOND * 1_000_000L / CueLimits.FRAMES_PER_SECOND

    fun toMilliseconds(): Long = toMicroseconds() / 1000L
}

data class CueMetadata(val title: String? = null, val performer: String? = null)

/** WAVE/MP3/AIFF describe a CUE dialect; they do not verify the referenced audio container. */
enum class CueFileType { WAVE, MP3, AIFF }

/**
 * Before the first FILE metadata is album-wide; after FILE and before its first TRACK it applies
 * only to that file. File TITLE is an album override, never an inherited track title. A new FILE
 * resets the file/track scope. Track PERFORMER overrides file PERFORMER, then album PERFORMER.
 */
@ConsistentCopyVisibility
data class CueFileDeclaration internal constructor(
    val index: Int,
    val path: CueRelativePath,
    val declaredType: CueFileType,
    val metadata: CueMetadata,
)

@ConsistentCopyVisibility
data class CueTrackDeclaration internal constructor(
    val number: Int,
    val fileIndex: Int,
    val metadata: CueMetadata,
    val index00: CueFrameTime?,
    val index01: CueFrameTime,
)

@ConsistentCopyVisibility
data class ParsedCue internal constructor(
    val encoding: CueEncoding,
    val rawContentToken: String,
    val metadata: CueMetadata,
    val files: List<CueFileDeclaration>,
    val tracks: List<CueTrackDeclaration>,
)

/** cueId is a repository-owned durable identity, separate from objectId, URI and title. */
data class CueDocumentIdentity(val sourceId: String, val cueId: String) {
    init {
        require(sourceId.isNotBlank() && sourceId.length <= 128)
        require(cueId.isNotBlank() && cueId.length <= 128)
        require((sourceId + cueId).none { Character.isISOControl(it) })
        require(StandardCharsets.UTF_8.newEncoder().canEncode(sourceId))
        require(StandardCharsets.UTF_8.newEncoder().canEncode(cueId))
    }
}

data class CueTrackIdentity(val document: CueDocumentIdentity, val number: Int) {
    init { require(number in 1..CueLimits.TRACKS) }

    val trackKey: TrackKey = run {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("melox-cue-track:v1:".toByteArray(StandardCharsets.US_ASCII))
        for (component in listOf(document.sourceId, document.cueId)) {
            val bytes = component.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        digest.update(number.toByte())
        TrackKey(TrackKey.LOCAL_PROVIDER, "cue-v1-${cueHex(digest.digest())}")
    }
}

/**
 * Caller must independently open the authorized CUE and fence its hash/contentRevision/authEpoch.
 * This snapshot and parsing a FILE name cannot create Android read permission.
 */
data class CueDocumentSnapshot(
    val identity: CueDocumentIdentity,
    val source: LibrarySource,
    val objectId: String,
    val location: LocalLocationReference,
    val rawContentToken: String,
)

/** Proven root-to-parent enumeration edges in an authorized TREE, followed by the CUE itself. */
data class CueDirectoryScope(
    val source: LibrarySource,
    val directories: List<ObservedCandidate>,
    val cue: ObservedCandidate,
)

/** NFC with exact case by default; FOLD_CASE explicitly selects Locale.ROOT lowercase plus NFC. */
enum class CueNamePolicy { EXACT, FOLD_CASE }

@ConsistentCopyVisibility
data class CueMatchedFile internal constructor(val fileIndex: Int, val candidate: ObservedCandidate)

@ConsistentCopyVisibility
data class CueMatchedFiles internal constructor(
    val rawContentToken: String,
    val scope: CueDirectoryScope,
    val files: List<CueMatchedFile>,
)

/** Duration comes from a real audio inspection; a CUE FILE type or suffix is insufficient. */
data class CueAudioFacts(val fileIndex: Int, val descriptor: LocalReadDescriptor, val durationUs: Long)

@ConsistentCopyVisibility
data class CueSegmentBounds internal constructor(
    val start: CueFrameTime,
    val endFrameExclusive: CueFrameTime?,
    val endUsExclusive: Long,
    val boundaryVersion: Int = CueLimits.BOUNDARY_VERSION,
) {
    val startUs: Long get() = start.toMicroseconds()
    val durationUs: Long get() = endUsExclusive - startUs
}

/** Logical metadata is never an assertion that the physical file has writable per-segment tags. */
@ConsistentCopyVisibility
data class CueSegment internal constructor(
    val identity: CueTrackIdentity,
    val fileIndex: Int,
    val audio: LocalReadDescriptor,
    val bounds: CueSegmentBounds,
    val index00: CueFrameTime?,
    val title: String?,
    val performer: String?,
    val album: String?,
    val albumPerformer: String?,
)

/** Preparation only: scan CAS/atomic commit and both playback clipping paths still belong to integration. */
@ConsistentCopyVisibility
data class PreparedCueGroup internal constructor(
    val document: CueDocumentSnapshot,
    val audioFiles: List<CueAudioFacts>,
    val segments: List<CueSegment>,
    val boundaryVersion: Int = CueLimits.BOUNDARY_VERSION,
)

internal fun cueHex(bytes: ByteArray): String {
    val alphabet = "0123456789abcdef"
    val result = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 255
        result.append(alphabet[value ushr 4]).append(alphabet[value and 15])
    }
    return result.toString()
}
