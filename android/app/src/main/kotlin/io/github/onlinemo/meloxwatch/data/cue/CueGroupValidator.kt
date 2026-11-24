package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.model.TrackKey

/**
 * Produces the complete group or throws, without touching files, the library or the active queue.
 * The scan integration must recheck every source authEpoch/contentRevision/hash and atomically
 * replace the prior logical group. The physical full tracks remain independent library entries.
 */
object CueGroupValidator {
    fun prepare(
        parsed: ParsedCue,
        document: CueDocumentSnapshot,
        matched: CueMatchedFiles,
        audioFacts: List<CueAudioFacts>,
        checkCancelled: () -> Unit = {},
    ): PreparedCueGroup {
        checkCancelled()
        validateCueScope(matched.scope)
        validateParsed(parsed, checkCancelled)
        val source = matched.scope.source
        val cue = matched.scope.cue
        if (document.rawContentToken != parsed.rawContentToken || matched.rawContentToken != parsed.rawContentToken ||
            !parsed.rawContentToken.matches(Regex("sha256:[0-9a-f]{64}"))) {
            throw CueException(CueIssue.SOURCE_CHANGED)
        }
        if (document.source != source || document.identity.sourceId != source.spec.sourceId ||
            document.objectId != cue.objectId || document.location.uri != cue.uri ||
            document.location.grantUri != source.spec.uri) {
            throw CueException(CueIssue.SOURCE_CHANGED)
        }
        if (matched.files.size > CueLimits.FILES || audioFacts.size > CueLimits.FILES) throw CueException(CueIssue.LIMIT_EXCEEDED)
        if (matched.files.size != parsed.files.size || audioFacts.size != parsed.files.size) throw CueException(CueIssue.MISSING_AUDIO)
        val matchesByIndex = matched.files.associateBy { it.fileIndex }
        val factsByIndex = audioFacts.associateBy { it.fileIndex }
        if (matchesByIndex.size != matched.files.size || factsByIndex.size != audioFacts.size) {
            throw CueException(CueIssue.DUPLICATE_REFERENCE)
        }
        val physicalKeys = HashSet<TrackKey>()
        val objectIds = HashSet<String>()
        val entityProofs = HashSet<String>()
        val uris = HashSet<String>()
        val factsInOrder = ArrayList<CueAudioFacts>()
        for (file in parsed.files) {
            checkCancelled()
            val candidate = matchesByIndex[file.index]?.candidate ?: throw CueException(CueIssue.MISSING_AUDIO)
            val facts = factsByIndex[file.index] ?: throw CueException(CueIssue.MISSING_AUDIO)
            val descriptor = facts.descriptor
            if (descriptor.contentValidation != LocalContentValidation.VERIFIED) throw CueException(CueIssue.UNVERIFIED_CONTENT)
            if (descriptor.source != source || descriptor.objectId != candidate.objectId ||
                descriptor.location.uri != candidate.uri || descriptor.location.grantUri != source.spec.uri ||
                candidate.sourceId != source.spec.sourceId ||
                (candidate.providerContentToken != null && candidate.providerContentToken != descriptor.providerContentToken)) {
                throw CueException(CueIssue.SOURCE_CHANGED)
            }
            if (candidate.directory || candidate.objectId == cue.objectId || descriptor.trackKey.providerId != TrackKey.LOCAL_PROVIDER ||
                facts.durationUs <= 0L) throw CueException(CueIssue.INVALID_AUDIO)
            val proof = candidate.entityProof?.takeIf { it.isNotBlank() }
            if (listOf(proof, descriptor.providerContentToken, descriptor.verifiedContentToken)
                    .any { it != null && it.length > CueLimits.FIELD_CHARACTERS }) {
                throw CueException(CueIssue.LIMIT_EXCEEDED)
            }
            if (!physicalKeys.add(descriptor.trackKey) || !objectIds.add(candidate.objectId) || !uris.add(candidate.uri) ||
                (proof != null && !entityProofs.add(proof))) throw CueException(CueIssue.DUPLICATE_REFERENCE)
            factsInOrder += facts
        }
        val segments = ArrayList<CueSegment>()
        for ((index, track) in parsed.tracks.withIndex()) {
            checkCancelled()
            val facts = factsByIndex[track.fileIndex] ?: throw CueException(CueIssue.MISSING_AUDIO)
            val file = parsed.files[track.fileIndex]
            val next = parsed.tracks.getOrNull(index + 1)?.takeIf { it.fileIndex == track.fileIndex }
            val endFrame = next?.index01
            val endUs = endFrame?.toMicroseconds() ?: facts.durationUs
            if (track.index01.toMicroseconds() >= facts.durationUs || endUs > facts.durationUs ||
                endUs <= track.index01.toMicroseconds()) throw CueException(CueIssue.INVALID_BOUNDARIES)
            val albumPerformer = file.metadata.performer ?: parsed.metadata.performer
            segments += CueSegment(
                identity = CueTrackIdentity(document.identity, track.number),
                fileIndex = track.fileIndex,
                audio = facts.descriptor,
                bounds = CueSegmentBounds(track.index01, endFrame, endUs),
                index00 = track.index00,
                title = track.metadata.title,
                performer = track.metadata.performer ?: albumPerformer,
                album = file.metadata.title ?: parsed.metadata.title,
                albumPerformer = albumPerformer,
            )
        }
        checkCancelled()
        return PreparedCueGroup(document, factsInOrder.toList(), segments.toList())
    }

    private fun validateParsed(parsed: ParsedCue, checkCancelled: () -> Unit) {
        if (parsed.files.isEmpty() || parsed.tracks.isEmpty()) throw CueException(CueIssue.INVALID_INPUT)
        if (parsed.files.size > CueLimits.FILES || parsed.tracks.size > CueLimits.TRACKS) throw CueException(CueIssue.LIMIT_EXCEEDED)
        validateMetadata(parsed.metadata)
        val paths = HashSet<CueRelativePath>()
        for ((index, file) in parsed.files.withIndex()) {
            checkCancelled()
            if (file.index != index || file.path.components.isEmpty()) throw CueException(CueIssue.INVALID_INPUT)
            if (!paths.add(file.path)) throw CueException(CueIssue.DUPLICATE_REFERENCE)
            validateMetadata(file.metadata)
        }
        val starts = arrayOfNulls<CueFrameTime>(parsed.files.size)
        var previousNumber = 0
        var previousFile = 0
        for (track in parsed.tracks) {
            checkCancelled()
            if (track.number !in 1..CueLimits.TRACKS || track.number <= previousNumber ||
                track.fileIndex !in parsed.files.indices || track.fileIndex < previousFile) throw CueException(CueIssue.INVALID_TRACK)
            val previousStart = starts[track.fileIndex]
            if ((track.index00?.frames ?: 0L) > track.index01.frames ||
                (previousStart != null && (track.index01.frames <= previousStart.frames ||
                    (track.index00 != null && track.index00.frames < previousStart.frames)))) {
                throw CueException(CueIssue.INVALID_BOUNDARIES)
            }
            validateMetadata(track.metadata)
            starts[track.fileIndex] = track.index01
            previousNumber = track.number
            previousFile = track.fileIndex
        }
        if (starts.any { it == null }) throw CueException(CueIssue.INVALID_TRACK)
    }

    private fun validateMetadata(metadata: CueMetadata) {
        for (value in listOf(metadata.title, metadata.performer)) {
            if (value == null) continue
            if (value.length > CueLimits.FIELD_CHARACTERS) throw CueException(CueIssue.LIMIT_EXCEEDED)
            if (value.any { Character.isISOControl(it) }) throw CueException(CueIssue.INVALID_SYNTAX)
        }
    }
}
