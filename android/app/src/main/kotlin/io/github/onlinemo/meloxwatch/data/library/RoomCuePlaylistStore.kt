package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.cue.CueAdoptionPlan
import io.github.onlinemo.meloxwatch.data.cue.CueAdoptionTargetMember
import io.github.onlinemo.meloxwatch.data.cue.CueLimits
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistGenerationEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistHeadEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMetaEntity
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 共享接纳核对生效的可移植提示，并通过发布复制的数据代次保留旧视图。 */
internal class RoomCuePlaylistStore(private val library: RoomLocalLibraryRepository) {
    private val dao = library.database.playlistDao()

    suspend fun mismatchedHints(key: TrackKey, current: LocalCueReference): Boolean {
        writer()
        var afterGeneration: String? = null
        var afterMember: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.cueMembersPage(key.providerId, key.trackId, afterGeneration, afterMember)
            if (page.isEmpty()) return false
            for (member in page) {
                val hint = member.cueReference?.let { CueReferenceCodec.decode(it, portableOnly = true) } ?: continue
                if (!currentCueMatchesPortableHint(current, hint)) return true
            }
            afterGeneration = page.last().generationId
            afterMember = page.last().memberId
        }
    }

    suspend fun preview(targets: List<CueAdoptionTargetMember>, sources: Map<Int, LocalCueReference>): RoomCuePlaylistProof {
        writer()
        val meta = dao.meta()
        val digest = Fields()
        digest.field("melox-cue-playlist-preview:v1");
        digest.field(meta?.revision.toString())
        var affected = 0L
        var highestRevision = 0L
        var cueChanged = false
        val unknownAudio = linkedSetOf<Int>()
        val changedAudio = linkedSetOf<Int>()
        val changedBounds = linkedSetOf<Int>()
        for (target in targets) {
            var afterGeneration: String? = null
            var afterMember: String? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = dao.cueMembersPage(target.trackKey.providerId, target.trackKey.trackId, afterGeneration, afterMember)
                if (page.isEmpty()) break
                if (meta == null) fail(LibraryIssue.RECOVERY_REQUIRED)
                for (member in page) {
                    currentCoroutineContext().ensureActive()
                    val number = target.reference.identity.trackNumber
                    val hint = member.cueReference?.let { CueReferenceCodec.decode(it, portableOnly = true) } ?: target.reference.portable()
                    if (hint.trackKey != target.trackKey || hint.identity.sourceId != target.reference.identity.sourceId ||
                        hint.identity.cueId != target.reference.identity.cueId || hint.identity.trackNumber != number) fail(LibraryIssue.RECOVERY_REQUIRED)
                    val actual = sources[number] ?: fail(LibraryIssue.INCOMPLETE)
                    highestRevision = maxOf(highestRevision, hint.identity.groupRevision)
                    cueChanged = cueChanged || hint.identity.cueContentToken != actual.identity.cueContentToken
                    val audio = hint.identity.audioContentTokenHint
                    if (audio == null) unknownAudio += number else if (audio != actual.binding?.audio?.verifiedContentToken) changedAudio += number
                    if (!sameCueBounds(hint, actual)) changedBounds += number
                    digest.field(member.generationId);
                    digest.field(member.memberId);
                    digest.field(member.ordinal.toString())
                    digest.field(member.referenceKey);
                    digest.nullable(member.providerId);
                    digest.nullable(member.trackId)
                    digest.nullable(member.placeholderId);
                    digest.nullable(member.issue);
                    digest.nullable(member.titleHint)
                    digest.nullable(member.artistHint);
                    digest.nullable(member.albumHint);
                    digest.nullable(member.relativePathHint)
                    digest.nullable(member.descriptionHint);
                    digest.field(member.durationMs.toString());
                    digest.nullable(member.cueReference)
                    affected = increment(affected)
                }
                afterGeneration = page.last().generationId
                afterMember = page.last().memberId
            }
        }
        val keys = targets.map { it.trackKey.trackId }
        var after: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val heads = dao.cueAffectedHeads(keys, after)
            if (heads.isEmpty()) break
            for (head in heads) {
                val current = checked(head)
                digest.field(head.playlistId);
                digest.field(head.revision.toString());
                digest.field(current.generationId)
                digest.field(current.dataGenerationId);
                digest.field(current.memberCount.toString());
                digest.field(current.fingerprint)
            }
            after = heads.last().playlistId
        }
        return RoomCuePlaylistProof(meta, digest.finish(), affected, highestRevision, cueChanged, unknownAudio, changedAudio, changedBounds)
    }

    suspend fun repair(plan: CueAdoptionPlan, accepted: List<LocalCueReference>, proof: RoomCuePlaylistProof): Long {
        writer()
        if (proof.affectedMembers == 0L) return 0L
        val meta = proof.meta ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        if (dao.meta() != meta) fail(LibraryIssue.CONFLICT)
        val nextGlobal = increment(meta.revision)
        val values = accepted.associateBy { it.trackKey.trackId }
        val keys = values.keys.toList()
        var repaired = 0L
        var after: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val heads = dao.cueAffectedHeads(keys, after)
            if (heads.isEmpty()) break
            for (head in heads) {
                currentCoroutineContext().ensureActive()
                val old = checked(head)
                val id = UUID.randomUUID().toString()
                dao.insertGeneration(old.copy(generationId = id, dataGenerationId = id, operationId = plan.id,
                    fingerprint = proof.signature, state = "STAGING", revision = increment(head.revision),
                    updatedEpochMs = library.epochMs(), memberCount = 0L, validFrom = null, validTo = null))
                var first = 0L
                while (first < old.memberCount) {
                    currentCoroutineContext().ensureActive()
                    val page = dao.membersPage(old.dataGenerationId, first, LibraryLimits.PAGE_SIZE)
                    if (page.isEmpty()) fail(LibraryIssue.INCOMPLETE)
                    val copied = page.mapIndexed { index, member ->
                        if (member.ordinal != add(first, index.toLong()) || member.ordinal >= old.memberCount) fail(LibraryIssue.RECOVERY_REQUIRED)
                        val value = member.trackId?.takeIf { member.providerId == TrackKey.LOCAL_PROVIDER }?.let(values::get)
                        if (value == null) member.copy(generationId = id) else {
                            repaired = increment(repaired)
                            member.copy(generationId = id, cueReference = CueReferenceCodec.encode(value.portable()),
                                durationMs = value.pcmWindow?.durationUs?.div(1000L)?.takeIf { it > 0L })
                        }
                    }
                    dao.insertMembers(copied)
                    first = add(first, page.size.toLong())
                }
                if (dao.seal(id, first) != 1 || dao.publish(id, nextGlobal) != 1 ||
                    dao.closeGeneration(old.generationId, nextGlobal - 1L) != 1 ||
                    dao.publishHead(head.playlistId, head.revision, old.generationId, id, increment(head.revision), deleted = false) != 1)
                    fail(LibraryIssue.CONFLICT)
            }
            after = heads.last().playlistId
        }
        if (repaired != proof.affectedMembers || dao.advanceCueRevision(meta.revision, nextGlobal) != 1) fail(LibraryIssue.CONFLICT)
        return repaired
    }

    private suspend fun checked(head: PlaylistHeadEntity): PlaylistGenerationEntity {
        val current = dao.generation(head.activeGenerationId ?: fail(LibraryIssue.RECOVERY_REQUIRED))
            ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        if (head.deleted || current.playlistId != head.playlistId || current.revision != head.revision ||
            current.state != "PUBLISHED" || current.validFrom == null || current.validTo != null || current.memberCount <= 0L)
            fail(LibraryIssue.RECOVERY_REQUIRED)
        // 重命名后的元数据可能共用较旧的数据代次；其完整行仍须属于此歌单。
        val owner = dao.generation(current.dataGenerationId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        if (owner.dataGenerationId != owner.generationId || owner.playlistId != current.playlistId ||
            owner.memberCount != current.memberCount || owner.state != "PUBLISHED" || owner.validFrom == null)
            fail(LibraryIssue.RECOVERY_REQUIRED)
        val statistics = dao.orderSummary(owner.generationId)
        if (statistics.count != current.memberCount || statistics.firstOrdinal != 0L || statistics.lastOrdinal != current.memberCount - 1L)
            fail(LibraryIssue.RECOVERY_REQUIRED)
        return current
    }
    private suspend fun writer() { if (!library.database.hasCurrentWriterTransaction()) fail(LibraryIssue.RECOVERY_REQUIRED) }
    private fun increment(value: Long): Long = add(value, 1L)
    private fun add(first: Long, second: Long): Long = try { Math.addExact(first, second) } catch (_: ArithmeticException) { fail(LibraryIssue.STORAGE_FULL) }
    private fun fail(issue: LibraryIssue): Nothing = throw LibraryException(issue)

    private class Fields {
        private val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII));
            digest.update(':'.code.toByte());
            digest.update(bytes)
        }
        fun nullable(value: String?) {
            field(if (value == null) "0" else "1");
            if (value != null) field(value)
        }
        fun finish(): String = "sha256:" + digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}

internal data class RoomCuePlaylistProof(val meta: PlaylistMetaEntity?, val signature: String, val affectedMembers: Long,
    val highestHintRevision: Long, val cueChanged: Boolean, val unknownAudio: Set<Int>, val changedAudio: Set<Int>, val changedBounds: Set<Int>) {
    init { require(listOf(unknownAudio, changedAudio, changedBounds).all { it.size <= CueLimits.TRACKS }) }
}
