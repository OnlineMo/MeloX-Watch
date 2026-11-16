package io.github.onlinemo.meloxwatch.data.playlists

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.os.SystemClock
import androidx.room.RoomDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 同一数据库的单个关系写入所有者。长操作仅构造不可见代次，每批至多100行。
 * 名称元数据与成员代次分开；改名不复制大歌单，回收保留仍被head/header/view引用的成员。
 */
class RoomPlaylistRepository(
    private val database: RoomDatabase,
    private val dao: PlaylistDao,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val epochMs: () -> Long = System::currentTimeMillis,
    private val processId: String = PROCESS_ID,
    private val folderFenceVerifier: PlaylistTransactionFenceVerifier? = null,
    private val libraryFenceVerifier: PlaylistLibraryTransactionFenceVerifier? = null,
) : LocalPlaylistRepository {
    private val actions = Mutex()

    override suspend fun initialize(): PlaylistOverview = storage {
        mutate {
            database.withPreservedTransaction {
                val inserted = dao.insertMeta(PlaylistMetaEntity())
                if (inserted != -1L) {
                    val time = epochMs()
                    dao.insertGeneration(PlaylistGenerationEntity(
                        INITIAL_GENERATION, PlaylistLimits.FAVORITES_ID, INITIAL_GENERATION, INITIAL_OPERATION,
                        "system", "PUBLISHED", 0L, "Favorites", "favorites", PlaylistKind.FAVORITES.name,
                        time, time, 0L, 0L, null, processId,
                    ))
                    dao.insertHead(PlaylistHeadEntity(PlaylistLimits.FAVORITES_ID, INITIAL_GENERATION, 0L, 0L))
                }
                requireCurrent(requireHead(PlaylistLimits.FAVORITES_ID))
                dao.expireViews(processId, elapsedMs())
                dao.abortAbandoned()
            }
            reclaimBatch()
            readOverview()
        }
    }

    override suspend fun overview(): PlaylistOverview = storage { database.withPreservedTransaction { readOverview() } }

    override fun observeOverview(): Flow<PlaylistOverview?> = dao.observeMeta().map { meta ->
        if (meta == null) null else overview()
    }

    override suspend fun snapshot(playlistId: String): PlaylistSnapshot? = storage {
        requirePlaylistIdentifier(playlistId)
        database.withPreservedTransaction { currentSnapshot(playlistId) }
    }

    override fun observePlaylist(playlistId: String): Flow<PlaylistSnapshot?> {
        requirePlaylistIdentifier(playlistId)
        return dao.observeCurrentGeneration(playlistId).map { it?.model() }
    }

    override suspend fun isFavorite(trackKey: TrackKey): Boolean = storage {
        val key = PlaylistReference(trackKey = trackKey).identityKey()
        database.withPreservedTransaction {
            requireCurrent(requireHead(PlaylistLimits.FAVORITES_ID))
            dao.favoriteContains(key)
        }
    }

    override fun observeFavorite(trackKey: TrackKey): Flow<Boolean> =
        dao.observeFavoriteContains(PlaylistReference(trackKey = trackKey).identityKey())

    override suspend fun apply(request: PlaylistMutationRequest, source: PlaylistPageSource?): PlaylistCommit = storage {
        mutate {
            require(request.operationId != INITIAL_OPERATION)
            val fingerprint = request.fingerprint()
            database.withPreservedTransaction { replay(request, fingerprint) }?.let { return@mutate it }
            validateSource(request, source)
            database.withPreservedTransaction {
                requireMeta()
                dao.expireViews(processId, elapsedMs())
            }
            reclaimBatch()
            val plan = database.withPreservedTransaction { plan(request) }
            if (plan.unchanged) return@mutate acceptUnchanged(request, fingerprint, checkNotNull(plan.old),
                result = unchangedResult(request.mutation))
            if (request.mutation is PlaylistMutation.Delete) {
                return@mutate acceptDelete(request, fingerprint, checkNotNull(plan.old))
            }

            var stagingId: String? = null
            var incomingId: String? = null
            var accepted = false
            var failure: Throwable? = null
            try {
                val staged = generation(request, plan, fingerprint)
                stagingId = staged.generationId
                currentCoroutineContext().ensureActive()
                database.withPreservedTransaction { dao.insertGeneration(staged) }
                val counts = if (plan.shareData) BuildCounts(checkNotNull(plan.old).memberCount, 0L, 0L)
                else if (request.mutation is PlaylistMutation.SynchronizeFolder) {
                    val incoming = generation(request, plan, fingerprint)
                    incomingId = incoming.generationId
                    database.withPreservedTransaction { dao.insertGeneration(incoming) }
                    buildSynchronizedMembers(staged, incoming, plan, checkNotNull(source))
                }
                else buildMembers(staged, plan, source)
                if (request.mutation is PlaylistMutation.Add && counts.added == 0L) {
                    return@mutate acceptUnchanged(request, fingerprint, checkNotNull(plan.old), counts.duplicates)
                }
                if (request.mutation is PlaylistMutation.SynchronizeFolder && counts.added == 0L && counts.removed == 0L) {
                    return@mutate acceptUnchanged(request, fingerprint, checkNotNull(plan.old), counts.duplicates,
                        PlaylistOperationResult.SYNCHRONIZED)
                }
                seal(staged, counts.count, plan.shareData)
                currentCoroutineContext().ensureActive()
                acceptGeneration(request, fingerprint, staged, plan, counts) { accepted = true }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                val unfinished = stagingId
                val incoming = incomingId
                if ((unfinished != null && !accepted) || incoming != null) {
                    try {
                        withContext(NonCancellable) {
                            database.withPreservedTransaction {
                                if (unfinished != null && !accepted) dao.abort(unfinished)
                                if (incoming != null) dao.abort(incoming)
                            }
                            reclaimBatch()
                        }
                    } catch (cleanup: Throwable) {
                        val original = failure
                        if (original == null) throw cleanup
                        if (original !== cleanup) original.addSuppressed(cleanup)
                    }
                }
            }
        }
    }

    override suspend fun openCatalogView(): PlaylistCatalogView = storage {
        database.withPreservedTransaction {
            val meta = requireMeta()
            reserveView(PlaylistViewOwner.UI)
            val count = dao.catalogCount(meta.revision)
            val row = PlaylistViewEntity(UUID.randomUUID().toString(), "CATALOG", null, meta.revision, count,
                PlaylistViewOwner.UI.name, expiresElapsedMs(), processId)
            dao.insertView(row)
            PlaylistCatalogView(row.viewId, row.revision, row.totalCount)
        }
    }

    override suspend fun catalogPage(
        view: PlaylistCatalogView,
        after: PlaylistCatalogCursor?,
        limit: Int,
    ): PlaylistCatalogPage = storage {
        requirePageLimit(limit)
        database.withPreservedTransaction {
            requireCatalogView(view)
            if (after != null) {
                require(after.viewId == view.viewId && after.revision == view.revision)
                val cursorGeneration = generationAt(after.playlistId, view.revision)
                    ?: throw PlaylistException(PlaylistIssue.INVALID_INPUT)
                require(cursorGeneration.kind != PlaylistKind.FAVORITES.name)
            }
            val records = dao.catalogPage(view.revision, after?.playlistId, limit)
            val playlists = records.map { it.model() }
            val last = playlists.lastOrNull()
            val next = if (last != null && dao.catalogHasNext(view.revision, last.playlistId)) {
                PlaylistCatalogCursor(view.viewId, view.revision, last.playlistId)
            } else null
            PlaylistCatalogPage(view, playlists, next)
        }
    }

    override suspend fun openView(
        playlistId: String,
        owner: PlaylistViewOwner,
        fromCatalog: PlaylistCatalogView?,
    ): PlaylistViewToken = storage {
        requirePlaylistIdentifier(playlistId)
        database.withPreservedTransaction {
            val meta = requireMeta()
            reserveView(owner)
            val generation = if (fromCatalog == null) requireCurrent(requireHead(playlistId)) else {
                requireCatalogView(fromCatalog)
                generationAt(playlistId, fromCatalog.revision) ?: throw PlaylistException(PlaylistIssue.NOT_FOUND)
            }
            createMemberView(generation, owner, fromCatalog?.revision ?: meta.revision)
        }
    }

    override suspend fun adoptView(viewId: String, owner: PlaylistViewOwner): PlaylistViewToken = storage {
        requirePlaylistIdentifier(viewId)
        database.withPreservedTransaction {
            val original = requireView(viewId, "MEMBERS")
            val generation = requireGeneration(checkNotNull(original.generationId))
            require(original.totalCount == generation.memberCount)
            reserveView(owner)
            createMemberView(generation, owner, original.revision)
        }
    }

    override suspend fun page(view: PlaylistViewToken, firstOrdinal: Long, limit: Int): PlaylistPage = storage {
        requirePageLimit(limit)
        require(firstOrdinal >= 0L && firstOrdinal <= view.snapshot.memberCount)
        database.withPreservedTransaction {
            val row = requireView(view.viewId, "MEMBERS")
            val generation = requireGeneration(checkNotNull(row.generationId))
            require(generation.model() == view.snapshot && row.owner == view.owner.name)
            require(row.totalCount == generation.memberCount)
            requireDataOwner(generation)
            val expected = minOf(limit.toLong(), generation.memberCount - firstOrdinal).toInt()
            val members = dao.membersPage(generation.dataGenerationId, firstOrdinal, limit)
            requireCompletePage(members, firstOrdinal, expected)
            val next = firstOrdinal + members.size
            PlaylistPage(view, members.map { it.model() }, next.takeIf { it < generation.memberCount })
        }
    }

    override suspend fun closeView(viewId: String) = storage {
        requirePlaylistIdentifier(viewId)
        dao.deleteView(viewId)
        Unit
    }

    override suspend fun maintain(): Boolean = storage {
        mutate {
            val expired = database.withPreservedTransaction {
                requireMeta()
                dao.expireViews(processId, elapsedMs())
            }
            reclaimBatch() || expired > 0
        }
    }

    private suspend fun plan(request: PlaylistMutationRequest): PlaylistMutationPlan {
        val edit = request.mutation
        if (edit is PlaylistMutation.Create || edit is PlaylistMutation.CreateFolder) {
            require(request.playlistId != PlaylistLimits.FAVORITES_ID && request.expectedRevision == 0L)
            if (dao.head(request.playlistId) != null) throw PlaylistException(PlaylistIssue.CONFLICT)
            val name = playlistName(when (edit) {
                is PlaylistMutation.Create -> edit.name
                is PlaylistMutation.CreateFolder -> edit.name
                else -> error("Only creation mutations enter the new-list branch")
            })
            return PlaylistMutationPlan(null, name, playlistNameKey(name), PlaylistOperationResult.CREATED,
                edit.frozenInput(), folderSynchronization = edit.folderSynchronization())
        }
        val head = requireHead(request.playlistId)
        if (head.revision != request.expectedRevision) throw PlaylistException(PlaylistIssue.CONFLICT)
        val old = requireCurrent(head)
        val model = old.model()
        if ((edit is PlaylistMutation.SetFavorite) != (model.kind == PlaylistKind.FAVORITES)) {
            throw PlaylistException(PlaylistIssue.INVALID_INPUT)
        }
        if (model.kind == PlaylistKind.FOLDER && (edit is PlaylistMutation.Add || edit is PlaylistMutation.Remove ||
                edit is PlaylistMutation.ReplaceReference)) {
            throw PlaylistException(PlaylistIssue.AUTOMATIC_MEMBERS)
        }
        return when (edit) {
            is PlaylistMutation.Rename -> {
                val name = playlistName(edit.name)
                PlaylistMutationPlan(old, name, playlistNameKey(name), PlaylistOperationResult.RENAMED,
                    shareData = true, unchanged = name == old.name)
            }
            is PlaylistMutation.Delete -> {
                require(edit.confirmed)
                PlaylistMutationPlan(old, old.name, old.nameKey, PlaylistOperationResult.DELETED)
            }
            is PlaylistMutation.Add -> PlaylistMutationPlan(old, old.name, old.nameKey, PlaylistOperationResult.ADDED, edit.input)
            is PlaylistMutation.Remove -> {
                require(edit.confirmed)
                val member = dao.member(old.dataGenerationId, edit.memberId)
                PlaylistMutationPlan(old, old.name, old.nameKey, PlaylistOperationResult.REMOVED,
                    removeMemberId = edit.memberId, removeOrdinal = member?.ordinal, unchanged = member == null)
            }
            is PlaylistMutation.Move -> {
                require(edit.destinationOrdinal < old.memberCount)
                val member = dao.member(old.dataGenerationId, edit.memberId) ?: throw PlaylistException(PlaylistIssue.NOT_FOUND)
                PlaylistMutationPlan(old, old.name, old.nameKey, PlaylistOperationResult.MOVED,
                    moveFrom = member.ordinal, moveTo = edit.destinationOrdinal, unchanged = member.ordinal == edit.destinationOrdinal)
            }
            is PlaylistMutation.SetFavorite -> {
                val member = dao.memberByReference(old.dataGenerationId, edit.reference.identityKey())
                val unchanged = edit.enabled == (member != null)
                PlaylistMutationPlan(old, old.name, old.nameKey, PlaylistOperationResult.FAVORITE_SET,
                    removeMemberId = if (edit.enabled) null else member?.memberId,
                    removeOrdinal = if (edit.enabled) null else member?.ordinal,
                    appendFavorite = edit.reference.takeIf { edit.enabled && member == null }, unchanged = unchanged)
            }
            is PlaylistMutation.SynchronizeFolder -> {
                require(model.kind == PlaylistKind.FOLDER && edit.synchronization.binding == model.folderBinding)
                PlaylistMutationPlan(old, old.name, old.nameKey, PlaylistOperationResult.SYNCHRONIZED,
                    edit.synchronization.input, folderSynchronization = edit.synchronization)
            }
            is PlaylistMutation.RebindFolder -> {
                require(edit.confirmed && model.kind == PlaylistKind.FOLDER)
                require(edit.synchronization.binding != model.folderBinding)
                PlaylistMutationPlan(old, old.name, old.nameKey, PlaylistOperationResult.REBOUND,
                    edit.synchronization.input, folderSynchronization = edit.synchronization, replaceMembers = true)
            }
            is PlaylistMutation.ReplaceReference -> {
                require(edit.confirmed && model.kind == PlaylistKind.ORDINARY)
                val member = dao.member(old.dataGenerationId, edit.memberId) ?: throw PlaylistException(PlaylistIssue.NOT_FOUND)
                val duplicate = dao.memberByReference(old.dataGenerationId, edit.reference.identityKey())
                if (duplicate != null && duplicate.memberId != member.memberId) {
                    throw PlaylistException(PlaylistIssue.DUPLICATE_REFERENCE)
                }
                PlaylistMutationPlan(old, old.name, old.nameKey, PlaylistOperationResult.REFERENCE_REPLACED,
                    replacementMemberId = member.memberId, replacementReference = edit.reference,
                    unchanged = member.model().reference == edit.reference)
            }
            is PlaylistMutation.Create, is PlaylistMutation.CreateFolder -> error("Creation was handled before the existing-list branch")
        }
    }

    private fun validateSource(request: PlaylistMutationRequest, source: PlaylistPageSource?) {
        val input = request.mutation.frozenInput()
        require((input == null) == (source == null))
        val mutationFence = request.mutation.folderSynchronization()?.fence
        val explicitFence = request.expectedSourceFence
        require(mutationFence == null || explicitFence == null || mutationFence == explicitFence)
        val effectiveFence = explicitFence ?: mutationFence
        request.expectedLibraryRevision?.let { revision ->
            if (effectiveFence != null) require(revision.catalogRevision == effectiveFence.catalogRevision &&
                revision.rulesRevision == effectiveFence.rulesRevision)
        }
        if (request.expectedLibraryRevision != null && libraryFenceVerifier == null) {
            throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
        }
        if (effectiveFence != null && folderFenceVerifier == null) throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
    }

    private fun generation(
        request: PlaylistMutationRequest,
        plan: PlaylistMutationPlan,
        fingerprint: String,
    ): PlaylistGenerationEntity {
        val id = UUID.randomUUID().toString()
        val old = plan.old
        val binding = plan.folderSynchronization?.binding ?: old?.model()?.folderBinding
        val time = epochMs()
        return PlaylistGenerationEntity(
            id, request.playlistId, if (plan.shareData) checkNotNull(old).dataGenerationId else id,
            request.operationId, fingerprint, "STAGING", if (old == null) 1L else increment(old.revision),
            plan.name, plan.nameKey, old?.kind ?: if (binding == null) PlaylistKind.ORDINARY.name else PlaylistKind.FOLDER.name,
            old?.createdEpochMs ?: time,
            time, if (plan.shareData) checkNotNull(old).memberCount else 0L, null, null, processId,
            binding?.sourceId, binding?.nodeId, binding?.subtree,
        )
    }

    private data class BuildCounts(val count: Long, val added: Long, val duplicates: Long, val removed: Long = 0L)

    private suspend fun buildMembers(
        staged: PlaylistGenerationEntity,
        plan: PlaylistMutationPlan,
        source: PlaylistPageSource?,
    ): BuildCounts {
        val old = plan.old
        val appending = old != null && plan.input != null && !plan.replaceMembers
        if (old != null) requireDataOwner(old)
        // 先完整验证新增输入；全重复添加不用复制原歌单或产生新可见版本。
        var count = if (appending) checkNotNull(old).memberCount
        else if (old != null && !plan.replaceMembers) copyMembers(staged, plan, old) else 0L
        var added = 0L
        var duplicates = 0L
        val input = plan.input
        if (input != null) {
            val inputCounts = stageInput(staged.generationId, input, checkNotNull(source), count,
                if (appending) checkNotNull(old).dataGenerationId else null,
                localOnly = plan.folderSynchronization != null)
            count = inputCounts.count
            added = inputCounts.added
            duplicates = inputCounts.duplicates
        }
        if (appending && added > 0L) copyMembers(staged, plan, checkNotNull(old))
        val favorite = plan.appendFavorite
        if (favorite != null) {
            insertBatch(listOf(favorite.record(staged.generationId, UUID.randomUUID().toString(), count)))
            count = increment(count)
            added += 1L
        }
        val removed = if (plan.replaceMembers) checkNotNull(old).memberCount else if (plan.removeOrdinal != null) 1L else 0L
        return BuildCounts(count, added, duplicates, removed)
    }

    /** incoming先完整封存；最终成员按旧人工序保留，再按source稳定序追加，内存不持有全表。 */
    private suspend fun buildSynchronizedMembers(
        staged: PlaylistGenerationEntity,
        incoming: PlaylistGenerationEntity,
        plan: PlaylistMutationPlan,
        source: PlaylistPageSource,
    ): BuildCounts {
        val old = checkNotNull(plan.old)
        requireDataOwner(old)
        val input = checkNotNull(plan.input)
        val inputCounts = stageInput(incoming.generationId, input, source, 0L, localOnly = true)
        seal(incoming, inputCounts.count, shareData = false)
        var first = 0L
        var retained = 0L
        while (first < old.memberCount) {
            currentCoroutineContext().ensureActive()
            val limit = minOf(PlaylistLimits.PAGE_SIZE.toLong(), old.memberCount - first).toInt()
            val page = dao.membersPage(old.dataGenerationId, first, limit)
            requireCompletePage(page, first, limit)
            val present = dao.existingReferences(incoming.generationId, page.map { it.referenceKey }).toSet()
            val kept = mutableListOf<PlaylistMemberEntity>()
            for (member in page) {
                member.model()
                if (member.referenceKey in present) {
                    kept.add(member.copy(generationId = staged.generationId, ordinal = retained))
                    retained = increment(retained)
                }
            }
            insertBatch(kept)
            first += page.size
        }
        first = 0L
        var count = retained
        var added = 0L
        while (first < inputCounts.count) {
            currentCoroutineContext().ensureActive()
            val limit = minOf(PlaylistLimits.PAGE_SIZE.toLong(), inputCounts.count - first).toInt()
            val page = dao.membersPage(incoming.generationId, first, limit)
            requireCompletePage(page, first, limit)
            val previous = dao.existingReferences(old.dataGenerationId, page.map { it.referenceKey }).toSet()
            val appended = mutableListOf<PlaylistMemberEntity>()
            for (member in page) {
                member.model()
                if (member.referenceKey !in previous) {
                    appended.add(member.copy(generationId = staged.generationId, ordinal = count))
                    count = increment(count)
                    added = increment(added)
                }
            }
            insertBatch(appended)
            first += page.size
        }
        return BuildCounts(count, added, inputCounts.duplicates, old.memberCount - retained)
    }

    private suspend fun stageInput(
        generationId: String,
        input: FrozenPlaylistInput,
        source: PlaylistPageSource,
        firstOrdinal: Long,
        existingGenerationId: String? = null,
        localOnly: Boolean = false,
    ): BuildCounts {
        var offset = 0L
        var count = firstOrdinal
        var added = 0L
        var duplicates = 0L
        do {
            currentCoroutineContext().ensureActive()
            val expected = minOf(PlaylistLimits.PAGE_SIZE.toLong(), input.totalCount - offset).toInt()
            val page = readInput(source, offset, maxOf(1, expected))
            currentCoroutineContext().ensureActive()
            if (page.view != input || page.offset != offset) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            if (page.references.size != expected || page.endReached != (offset + expected == input.totalCount)) {
                throw PlaylistException(PlaylistIssue.INCOMPLETE)
            }
            val references = page.references.toList()
            if (references.size != expected) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            if (localOnly && references.any { it.trackKey == null }) throw PlaylistException(PlaylistIssue.INVALID_INPUT)
            val keys = references.map { it.identityKey() }
            val seen = dao.existingReferences(generationId, keys).toMutableSet()
            if (existingGenerationId != null) seen.addAll(dao.existingReferences(existingGenerationId, keys))
            val members = mutableListOf<PlaylistMemberEntity>()
            for (reference in references) {
                if (!seen.add(reference.identityKey())) {
                    duplicates = increment(duplicates)
                } else {
                    members.add(reference.record(generationId, UUID.randomUUID().toString(), count))
                    count = increment(count)
                    added = increment(added)
                }
            }
            insertBatch(members)
            offset += references.size
        } while (offset < input.totalCount)
        return BuildCounts(count, added, duplicates)
    }

    private suspend fun copyMembers(
        staged: PlaylistGenerationEntity,
        plan: PlaylistMutationPlan,
        old: PlaylistGenerationEntity,
    ): Long {
        var first = 0L
        var count = 0L
        while (first < old.memberCount) {
            currentCoroutineContext().ensureActive()
            val limit = minOf(PlaylistLimits.PAGE_SIZE.toLong(), old.memberCount - first).toInt()
            val page = dao.membersPage(old.dataGenerationId, first, limit)
            requireCompletePage(page, first, limit)
            val kept = page.filter { it.memberId != plan.removeMemberId }.map { member ->
                member.model()
                val ordinal = plan.ordinalAfter(member.ordinal)
                if (member.memberId == plan.replacementMemberId) {
                    checkNotNull(plan.replacementReference).record(staged.generationId, member.memberId, ordinal)
                } else member.copy(generationId = staged.generationId, ordinal = ordinal)
            }
            insertBatch(kept)
            count += kept.size
            first += page.size
        }
        return count
    }

    private suspend fun readInput(source: PlaylistPageSource, offset: Long, limit: Int): PlaylistInputPage {
        try {
            return source.read(offset, limit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PlaylistException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw PlaylistException(PlaylistIssue.INVALID_INPUT, error)
        } catch (error: Exception) {
            throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE, error)
        }
    }

    private suspend fun insertBatch(members: List<PlaylistMemberEntity>) {
        require(members.size <= PlaylistLimits.PAGE_SIZE)
        currentCoroutineContext().ensureActive()
        if (members.isNotEmpty()) database.withPreservedTransaction { dao.insertMembers(members) }
    }

    private suspend fun seal(generation: PlaylistGenerationEntity, count: Long, shareData: Boolean) {
        currentCoroutineContext().ensureActive()
        database.withPreservedTransaction {
            if (shareData) requireDataOwner(generation) else {
                val order = dao.orderSummary(generation.generationId)
                if (order.count != count || (count == 0L && (order.firstOrdinal != null || order.lastOrdinal != null)) ||
                    (count > 0L && (order.firstOrdinal != 0L || order.lastOrdinal != count - 1L))) {
                    throw PlaylistException(PlaylistIssue.INCOMPLETE)
                }
            }
            if (dao.seal(generation.generationId, count) != 1) throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
        }
    }

    private suspend fun acceptGeneration(
        request: PlaylistMutationRequest,
        fingerprint: String,
        staged: PlaylistGenerationEntity,
        plan: PlaylistMutationPlan,
        counts: BuildCounts,
        onAccepted: () -> Unit,
    ): PlaylistCommit = withContext(NonCancellable) {
        val commit = database.withPreservedTransaction {
            replay(request, fingerprint)?.let { return@withPreservedTransaction it }
            verifyExpectedHead(request, plan.old)
            verifyTransactionFences(request)
            if (dao.generation(staged.generationId) != staged.copy(state = "SEALED", memberCount = counts.count)) {
                throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
            }
            val meta = requireMeta()
            val nextGlobal = increment(meta.revision)
            val old = plan.old
            if (dao.publish(staged.generationId, nextGlobal) != 1) throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
            if (old == null) {
                dao.insertHead(PlaylistHeadEntity(request.playlistId, staged.generationId, staged.revision, meta.nextPlaylistSequence))
            } else {
                if (dao.closeGeneration(old.generationId, nextGlobal - 1L) != 1) throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
                if (dao.publishHead(request.playlistId, old.revision, old.generationId,
                        staged.generationId, staged.revision, deleted = false) != 1) {
                    throw PlaylistException(PlaylistIssue.CONFLICT)
                }
            }
            val nextSequence = if (old == null) increment(meta.nextPlaylistSequence) else meta.nextPlaylistSequence
            if (dao.updateMeta(meta.copy(revision = nextGlobal, nextPlaylistSequence = nextSequence)) != 1) {
                throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
            }
            val sameName = dao.sameNameCount(plan.nameKey, request.playlistId)
            val receipt = PlaylistReceiptEntity(request.operationId, request.playlistId, fingerprint, staged.revision,
                plan.result.name, counts.added, counts.removed, counts.duplicates, sameName)
            dao.insertReceipt(receipt)
            receipt.model(currentSnapshot(request.playlistId), wasReplay = false)
        }
        // 事务返回才代表提交成功；历史receipt重放不能认领本次staging。
        if (!commit.wasReplay) onAccepted()
        commit
    }

    private suspend fun acceptUnchanged(
        request: PlaylistMutationRequest,
        fingerprint: String,
        old: PlaylistGenerationEntity,
        duplicateCount: Long = 0L,
        result: PlaylistOperationResult = PlaylistOperationResult.UNCHANGED,
    ): PlaylistCommit {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            database.withPreservedTransaction {
                replay(request, fingerprint)?.let { return@withPreservedTransaction it }
                verifyExpectedHead(request, old)
                verifyTransactionFences(request)
                val head = requireHead(request.playlistId)
                val current = requireCurrent(head)
                val receipt = PlaylistReceiptEntity(request.operationId, request.playlistId, fingerprint, head.revision,
                    result.name, 0L, 0L, duplicateCount, dao.sameNameCount(current.nameKey, request.playlistId))
                dao.insertReceipt(receipt)
                receipt.model(current.model(), wasReplay = false)
            }
        }
    }

    private suspend fun acceptDelete(
        request: PlaylistMutationRequest,
        fingerprint: String,
        old: PlaylistGenerationEntity,
    ): PlaylistCommit {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            database.withPreservedTransaction {
                replay(request, fingerprint)?.let { return@withPreservedTransaction it }
                verifyExpectedHead(request, old)
                verifyTransactionFences(request)
                val meta = requireMeta()
                val nextGlobal = increment(meta.revision)
                val nextList = increment(old.revision)
                if (dao.closeGeneration(old.generationId, nextGlobal - 1L) != 1) throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
                if (dao.publishHead(request.playlistId, old.revision, old.generationId, null, nextList, deleted = true) != 1) {
                    throw PlaylistException(PlaylistIssue.CONFLICT)
                }
                if (dao.updateMeta(meta.copy(revision = nextGlobal)) != 1) throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
                val receipt = PlaylistReceiptEntity(request.operationId, request.playlistId, fingerprint, nextList,
                    PlaylistOperationResult.DELETED.name, 0L, old.memberCount, 0L, 0L)
                dao.insertReceipt(receipt)
                receipt.model(null, wasReplay = false)
            }
        }
    }

    private suspend fun replay(request: PlaylistMutationRequest, fingerprint: String): PlaylistCommit? {
        val receipt = dao.receipt(request.operationId) ?: return null
        if (receipt.playlistId != request.playlistId || receipt.fingerprint != fingerprint) {
            throw PlaylistException(PlaylistIssue.INVALID_INPUT)
        }
        return receipt.model(currentSnapshot(receipt.playlistId), wasReplay = true)
    }

    private suspend fun verifyExpectedHead(request: PlaylistMutationRequest, old: PlaylistGenerationEntity?) {
        val head = dao.head(request.playlistId)
        if (old == null) {
            if (head != null || request.expectedRevision != 0L) throw PlaylistException(PlaylistIssue.CONFLICT)
        } else if (head == null || head.deleted || head.revision != request.expectedRevision ||
            head.activeGenerationId != old.generationId) {
            throw PlaylistException(PlaylistIssue.CONFLICT)
        }
    }

    /** 位于head CAS的同一短事务；中途扫描、撤权或规则变化不能被调用前boolean掩盖。 */
    private suspend fun verifyTransactionFences(request: PlaylistMutationRequest) {
        check(database.inTransaction())
        request.expectedLibraryRevision?.let {
            val verifier = libraryFenceVerifier ?: throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
            verifier.verify(it)
        }
        (request.expectedSourceFence ?: request.mutation.folderSynchronization()?.fence)?.let {
            val verifier = folderFenceVerifier ?: throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
            verifier.verify(it)
        }
    }

    private fun unchangedResult(mutation: PlaylistMutation): PlaylistOperationResult = when (mutation) {
        is PlaylistMutation.SynchronizeFolder -> PlaylistOperationResult.SYNCHRONIZED
        is PlaylistMutation.ReplaceReference -> PlaylistOperationResult.REFERENCE_REPLACED
        else -> PlaylistOperationResult.UNCHANGED
    }

    private suspend fun currentSnapshot(id: String): PlaylistSnapshot? {
        val head = dao.head(id) ?: return null
        if (head.deleted) return null
        return requireCurrent(head).model()
    }

    private suspend fun requireCurrent(head: PlaylistHeadEntity): PlaylistGenerationEntity {
        if (head.deleted) throw PlaylistException(PlaylistIssue.NOT_FOUND)
        val generation = requireGeneration(head.activeGenerationId ?: throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED))
        if (generation.playlistId != head.playlistId || generation.revision != head.revision || generation.validTo != null) {
            throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
        }
        generation.model()
        return generation
    }

    private suspend fun requireGeneration(id: String): PlaylistGenerationEntity =
        dao.generation(id) ?: throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)

    private suspend fun requireHead(id: String): PlaylistHeadEntity =
        dao.head(id) ?: throw PlaylistException(PlaylistIssue.NOT_FOUND)

    private suspend fun requireMeta(): PlaylistMetaEntity {
        val meta = dao.meta() ?: throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
        if (meta.revision < 0L || meta.nextPlaylistSequence <= 0L) throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
        return meta
    }

    private suspend fun requireDataOwner(generation: PlaylistGenerationEntity) {
        val owner = requireGeneration(generation.dataGenerationId)
        if (owner.dataGenerationId != owner.generationId || owner.memberCount != generation.memberCount || owner.state != "PUBLISHED") {
            throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
        }
    }

    private suspend fun generationAt(id: String, revision: Long): PlaylistGenerationEntity? {
        val matches = dao.generationAt(id, revision)
        if (matches.size > 1) throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
        return matches.singleOrNull()
    }

    private suspend fun readOverview(): PlaylistOverview {
        val meta = requireMeta()
        val favorites = requireCurrent(requireHead(PlaylistLimits.FAVORITES_ID))
        return PlaylistOverview(meta.revision, dao.catalogCount(meta.revision), favorites.memberCount)
    }

    private suspend fun reserveView(owner: PlaylistViewOwner) {
        dao.expireViews(processId, elapsedMs())
        val limit = if (owner == PlaylistViewOwner.UI) PlaylistLimits.UI_VIEWS else PlaylistLimits.QUEUE_VIEWS
        if (dao.viewCount(owner.name) >= limit) throw PlaylistException(PlaylistIssue.BUSY)
    }

    private suspend fun createMemberView(
        generation: PlaylistGenerationEntity,
        owner: PlaylistViewOwner,
        globalRevision: Long,
    ): PlaylistViewToken {
        val snapshot = generation.model()
        requireDataOwner(generation)
        val row = PlaylistViewEntity(UUID.randomUUID().toString(), "MEMBERS", generation.generationId,
            globalRevision, generation.memberCount, owner.name, expiresElapsedMs(), processId)
        dao.insertView(row)
        return PlaylistViewToken(row.viewId, snapshot, owner)
    }

    private suspend fun requireView(id: String, kind: String): PlaylistViewEntity {
        val view = dao.view(id) ?: throw PlaylistException(PlaylistIssue.VIEW_EXPIRED)
        if (view.processId != processId || view.expiresElapsedMs <= elapsedMs()) throw PlaylistException(PlaylistIssue.VIEW_EXPIRED)
        if (view.kind != kind) throw PlaylistException(PlaylistIssue.INVALID_INPUT)
        if (view.revision < 0L || view.totalCount < 0L) throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
        return view
    }

    private suspend fun requireCatalogView(view: PlaylistCatalogView) {
        val row = requireView(view.viewId, "CATALOG")
        require(row.revision == view.revision && row.totalCount == view.totalCount && row.generationId == null)
    }

    private fun requireCompletePage(page: List<PlaylistMemberEntity>, first: Long, count: Int) {
        if (page.size != count || page.withIndex().any { (index, member) -> member.ordinal != first + index }) {
            throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
        }
    }

    private fun requirePageLimit(limit: Int) {
        require(limit in 1..PlaylistLimits.PAGE_SIZE)
    }

    private fun expiresElapsedMs(): Long {
        val now = elapsedMs()
        require(now >= 0L)
        if (now > Long.MAX_VALUE - PlaylistLimits.VIEW_LEASE_MS) throw PlaylistException(PlaylistIssue.REVISION_EXHAUSTED)
        return now + PlaylistLimits.VIEW_LEASE_MS
    }

    private suspend fun reclaimBatch(): Boolean = database.withPreservedTransaction {
        val candidate = dao.reclaimCandidate() ?: return@withPreservedTransaction false
        dao.deleteMembersBatch(candidate.generationId)
        if (!dao.hasMembers(candidate.generationId)) dao.deleteGeneration(candidate.generationId)
        true
    }

    private suspend fun <T> mutate(block: suspend () -> T): T {
        if (!actions.tryLock()) throw PlaylistException(PlaylistIssue.BUSY)
        try {
            return block()
        } finally {
            actions.unlock()
        }
    }

    private suspend fun <T> storage(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: PlaylistException) {
            throw error
        } catch (error: SQLiteFullException) {
            throw PlaylistException(PlaylistIssue.STORAGE_FULL, error)
        } catch (error: SQLiteConstraintException) {
            throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED, error)
        } catch (error: SQLiteException) {
            throw PlaylistException(PlaylistIssue.STORAGE_UNAVAILABLE, error)
        } catch (error: IllegalArgumentException) {
            throw PlaylistException(PlaylistIssue.INVALID_INPUT, error)
        }
    }

    private fun increment(value: Long): Long {
        if (value == Long.MAX_VALUE) throw PlaylistException(PlaylistIssue.REVISION_EXHAUSTED)
        return value + 1L
    }

    private fun PlaylistReceiptEntity.model(snapshot: PlaylistSnapshot?, wasReplay: Boolean): PlaylistCommit {
        val operationResult = try {
            PlaylistOperationResult.valueOf(result)
        } catch (error: IllegalArgumentException) {
            throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED, error)
        }
        if (appliedRevision < 0L || addedCount < 0L || removedCount < 0L || duplicateCount < 0L || sameNameCount < 0L) {
            throw PlaylistException(PlaylistIssue.RECOVERY_REQUIRED)
        }
        return PlaylistCommit(playlistId, appliedRevision, operationResult, snapshot, wasReplay,
            addedCount, removedCount, duplicateCount, sameNameCount)
    }

    companion object {
        private val PROCESS_ID = UUID.randomUUID().toString()
        private const val INITIAL_GENERATION = "system:favorites:initial"
        private const val INITIAL_OPERATION = "system:initialize-favorites"
    }
}
