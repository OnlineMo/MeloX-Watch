package io.github.onlinemo.meloxwatch.data.lyrics

/** Bounds apply to the whole metadata read, including rejected lyric candidates. */
data class EmbeddedLyricsLimits(
    val lyrics: LyricsLimits = LyricsLimits(),
    val maxMetadataBytes: Int = METADATA_BYTES,
    val maxStructures: Int = STRUCTURES,
    val maxCandidates: Int = CANDIDATES,
    val maxReadOperations: Int = READ_OPERATIONS,
    val ioChunkBytes: Int = IO_CHUNK_BYTES,
    val maxDescriptorCodePoints: Int = DESCRIPTOR_CODE_POINTS,
    val maxVorbisKeyBytes: Int = VORBIS_KEY_BYTES,
) {
    init {
        require(maxMetadataBytes in 1..METADATA_BYTES && maxStructures in 1..STRUCTURES)
        require(maxCandidates in 1..CANDIDATES && maxReadOperations in 1..READ_OPERATIONS)
        require(ioChunkBytes in 1..IO_CHUNK_BYTES)
        require(maxDescriptorCodePoints in 1..DESCRIPTOR_CODE_POINTS && maxVorbisKeyBytes in 1..VORBIS_KEY_BYTES)
    }

    companion object {
        const val METADATA_BYTES = 33_554_432
        const val STRUCTURES = 4_096
        const val CANDIDATES = 4
        const val READ_OPERATIONS = 65_536
        const val IO_CHUNK_BYTES = 8_192
        const val DESCRIPTOR_CODE_POINTS = 256
        const val VORBIS_KEY_BYTES = 128
    }
}

enum class EmbeddedLyricsContainer { MP3_ID3, FLAC, VORBIS_COMMENT_PACKET, OGG_VORBIS, OGG_OPUS }
enum class EmbeddedLyricsField { ID3_USLT, ID3_SYLT, VORBIS_LYRICS }
enum class EmbeddedLyricsWireEncoding { ISO_8859_1, UTF16_BOM, UTF16_BE, UTF8 }

enum class EmbeddedLyricsIssue {
    UNSUPPORTED_CONTAINER, UNSUPPORTED_ID3_VERSION, UNSUPPORTED_ID3_TAG_FLAGS,
    UNSUPPORTED_ID3_FRAME_FLAGS, UNSUPPORTED_TIMESTAMP_FORMAT, UNSUPPORTED_SYLT_CONTENT,
    TRUNCATED_METADATA, INVALID_CONTAINER, INVALID_OGG_CHECKSUM, INVALID_ID3_FRAME, INVALID_LANGUAGE,
    INVALID_ENCODING, INVALID_TEXT, INVALID_TIMESTAMP, DESCRIPTOR_TOO_LONG, DUPLICATE_ID3_SOURCE,
    METADATA_TOO_LARGE, LYRICS_INPUT_TOO_LARGE, TOO_MANY_STRUCTURES, TOO_MANY_CANDIDATES,
    TOO_MANY_LINES, TOO_MANY_TIMED_LINES, TOO_MANY_WORD_EVENTS, LINE_TOO_LONG, MEMORY_LIMIT,
    READ_LIMIT, IO_FAILURE, READ_PERMISSION_DENIED,
}

/** Only bounded enums and ordinals escape a failure; exception messages and source text do not. */
data class EmbeddedLyricsDiagnostic(val issue: EmbeddedLyricsIssue, val sourceOrdinal: Int? = null)

/** Content identity is separate from the caller's TrackKey, authorization and acquisition generation. */
class EmbeddedLyricsSource internal constructor(
    val container: EmbeddedLyricsContainer,
    val field: EmbeddedLyricsField,
    val ordinal: Int,
    val language: String?,
    val descriptor: String?,
    val wireEncoding: EmbeddedLyricsWireEncoding,
    val contentSha256: String,
) {
    val schemaVersion: Int = 1
    val candidateId: String = "embedded-lyrics-v1-${container.name}-${field.name}-$ordinal-$contentSha256"
}

class EmbeddedLyricsCandidate internal constructor(val source: EmbeddedLyricsSource, val document: LyricsDocument)

class EmbeddedLyricsReadResult internal constructor(
    val candidates: List<EmbeddedLyricsCandidate>,
    val diagnostics: List<EmbeddedLyricsDiagnostic>,
    val metadataComplete: Boolean,
    /** Charged representation bound, never heap/PSS; null when a failed sub-parser has no resident count. */
    val accountedBytes: Long?,
    val cumulativePayloadBytes: Int,
) {
    /** Within each capability, metadata source order wins; no guessed language preference. */
    val defaultCandidate: EmbeddedLyricsCandidate? = candidates.firstOrNull { it.document.kind == LyricsKind.SYNCHRONIZED }
        ?: candidates.firstOrNull { it.document.kind == LyricsKind.PLAIN_TEXT }
}

internal class EmbeddedLyricsReadException(val issue: EmbeddedLyricsIssue) : Exception(issue.name)

internal fun embeddedLyricsFailure(issue: EmbeddedLyricsIssue): Nothing = throw EmbeddedLyricsReadException(issue)

/**
 * Charges happen before allocation. Payload, decoder storage, retained documents and drafts are
 * cumulative, so a failed candidate cannot start a fresh 8 MiB allowance. Only one current-line
 * scratch owner is live, and its high-water mark stays charged. These are representation bounds;
 * JVM allocation/PSS still need measurement. The acquisition owner must also bound concurrent reads.
 */
internal class EmbeddedLyricsBudget(val limits: EmbeddedLyricsLimits) {
    private var chargedBytes = 2_048L + limits.ioChunkBytes + limits.maxVorbisKeyBytes +
        (limits.maxCandidates + 1L) * 96L
    private var lineScratchBytes = 0L
    var cumulativePayloadBytes = 0
        private set
    var candidateAttempts = 0
        private set
    private var structures = 0
    private var physicalLines = 0
    private var timedLines = 0
    private var words = 0
    private var syltEvents = 0
    var accountingComplete = true
        private set

    val accountedBytes: Long get() = chargedBytes + lineScratchBytes

    init { check() }

    fun charge(bytes: Long) {
        if (bytes < 0L || bytes > limits.lyrics.maxAccountedBytes - accountedBytes) {
            embeddedLyricsFailure(EmbeddedLyricsIssue.MEMORY_LIMIT)
        }
        chargedBytes += bytes
    }

    fun structure() {
        if (structures == limits.maxStructures) embeddedLyricsFailure(EmbeddedLyricsIssue.TOO_MANY_STRUCTURES)
        structures++
        charge(64L)
    }

    fun candidate(): Int {
        if (candidateAttempts == limits.maxCandidates) embeddedLyricsFailure(EmbeddedLyricsIssue.TOO_MANY_CANDIDATES)
        return ++candidateAttempts
    }

    fun payload(length: Int) {
        if (length < 0 || length > limits.lyrics.maxInputBytes - cumulativePayloadBytes) {
            embeddedLyricsFailure(EmbeddedLyricsIssue.LYRICS_INPUT_TOO_LARGE)
        }
        cumulativePayloadBytes += length
    }

    fun rawAllocation(length: Int) { charge(32L + length) }
    fun decoding(length: Int) { charge(192L + length.toLong() * 4L) }

    fun line(synchronized: Boolean) {
        if (physicalLines == limits.lyrics.maxPhysicalLines) embeddedLyricsFailure(EmbeddedLyricsIssue.TOO_MANY_LINES)
        if (synchronized && timedLines == limits.lyrics.maxTimelineEntries) {
            embeddedLyricsFailure(EmbeddedLyricsIssue.TOO_MANY_TIMED_LINES)
        }
        // Synchronized rows also keep an open-row/draft representation until final construction.
        charge(192L + if (synchronized) 352L else 0L)
        physicalLines++
        if (synchronized) timedLines++
    }

    fun syltEvent() {
        if (syltEvents == limits.lyrics.maxWordEvents) embeddedLyricsFailure(EmbeddedLyricsIssue.TOO_MANY_WORD_EVENTS)
        syltEvents++
    }

    fun word() {
        if (words == limits.lyrics.maxWordEvents) embeddedLyricsFailure(EmbeddedLyricsIssue.TOO_MANY_WORD_EVENTS)
        // Both mutable boundary draft and final timing coexist, plus bounded pending/list references.
        charge(192L)
        words++
    }

    fun scratch(utf16Units: Int) {
        val next = utf16Units.toLong() * 64L
        if (next > lineScratchBytes) {
            if (next > limits.lyrics.maxAccountedBytes - chargedBytes) embeddedLyricsFailure(EmbeddedLyricsIssue.MEMORY_LIMIT)
            lineScratchBytes = next
        }
    }

    /** The frozen text parser sees only the still-available part of the shared read budget. */
    fun textParserLimits(inputLength: Int): LyricsLimits {
        fun remaining(used: Int, maximum: Int, issue: EmbeddedLyricsIssue): Int {
            if (used == maximum && inputLength != 0) embeddedLyricsFailure(issue)
            return (maximum - used).coerceAtLeast(1)
        }
        val available = limits.lyrics.maxAccountedBytes - accountedBytes
        if (available <= 0L) embeddedLyricsFailure(EmbeddedLyricsIssue.MEMORY_LIMIT)
        return limits.lyrics.copy(
            maxPhysicalLines = remaining(physicalLines, limits.lyrics.maxPhysicalLines, EmbeddedLyricsIssue.TOO_MANY_LINES),
            maxTimelineEntries = remaining(timedLines, limits.lyrics.maxTimelineEntries, EmbeddedLyricsIssue.TOO_MANY_TIMED_LINES),
            maxWordEvents = remaining(words, limits.lyrics.maxWordEvents, EmbeddedLyricsIssue.TOO_MANY_WORD_EVENTS),
            maxAccountedBytes = available,
        )
    }

    fun parsedTextDocument(document: LyricsDocument, inputLength: Int, check: () -> Unit) {
        if (document.physicalLineCount > limits.lyrics.maxPhysicalLines - physicalLines) {
            embeddedLyricsFailure(EmbeddedLyricsIssue.TOO_MANY_LINES)
        }
        if (document.timeline.size > limits.lyrics.maxTimelineEntries - timedLines) {
            embeddedLyricsFailure(EmbeddedLyricsIssue.TOO_MANY_TIMED_LINES)
        }
        var wordCount = 0
        for ((index, line) in document.sourceLines.withIndex()) {
            if (index % 64 == 0) check()
            wordCount += line.wordTimings.size
        }
        if (wordCount > limits.lyrics.maxWordEvents - words) embeddedLyricsFailure(EmbeddedLyricsIssue.TOO_MANY_WORD_EVENTS)
        // Keep earlier parser copies/decoder buffers charged until this whole acquisition returns.
        charge(document.accountedResidentBytes + inputLength.toLong() * 6L + 256L)
        physicalLines += document.physicalLineCount
        timedLines += document.timeline.size
        words += wordCount
    }

    fun textParserFailed() { accountingComplete = false }

    private fun check() {
        if (accountedBytes > limits.lyrics.maxAccountedBytes) embeddedLyricsFailure(EmbeddedLyricsIssue.MEMORY_LIMIT)
    }
}

/** Each candidate is atomic; malformed container framing invalidates the entire private result. */
internal class EmbeddedLyricsCollector(private val budget: EmbeddedLyricsBudget) {
    private data class Id3Identity(
        val field: EmbeddedLyricsField,
        val language: String?,
        val descriptor: String?,
        val ordinal: Int,
        var duplicate: Boolean,
    )

    private val candidates = ArrayList<EmbeddedLyricsCandidate>(budget.limits.maxCandidates)
    private val diagnostics = ArrayList<EmbeddedLyricsDiagnostic>(budget.limits.maxCandidates + 1)
    private val id3Identities = ArrayList<Id3Identity>(budget.limits.maxCandidates)

    fun rejected(issue: EmbeddedLyricsIssue, ordinal: Int) { diagnostics += EmbeddedLyricsDiagnostic(issue, ordinal) }

    fun add(candidate: EmbeddedLyricsCandidate) {
        val source = candidate.source
        if (source.container == EmbeddedLyricsContainer.MP3_ID3) {
            val prior = id3Identities.firstOrNull {
                it.field == source.field && it.language == source.language && it.descriptor == source.descriptor
            }
            if (prior != null) {
                if (!prior.duplicate) {
                    prior.duplicate = true
                    candidates.removeAll { it.source.ordinal == prior.ordinal }
                    rejected(EmbeddedLyricsIssue.DUPLICATE_ID3_SOURCE, prior.ordinal)
                }
                rejected(EmbeddedLyricsIssue.DUPLICATE_ID3_SOURCE, source.ordinal)
                return
            }
            id3Identities += Id3Identity(source.field, source.language, source.descriptor, source.ordinal, false)
        }
        candidates += candidate
    }

    fun result(fatal: EmbeddedLyricsIssue? = null): EmbeddedLyricsReadResult {
        if (fatal != null) {
            candidates.clear()
            diagnostics += EmbeddedLyricsDiagnostic(fatal)
        }
        return EmbeddedLyricsReadResult(
            immutableLyricsList(candidates), immutableLyricsList(diagnostics), fatal == null,
            if (budget.accountingComplete) budget.accountedBytes else null, budget.cumulativePayloadBytes,
        )
    }
}

internal fun LyricsParseIssue.toEmbeddedIssue(): EmbeddedLyricsIssue = when (this) {
    LyricsParseIssue.INPUT_TOO_LARGE -> EmbeddedLyricsIssue.LYRICS_INPUT_TOO_LARGE
    LyricsParseIssue.TOO_MANY_LINES -> EmbeddedLyricsIssue.TOO_MANY_LINES
    LyricsParseIssue.TOO_MANY_TIMED_LINES -> EmbeddedLyricsIssue.TOO_MANY_TIMED_LINES
    LyricsParseIssue.TOO_MANY_WORD_EVENTS -> EmbeddedLyricsIssue.TOO_MANY_WORD_EVENTS
    LyricsParseIssue.LINE_TOO_LONG -> EmbeddedLyricsIssue.LINE_TOO_LONG
    LyricsParseIssue.MEMORY_LIMIT -> EmbeddedLyricsIssue.MEMORY_LIMIT
    LyricsParseIssue.INVALID_ENCODING, LyricsParseIssue.ENCODING_MISMATCH,
    LyricsParseIssue.UNSUPPORTED_ENCODING -> EmbeddedLyricsIssue.INVALID_ENCODING
    LyricsParseIssue.INVALID_TEXT -> EmbeddedLyricsIssue.INVALID_TEXT
}

internal fun EmbeddedLyricsIssue.isCandidateFailure(): Boolean = when (this) {
    EmbeddedLyricsIssue.UNSUPPORTED_ID3_FRAME_FLAGS, EmbeddedLyricsIssue.UNSUPPORTED_TIMESTAMP_FORMAT,
    EmbeddedLyricsIssue.UNSUPPORTED_SYLT_CONTENT, EmbeddedLyricsIssue.INVALID_LANGUAGE,
    EmbeddedLyricsIssue.INVALID_ENCODING, EmbeddedLyricsIssue.INVALID_TEXT, EmbeddedLyricsIssue.INVALID_TIMESTAMP,
    EmbeddedLyricsIssue.DESCRIPTOR_TOO_LONG -> true
    else -> false
}
