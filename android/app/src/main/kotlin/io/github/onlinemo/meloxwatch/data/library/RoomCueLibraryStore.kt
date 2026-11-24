package io.github.onlinemo.meloxwatch.data.library

import android.content.Intent
import io.github.onlinemo.meloxwatch.data.cue.CueDirectoryScope
import io.github.onlinemo.meloxwatch.data.cue.CueDocumentAccess
import io.github.onlinemo.meloxwatch.data.cue.CueDocumentEntity
import io.github.onlinemo.meloxwatch.data.cue.CueDocumentIdentity
import io.github.onlinemo.meloxwatch.data.cue.CueException
import io.github.onlinemo.meloxwatch.data.cue.CueGroupCommit
import io.github.onlinemo.meloxwatch.data.cue.CueGroupVersionEntity
import io.github.onlinemo.meloxwatch.data.cue.CueNativeSegmentEntity
import io.github.onlinemo.meloxwatch.data.cue.CueIssue
import io.github.onlinemo.meloxwatch.data.cue.CueLimits
import io.github.onlinemo.meloxwatch.data.cue.CuePlaybackRead
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.cue.CueScanContext
import io.github.onlinemo.meloxwatch.data.cue.CueSegment
import io.github.onlinemo.meloxwatch.data.cue.PreparedCueGroup
import io.github.onlinemo.meloxwatch.data.cue.validateCueScope
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.scan.ScanState
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import io.github.onlinemo.meloxwatch.model.CueFileBinding
import io.github.onlinemo.meloxwatch.model.CueBoundDocumentReference
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import io.github.onlinemo.meloxwatch.model.CueSegmentBinding
import io.github.onlinemo.meloxwatch.model.CueTrackReference
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 无状态 CUE 工作只在既有资料库互斥区和沿用的写事务内执行，不自行执行 I/O。 */
internal class RoomCueLibraryStore(private val library: RoomLocalLibraryRepository) {
    private val dao = library.dao
    private val cues = library.database.cueDao()

    suspend fun registerDocument(candidate: ObservedCandidate, alias: LibraryAliasEntity) {
        writer()
        requireCue(candidate.mayContainCue && alias.sourceId == candidate.sourceId && alias.objectId == candidate.objectId,
            CueIssue.INVALID_INPUT)
        val old = cues.document(candidate.sourceId, candidate.objectId)
        if (old != null) {
            requireCue(old.aliasId == alias.aliasId && old.locationId == alias.locationId, CueIssue.SOURCE_CHANGED)
        } else cues.insertDocument(CueDocumentEntity(id(), candidate.sourceId, candidate.objectId, alias.aliasId, alias.locationId))
    }

    internal suspend fun registerPendingHint(reference: LocalCueReference, metadata: LocalAudioMetadata,
        filename: String): LocalCueReference {
        writer()
        currentCoroutineContext().ensureActive()
        requireCue(!reference.isBound && filename.isNotBlank() && filename.length <= 512 &&
            filename.none { Character.isISOControl(it) }, CueIssue.INVALID_INPUT)
        val key = reference.trackKey
        val old = dao.currentTrack(key.trackId)
        if (old != null) {
            val saved = old.cueReference?.let { CueReferenceCodec.decode(it) } ?: fail(LibraryIssue.CONFLICT)
            if (saved.trackKey != key || saved.identity.sourceId != reference.identity.sourceId ||
                saved.identity.cueId != reference.identity.cueId || saved.identity.trackNumber != reference.identity.trackNumber)
                fail(LibraryIssue.CONFLICT)
            return saved
        }
        val current = library.meta()
        val revision = increment(current.revision)
        val identity = dao.track(key.trackId) ?: LibraryTrackEntity(key.trackId, key.providerId, library.epochMs(),
            current.nextAddedSequence).also { dao.insertTrack(it) }
        if (identity.providerId != key.providerId) fail(LibraryIssue.CONFLICT)
        val draft = library.emptyTrackVersion(identity, filename, revision).copy(member = false,
            cueReference = CueReferenceCodec.encode(reference))
        dao.insertTrackVersion(draft)
        library.writeTrackVersion(draft, metadata.copy(codecParameters = null), filename, null, revision)
        if (dao.advanceCueHintMeta(current.revision, revision,
                maxOf(current.nextAddedSequence, increment(identity.firstAddedSequence))) != 1) fail(LibraryIssue.CONFLICT)
        return reference
    }

    suspend fun next(token: ScanToken): ObservedCandidate? {
        writer()
        library.checkedScan(token, allowStaging = false)
        if (dao.pendingDirectories(token.scanId) != 0L) fail(LibraryIssue.INCOMPLETE)
        if (dao.runningCueCount(token.scanId) != 0L) fail(LibraryIssue.BUSY)
        val entry = dao.nextCueFrontier(token.scanId) ?: return null
        val candidate = LibraryCodec.candidate(entry.candidate)
        requireCue(candidate.sourceId == token.sourceId && candidate.mayContainCue, CueIssue.INVALID_INPUT)
        dao.updateFrontier(entry.copy(state = RUNNING))
        return candidate
    }

    suspend fun context(token: ScanToken, candidate: ObservedCandidate): CueScanContext {
        writer()
        library.checkedScan(token, allowStaging = false)
        checkedFrontier(token, candidate)
        val source = source(token.sourceId)
        if (source.spec.kind != LocalSourceKind.TREE || source.grant.read != ObservedCapability.AVAILABLE ||
            source.grant.enumerate != ObservedCapability.AVAILABLE) throw CueException(CueIssue.AUTHORIZATION_REQUIRED)
        val document = cues.document(token.sourceId, candidate.objectId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val seen = dao.seen(token.scanId, candidate.objectId) ?: fail(LibraryIssue.INVALID_INPUT)
        if (seen.aliasId != document.aliasId) fail(LibraryIssue.SOURCE_CHANGED)
        val alias = dao.currentAlias(document.aliasId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val location = dao.location(document.locationId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val read = readDescriptor(TrackKey(TrackKey.LOCAL_PROVIDER, "cue-document:${document.cueId}"), source,
            alias, location, candidate.objectId)
        val bytes = read.sizeBytes ?: throw CueException(CueIssue.UNVERIFIED_CONTENT)
        requireCue(bytes in 1L..CueLimits.RAW_BYTES && candidate.uri == read.location.uri &&
            (candidate.sizeBytes == null || candidate.sizeBytes == bytes), CueIssue.SOURCE_CHANGED)
        val directories = ArrayList<ObservedCandidate>()
        val visited = HashSet<String>()
        var parentId = dao.currentNode(seen.nodeId)?.parentNodeId ?: throw CueException(CueIssue.INVALID_PATH)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (directories.size == CueLimits.DIRECTORY_DEPTH || !visited.add(parentId)) throw CueException(CueIssue.INVALID_PATH)
            val node = dao.nodeById(parentId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            val version = dao.currentNode(parentId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            val parentSeen = dao.seen(token.scanId, node.objectId) ?: throw CueException(CueIssue.INVALID_PATH)
            val frontier = dao.frontier(token.scanId, node.objectId) ?: throw CueException(CueIssue.INVALID_PATH)
            if (node.sourceId != token.sourceId || version.sourceId != token.sourceId || !version.directory ||
                parentSeen.nodeId != node.nodeId || frontier.state !in setOf("DONE", "FAILED")) throw CueException(CueIssue.INVALID_PATH)
            val observed = LibraryCodec.candidate(frontier.candidate)
            if (!observed.directory || observed.objectId != node.objectId || observed.uri != version.uri) throw CueException(CueIssue.SOURCE_CHANGED)
            directories += observed
            val next = version.parentNodeId ?: break
            parentId = next
        }
        val scope = CueDirectoryScope(source, directories.asReversed().toList(), candidate)
        validateCueScope(scope)
        return CueScanContext(CueDocumentAccess(CueDocumentIdentity(token.sourceId, document.cueId), source,
            alias.aliasId, candidate.objectId, read.location, read.providerContentToken, bytes), scope)
    }

    suspend fun audioDescriptor(token: ScanToken, candidate: ObservedCandidate): LocalReadDescriptor {
        writer()
        library.checkedScan(token, allowStaging = false)
        if (candidate.sourceId != token.sourceId || candidate.directory || candidate.mayContainCue || !candidate.mayContainAudio ||
            library.excludedUnlocked(token, candidate.objectId, candidate.parentObjectId, library.meta().revision)) throw CueException(CueIssue.INVALID_AUDIO)
        val seen = dao.seen(token.scanId, candidate.objectId) ?: throw CueException(CueIssue.MISSING_AUDIO)
        if (!seen.metadataProcessed) throw CueException(CueIssue.UNVERIFIED_CONTENT)
        val alias = dao.currentAlias(seen.aliasId ?: throw CueException(CueIssue.MISSING_AUDIO)) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val identity = dao.aliasById(alias.aliasId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        if (identity.objectId != candidate.objectId || alias.uri != candidate.uri ||
            (candidate.providerContentToken != null && candidate.providerContentToken != alias.providerContentToken)) throw CueException(CueIssue.SOURCE_CHANGED)
        val tracks = dao.physicalTracks(alias.locationId)
        if (tracks.size != 1) throw CueException(CueIssue.INVALID_AUDIO)
        val track = tracks.single()
        val location = dao.location(alias.locationId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        return readDescriptor(TrackKey(track.providerId, track.trackId), source(token.sourceId), alias, location, candidate.objectId)
    }

    suspend fun commit(token: ScanToken, prepared: PreparedCueGroup, pcmFacts: Map<Int, CuePcmWaveFacts>): CueGroupCommit {
        writer()
        currentCoroutineContext().ensureActive()
        val scan = library.checkedScan(token, allowStaging = false)
        val frontier = dao.frontier(token.scanId, prepared.document.objectId) ?: fail(LibraryIssue.INVALID_INPUT)
        if (frontier.state !in setOf(RUNNING, VALIDATED)) fail(LibraryIssue.CONFLICT)
        val candidate = LibraryCodec.candidate(frontier.candidate)
        val context = context(token, candidate)
        val document = cues.documentById(context.document.identity.cueId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val explicitAlias = cues.alias(document.sourceId, document.cueId)
        val before = currentGroup(document.cueId)
        validatePrepared(prepared, context, pcmFacts)
        if (before != null) {
            if (document.reassociationPending) fail(LibraryIssue.RECOVERY_REQUIRED)
            groupRows(document, before)
        } else if (document.lastGroupRevision != 0L && !document.reassociationPending &&
            (explicitAlias == null || explicitAlias.reassociationPending || cues.currentAliasVersions(explicitAlias.aliasId).size != 1))
            fail(LibraryIssue.RECOVERY_REQUIRED)
        val current = library.meta()
        val known = dao.currentLogicalCueTracks(document.sourceId, document.cueId)
        if (known.size > CueLimits.TRACKS) fail(LibraryIssue.RECOVERY_REQUIRED)
        val trialRevision = before?.groupRevision ?: increment(document.lastGroupRevision)
        val trial = drafts(prepared, pcmFacts, context, trialRevision, predicted = true)
        if (before != null && signature(trial, prepared.document.encoding.name) == before.signature) {
            cues.updateDocument(document.copy(lastIssue = null, lastEncoding = prepared.document.encoding.name))
            dao.updateFrontier(frontier.copy(state = VALIDATED))
            return CueGroupCommit(current.revision, before.groupRevision, 0L, true)
        }
        val revision = increment(current.revision)
        val groupRevision = increment(document.lastGroupRevision)
        library.writeVerifiedContent(document.locationId, prepared.document.rawContentToken, revision)
        refreshAliasContent(document.aliasId, revision)
        for (audio in prepared.audioFiles) {
            currentCoroutineContext().ensureActive()
            val descriptor = audio.descriptor
            val proof = descriptor.verifiedContentToken ?: throw CueException(CueIssue.UNVERIFIED_CONTENT)
            library.writeVerifiedContent(descriptor.location.locationId, proof, revision)
            refreshAliasContent(descriptor.aliasId, revision)
        }
        val accepted = drafts(prepared, pcmFacts, context, groupRevision, predicted = false)
        // 原生扫描独立记录事实；已确认的访问映射决定对外边界。
        val preserveLogical = explicitAlias?.lastGroupRevision?.let { it > 0L } == true ||
            document.reassociationPending && !pendingMatches(known, accepted)
        var added = 0L
        var nextAdded = current.nextAddedSequence
        val wanted = accepted.mapTo(HashSet()) { it.reference.trackKey.trackId }
        for (old in if (preserveLogical) emptyList() else known) {
            currentCoroutineContext().ensureActive()
            if (old.trackId in wanted) continue
            val reference = CueReferenceCodec.decode(old.cueReference ?: fail(LibraryIssue.RECOVERY_REQUIRED))
            if (before != null && reference.identity.groupRevision != before.groupRevision) continue
            library.writeTrackVersion(old.copy(member = false), old.metadata(), old.filename, old.metadataIssue, revision)
            dao.closeTrackRelations(old.trackId, revision)
        }
        val knownKeys = known.mapTo(HashSet()) { it.trackId }
        for (draft in if (preserveLogical) accepted.filter { it.reference.trackKey.trackId !in knownKeys } else accepted) {
            currentCoroutineContext().ensureActive()
            val key = draft.reference.trackKey
            var identity = dao.track(key.trackId)
            if (identity == null) {
                identity = LibraryTrackEntity(key.trackId, key.providerId, library.epochMs(), nextAdded)
                nextAdded = increment(nextAdded)
                added = increment(added)
                dao.insertTrack(identity)
                val empty = library.emptyTrackVersion(identity, draft.filename, revision)
                dao.insertTrackVersion(empty)
            }
            val old = dao.currentTrack(key.trackId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            if (old.providerId != key.providerId || old.cueReference == null && old.validFrom != revision) fail(LibraryIssue.CONFLICT)
            val oldReference = old.cueReference?.let { CueReferenceCodec.decode(it) }
            val member = if (document.reassociationPending && oldReference != null) old.member
                else old.member || oldReference?.identity?.groupRevision != before?.groupRevision
            val audio = requireNotNull(draft.reference.binding).audio
            val relations = dao.trackRelations(key.trackId)
            if (relations.size > 1 || relations.any { it.locationId != audio.location.locationId }) dao.closeTrackRelations(key.trackId, revision)
            if (!dao.hasTrackLocationRelation(key.trackId, audio.location.locationId)) {
                dao.insertRelation(LibraryTrackLocationEntity(id(), key.trackId, audio.location.locationId, revision, null))
            }
            library.writeTrackVersion(old.copy(member = member, cueReference = CueReferenceCodec.encode(draft.reference)),
                draft.metadata, draft.filename, null, revision, draft.fallbackTitle)
        }
        currentCoroutineContext().ensureActive()
        if (before != null && cues.closeGroup(document.cueId, revision) != 1) fail(LibraryIssue.RECOVERY_REQUIRED)
        val cue = requireNotNull(accepted.first().reference.binding).cue
        val groupId = id()
        cues.insertGroup(CueGroupVersionEntity(groupId, document.cueId, groupRevision,
            signature(accepted, prepared.document.encoding.name), prepared.document.rawContentToken, accepted.size,
            cue.sourceRevision, cue.authEpoch, cue.grantId, cue.location.contentRevision, prepared.document.encoding.name,
            prepared.boundaryVersion, revision, null))
        cues.insertNativeSegments(accepted.map { draft -> CueNativeSegmentEntity(groupId,
            draft.reference.identity.trackNumber, draft.reference.trackKey.trackId, CueReferenceCodec.encode(draft.reference),
            LibraryCodec.metadata(draft.metadata), draft.filename,
            draft.metadata.title?.takeIf { it.isNotBlank() } ?: draft.fallbackTitle) })
        cues.updateDocument(document.copy(lastGroupRevision = groupRevision, lastIssue = null, lastEncoding = prepared.document.encoding.name, reassociationPending = false))
        dao.updateFrontier(frontier.copy(state = VALIDATED))
        dao.updateScan(scan.copy(added = Math.addExact(scan.added, added)))
        dao.updateMeta(current.copy(revision = revision, nextAddedSequence = nextAdded))
        return CueGroupCommit(revision, groupRevision, added, false)
    }

    suspend fun complete(token: ScanToken, objectId: String, issue: CueIssue?): ScanState {
        writer()
        val scan = library.checkedScan(token, allowStaging = false)
        val frontier = dao.frontier(token.scanId, objectId) ?: fail(LibraryIssue.INVALID_INPUT)
        val document = cues.document(token.sourceId, objectId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        if (frontier.state in setOf("DONE", "FAILED")) {
            if ((frontier.state == "DONE") != (issue == null) || document.lastIssue != issue?.name) fail(LibraryIssue.CONFLICT)
            return scan.model()
        }
        if (issue == null && frontier.state != VALIDATED || issue != null && frontier.state != RUNNING) fail(LibraryIssue.INCOMPLETE)
        val seen = dao.seen(token.scanId, objectId) ?: fail(LibraryIssue.INVALID_INPUT)
        if (seen.metadataProcessed) fail(LibraryIssue.RECOVERY_REQUIRED)
        cues.updateDocument(document.copy(lastIssue = issue?.name))
        dao.updateSeen(seen.copy(metadataProcessed = true))
        dao.updateFrontier(frontier.copy(state = if (issue == null) "DONE" else "FAILED"))
        val next = scan.copy(processed = increment(scan.processed), failed = if (issue == null) scan.failed else increment(scan.failed),
            issue = issue?.let(::scanIssue)?.name ?: scan.issue)
        dao.updateScan(next)
        return next.model()
    }

    suspend fun resolve(key: TrackKey, expected: LocalCueReference?): CuePlaybackRead {
        writer()
        if (key.providerId != TrackKey.LOCAL_PROVIDER) fail(LibraryIssue.INVALID_INPUT)
        val track = dao.currentTrack(key.trackId) ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
        if (expected == null && !track.member) fail(LibraryIssue.SOURCE_UNAVAILABLE)
        val reference = track.cueReference?.let { CueReferenceCodec.decode(it) } ?: fail(LibraryIssue.SOURCE_CHANGED)
        if (reference.trackKey != key || expected != null && expected != reference) fail(LibraryIssue.SOURCE_CHANGED)
        val binding = reference.binding ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
        val native = nativeGroup(reference.actualDocument)
        val segment = native.segments.singleOrNull { it.reference.identity.trackNumber == reference.identity.trackNumber }
            ?: fail(LibraryIssue.SOURCE_CHANGED)
        if (!sameAcceptedSegment(reference, segment.reference)) fail(LibraryIssue.SOURCE_CHANGED)
        if (binding.adoptionAliasId != null) library.validateCueAliasUnlocked(reference, native.group)
        else if (reference != segment.reference) fail(LibraryIssue.SOURCE_CHANGED)
        return nativeRead(reference).copy(metadata = track.metadata())
    }

    /** 重核完整原生行、授权和两份文件围栏，不执行提供方 I/O。 */
    internal suspend fun nativeGroup(actual: CueBoundDocumentReference): RoomNativeCueGroup {
        writer()
        val document = cues.documentById(actual.cueId) ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
        if (document.reassociationPending) fail(LibraryIssue.SOURCE_UNAVAILABLE)
        if (document.sourceId != actual.sourceId) fail(LibraryIssue.SOURCE_CHANGED)
        val group = currentGroup(document.cueId) ?: fail(LibraryIssue.SOURCE_CHANGED)
        if (group.groupRevision != actual.groupRevision) fail(LibraryIssue.SOURCE_CHANGED)
        val segments = groupRows(document, group)
        for (segment in segments) {
            currentCoroutineContext().ensureActive()
            val binding = segment.reference.binding ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            checkedBinding(binding.cue)
            checkedBinding(binding.audio)
        }
        return RoomNativeCueGroup(document, group, segments)
    }

    internal suspend fun registeredSelection(key: TrackKey): CueBoundDocumentReference {
        writer()
        val row = dao.currentTrack(key.trackId) ?: fail(LibraryIssue.FILE_MISSING)
        val reference = row.cueReference?.let { CueReferenceCodec.decode(it) } ?: fail(LibraryIssue.INVALID_INPUT)
        if (reference.trackKey != key) fail(LibraryIssue.INVALID_INPUT)
        val document = cues.documentById(reference.identity.cueId) ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
        if (document.sourceId != reference.identity.sourceId) fail(LibraryIssue.SOURCE_CHANGED)
        if (document.reassociationPending) fail(LibraryIssue.SOURCE_UNAVAILABLE)
        val group = currentGroup(document.cueId) ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
        val actual = CueBoundDocumentReference(document.sourceId, document.cueId, group.groupRevision, reference.identity.trackNumber)
        if (nativeGroup(actual).segments.none { it.reference.trackKey == key }) fail(LibraryIssue.INCOMPLETE)
        return actual
    }

    internal suspend fun nativeRead(reference: LocalCueReference): CuePlaybackRead {
        writer()
        val binding = reference.binding ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
        return CuePlaybackRead(reference,
            descriptor(binding.cue, checkedBinding(binding.cue), TrackKey(TrackKey.LOCAL_PROVIDER, "cue-document:${reference.actualDocument.cueId}")),
            descriptor(binding.audio, checkedBinding(binding.audio), reference.trackKey).copy(cueReference = reference))
    }

    suspend fun relativeMatches(view: LibraryViewToken, rootNodeId: String, components: List<String>, number: Int): List<TrackKey> {
        writer()
        requireCue(number in 1..CueLimits.TRACKS, CueIssue.INVALID_TRACK)
        val documents = dao.cuePathDocuments(LibrarySql.scopedCueDocumentMatches(view.revision, view.query.sourceId
            ?: dao.currentNode(rootNodeId)?.sourceId ?: fail(LibraryIssue.INVALID_INPUT), rootNodeId, components))
        if (documents.size > 1) return documents.map { io.github.onlinemo.meloxwatch.model.cueTrackKey(it.sourceId, it.cueId, number) }
        val document = documents.singleOrNull() ?: return emptyList()
        val key = io.github.onlinemo.meloxwatch.model.cueTrackKey(document.sourceId, document.cueId, number)
        val row = dao.rows(LibrarySql.row(view, key.providerId, key.trackId)).singleOrNull() ?: return emptyList()
        val reference = row.cueReference() ?: return emptyList()
        return if (reference.isBound && reference.identity.trackNumber == number && reference.actualDocument.cueId == document.cueId &&
            reference.actualDocument.sourceId == document.sourceId && row.availabilityRank == 0) listOf(key) else emptyList()
    }

    suspend fun relativePath(view: LibraryViewToken, track: LibraryTrack): String? {
        writer()
        val reference = track.cueReference ?: return null
        val binding = reference.binding ?: return null
        val document = cues.documentById(reference.actualDocument.cueId) ?: return null
        if (document.sourceId != track.sourceId || document.aliasId != binding.cue.aliasId) return null
        val alias = dao.aliasRowsForCue(LibrarySql.cueAliasAt(view.revision, document.aliasId)).singleOrNull() ?: return null
        return dao.text(LibrarySql.relativePath(view.revision, document.sourceId, alias.nodeId))
    }

    private suspend fun drafts(prepared: PreparedCueGroup, pcm: Map<Int, CuePcmWaveFacts>, context: CueScanContext,
        groupRevision: Long, predicted: Boolean): List<Draft> {
        val document = context.document
        val cueDescriptor = document.readDescriptor(prepared.document.rawContentToken)
        val cue = fileBinding(cueDescriptor, prepared.document.rawContentToken, predicted)
        val cueName = context.scope.cue.displayName
        return prepared.segments.map { segment ->
            currentCoroutineContext().ensureActive()
            val wave = pcm[segment.fileIndex] ?: throw CueException(CueIssue.INVALID_AUDIO)
            val reference = LocalCueReference(CueTrackReference(document.identity.sourceId, document.identity.cueId,
                segment.identity.number, groupRevision, segment.bounds.start.frames, segment.bounds.endFrameExclusive?.frames,
                segment.bounds.endUsExclusive, segment.index00?.frames, prepared.document.rawContentToken, prepared.boundaryVersion),
                CueSegmentBinding(cue, fileBinding(segment.audio, segment.audio.verifiedContentToken
                    ?: throw CueException(CueIssue.UNVERIFIED_CONTENT), predicted), wave))
            val alias = dao.currentAlias(segment.audio.aliasId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            val duration = requireNotNull(reference.pcmWindow).durationUs / 1000L
            val metadata = LocalAudioMetadata(segment.title, segment.performer, segment.performer?.let(::listOf).orEmpty(),
                segment.album, segment.albumPerformer, duration.takeIf { it > 0L }, trackNumber = segment.identity.number,
                mimeType = "audio/wav", parserVersion = CueLimits.BOUNDARY_VERSION,
                codecParameters = LocalCodecParameters("WAV", "PCM", wave.sampleRateHz, 16, wave.channels, wave.sampleRateHz, 16))
            Draft(reference, metadata, alias.filename, "$cueName #${segment.identity.number.toString().padStart(2, '0')}")
        }
    }

    private suspend fun fileBinding(read: LocalReadDescriptor, token: String, predicted: Boolean): CueFileBinding {
        val location = dao.location(read.location.locationId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val revision = if (predicted) predictedRevision(location, token) else location.contentRevision
        return CueFileBinding(read.source.spec.sourceId, read.source.revision, read.source.authEpoch, read.source.grantId,
            read.aliasId, read.objectId, read.location.copy(contentRevision = revision), read.providerContentToken,
            token, read.sizeBytes ?: throw CueException(CueIssue.UNVERIFIED_CONTENT))
    }

    private suspend fun validatePrepared(prepared: PreparedCueGroup, context: CueScanContext, pcm: Map<Int, CuePcmWaveFacts>) {
        requireCue(prepared.document.identity == context.document.identity && prepared.document.source.spec == context.document.source.spec &&
            prepared.document.source.revision == context.document.source.revision && prepared.document.source.authEpoch == context.document.source.authEpoch &&
            prepared.document.source.grantId == context.document.source.grantId && prepared.document.location == context.document.location &&
            prepared.document.objectId == context.document.objectId && prepared.document.rawContentToken.matches(TOKEN), CueIssue.SOURCE_CHANGED)
        requireCue(prepared.boundaryVersion == CueLimits.BOUNDARY_VERSION && prepared.segments.size in 1..CueLimits.TRACKS &&
            prepared.audioFiles.size in 1..CueLimits.FILES && pcm.keys == prepared.audioFiles.map { it.fileIndex }.toSet(), CueIssue.INVALID_INPUT)
        val files = HashMap<Int, LocalReadDescriptor>()
        val locations = HashSet<String>()
        val objects = HashSet<String>()
        val keys = HashSet<TrackKey>()
        for (audio in prepared.audioFiles) {
            currentCoroutineContext().ensureActive()
            val read = audio.descriptor
            val wave = pcm[audio.fileIndex] ?: throw CueException(CueIssue.INVALID_AUDIO)
            requireCue(files.put(audio.fileIndex, read) == null && locations.add(read.location.locationId) && objects.add(read.objectId) &&
                keys.add(read.trackKey) && read.trackKey.providerId == TrackKey.LOCAL_PROVIDER && read.cueReference == null &&
                read.source.spec == context.document.source.spec && read.source.grantId == context.document.source.grantId &&
                read.source.revision == context.document.source.revision && read.source.authEpoch == context.document.source.authEpoch &&
                read.location.locationId != context.document.location.locationId && read.objectId != context.document.objectId &&
                read.contentValidation == LocalContentValidation.VERIFIED && read.verifiedContentToken?.matches(TOKEN) == true &&
                read.sizeBytes == wave.fileBytes && audio.durationUs == wave.durationUs, CueIssue.UNVERIFIED_CONTENT)
            checkedDescriptor(read)
            if (dao.physicalTracks(read.location.locationId).singleOrNull()?.trackId != read.trackKey.trackId) throw CueException(CueIssue.INVALID_AUDIO)
        }
        var previousNumber = 0
        var previousFile = -1
        val visitedFiles = HashSet<Int>()
        for ((index, segment) in prepared.segments.withIndex()) {
            currentCoroutineContext().ensureActive()
            val read = files[segment.fileIndex] ?: throw CueException(CueIssue.MISSING_AUDIO)
            requireCue(segment.identity.document == context.document.identity && segment.identity.number > previousNumber &&
                segment.fileIndex >= previousFile && segment.audio == read, CueIssue.INVALID_TRACK)
            val next = prepared.segments.getOrNull(index + 1)?.takeIf { it.fileIndex == segment.fileIndex }
            requireCue(segment.bounds.boundaryVersion == prepared.boundaryVersion &&
                segment.bounds.endFrameExclusive == next?.bounds?.start &&
                segment.bounds.endUsExclusive == (next?.bounds?.startUs ?: pcm.getValue(segment.fileIndex).durationUs), CueIssue.INVALID_BOUNDARIES)
            requireCue(segment.index00 == null || segment.index00.frames <= segment.bounds.start.frames,
                CueIssue.INVALID_BOUNDARIES)
            if (index > 0 && previousFile == segment.fileIndex) {
                val prior = prepared.segments[index - 1]
                requireCue(segment.bounds.start.frames > prior.bounds.start.frames &&
                    (segment.index00 == null || segment.index00.frames >= prior.bounds.start.frames), CueIssue.INVALID_BOUNDARIES)
            }
            for (text in listOf(segment.title, segment.performer, segment.album, segment.albumPerformer)) {
                requireCue(text == null || text.length <= CueLimits.FIELD_CHARACTERS && text.none { Character.isISOControl(it) }, CueIssue.INVALID_SYNTAX)
            }
            previousNumber = segment.identity.number
            previousFile = segment.fileIndex
            visitedFiles += segment.fileIndex
        }
        requireCue(visitedFiles == files.keys && files.keys == (0 until files.size).toSet(), CueIssue.INVALID_TRACK)
    }

    private suspend fun checkedDescriptor(read: LocalReadDescriptor) {
        val current = source(read.source.spec.sourceId)
        requireCue(current.spec == read.source.spec && current.revision == read.source.revision && current.authEpoch == read.source.authEpoch &&
            current.grantId == read.source.grantId && current.state == LibrarySourceState.ACTIVE && current.grant.read == ObservedCapability.AVAILABLE,
            CueIssue.SOURCE_CHANGED)
        val identity = dao.aliasById(read.aliasId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val alias = dao.currentAlias(read.aliasId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val location = dao.location(read.location.locationId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        requireCue(identity.sourceId == current.spec.sourceId && identity.objectId == read.objectId && identity.locationId == read.location.locationId &&
            alias.uri == read.location.uri && alias.sourceId == current.spec.sourceId &&
            location.contentRevision == read.location.contentRevision && location.contentToken == read.providerContentToken &&
            (location.sizeBytes == null || location.sizeBytes == read.sizeBytes) &&
            (alias.sizeBytes == null || alias.sizeBytes == read.sizeBytes), CueIssue.SOURCE_CHANGED)
        accessIssue(alias.readIssue)
    }

    private suspend fun checkedBinding(binding: CueFileBinding): LibrarySource {
        val current = source(binding.sourceId)
        if (current.state == LibrarySourceState.NEEDS_AUTHORIZATION || current.grant.read == ObservedCapability.UNAVAILABLE) fail(LibraryIssue.PERMISSION_REVOKED)
        if (current.state != LibrarySourceState.ACTIVE || current.grant.read != ObservedCapability.AVAILABLE) fail(LibraryIssue.SOURCE_UNAVAILABLE)
        if (current.revision != binding.sourceRevision || current.authEpoch != binding.authEpoch || current.grantId != binding.grantId ||
            current.spec.uri != binding.location.grantUri) fail(LibraryIssue.SOURCE_CHANGED)
        val identity = dao.aliasById(binding.aliasId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val alias = dao.currentAlias(binding.aliasId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val location = dao.location(binding.location.locationId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        if (identity.sourceId != binding.sourceId || identity.objectId != binding.objectId || identity.locationId != binding.location.locationId ||
            alias.uri != binding.location.uri || alias.locationId != binding.location.locationId ||
            location.contentRevision != binding.location.contentRevision || location.contentToken != binding.providerContentToken ||
            location.sizeBytes != null && location.sizeBytes != binding.sizeBytes || alias.sizeBytes != null && alias.sizeBytes != binding.sizeBytes ||
            location.verifiedContentToken != null && location.verifiedContentToken != binding.verifiedContentToken ||
            location.verifiedContentToken == null && location.previousVerifiedContentToken != null && location.previousVerifiedContentToken != binding.verifiedContentToken) fail(LibraryIssue.SOURCE_CHANGED)
        accessIssue(alias.readIssue)
        val sourceRow = dao.source(binding.sourceId) ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
        if (sourceRow.absenceScanId != null && sourceRow.absenceScanSequence != null && alias.lastSeenSequence < sourceRow.absenceScanSequence &&
            dao.scalar(LibrarySql.raw("SELECT EXISTS (SELECT 1 FROM scan_absence_candidates WHERE scanId = ? AND aliasId = ?)",
                arrayOf(sourceRow.absenceScanId, binding.aliasId))) != 0L) fail(LibraryIssue.FILE_MISSING)
        return current
    }

    private suspend fun groupRows(document: CueDocumentEntity, group: CueGroupVersionEntity): List<RoomNativeCueSegment> {
        if (group.groupRevision <= 0L || group.groupRevision != document.lastGroupRevision || group.segmentCount !in 1..CueLimits.TRACKS ||
            group.boundaryVersion != CueLimits.BOUNDARY_VERSION || !group.signature.matches(TOKEN) || !group.rawContentToken.matches(TOKEN)) fail(LibraryIssue.RECOVERY_REQUIRED)
        val rows = cues.nativeSegments(group.versionId)
        if (rows.size != group.segmentCount) fail(LibraryIssue.RECOVERY_REQUIRED)
        val drafts = rows.map { row ->
            val reference = CueReferenceCodec.decode(row.cueReference)
            val binding = reference.binding ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            if (reference.trackKey != TrackKey(TrackKey.LOCAL_PROVIDER, row.trackId) || reference.identity.cueId != document.cueId ||
                reference.identity.sourceId != document.sourceId || reference.identity.trackNumber != row.trackNumber ||
                reference.identity.groupRevision != group.groupRevision || reference.identity.cueContentToken != group.rawContentToken ||
                binding.actualDocument != null || binding.adoptionAliasId != null || binding.cue.aliasId != document.aliasId ||
                binding.cue.objectId != document.objectId || binding.cue.location.locationId != document.locationId ||
                binding.cue.location.contentRevision != group.documentContentRevision || binding.cue.sourceRevision != group.sourceRevision ||
                binding.cue.authEpoch != group.authEpoch || binding.cue.grantId != group.grantId ||
                reference.identity.boundaryVersion != group.boundaryVersion || binding.audio.sourceId != document.sourceId ||
                binding.audio.sourceRevision != group.sourceRevision || binding.audio.authEpoch != group.authEpoch ||
                binding.audio.grantId != group.grantId || binding.audio.verifiedContentToken?.matches(TOKEN) != true) fail(LibraryIssue.RECOVERY_REQUIRED)
            Draft(reference, LibraryCodec.metadata(row.metadata), row.filename, row.displayTitle)
        }
        if (signature(drafts, group.encoding, effectiveTitles = rows.map { it.displayTitle }) != group.signature) fail(LibraryIssue.RECOVERY_REQUIRED)
        return drafts.map { RoomNativeCueSegment(it.reference, it.metadata, it.filename, it.fallbackTitle) }
    }

    private fun pendingMatches(known: List<LibraryTrackVersionEntity>, accepted: List<Draft>): Boolean {
        if (known.size != accepted.size || known.isEmpty()) return false
        val native = accepted.associateBy { it.reference.identity.trackNumber }
        return known.all { row ->
            val old = row.cueReference?.let { CueReferenceCodec.decode(it) } ?: return@all false
            val next = native[old.identity.trackNumber]?.reference ?: return@all false
            old.portable().identity.audioContentTokenHint != null &&
                old.portable().identity.audioContentTokenHint == next.binding?.audio?.verifiedContentToken &&
                old.identity.cueContentToken == next.identity.cueContentToken && sameCueBounds(old, next)
        }
    }

    private suspend fun checkedFrontier(token: ScanToken, candidate: ObservedCandidate) {
        val frontier = dao.frontier(token.scanId, candidate.objectId) ?: fail(LibraryIssue.INVALID_INPUT)
        if (frontier.state !in setOf(RUNNING, VALIDATED) || LibraryCodec.candidate(frontier.candidate) != candidate || !candidate.mayContainCue) fail(LibraryIssue.CONFLICT)
    }
    private suspend fun refreshAliasContent(aliasId: String, revision: Long) {
        val alias = dao.currentAlias(aliasId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val location = dao.location(alias.locationId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        if (alias.contentRevision != location.contentRevision) library.writeAliasVersion(alias.copy(contentRevision = location.contentRevision), revision)
    }
    private suspend fun currentGroup(cueId: String): CueGroupVersionEntity? {
        val rows = cues.currentGroups(cueId)
        if (rows.size > 1) fail(LibraryIssue.RECOVERY_REQUIRED)
        return rows.singleOrNull()
    }
    private suspend fun source(sourceId: String): LibrarySource = library.sourceModel(dao.source(sourceId) ?: fail(LibraryIssue.SOURCE_UNAVAILABLE))
    private fun descriptor(binding: CueFileBinding, source: LibrarySource, key: TrackKey) = LocalReadDescriptor(key, source,
        binding.aliasId, binding.location, binding.objectId, binding.providerContentToken, binding.sizeBytes,
        verifiedContentToken = binding.verifiedContentToken)
    private fun readDescriptor(key: TrackKey, source: LibrarySource, alias: LibraryAliasVersionEntity,
        location: LibraryLocationEntity, objectId: String): LocalReadDescriptor {
        accessIssue(alias.readIssue)
        val access = when {
            source.grant.appOwned && source.spec.kind == LocalSourceKind.APP_OWNED -> LocalAccess.APP_OWNED
            source.spec.kind == LocalSourceKind.MEDIA_STORE -> LocalAccess.MEDIA_PERMISSION
            source.grant.persistedRead == ObservedCapability.AVAILABLE && source.grant.acceptedPersistedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 -> LocalAccess.PERSISTED
            else -> LocalAccess.TEMPORARY
        }
        return LocalReadDescriptor(key, source, alias.aliasId,
            LocalLocationReference(location.locationId, alias.uri, source.spec.uri, source.grant.acceptedPersistedFlags, access, location.contentRevision),
            objectId, location.contentToken, location.sizeBytes, location.verifiedContentToken ?: location.previousVerifiedContentToken)
    }
    private fun signature(drafts: List<Draft>, encoding: String, effectiveTitles: List<String>? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII));
            digest.update(':'.code.toByte());
            digest.update(bytes)
        }
        field("melox-cue-group:v1");
        field(encoding);
        field(drafts.size.toString())
        drafts.forEachIndexed { index, draft ->
            field(CueReferenceCodec.encode(draft.reference));
            field(LibraryCodec.metadata(draft.metadata));
            field(draft.filename)
            field(effectiveTitles?.get(index) ?: draft.metadata.title?.takeIf { it.isNotBlank() } ?: draft.fallbackTitle)
        }
        return "sha256:" + digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    private fun predictedRevision(location: LibraryLocationEntity, token: String): Long {
        val previous = location.verifiedContentToken ?: location.previousVerifiedContentToken
        return if (previous != null && previous != token) increment(location.contentRevision) else location.contentRevision
    }
    private fun accessIssue(issue: String?) {
        when (issue) {
            SourceReadIssue.PERMISSION_DENIED.name, SourceReadIssue.PERMISSION_REVOKED.name -> fail(LibraryIssue.PERMISSION_REVOKED)
            SourceReadIssue.FILE_MISSING.name -> fail(LibraryIssue.FILE_MISSING)
            SourceReadIssue.SOURCE_UNAVAILABLE.name -> fail(LibraryIssue.SOURCE_UNAVAILABLE)
            SourceReadIssue.SOURCE_CHANGED.name -> fail(LibraryIssue.SOURCE_CHANGED)
            else -> Unit
        }
    }
    private fun scanIssue(issue: CueIssue): SourceReadIssue = when (issue) {
        CueIssue.AUTHORIZATION_REQUIRED -> SourceReadIssue.PERMISSION_REVOKED
        CueIssue.SOURCE_CHANGED -> SourceReadIssue.SOURCE_CHANGED
        CueIssue.MISSING_AUDIO -> SourceReadIssue.FILE_MISSING
        CueIssue.UNVERIFIED_CONTENT -> SourceReadIssue.CONTENT_UNKNOWN
        else -> SourceReadIssue.METADATA_UNREADABLE
    }
    private suspend fun writer() { if (!library.database.hasCurrentWriterTransaction()) fail(LibraryIssue.RECOVERY_REQUIRED) }
    private fun requireCue(valid: Boolean, issue: CueIssue) { if (!valid) throw CueException(issue) }
    private fun increment(value: Long): Long = try { Math.addExact(value, 1L) } catch (_: ArithmeticException) { fail(LibraryIssue.STORAGE_FULL) }
    private fun fail(issue: LibraryIssue): Nothing = throw LibraryException(issue)
    private fun id(): String = UUID.randomUUID().toString()
    private data class Draft(val reference: LocalCueReference, val metadata: LocalAudioMetadata, val filename: String, val fallbackTitle: String)
    private companion object {
        const val RUNNING = "CUE_RUNNING"
        const val VALIDATED = "CUE_VALIDATED"
        val TOKEN = Regex("sha256:[0-9a-f]{64}")
    }
}

internal data class RoomNativeCueSegment(val reference: LocalCueReference, val metadata: LocalAudioMetadata,
    val filename: String, val displayTitle: String)

internal data class RoomNativeCueGroup(val document: CueDocumentEntity, val group: CueGroupVersionEntity,
    val segments: List<RoomNativeCueSegment>)

internal fun sameCueBounds(first: LocalCueReference, second: LocalCueReference): Boolean {
    val old = first.identity
    val next = second.identity
    return old.trackNumber == next.trackNumber && old.startFrame == next.startFrame &&
        old.endFrameExclusive == next.endFrameExclusive && old.endUsExclusive == next.endUsExclusive &&
        old.index00Frame == next.index00Frame && old.boundaryVersion == next.boundaryVersion &&
        (first.binding == null || second.binding == null || first.binding.pcm == second.binding.pcm && first.pcmWindow == second.pcmWindow)
}

/** 只有经过单独验证并持久化的别名，才能允许逻辑标识不同。 */
internal fun sameAcceptedSegment(logical: LocalCueReference, native: LocalCueReference): Boolean {
    val binding = logical.binding ?: return false
    val original = native.binding ?: return false
    return logical.actualDocument == native.actualDocument && sameCueBounds(logical, native) &&
        logical.identity.cueContentToken == native.identity.cueContentToken &&
        (logical.identity.audioContentTokenHint == null || logical.identity.audioContentTokenHint == original.audio.verifiedContentToken) &&
        binding.copy(actualDocument = null, adoptionAliasId = null) == original
}

/** 旧可移植修订只能复用完全匹配的字节与边界，以及当前已授权的绑定。 */
internal fun currentCueMatchesPortableHint(current: LocalCueReference, hint: LocalCueReference): Boolean =
    current.isBound && !hint.isBound && current.trackKey == hint.trackKey &&
        current.identity.sourceId == hint.identity.sourceId && current.identity.cueId == hint.identity.cueId &&
        hint.identity.groupRevision <= current.identity.groupRevision && sameCueBounds(current, hint) &&
        current.identity.cueContentToken == hint.identity.cueContentToken && hint.identity.audioContentTokenHint != null &&
        hint.identity.audioContentTokenHint == current.binding?.audio?.verifiedContentToken

internal fun sameCueAccess(first: LocalCueReference, second: LocalCueReference): Boolean = first.isBound && second.isBound &&
    first.actualDocument == second.actualDocument && first.binding?.cue == second.binding?.cue && first.binding?.audio == second.binding?.audio
