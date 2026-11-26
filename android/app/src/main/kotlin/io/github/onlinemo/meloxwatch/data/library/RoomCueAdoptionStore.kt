package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.cue.CueAdoptionCommit
import io.github.onlinemo.meloxwatch.data.cue.CueAdoptionDifferences
import io.github.onlinemo.meloxwatch.data.cue.CueAdoptionPlan
import io.github.onlinemo.meloxwatch.data.cue.CueAdoptionSourceMember
import io.github.onlinemo.meloxwatch.data.cue.CueAdoptionTargetMember
import io.github.onlinemo.meloxwatch.data.cue.CueAliasEntity
import io.github.onlinemo.meloxwatch.data.cue.CueAliasSegmentEntity
import io.github.onlinemo.meloxwatch.data.cue.CueAliasVersionEntity
import io.github.onlinemo.meloxwatch.data.cue.CueGroupVersionEntity
import io.github.onlinemo.meloxwatch.data.cue.CueLimits
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.queue.QueueGenerationEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueHeadEntity
import io.github.onlinemo.meloxwatch.data.queue.QueuePhase
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.model
import io.github.onlinemo.meloxwatch.model.CueBoundDocumentReference
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 有界预览使用既有资料库写事务；提供方验证在该事务之外执行。 */
internal class RoomCueAdoptionStore(private val library: RoomLocalLibraryRepository,
    private val native: RoomCueLibraryStore, private val elapsedMs: () -> Long) {
    private val dao = library.dao
    private val cues = library.database.cueDao()
    private val queue = library.database.queueDao()
    private val playlists = RoomCuePlaylistStore(library)
    private var pending: Pending? = null

    suspend fun needsReassociation(key: TrackKey): Boolean {
        writer()
        val row = dao.currentTrack(key.trackId) ?: return false
        val saved = row.cueReference?.let { CueReferenceCodec.decode(it) } ?: return false
        if (saved.trackKey != key) fail(LibraryIssue.RECOVERY_REQUIRED)
        if (!saved.isBound) return true
        try { native.resolve(key, saved) }
        catch (error: LibraryException) {
            if (error.issue in setOf(LibraryIssue.STORAGE_FULL, LibraryIssue.STORAGE_UNAVAILABLE)) throw error
            return true
        }
        if (playlists.mismatchedHints(key, saved)) return true
        val generation = queue.head()?.activeGenerationId ?: return false
        var after: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = queue.cueTrackEntryPage(generation, key.providerId, key.trackId, after)
            if (page.isEmpty()) return false
            if (page.any { it.cueReference?.let { value -> CueReferenceCodec.decode(value) } != saved }) return true
            after = page.last().queueEntryId
        }
    }

    suspend fun preview(missing: TrackKey, selected: TrackKey, nativeSelection: CueBoundDocumentReference? = null): CueAdoptionPlan {
        writer()
        currentCoroutineContext().ensureActive()
        pending = null
        if (missing.providerId != TrackKey.LOCAL_PROVIDER || selected.providerId != TrackKey.LOCAL_PROVIDER) fail(LibraryIssue.INVALID_INPUT)
        val missingRow = dao.currentTrack(missing.trackId) ?: fail(LibraryIssue.FILE_MISSING)
        val missingRef = reference(missingRow)
        if (missingRef.trackKey != missing) fail(LibraryIssue.RECOVERY_REQUIRED)
        val targets = dao.currentLogicalCueTracks(missingRef.identity.sourceId, missingRef.identity.cueId)
        if (targets.size !in 1..CueLimits.TRACKS || targets.none { it.trackId == missing.trackId }) fail(LibraryIssue.INCOMPLETE)
        val oldMembers = targets.map { row ->
            val old = reference(row)
            if (old.trackKey != TrackKey(row.providerId, row.trackId)) fail(LibraryIssue.RECOVERY_REQUIRED)
            CueAdoptionTargetMember(old.trackKey, old, row.displayTitle, row.member)
        }.sortedBy { it.reference.identity.trackNumber }
        if (oldMembers.map { it.reference.identity.trackNumber }.distinct().size != oldMembers.size) fail(LibraryIssue.RECOVERY_REQUIRED)

        val selectedActual = if (nativeSelection != null) {
            // 显式选择原生文档时，可以选取被既有公开别名隐藏的事实。
            if (io.github.onlinemo.meloxwatch.model.cueTrackKey(nativeSelection.sourceId, nativeSelection.cueId,
                    nativeSelection.trackNumber) != selected) fail(LibraryIssue.INVALID_INPUT)
            nativeSelection
        } else {
            val selectedRow = dao.currentTrack(selected.trackId) ?: fail(LibraryIssue.FILE_MISSING)
            val selectedRef = reference(selectedRow)
            if (!selectedRow.member || selectedRef.trackKey != selected) fail(LibraryIssue.INVALID_INPUT)
            if (selectedRef.isBound) native.resolve(selected, selectedRef).reference.actualDocument else {
                // 待确认的逻辑提示不授予访问能力；只能选择已独立发布的原生组。
                val document = cues.documentById(selectedRef.identity.cueId) ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
                if (document.sourceId != selectedRef.identity.sourceId) fail(LibraryIssue.SOURCE_CHANGED)
                CueBoundDocumentReference(document.sourceId, document.cueId, document.lastGroupRevision, selectedRef.identity.trackNumber)
            }
        }
        val group = native.nativeGroup(selectedActual)
        if (group.segments.none { it.reference.identity.trackNumber == selectedActual.trackNumber }) fail(LibraryIssue.INCOMPLETE)
        val sources = group.segments.map { segment ->
            val read = native.nativeRead(segment.reference)
            CueAdoptionSourceMember(segment.reference.trackKey, segment.reference,
                segment.metadata.copy(artists = Collections.unmodifiableList(segment.metadata.artists.toList())), read.cue, read.audio)
        }
        val byNumber = sources.associateBy { it.reference.identity.trackNumber }
        if (oldMembers.any { it.reference.identity.trackNumber !in byNumber }) fail(LibraryIssue.INCOMPLETE)
        val alias = cues.alias(missingRef.identity.sourceId, missingRef.identity.cueId)
        val aliasVersion = alias?.let { currentAlias(it.aliasId) }
        val documentRevision = cues.documentById(missingRef.identity.cueId)
            ?.takeIf { it.sourceId == missingRef.identity.sourceId }?.lastGroupRevision ?: 0L
        val head = queue.head()
        val generation = checkedGeneration(head)
        val projection = queueProjection(head?.activeGenerationId, oldMembers, byNumber.mapValues { it.value.reference })
        val playlistProof = playlists.preview(oldMembers, byNumber.mapValues { it.value.reference })
        val logicalRevision = increment(maxOf(documentRevision, alias?.lastGroupRevision ?: 0L,
            oldMembers.maxOf { it.reference.identity.groupRevision }, group.group.groupRevision, projection.highestRevision,
            playlistProof.highestHintRevision))
        var cueChanged = projection.cueChanged || playlistProof.cueChanged
        val unknownAudio = (projection.unknownAudio + playlistProof.unknownAudio).toMutableSet()
        val changedAudio = (projection.changedAudio + playlistProof.changedAudio).toMutableSet()
        val changedBounds = (projection.changedBounds + playlistProof.changedBounds).toMutableSet()
        for (target in oldMembers) {
            val old = target.reference
            val next = byNumber.getValue(old.identity.trackNumber).reference
            cueChanged = cueChanged || old.identity.cueContentToken != next.identity.cueContentToken
            val oldAudio = old.portable().identity.audioContentTokenHint
            if (oldAudio == null) unknownAudio += old.identity.trackNumber
            else if (oldAudio != next.binding?.audio?.verifiedContentToken) changedAudio += old.identity.trackNumber
            if (!sameCueBounds(old, next)) changedBounds += old.identity.trackNumber
        }
        val differences = CueAdoptionDifferences(
            projection.identityChanged || oldMembers.any { !sameCueAccess(it.reference,
                byNumber.getValue(it.reference.identity.trackNumber).reference) }, cueChanged, unknownAudio.size, changedAudio.size, changedBounds.size,
            sources.size - oldMembers.size)
        val plan = CueAdoptionPlan(id(), library.meta().revision, missingRef.identity.sourceId, missingRef.identity.cueId,
            alias?.aliasId ?: id(), alias?.lastGroupRevision, logicalRevision,
            CueBoundDocumentReference(group.document.sourceId, group.document.cueId, group.group.groupRevision, selectedActual.trackNumber),
            oldMembers, sources, head?.activeGenerationId, head?.queueRevision ?: 0L, head?.checkpointRevision ?: 0L, projection.count,
            differences, affectedPlaylistEntries = playlistProof.affectedMembers)
        val now = elapsedMs()
        pending = Pending(plan, now, add(now, LibraryLimits.VIEW_LEASE_MS), targets.toList(), alias, aliasVersion, group, head,
            generation, projection.signature, playlistProof)
        return plan
    }

    suspend fun isCurrent(plan: CueAdoptionPlan): Boolean {
        writer()
        val fence = pending?.takeIf { it.plan === plan } ?: return false
        val now = elapsedMs()
        if (now < fence.createdElapsedMs || now >= fence.expiresElapsedMs) {
            pending = null;
            return false
        }
        return try {
            currentCoroutineContext().ensureActive()
            if (library.meta().revision != plan.libraryRevision || queue.head() != fence.head || checkedGeneration(fence.head) != fence.generation) return false
            if (dao.currentLogicalCueTracks(plan.logicalSourceId, plan.logicalCueId) != fence.targets) return false
            val alias = cues.alias(plan.logicalSourceId, plan.logicalCueId)
            if (alias != fence.alias || alias?.let { currentAlias(it.aliasId) } != fence.aliasVersion) return false
            val actual = native.nativeGroup(plan.nativeDocument)
            if (actual != fence.native) return false
            val projection = queueProjection(plan.queueGenerationId, plan.targets,
                actual.segments.associate { it.reference.identity.trackNumber to it.reference })
            if (projection.count != plan.affectedQueueEntries || projection.signature != fence.queueSignature) return false
            if (playlists.preview(plan.targets, actual.segments.associate { it.reference.identity.trackNumber to it.reference }) != fence.playlists) return false
            true
        } catch (error: CancellationException) { throw error }
        catch (error: LibraryException) {
            if (error.issue in setOf(LibraryIssue.STORAGE_FULL, LibraryIssue.STORAGE_UNAVAILABLE)) throw error
            false
        }
    }

    suspend fun commit(plan: CueAdoptionPlan, allowCueDifferences: Boolean, allowDifferentContent: Boolean): CueAdoptionCommit {
        writer()
        library.database.backupMutationBarrier?.requireExclusiveOwner()
        currentCoroutineContext().ensureActive()
        if (plan.differences.requiresExplicitConfirmation && !allowCueDifferences ||
            plan.differences.differentOrUnknownContent && !allowDifferentContent) fail(LibraryIssue.INVALID_INPUT)
        val intent = intentSignature(plan)
        cues.adoptionOperation(plan.id)?.let { receipt ->
            if (receipt.intentSignature != intent || receipt.aliasId != plan.aliasId || receipt.logicalGroupRevision != plan.logicalGroupRevision ||
                receipt.nativeCueId != plan.nativeDocument.cueId || receipt.nativeGroupRevision != plan.nativeDocument.groupRevision) fail(LibraryIssue.CONFLICT)
            return CueAdoptionCommit(receipt.validFrom, receipt.logicalGroupRevision, receipt.memberCount, receipt.repairedQueueEntries)
        }
        if (!isCurrent(plan)) fail(LibraryIssue.CONFLICT)
        val fence = pending ?: fail(LibraryIssue.CONFLICT)
        val current = library.meta()
        val revision = increment(current.revision)
        val alias = fence.alias ?: CueAliasEntity(plan.aliasId, plan.logicalSourceId, plan.logicalCueId).also { cues.insertAlias(it) }
        val sources = fence.native.segments.associateBy { it.reference.identity.trackNumber }
        val accepted = plan.targets.map { target ->
            val actual = sources.getValue(target.reference.identity.trackNumber).reference
            val binding = actual.binding ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            LocalCueReference(actual.identity.copy(sourceId = plan.logicalSourceId, cueId = plan.logicalCueId,
                groupRevision = plan.logicalGroupRevision, audioContentTokenHint = binding.audio.verifiedContentToken),
                binding.copy(actualDocument = actual.actualDocument, adoptionAliasId = plan.aliasId))
        }
        val aliasVersionId = id()
        val segments = accepted.zip(plan.targets) { value, target -> CueAliasSegmentEntity(aliasVersionId,
            value.identity.trackNumber, value.trackKey.trackId, CueReferenceCodec.encode(value), target.member) }
        if (cues.closeAliasVersion(alias.aliasId, revision) != (if (fence.aliasVersion == null) 0 else 1)) fail(LibraryIssue.CONFLICT)
        cues.insertAliasVersion(CueAliasVersionEntity(aliasVersionId, alias.aliasId, plan.logicalGroupRevision,
            fence.native.group.versionId, fence.native.document.cueId, fence.native.group.groupRevision, segments.size,
            fence.native.segments.size, aliasSignature(alias, plan.logicalGroupRevision, fence.native.group, segments), intent,
            plan.id, plan.affectedQueueEntries, revision, null))
        cues.insertAliasSegments(segments)
        if (cues.updateAlias(alias.copy(lastGroupRevision = plan.logicalGroupRevision, reassociationPending = false)) != 1) fail(LibraryIssue.CONFLICT)
        val logicalDocument = cues.documentById(plan.logicalCueId)
        if (logicalDocument?.sourceId == plan.logicalSourceId && logicalDocument.reassociationPending &&
            cues.currentGroups(logicalDocument.cueId).isEmpty()) {
            // 完成整组接纳后，旧原生证据退出，不授予旧文档访问能力。
            if (cues.updateDocument(logicalDocument.copy(reassociationPending = false)) != 1) fail(LibraryIssue.CONFLICT)
        }
        for ((index, target) in plan.targets.withIndex()) {
            currentCoroutineContext().ensureActive()
            val old = fence.targets.single { it.trackId == target.trackKey.trackId }
            val value = accepted[index]
            val binding = value.binding ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            dao.closeTrackRelations(old.trackId, revision)
            dao.insertRelation(LibraryTrackLocationEntity(id(), old.trackId, binding.audio.location.locationId, revision, null))
            val source = sources.getValue(value.identity.trackNumber)
            // 已接纳的访问改变时长与编解码事实；逻辑标签和显示覆盖值保留原标识。
            val metadata = old.metadata().copy(durationMs = durationMs(value), codecParameters = source.metadata.codecParameters)
            library.writeTrackVersion(old.copy(cueReference = CueReferenceCodec.encode(value)), metadata, source.filename,
                old.metadataIssue, revision, old.displayTitle)
        }
        if (queue.head() != fence.head || checkedGeneration(fence.head) != fence.generation ||
            queueProjection(plan.queueGenerationId, plan.targets, sources.mapValues { it.value.reference }).signature != fence.queueSignature)
            fail(LibraryIssue.CONFLICT)
        val repaired = repairQueue(plan, accepted, fence.head)
        if (repaired != plan.affectedQueueEntries) fail(LibraryIssue.CONFLICT)
        if (playlists.preview(plan.targets, sources.mapValues { it.value.reference }) != fence.playlists ||
            playlists.repair(plan, accepted, fence.playlists) != plan.affectedPlaylistEntries) fail(LibraryIssue.CONFLICT)
        currentCoroutineContext().ensureActive()
        if (dao.updateMeta(current.copy(revision = revision)) != 1) fail(LibraryIssue.CONFLICT)
        return CueAdoptionCommit(revision, plan.logicalGroupRevision, accepted.size, repaired)
    }

    suspend fun validateAlias(reference: LocalCueReference, nativeGroup: CueGroupVersionEntity) {
        writer()
        val aliasId = reference.binding?.adoptionAliasId ?: fail(LibraryIssue.SOURCE_CHANGED)
        val alias = cues.aliasById(aliasId) ?: fail(LibraryIssue.SOURCE_CHANGED)
        if (alias.reassociationPending || alias.logicalSourceId != reference.identity.sourceId || alias.logicalCueId != reference.identity.cueId ||
            alias.lastGroupRevision != reference.identity.groupRevision) fail(LibraryIssue.SOURCE_CHANGED)
        val version = currentAlias(aliasId) ?: fail(LibraryIssue.SOURCE_CHANGED)
        if (version.logicalGroupRevision != reference.identity.groupRevision || version.nativeGroupVersionId != nativeGroup.versionId ||
            version.nativeCueId != reference.actualDocument.cueId || version.nativeGroupRevision != reference.actualDocument.groupRevision ||
            version.memberCount !in 1..CueLimits.TRACKS || version.nativeMemberCount != nativeGroup.segmentCount) fail(LibraryIssue.SOURCE_CHANGED)
        val members = cues.aliasSegments(version.versionId)
        if (members.size != version.memberCount || aliasSignature(alias, version.logicalGroupRevision, nativeGroup, members) != version.signature) fail(LibraryIssue.RECOVERY_REQUIRED)
        val original = native.nativeGroup(reference.actualDocument).segments.associateBy { it.reference.identity.trackNumber }
        for (member in members) {
            currentCoroutineContext().ensureActive()
            val accepted = CueReferenceCodec.decode(member.cueReference)
            val row = dao.currentTrack(member.logicalTrackId) ?: fail(LibraryIssue.SOURCE_CHANGED)
            if (accepted.trackKey != TrackKey(row.providerId, row.trackId) || accepted.identity.trackNumber != member.trackNumber ||
                accepted.identity.sourceId != alias.logicalSourceId || accepted.identity.cueId != alias.logicalCueId ||
                accepted.identity.groupRevision != version.logicalGroupRevision || accepted.binding?.adoptionAliasId != aliasId ||
                row.cueReference != member.cueReference || !sameAcceptedSegment(accepted, original[member.trackNumber]?.reference
                    ?: fail(LibraryIssue.SOURCE_CHANGED))) fail(LibraryIssue.SOURCE_CHANGED)
        }
        if (members.singleOrNull { it.logicalTrackId == reference.trackKey.trackId }?.cueReference != CueReferenceCodec.encode(reference)) fail(LibraryIssue.SOURCE_CHANGED)
    }

    private suspend fun repairQueue(plan: CueAdoptionPlan, references: List<LocalCueReference>, head: QueueHeadEntity?): Long {
        val state = head ?: return 0L
        val generationId = state.activeGenerationId ?: return 0L
        var repaired = 0L
        for (value in references) {
            var after: String? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = queue.cueTrackEntryPage(generationId, value.trackKey.providerId, value.trackKey.trackId, after)
                if (page.isEmpty()) break
                val binding = value.binding ?: fail(LibraryIssue.RECOVERY_REQUIRED)
                val location = binding.audio.location
                for (entry in page) {
                    currentCoroutineContext().ensureActive()
                    val old = entry.cueReference?.let { CueReferenceCodec.decode(it) } ?: fail(LibraryIssue.RECOVERY_REQUIRED)
                    if (old.trackKey != value.trackKey || old.identity.trackNumber != value.identity.trackNumber || entry.accountId != null) fail(LibraryIssue.RECOVERY_REQUIRED)
                    if (queue.repairCueEntry(generationId, entry.queueEntryId, entry.providerId, entry.trackId, entry.cueReference,
                            CueReferenceCodec.encode(value), durationMs(value), location.locationId, location.uri, location.grantUri,
                            location.grantFlags, location.access.name, location.contentRevision) != 1) fail(LibraryIssue.CONFLICT)
                    repaired = increment(repaired)
                }
                after = page.last().queueEntryId
            }
        }
        if (repaired != plan.affectedQueueEntries) fail(LibraryIssue.CONFLICT)
        if (repaired > 0L) {
            currentCoroutineContext().ensureActive()
            val current = state.currentEntryId?.let { queue.entry(generationId, it) }
            val affectedCurrent = references.singleOrNull { it.trackKey == current?.let { row -> TrackKey(row.providerId, row.trackId) } }
            val position = if (affectedCurrent == null) state.positionMs else state.positionMs.coerceIn(0L, durationMs(affectedCurrent) ?: 0L)
            val nextQueue = increment(state.queueRevision)
            val nextCheckpoint = increment(state.checkpointRevision)
            if (queue.advanceCueRepairGeneration(generationId, state.queueRevision, nextQueue) != 1 ||
                queue.finishCueRepair(generationId, state.queueRevision, state.checkpointRevision, state.currentEntryId,
                    nextQueue, nextCheckpoint, position) != 1) fail(LibraryIssue.CONFLICT)
        }
        return repaired
    }

    /** 仅恢复队列可能保留比资料库更旧的边界；每个匹配实例都在确认范围内。 */
    private suspend fun queueProjection(generationId: String?, targets: List<CueAdoptionTargetMember>,
        sources: Map<Int, LocalCueReference>): QueueProjection {
        val digest = CueSignatureFields()
        digest.field("melox-cue-queue-preview:v1");
        digest.field(generationId.orEmpty())
        var count = 0L
        var highestRevision = 0L
        var cueChanged = false
        var identityChanged = false
        val unknownAudio = linkedSetOf<Int>()
        val changedAudio = linkedSetOf<Int>()
        val changedBounds = linkedSetOf<Int>()
        if (generationId == null) return QueueProjection(count, digest.finish(), highestRevision, cueChanged, identityChanged, unknownAudio, changedAudio, changedBounds)
        for (target in targets) {
            var after: String? = null
            var trackCount = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = queue.cueTrackEntryPage(generationId, target.trackKey.providerId, target.trackKey.trackId, after)
                if (page.isEmpty()) break
                for (entry in page) {
                    currentCoroutineContext().ensureActive()
                    val model = try { entry.model() } catch (error: QueueStoreException) { throw LibraryException(LibraryIssue.RECOVERY_REQUIRED, error) }
                    val old = model.cueReference ?: fail(LibraryIssue.INCOMPLETE)
                    highestRevision = maxOf(highestRevision, old.identity.groupRevision)
                    val number = target.reference.identity.trackNumber
                    val next = sources[number] ?: fail(LibraryIssue.INCOMPLETE)
                    if (old.trackKey != target.trackKey || old.identity.trackNumber != number || entry.accountId != null) fail(LibraryIssue.RECOVERY_REQUIRED)
                    cueChanged = cueChanged || old.identity.cueContentToken != next.identity.cueContentToken
                    identityChanged = identityChanged || !sameCueAccess(old, next)
                    val oldAudio = old.portable().identity.audioContentTokenHint
                    if (oldAudio == null) unknownAudio += number else if (oldAudio != next.binding?.audio?.verifiedContentToken) changedAudio += number
                    if (!sameCueBounds(old, next)) changedBounds += number
                    digest.field(entry.queueEntryId);
                    digest.field(entry.providerId);
                    digest.field(entry.trackId)
                    digest.field(entry.originalOrder.toString());
                    digest.field(entry.playbackOrder.toString())
                    digest.nullable(entry.displayName);
                    digest.field(entry.durationMs.toString());
                    digest.nullable(entry.accountId)
                    digest.nullable(entry.locationId);
                    digest.nullable(entry.locationUri);
                    digest.nullable(entry.grantUri)
                    digest.field(entry.grantFlags.toString());
                    digest.nullable(entry.access);
                    digest.field(entry.contentRevision.toString())
                    digest.field(CueReferenceCodec.encode(old))
                    count = increment(count);
                    trackCount = increment(trackCount)
                }
                after = page.last().queueEntryId
            }
            if (trackCount != queue.cueTrackEntryCount(generationId, target.trackKey.providerId, target.trackKey.trackId)) fail(LibraryIssue.CONFLICT)
        }
        return QueueProjection(count, digest.finish(), highestRevision, cueChanged, identityChanged, unknownAudio, changedAudio, changedBounds)
    }

    private suspend fun checkedGeneration(head: QueueHeadEntity?): QueueGenerationEntity? {
        val state = head ?: return null
        val id = state.activeGenerationId ?: return null
        val generation = queue.generation(id) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        if (generation.phase != QueuePhase.PUBLISHED || generation.committedQueueRevision != state.queueRevision) fail(LibraryIssue.CONFLICT)
        return generation
    }
    private suspend fun currentAlias(id: String): CueAliasVersionEntity? {
        val versions = cues.currentAliasVersions(id)
        if (versions.size > 1) fail(LibraryIssue.RECOVERY_REQUIRED)
        return versions.singleOrNull()
    }
    private fun reference(row: LibraryTrackVersionEntity): LocalCueReference = row.cueReference?.let { CueReferenceCodec.decode(it) }
        ?: fail(LibraryIssue.INVALID_INPUT)
    private fun durationMs(reference: LocalCueReference): Long? = (reference.pcmWindow?.durationUs ?: fail(LibraryIssue.RECOVERY_REQUIRED))
        .div(1000L).takeIf { it > 0L }
    private suspend fun writer() { if (!library.database.hasCurrentWriterTransaction()) fail(LibraryIssue.RECOVERY_REQUIRED) }
    private fun increment(value: Long): Long = add(value, 1L)
    private fun add(first: Long, second: Long): Long = try { Math.addExact(first, second) } catch (_: ArithmeticException) { fail(LibraryIssue.STORAGE_FULL) }
    private fun fail(issue: LibraryIssue): Nothing = throw LibraryException(issue)
    private fun id(): String = UUID.randomUUID().toString()

    private data class Pending(val plan: CueAdoptionPlan, val createdElapsedMs: Long, val expiresElapsedMs: Long,
        val targets: List<LibraryTrackVersionEntity>, val alias: CueAliasEntity?, val aliasVersion: CueAliasVersionEntity?,
        val native: RoomNativeCueGroup, val head: QueueHeadEntity?, val generation: QueueGenerationEntity?, val queueSignature: String,
        val playlists: RoomCuePlaylistProof)
    private data class QueueProjection(val count: Long, val signature: String, val highestRevision: Long, val cueChanged: Boolean, val identityChanged: Boolean,
        val unknownAudio: Set<Int>, val changedAudio: Set<Int>, val changedBounds: Set<Int>)
}

internal fun aliasSignature(alias: CueAliasEntity, revision: Long, native: CueGroupVersionEntity,
    segments: List<CueAliasSegmentEntity>): String = cueSignature {
    field("melox-cue-alias:v1");
    field(alias.aliasId);
    field(alias.logicalSourceId);
    field(alias.logicalCueId)
    field(revision.toString());
    field(native.versionId);
    field(native.groupRevision.toString());
    field(native.signature)
    field(segments.size.toString())
    segments.sortedBy { it.trackNumber }.forEach {
        field(it.logicalTrackId);
        field(it.cueReference);
        field(it.member.toString())
    }
}

private fun intentSignature(plan: CueAdoptionPlan): String = cueSignature {
    field("melox-cue-adoption-intent:v1");
    field(plan.id);
    field(plan.libraryRevision.toString());
    field(plan.aliasId)
    field(plan.expectedAliasRevision.toString());
    field(plan.logicalSourceId);
    field(plan.logicalCueId);
    field(plan.logicalGroupRevision.toString())
    field(plan.nativeDocument.sourceId);
    field(plan.nativeDocument.cueId);
    field(plan.nativeDocument.groupRevision.toString())
    field(plan.nativeDocument.trackNumber.toString());
    field(plan.queueGenerationId.orEmpty());
    field(plan.queueRevision.toString())
    field(plan.checkpointRevision.toString());
    field(plan.affectedQueueEntries.toString());
    field(plan.affectedPlaylistEntries.toString());
    field(plan.differences.toString())
    plan.targets.forEach {
        field(CueReferenceCodec.encode(it.reference));
        field(it.title);
        field(it.member.toString())
    }
    plan.sources.forEach {
        field(CueReferenceCodec.encode(it.reference));
        field(LibraryCodec.metadata(it.metadata))
    }
}

private class CueSignatureFields {
    private val digest = MessageDigest.getInstance("SHA-256")
    fun field(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(':'.code.toByte());
        digest.update(bytes)
    }
    fun nullable(value: String?) {
        field(if (value == null) "0" else "1");
        if (value != null) field(value)
    }
    fun finish(): String = "sha256:" + digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
private fun cueSignature(block: CueSignatureFields.() -> Unit): String = CueSignatureFields().apply(block).finish()
