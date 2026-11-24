package io.github.onlinemo.meloxwatch.data.library

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.sqlite.SQLiteException
import io.github.onlinemo.meloxwatch.data.database.toPlatformSQLiteException
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.backup.readBackupProjection
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.data.scan.ScanState
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueCatalogToken
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import org.json.JSONException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One application owner; only short database work is serialized, never provider I/O. */
class RoomLocalLibraryRepository(
    internal val database: MeloXDatabase,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val epochMs: () -> Long = System::currentTimeMillis,
) : LocalLibraryRepository, LibraryMutationStore {
    internal val dao = database.libraryDao()
    private val mutex = Mutex()
    private val sessionId = id()
    private val liveViews = linkedMapOf<String, LibraryViewToken>()
    private var initialized = false

    override suspend fun initialize(): LibraryOverview = access {
        if (!initialized) {
            database.withPreservedTransaction {
                meta()
                dao.expireViews(sessionId, elapsedMs())
            }
            var after: String? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val batch = database.withPreservedTransaction {
                    dao.activeSourcesPage(after).also { sources ->
                        for (source in sources) {
                            source.activeScanId?.let { scanId ->
                                dao.scan(scanId)?.takeUnless { terminal(it.phase) }?.let {
                                    dao.updateScan(it.copy(phase = ScanPhase.INTERRUPTED.name))
                                }
                                dao.putSource(source.copy(activeScanId = null))
                            }
                        }
                    }
                }
                if (batch.isEmpty()) break
                after = batch.last().sourceId
            }
            initialized = true
            cleanVersions()
        }
        overviewUnlocked()
    }

    override suspend fun overview(): LibraryOverview = access { database.withPreservedTransaction { overviewUnlocked() } }
    override fun observeOverview(): Flow<LibraryOverview> = flow {
        database.backupMutationBarrier.readBackupProjection { Unit }
        emitAll(dao.observeMeta().map {
            database.backupMutationBarrier.readBackupProjection { overview() }
        })
    }.distinctUntilChanged()
    override suspend fun source(sourceId: String): LibrarySource? = access { dao.source(sourceId)?.let { sourceModel(it) } }
    override suspend fun sourcesPage(afterSourceId: String?, limit: Int): LibrarySourcePage = access {
        pageLimit(limit)
        database.withPreservedTransaction {
            val sources = dao.sourcesPage(afterSourceId, limit)
            val last = sources.lastOrNull()?.sourceId
            val more = last != null && dao.sourcesPage(last, 1).isNotEmpty()
            LibrarySourcePage(sources.map { sourceModel(it) }, last.takeIf { more })
        }
    }

    override suspend fun openView(query: LibraryQuery, sort: LibrarySort, owner: LibraryViewOwner): LibraryViewToken = access {
        database.withPreservedTransaction {
            expireViews()
            val current = meta()
            query.folderNodeId?.let { nodeId ->
                val folder = dao.currentNode(nodeId) ?: fail(LibraryIssue.INVALID_INPUT)
                if (folder.sourceId != query.sourceId || !folder.directory) fail(LibraryIssue.INVALID_INPUT)
                if (dao.text(LibrarySql.relativePath(current.revision, folder.sourceId, nodeId)) == null) {
                    fail(LibraryIssue.SOURCE_CHANGED)
                }
            }
            val draft = LibraryViewToken(id(), current.revision, current.rulesRevision, 0L, query, sort, current.shortAudioFilter, owner,
                statisticsEpoch = current.statisticsEpoch)
            val token = draft.copy(totalCount = dao.scalar(LibrarySql.count(draft)))
            storeView(token)
            token
        }
    }

    override suspend fun openQueueView(fromViewId: String): LibraryViewToken = access {
        database.withPreservedTransaction {
            val original = liveViews[fromViewId] ?: fail(LibraryIssue.VIEW_EXPIRED)
            checkView(original)
            if (original.owner != LibraryViewOwner.UI) fail(LibraryIssue.INVALID_INPUT)
            val token = original.copy(viewId = id(), owner = LibraryViewOwner.QUEUE)
            storeView(token)
            token
        }
    }

    override suspend fun page(view: LibraryViewToken, after: LibraryCursor?, limit: Int): LibraryPage = access {
        database.withPreservedTransaction {
            checkPageInput(view, after, limit)
            // 合法 after 已在同一事务中核验，它证明上一页存在；查询窗口仍保持原 limit。
            val rows = dao.rows(LibrarySql.rows(view, after, limit))
            pageResult(view, rows, moreBefore = after != null, moreAfter = null)
        }
    }

    override suspend fun previousPage(view: LibraryViewToken, before: LibraryCursor, limit: Int): LibraryPage = access {
        database.withPreservedTransaction {
            checkPageInput(view, before, limit)
            val rows = dao.rows(LibrarySql.rows(view, before, limit, before = true)).asReversed()
            pageResult(view, rows, moreBefore = null, moreAfter = true)
        }
    }

    override suspend fun groups(view: LibraryViewToken, kind: LibraryGroupKind, afterKey: String?, limit: Int): LibraryGroupPage = access {
        database.withPreservedTransaction {
            checkView(view); pageLimit(limit)
            val rows = dao.groupRows(LibrarySql.groups(view, kind, afterKey, limit))
            val last = rows.lastOrNull()?.groupKey
            val more = last != null && dao.groupRows(LibrarySql.groups(view, kind, last, 1)).isNotEmpty()
            LibraryGroupPage(view, rows.map { LibraryGroup(it.groupKey, it.title, it.subtitle, it.trackCount) }, last.takeIf { more })
        }
    }

    override suspend fun folders(view: LibraryViewToken, parentNodeId: String?, afterNodeId: String?, limit: Int): LibraryFolderPage = access {
        database.withPreservedTransaction {
            checkView(view); pageLimit(limit)
            if (parentNodeId != null && folderUnlocked(view, parentNodeId) == null) fail(LibraryIssue.INVALID_INPUT)
            val rows = dao.nodeRows(LibrarySql.folders(view, parentNodeId, afterNodeId, limit))
            val last = rows.lastOrNull()?.nodeId
            val more = last != null && dao.nodeRows(LibrarySql.folders(view, parentNodeId, last, 1)).isNotEmpty()
            LibraryFolderPage(view, rows.map { LibraryFolder(it.nodeId, it.sourceId, it.name, it.parentNodeId) }, last.takeIf { more })
        }
    }

    override suspend fun folder(view: LibraryViewToken, nodeId: String): LibraryFolder? = access {
        database.withPreservedTransaction { checkView(view); folderUnlocked(view, nodeId) }
    }

    override suspend fun sections(view: LibraryViewToken): List<LibrarySection> = access {
        database.withPreservedTransaction {
            checkView(view)
            dao.sectionRows(LibrarySql.sections(view)).map { section ->
                val first = dao.rows(LibrarySql.firstInSection(view, section.sectionKey)).firstOrNull()
                LibrarySection(section.sectionKey, section.trackCount, first?.let { cursorBefore(view, it.cursor(view)) })
            }
        }
    }

    override suspend fun locate(view: LibraryViewToken, trackKey: TrackKey): LibraryLocation? = access {
        database.withPreservedTransaction {
            checkView(view)
            val row = dao.rows(LibrarySql.row(view, trackKey.providerId, trackKey.trackId)).firstOrNull()
            row?.let { LibraryLocation(it.model(), dao.scalar(LibrarySql.countBefore(view, it.cursor(view))), cursorBefore(view, it.cursor(view))) }
        }
    }

    /** Resolve only within the exact live lease; a caller-supplied id cannot open latest data. */
    internal suspend fun playlistTracks(context: PlaylistQueueCatalogToken, keys: List<TrackKey>): List<LibraryTrack?> = access {
        if (keys.size > LibraryLimits.PAGE_SIZE) fail(LibraryIssue.INVALID_INPUT)
        database.withPreservedTransaction {
            val view = liveViews[context.id] ?: fail(LibraryIssue.VIEW_EXPIRED)
            checkView(view)
            if (view.revision != context.revision || view.rulesRevision != context.rulesRevision) fail(LibraryIssue.SOURCE_CHANGED)
            val distinct = keys.distinct()
            val rows = dao.rows(LibrarySql.rowsForKeys(view, distinct))
            val resolved = linkedMapOf<TrackKey, LibraryTrack>()
            for (row in rows) {
                val track = row.model()
                if (track.trackKey !in distinct || resolved.put(track.trackKey, track) != null) fail(LibraryIssue.RECOVERY_REQUIRED)
            }
            keys.map { resolved[it] }
        }
    }

    internal suspend fun playlistRelativeMatches(
        view: LibraryViewToken, rootNodeId: String, components: List<String>,
    ): List<TrackKey> = access {
        database.withPreservedTransaction {
            checkView(view)
            if (view.query.sourceId == null || view.query.folderNodeId != rootNodeId || !view.query.folderSubtree) {
                fail(LibraryIssue.INVALID_INPUT)
            }
            dao.trackKeys(LibrarySql.scopedRelativePathMatches(view.revision, view.query.sourceId, rootNodeId, components, 2))
                .map { TrackKey(it.providerId, it.trackId) }
        }
    }

    internal suspend fun playlistRelativePath(view: LibraryViewToken, track: LibraryTrack): String? = access {
        database.withPreservedTransaction {
            checkView(view)
            if (track.sourceId == null || track.nodeId == null || track.availability != LibraryAvailability.AVAILABLE) null
            else dao.text(LibrarySql.relativePath(view.revision, track.sourceId, track.nodeId))
        }
    }

    internal suspend fun validatePlaylistView(view: LibraryViewToken) = access {
        database.withPreservedTransaction { checkView(view) }
    }

    internal suspend fun playlistOverlap(view: LibraryViewToken, base: io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken): Long = access {
        database.withPreservedTransaction {
            checkView(view)
            val playlistDao = database.playlistDao()
            val lease = playlistDao.view(base.viewId) ?: fail(LibraryIssue.VIEW_EXPIRED)
            if (lease.generationId != base.snapshot.generationId || lease.expiresElapsedMs <= elapsedMs()) {
                fail(LibraryIssue.VIEW_EXPIRED)
            }
            val generation = playlistDao.generation(base.snapshot.generationId) ?: fail(LibraryIssue.VIEW_EXPIRED)
            if (generation.revision != base.snapshot.revision || generation.memberCount != base.snapshot.memberCount) {
                fail(LibraryIssue.SOURCE_CHANGED)
            }
            dao.scalar(LibrarySql.playlistOverlap(view, generation.dataGenerationId))
        }
    }

    internal suspend fun playlistAvailableScopeCount(view: LibraryViewToken): Long = access {
        database.withPreservedTransaction {
            checkView(view)
            dao.scalar(LibrarySql.count(view.copy(query = LibraryQuery(readableOnly = true))))
        }
    }

    override suspend fun closeView(viewId: String) {
        // A cancelled owner still releases its lease; large reclamation retains cancellation.
        withContext(NonCancellable) {
            releaseViewMetadata(viewId)
        }
        access { cleanVersions() }
    }

    /** A playlist owner retains this exact token when the cancellable close deadline expires. */
    suspend fun closePlaylistView(viewId: String, timeoutMs: Long) {
        require(timeoutMs > 0L)
        withContext(NonCancellable) {
            // Room 2.8 retries every TimeoutCancellationException from its connection wait.
            // Keep the worker outside the await deadline, then join a plain cancellation so
            // queued metadata cannot delete the retained token after this handoff returns.
            // Supervision preserves a worker failure for await while the handoff stays active.
            supervisorScope {
                val metadata = async {
                    releaseViewMetadata(viewId)
                    access { cleanVersions() }
                }
                var failure: Throwable? = null
                try {
                    withTimeout(timeoutMs) { metadata.await() }
                } catch (error: Throwable) {
                    failure = error
                    try { metadata.cancel(CancellationException("Playlist view close did not complete", error)) }
                    catch (cleanup: Throwable) {
                        if (cleanup !== error) error.addSuppressed(cleanup)
                    }
                    throw error
                } finally {
                    try { metadata.cancelAndJoin() }
                    catch (cleanup: Throwable) {
                        val original = failure
                        if (original == null) throw cleanup
                        if (cleanup !== original) original.addSuppressed(cleanup)
                    }
                }
            }
        }
    }

    private suspend fun releaseViewMetadata(viewId: String) = access {
        database.withPreservedTransaction { dao.deleteView(viewId) }
        liveViews.remove(viewId)
    }

    override fun queueSource(view: LibraryViewToken): QueuePageSource {
        val readMutex = Mutex()
        var cursor: LibraryCursor? = null
        var nextOffset = 0L
        val frozen = FrozenQueueView(view.viewId, view.revision, view.totalCount)
        return QueuePageSource { offset, limit ->
            readMutex.withLock {
                if (view.owner != LibraryViewOwner.QUEUE || offset != nextOffset || limit !in 1..LibraryLimits.PAGE_SIZE) {
                    throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
                }
                try {
                    val (entries, lastCursor) = access {
                        database.withPreservedTransaction {
                            checkView(view)
                            val rows = dao.rows(LibrarySql.rows(view, cursor, limit))
                            rows.mapIndexed { index, row ->
                                val track = row.model()
                                val order = add(offset, index.toLong())
                                QueueEntry(id(), track.trackKey, order, order, track.title, track.metadata.durationMs, track.location)
                            } to rows.lastOrNull()?.cursor(view)
                        }
                    }
                    nextOffset = add(nextOffset, entries.size.toLong())
                    cursor = lastCursor ?: cursor
                    QueueInputPage(frozen, offset, entries)
                } catch (error: LibraryException) {
                    val issue = when (error.issue) {
                        LibraryIssue.VIEW_EXPIRED, LibraryIssue.SOURCE_CHANGED, LibraryIssue.CONFLICT -> QueueStoreIssue.SOURCE_CHANGED
                        LibraryIssue.STORAGE_FULL -> QueueStoreIssue.STORAGE_FULL
                        LibraryIssue.INVALID_INPUT -> QueueStoreIssue.INVALID_INPUT
                        else -> QueueStoreIssue.SOURCE_UNAVAILABLE
                    }
                    throw QueueStoreException(issue, error)
                }
            }
        }
    }

    override suspend fun resolveLocalRead(trackKey: TrackKey, expectedLocationId: String?, expectedContentRevision: Long?): LocalReadDescriptor = access {
        database.withPreservedTransaction {
            val current = meta()
            val view = internalView(current, LibraryQuery(includeHidden = true))
            val row = dao.rows(LibrarySql.row(view, trackKey.providerId, trackKey.trackId)).firstOrNull() ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
            when (row.model().availability) {
                LibraryAvailability.NEEDS_AUTHORIZATION -> fail(LibraryIssue.PERMISSION_REVOKED)
                LibraryAvailability.MISSING -> fail(LibraryIssue.FILE_MISSING)
                LibraryAvailability.UNAVAILABLE, LibraryAvailability.REMOVED -> fail(LibraryIssue.SOURCE_UNAVAILABLE)
                else -> Unit
            }
            val reference = row.reference() ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
            if (expectedLocationId != null && expectedLocationId != reference.locationId) fail(LibraryIssue.SOURCE_CHANGED)
            if (expectedContentRevision != null && expectedContentRevision != reference.contentRevision) fail(LibraryIssue.SOURCE_CHANGED)
            val source = dao.source(row.selectedSourceId ?: fail(LibraryIssue.RECOVERY_REQUIRED)) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            LocalReadDescriptor(trackKey, sourceModel(source), row.selectedAliasId ?: fail(LibraryIssue.RECOVERY_REQUIRED), reference,
                row.selectedObjectId ?: fail(LibraryIssue.RECOVERY_REQUIRED), row.selectedProviderContentToken, row.selectedSizeBytes,
                verifiedContentToken = row.selectedVerifiedContentToken)
        }
    }

    override suspend fun registerSource(spec: LocalSourceSpec, grant: LocalGrantObservation): LibrarySource = access {
        database.withPreservedTransaction {
            validateSource(spec, grant)
            val old = dao.source(spec.sourceId)
            if (spec.kind == LocalSourceKind.APP_OWNED && old == null) fail(LibraryIssue.INVALID_INPUT)
            if (old != null) {
                if (LibraryCodec.source(old.spec) != spec) fail(LibraryIssue.SOURCE_CHANGED)
                if (sourceModel(old).grant != grant) fail(LibraryIssue.CONFLICT)
                return@withPreservedTransaction sourceModel(old)
            }
            val current = meta()
            val revision = increment(current.revision)
            val source = LibrarySourceEntity(spec.sourceId, LibraryCodec.source(spec), id(), 1L, 1L, sourceState(grant).name, null, null, null, null, null)
            dao.putSource(source)
            dao.insertGrant(grantRecord(source.grantId, spec, grant))
            dao.insertSourceVersion(sourceVersion(source, revision))
            dao.updateMeta(current.copy(revision = revision))
            sourceModel(source)
        }.also { cleanVersions() }
    }

    override suspend fun reauthorizeSource(sourceId: String, expectedAuthEpoch: Long, selectedSpec: LocalSourceSpec, grant: LocalGrantObservation): LibrarySource = access {
        database.withPreservedTransaction {
            val old = checkedSource(sourceId, expectedAuthEpoch)
            val original = LibraryCodec.source(old.spec)
            validateSource(selectedSpec, grant)
            if (!sameSourceIdentity(original, selectedSpec) || grant.read != ObservedCapability.AVAILABLE) fail(LibraryIssue.SOURCE_CHANGED)
            val selected = selectedSpec.copy(sourceId = sourceId)
            if (old.spec == LibraryCodec.source(selected) && sourceModel(old).grant == grant && old.state == LibrarySourceState.ACTIVE.name) return@withPreservedTransaction sourceModel(old)
            invalidateScan(old, ScanPhase.CANCELLED, SourceReadIssue.SOURCE_CHANGED)
            val current = meta()
            val revision = increment(current.revision)
            val next = old.copy(spec = LibraryCodec.source(selected), grantId = id(), revision = increment(old.revision), authEpoch = increment(old.authEpoch), state = LibrarySourceState.ACTIVE.name, activeScanId = null)
            dao.insertGrant(grantRecord(next.grantId, selected, grant))
            dao.putSource(next)
            dao.closeSourceVersion(sourceId, revision)
            dao.insertSourceVersion(sourceVersion(next, revision))
            dao.updateMeta(current.copy(revision = revision))
            sourceModel(next)
        }.also { cleanVersions() }
    }

    override suspend fun setSourceIssue(sourceId: String, expectedAuthEpoch: Long, issue: SourceReadIssue): LibrarySource = changeSource(sourceId, expectedAuthEpoch,
        if (issue == SourceReadIssue.PERMISSION_REVOKED || issue == SourceReadIssue.PERMISSION_DENIED) LibrarySourceState.NEEDS_AUTHORIZATION else LibrarySourceState.UNAVAILABLE, issue)
    override suspend fun removeSource(sourceId: String, expectedAuthEpoch: Long): LibrarySource = changeSource(sourceId, expectedAuthEpoch, LibrarySourceState.REMOVED, null)

    override suspend fun beginScan(sourceId: String, expectedAuthEpoch: Long, expectedRulesRevision: Long, mode: ScanMode): ScanToken = access {
        database.withPreservedTransaction {
            var source = checkedSource(sourceId, expectedAuthEpoch)
            val current = meta()
            if (current.rulesRevision != expectedRulesRevision) fail(LibraryIssue.CONFLICT)
            if (source.state != LibrarySourceState.ACTIVE.name) fail(LibraryIssue.SOURCE_UNAVAILABLE)
            source.activeScanId?.let { active ->
                val scan = dao.scan(active) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
                val valid = scan.sourceRevision == source.revision && scan.authEpoch == source.authEpoch &&
                    scan.rulesRevision == current.rulesRevision && !terminal(scan.phase)
                if (valid) {
                    if (scan.mode != mode.name || scan.phase == ScanPhase.STOPPING.name) fail(LibraryIssue.BUSY)
                    return@withPreservedTransaction scan.token()
                }
                // Losing a fence does not prove that blocking provider work has actually stopped.
                if (!terminal(scan.phase)) fail(LibraryIssue.SOURCE_CHANGED)
                source = source.copy(activeScanId = null)
                dao.putSource(source)
            }
            if (dao.activeScanCount() != 0L) fail(LibraryIssue.BUSY)
            val scan = LibraryScanEntity(id(), sourceId, source.revision, source.authEpoch, current.rulesRevision, mode.name, ScanPhase.PREPARING.name,
                current.nextScanSequence, current.revision, 0L, 0L, 0L, 0L, 0L, false, false, false, false, false, null, false, null, null)
            dao.insertScan(scan)
            dao.putSource(source.copy(activeScanId = scan.scanId, latestScanId = scan.scanId))
            dao.updateMeta(current.copy(nextScanSequence = increment(current.nextScanSequence)))
            scan.token()
        }
    }

    override suspend fun commitSeen(token: ScanToken, candidates: List<ObservedCandidate>): SeenBatchCommit = access {
        batch(candidates.size)
        database.withPreservedTransaction {
            var scan = checkedScan(token, allowStaging = false)
            val source = dao.source(token.sourceId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            val spec = LibraryCodec.source(source.spec)
            var current = meta()
            val revision = increment(current.revision)
            var seen = 0L
            var added = 0L
            var skipped = 0L
            for (observed in candidates) {
                val candidate = if (spec.kind == LocalSourceKind.APP_OWNED) ownedObservation(spec, observed) else observed
                validateCandidate(token.sourceId, candidate)
                if (dao.seen(token.scanId, candidate.objectId) != null) continue
                val node = writeNode(candidate, revision)
                // New ancestry belongs to this pending revision, before the batch publishes library_meta.
                val excluded = excludedUnlocked(token, candidate.objectId, candidate.parentObjectId, revision)
                val alias = if (candidate.mayContainAudio && !excluded) {
                    val result = writeAudio(spec, candidate, node.nodeId, scan.sequence, revision, current.nextAddedSequence)
                    if (result.second) { added++; current = current.copy(nextAddedSequence = increment(current.nextAddedSequence)) }
                    result.first
                } else null
                dao.insertSeen(LibrarySeenEntity(token.scanId, candidate.objectId, node.nodeId, alias?.aliasId, false))
                if (candidate.directory && !excluded) dao.insertFrontier(LibraryFrontierEntity(token.scanId, candidate.objectId, LibraryCodec.candidate(candidate), "QUEUED"))
                if (excluded || (!candidate.directory && !candidate.mayContainAudio)) skipped++
                if (excluded) scan = scan.copy(excludedSubtrees = true)
                seen++
            }
            if (seen > 0L) dao.updateMeta(current.copy(revision = revision))
            dao.updateScan(scan.copy(phase = ScanPhase.RUNNING.name, seen = add(scan.seen, seen), added = add(scan.added, added), skipped = add(scan.skipped, skipped)))
            SeenBatchCommit(if (seen == 0L) current.revision else revision, seen, added)
        }.also { cleanVersions() }
    }

    override suspend fun commitMetadata(token: ScanToken, outcomes: List<MetadataOutcome>): MetadataBatchCommit = access {
        batch(outcomes.size)
        var revision = meta().revision
        var processed = 0L
        var failed = 0L
        var outcomeIndex = 0
        while (outcomeIndex < outcomes.size) {
            currentCoroutineContext().ensureActive()
            database.withPreservedTransaction {
                var scan = checkedScan(token, allowStaging = false)
                val current = meta()
                val nextRevision = increment(current.revision)
                var budget = LibraryLimits.PAGE_SIZE
                var changed = false
                while (outcomeIndex < outcomes.size && budget > 0) {
                    val outcome = outcomes[outcomeIndex]
                    val seen = dao.seen(token.scanId, outcome.objectId) ?: fail(LibraryIssue.INVALID_INPUT)
                    if (seen.metadataPayload != null && LibraryCodec.outcome(seen.metadataPayload) != outcome) fail(LibraryIssue.CONFLICT)
                    if (seen.metadataProcessed) { outcomeIndex++; continue }
                    val alias = dao.currentAlias(seen.aliasId ?: fail(LibraryIssue.INVALID_INPUT)) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
                    val relations = dao.locationRelations(alias.locationId, seen.metadataAfterRelationId, budget)
                    if (relations.isEmpty() && seen.metadataAfterRelationId == null) fail(LibraryIssue.RECOVERY_REQUIRED)
                    if (seen.metadataPayload == null && outcome.verifiedContentToken != null) {
                        writeVerifiedContent(alias.locationId, outcome.verifiedContentToken, nextRevision)
                    }
                    for (relation in relations) {
                        val old = dao.currentTrack(relation.trackId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
                        writeTrackVersion(old, outcome.metadata ?: old.metadata(), alias.filename, outcome.issue?.name, nextRevision)
                    }
                    val after = relations.lastOrNull()?.relationId ?: seen.metadataAfterRelationId
                    val done = relations.size < budget || dao.locationRelations(alias.locationId, after, 1).isEmpty()
                    if (done) {
                        val accessIssue = outcome.issue?.takeIf { it in setOf(SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED,
                            SourceReadIssue.SOURCE_UNAVAILABLE, SourceReadIssue.SOURCE_CHANGED, SourceReadIssue.FILE_MISSING) }
                        if (accessIssue != null) writeAliasVersion(alias.copy(readIssue = accessIssue.name), nextRevision)
                        processed++
                        if (outcome.issue != null) failed++
                        scan = scan.copy(processed = increment(scan.processed), failed = if (outcome.issue == null) scan.failed else increment(scan.failed))
                        outcomeIndex++
                    }
                    dao.updateSeen(seen.copy(metadataPayload = LibraryCodec.outcome(outcome), metadataAfterRelationId = after, metadataProcessed = done))
                    budget -= relations.size
                    changed = true
                }
                if (changed) {
                    revision = nextRevision
                    dao.updateMeta(current.copy(revision = revision))
                    dao.updateScan(scan)
                }
            }
        }
        MetadataBatchCommit(revision, processed, failed).also { cleanVersions() }
    }

    override suspend fun nextDirectory(token: ScanToken): ObservedCandidate? = access {
        database.withPreservedTransaction {
            checkedScan(token, allowStaging = false)
            dao.nextFrontier(token.scanId)?.let { dao.updateFrontier(it.copy(state = "RUNNING")); LibraryCodec.candidate(it.candidate) }
        }
    }

    override suspend fun completeDirectory(token: ScanToken, objectId: String, successful: Boolean): ScanState = access {
        database.withPreservedTransaction {
            val scan = checkedScan(token, allowStaging = false)
            val entry = dao.frontier(token.scanId, objectId) ?: fail(LibraryIssue.INVALID_INPUT)
            if (entry.state == "DONE" || entry.state == "FAILED") return@withPreservedTransaction scan.model()
            if (entry.state != "RUNNING") fail(LibraryIssue.CONFLICT)
            dao.updateFrontier(entry.copy(state = if (successful) "DONE" else "FAILED"))
            val next = if (successful) scan else scan.copy(failed = increment(scan.failed))
            dao.updateScan(next)
            next.model()
        }
    }

    override suspend fun isExcluded(token: ScanToken, objectId: String, parentObjectId: String?): Boolean = access {
        database.withPreservedTransaction {
            checkedScan(token, allowStaging = false)
            excludedUnlocked(token, objectId, parentObjectId, meta().revision)
        }
    }
    override suspend fun setCoverage(token: ScanToken, coverage: ScanCoverage): ScanState = access {
        database.withPreservedTransaction {
            val old = checkedScan(token)
            val next = old.withCoverage(coverage.copy(excludedSubtrees = coverage.excludedSubtrees || old.excludedSubtrees))
            dao.updateScan(next); next.model()
        }
    }
    override suspend fun setWatermark(token: ScanToken, watermark: String?): ScanState = access {
        if (watermark != null && watermark.length > 8192) fail(LibraryIssue.INVALID_INPUT)
        database.withPreservedTransaction { val next = checkedScan(token).copy(watermark = watermark); dao.updateScan(next); next.model() }
    }

    override suspend fun stageAbsence(token: ScanToken, afterAliasId: String?, limit: Int): AbsencePage = access {
        pageLimit(limit)
        database.withPreservedTransaction {
            val scan = checkedScan(token)
            if (token.mode != ScanMode.FULL || !scan.coverage().permitsAbsence || dao.incompleteFrontier(token.scanId) != 0L) fail(LibraryIssue.INCOMPLETE)
            if (scan.absenceAfterAliasId != afterAliasId) fail(LibraryIssue.CONFLICT)
            if (scan.absenceDone) return@withPreservedTransaction AbsencePage(scan.absenceAfterAliasId, dao.absenceCount(token.scanId), true)
            val aliases = dao.aliasRows(LibrarySql.absentAliases(token.sourceId, token.scanId, afterAliasId, limit))
            dao.insertAbsence(aliases.map { LibraryAbsenceEntity(token.scanId, it.aliasId) })
            val last = aliases.lastOrNull()?.aliasId ?: afterAliasId
            val complete = aliases.size < limit || dao.aliasRows(LibrarySql.absentAliases(token.sourceId, token.scanId, last, 1)).isEmpty()
            dao.updateScan(scan.copy(phase = ScanPhase.STAGING_ABSENCE.name, absenceAfterAliasId = last, absenceDone = complete))
            AbsencePage(last, dao.absenceCount(token.scanId), complete)
        }
    }

    override suspend fun finishScan(token: ScanToken, counters: ScanCounters, coverage: ScanCoverage): ScanState = access {
        if (listOf(counters.seen, counters.processed, counters.added, counters.skipped, counters.failed).any { it < 0L }) fail(LibraryIssue.INVALID_INPUT)
        // The only non-cancellable section publishes one pointer; no source or large cleanup runs here.
        withContext(NonCancellable) {
            database.withPreservedTransaction {
                val stored = dao.scan(token.scanId) ?: fail(LibraryIssue.SOURCE_CHANGED)
                if (stored.token() != token) fail(LibraryIssue.SOURCE_CHANGED)
                if (stored.phase == ScanPhase.COMPLETED.name) return@withPreservedTransaction stored.model()
                val old = checkedScan(token)
                val finalCoverage = coverage.copy(excludedSubtrees = coverage.excludedSubtrees || old.excludedSubtrees)
                val canReconcile = token.mode == ScanMode.FULL && finalCoverage.permitsAbsence
                if (canReconcile && (!old.absenceDone || !old.coverage().permitsAbsence || dao.incompleteFrontier(token.scanId) != 0L)) fail(LibraryIssue.INCOMPLETE)
                val source = dao.source(token.sourceId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
                val nextSource = source.copy(activeScanId = null,
                    absenceScanId = if (canReconcile) token.scanId else source.absenceScanId,
                    absenceScanSequence = if (canReconcile) old.sequence else source.absenceScanSequence,
                    watermark = if (finalCoverage.permitsAbsence) old.watermark ?: source.watermark else source.watermark)
                if (canReconcile) {
                    val current = meta()
                    val revision = increment(current.revision)
                    dao.closeSourceVersion(source.sourceId, revision)
                    dao.insertSourceVersion(sourceVersion(nextSource, revision))
                    dao.updateMeta(current.copy(revision = revision))
                }
                dao.putSource(nextSource)
                // Counts derived from acknowledged rows cannot be replaced by UI estimates.
                val next = old.withCoverage(finalCoverage).copy(phase = ScanPhase.COMPLETED.name)
                dao.updateScan(next)
                next.model()
            }
        }.also { cleanVersions() }
    }

    override suspend fun setScanState(token: ScanToken, phase: ScanPhase, issue: SourceReadIssue?): ScanState = access {
        if (phase == ScanPhase.COMPLETED || phase == ScanPhase.STAGING_ABSENCE) fail(LibraryIssue.INVALID_INPUT)
        database.withPreservedTransaction {
            val stored = dao.scan(token.scanId) ?: fail(LibraryIssue.SOURCE_CHANGED)
            if (stored.token() != token) fail(LibraryIssue.SOURCE_CHANGED)
            if (terminal(stored.phase)) return@withPreservedTransaction stored.model()
            val source = dao.source(token.sourceId)
            val isTerminal = terminal(phase.name)
            val old = if (isTerminal) stored else checkedScan(token, allowStopping = phase == ScanPhase.STOPPING)
            if (!isTerminal && stored.phase == ScanPhase.STOPPING.name && phase != ScanPhase.STOPPING) fail(LibraryIssue.SOURCE_CHANGED)
            val next = old.copy(phase = phase.name, issue = issue?.name)
            dao.updateScan(next)
            if (isTerminal && source != null && source.activeScanId == token.scanId) dao.putSource(source.copy(activeScanId = null))
            next.model()
        }
    }
    override suspend fun latestScan(sourceId: String): ScanState? = access { dao.source(sourceId)?.latestScanId?.let { dao.scan(it)?.model() } }
    override fun observeScan(scanId: String): Flow<ScanState?> = flow {
        database.backupMutationBarrier.readBackupProjection { Unit }
        emitAll(dao.observeScan(scanId).map {
            database.backupMutationBarrier.readBackupProjection { dao.scan(scanId)?.model() }
        })
    }.distinctUntilChanged()

    override suspend fun rules(): LibraryRules = access { rulesUnlocked() }
    override suspend fun exclusionPage(afterRuleId: String?, limit: Int): LibraryExclusionPage = access {
        pageLimit(limit)
        database.withPreservedTransaction {
            val rows = dao.rulesPage(afterRuleId, limit)
            val last = rows.lastOrNull()?.ruleId
            val more = last != null && dao.rulesPage(last, 1).isNotEmpty()
            val sourceNames = HashMap<String, String?>()
            LibraryExclusionPage(rows.map { row ->
                val node = dao.nodeById(row.nodeId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
                if (!sourceNames.containsKey(row.sourceId)) {
                    val source = dao.source(row.sourceId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
                    sourceNames[row.sourceId] = sourceModel(source).displayName
                }
                LibraryExclusion(row.ruleId, row.sourceId, row.nodeId, row.subtree,
                    node.lastKnownName, sourceNames[row.sourceId])
            }, last.takeIf { more })
        }
    }
    override suspend fun setShortAudioFilter(expectedRulesRevision: Long, enabled: Boolean): LibraryRules = access {
        database.withPreservedTransaction {
            val current = checkedRules(expectedRulesRevision)
            if (current.shortAudioFilter != enabled) dao.updateMeta(current.copy(revision = increment(current.revision), rulesRevision = increment(current.rulesRevision), shortAudioFilter = enabled))
            rulesUnlocked()
        }.also { cleanVersions() }
    }
    override suspend fun addExclusion(expectedRulesRevision: Long, sourceId: String, nodeId: String, subtree: Boolean): LibraryRules = access {
        database.withPreservedTransaction {
            val current = checkedRules(expectedRulesRevision)
            val node = dao.nodeById(nodeId) ?: fail(LibraryIssue.INVALID_INPUT)
            val version = dao.currentNode(nodeId) ?: fail(LibraryIssue.INVALID_INPUT)
            if (node.sourceId != sourceId || (subtree && !version.directory)) fail(LibraryIssue.INVALID_INPUT)
            val source = dao.source(sourceId) ?: fail(LibraryIssue.INVALID_INPUT)
            if (source.state != LibrarySourceState.ACTIVE.name) fail(LibraryIssue.SOURCE_UNAVAILABLE)
            if (!dao.hasRule(sourceId, nodeId, subtree)) {
                val revision = increment(current.revision)
                dao.insertRule(LibraryRuleEntity(id(), id(), sourceId, nodeId, subtree, revision, null))
                dao.updateMeta(current.copy(revision = revision, rulesRevision = increment(current.rulesRevision)))
            }
            rulesUnlocked()
        }.also { cleanVersions() }
    }
    override suspend fun removeExclusion(expectedRulesRevision: Long, ruleId: String): LibraryRules = access {
        database.withPreservedTransaction {
            val current = checkedRules(expectedRulesRevision)
            if (dao.rule(ruleId) != null) {
                val revision = increment(current.revision)
                dao.closeRule(ruleId, revision)
                dao.updateMeta(current.copy(revision = revision, rulesRevision = increment(current.rulesRevision)))
            }
            rulesUnlocked()
        }.also { cleanVersions() }
    }
    /** The event ledger and the library's sort projection commit in the same database transaction. */
    internal suspend fun updateListeningProjectionUnlocked(trackKey: TrackKey, statisticsEpoch: Long, playCount: Long, listeningMs: Long) {
        if (playCount < 0L || listeningMs < 0L) fail(LibraryIssue.INVALID_INPUT)
        val current = meta()
        if (current.statisticsEpoch != statisticsEpoch) fail(LibraryIssue.SOURCE_CHANGED)
        if (trackKey.providerId != TrackKey.LOCAL_PROVIDER) return
        val old = dao.currentTrack(trackKey.trackId) ?: return
        if (old.statisticsEpoch != statisticsEpoch || old.playCount != playCount || old.listeningMs != listeningMs) {
            val revision = increment(current.revision)
            writeTrackVersion(old.copy(statisticsEpoch = statisticsEpoch, playCount = playCount, listeningMs = listeningMs),
                old.metadata(), old.filename, old.metadataIssue, revision)
            dao.updateMeta(current.copy(revision = revision))
        }
    }

    /** Epoch switching resets all new views atomically without rewriting the whole library. */
    internal suspend fun clearListeningProjectionUnlocked(expectedStatisticsEpoch: Long, nextStatisticsEpoch: Long) {
        val current = meta()
        if (current.statisticsEpoch != expectedStatisticsEpoch || nextStatisticsEpoch != increment(expectedStatisticsEpoch)) fail(LibraryIssue.CONFLICT)
        dao.updateMeta(current.copy(revision = increment(current.revision), statisticsEpoch = nextStatisticsEpoch))
    }

    internal suspend fun <T> libraryTransaction(finalPublication: Boolean = false, cleanAfter: Boolean = false,
        action: suspend () -> T): T = access {
        val result = if (finalPublication) withContext(NonCancellable) { database.withPreservedTransaction { action() } }
        else database.withPreservedTransaction { action() }
        if (cleanAfter) cleanVersions()
        result
    }

    /** 主库提交已核验；清除旧查询和初始化缓存，后续读取使用恢复后的代次。 */
    internal suspend fun invalidateAfterBackupRestore() = access {
        database.backupMutationBarrier?.requireExclusiveOwner()
        liveViews.clear()
        initialized = false
    }

    /** The transfer owner queries only its exact lease inside this repository's real transaction. */
    internal suspend fun <T> transferTransaction(view: LibraryViewToken? = null,
        action: suspend (LibraryMetaEntity) -> T): T = access {
        database.withPreservedTransaction {
            if (view != null) {
                if (view.owner != LibraryViewOwner.TRANSFER) fail(LibraryIssue.INVALID_INPUT)
                checkView(view)
            }
            action(meta())
        }
    }

    /** Called only by transferTransaction; it must not reacquire the library mutex. */
    internal suspend fun openTransferViewUnlocked(query: LibraryQuery = LibraryQuery(),
        sort: LibrarySort = LibrarySort()): LibraryViewToken {
        if (!database.hasCurrentWriterTransaction()) fail(LibraryIssue.RECOVERY_REQUIRED)
        val current = meta()
        val draft = LibraryViewToken(id(), current.revision, current.rulesRevision, 0L, query, sort,
            current.shortAudioFilter, LibraryViewOwner.TRANSFER, statisticsEpoch = current.statisticsEpoch)
        return draft.copy(totalCount = dao.scalar(LibrarySql.count(draft))).also { storeView(it) }
    }

    /** Metadata retirement is bounded and idempotent; no unrelated user's view can be released. */
    internal suspend fun closeTransferView(view: LibraryViewToken) = withContext(NonCancellable) {
        access {
            if (view.owner != LibraryViewOwner.TRANSFER) fail(LibraryIssue.INVALID_INPUT)
            val held = liveViews[view.viewId]
            if (held != null && held != view) fail(LibraryIssue.INVALID_INPUT)
            database.withPreservedTransaction {
                val row = dao.view(view.viewId)
                if (row != null && (row.sessionId != sessionId || row.owner != LibraryViewOwner.TRANSFER.name ||
                        row.fingerprint != fingerprint(view))) fail(LibraryIssue.INVALID_INPUT)
                dao.deleteView(view.viewId)
            }
            liveViews.remove(view.viewId)
        }
    }

    /** Called only in the ledger transaction; it must never acquire the repository mutex again. */
    internal suspend fun commitOwnedCopyUnlocked(spec: LocalSourceSpec, grant: LocalGrantObservation,
        candidate: ObservedCandidate, metadata: LocalAudioMetadata): TrackKey {
        validateSource(spec, grant)
        validateCandidate(spec.sourceId, candidate)
        if (spec.kind != LocalSourceKind.APP_OWNED || dao.source(spec.sourceId) != null) fail(LibraryIssue.CONFLICT)
        val current = meta()
        val revision = increment(current.revision)
        val source = LibrarySourceEntity(spec.sourceId, LibraryCodec.source(spec), id(), 1L, 1L,
            LibrarySourceState.ACTIVE.name, null, null, null, null, null)
        dao.putSource(source)
        dao.insertGrant(grantRecord(source.grantId, spec, grant))
        dao.insertSourceVersion(sourceVersion(source, revision))
        val node = writeNode(candidate, revision)
        val (alias, added) = writeAudio(spec, candidate, node.nodeId, current.nextScanSequence, revision, current.nextAddedSequence)
        if (!added) fail(LibraryIssue.CONFLICT)
        val relation = dao.locationRelations(alias.locationId, null).singleOrNull() ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val track = dao.currentTrack(relation.trackId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        writeTrackVersion(track, metadata, candidate.displayName, null, revision)
        // The import ledger already checked the complete installed bytes before this publication.
        writeVerifiedContent(alias.locationId, candidate.providerContentToken ?: fail(LibraryIssue.RECOVERY_REQUIRED), revision)
        dao.updateMeta(current.copy(revision = revision, nextAddedSequence = increment(current.nextAddedSequence),
            nextScanSequence = increment(current.nextScanSequence)))
        return TrackKey(track.providerId, track.trackId)
    }

    private suspend fun ownedObservation(spec: LocalSourceSpec, observed: ObservedCandidate): ObservedCandidate {
        val ordinary = dao.ownedImport(spec.sourceId)
        val actualBytes: Long
        val original: ObservedCandidate
        if (ordinary != null) {
            val record = ordinary.model()
            actualBytes = record.actualBytes
            original = ordinary.ownedCandidate?.let(LibraryCodec::candidate) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            if (original.objectId != record.intent.fileToken || original.displayName != record.intent.candidate.displayName) fail(LibraryIssue.RECOVERY_REQUIRED)
        } else {
            val transferred = database.transferredImportDao().ownedTransferredImport(spec.sourceId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            val record = transferred.transferModel()
            actualBytes = record.actualBytes
            original = transferred.ownedCandidate?.let(LibraryCodec::candidate) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
            if (transferred.ownedSpec?.let(LibraryCodec::source) != spec || original.objectId != record.selection.fileToken ||
                original.displayName != record.selection.declaration.displayName) fail(LibraryIssue.RECOVERY_REQUIRED)
        }
        if (original.sizeBytes != actualBytes) fail(LibraryIssue.RECOVERY_REQUIRED)
        if (observed.objectId != original.objectId || observed.uri != spec.uri ||
            (observed.sizeBytes != null && observed.sizeBytes != actualBytes) ||
            (observed.providerContentToken != null && observed.providerContentToken != original.providerContentToken) ||
            (observed.entityProof != null && observed.entityProof != original.entityProof)) fail(LibraryIssue.SOURCE_CHANGED)
        return observed.copy(displayName = original.displayName, kind = LocalNodeKind.AUDIO,
            mimeType = observed.mimeType ?: original.mimeType,
            providerContentToken = original.providerContentToken, entityProof = original.entityProof)
    }

    private suspend fun meta(): LibraryMetaEntity {
        dao.insertMeta(LibraryMetaEntity())
        return dao.meta() ?: fail(LibraryIssue.RECOVERY_REQUIRED)
    }
    private suspend fun overviewUnlocked(): LibraryOverview {
        val current = meta()
        val visible = internalView(current)
        val all = internalView(current, LibraryQuery(includeHidden = true))
        return LibraryOverview(current.revision, current.rulesRevision, dao.scalar(LibrarySql.count(all)), dao.scalar(LibrarySql.count(visible)),
            dao.scalar(LibrarySql.availableCount(visible)), dao.scalar(LibrarySql.raw("SELECT COUNT(*) FROM local_sources WHERE state != 'REMOVED'")), current.shortAudioFilter)
    }
    private fun internalView(meta: LibraryMetaEntity, query: LibraryQuery = LibraryQuery()) = LibraryViewToken("internal", meta.revision, meta.rulesRevision,
        0L, query, LibrarySort(), meta.shortAudioFilter, LibraryViewOwner.UI, statisticsEpoch = meta.statisticsEpoch)
    private suspend fun sourceModel(value: LibrarySourceEntity): LibrarySource {
        val source = value.model(dao.grant(value.grantId) ?: fail(LibraryIssue.RECOVERY_REQUIRED))
        val root = source.spec.rootObjectId?.let { dao.node(value.sourceId, it) }
        return source.copy(displayName = root?.lastKnownName)
    }
    private fun sourceVersion(value: LibrarySourceEntity, revision: Long) = LibrarySourceVersionEntity(
        value.sourceId, revision, null, value.state, value.spec, value.grantId, value.absenceScanId, value.absenceScanSequence,
        rootObjectId = LibraryCodec.source(value.spec).rootObjectId,
    )
    private fun grantRecord(id: String, spec: LocalSourceSpec, value: LocalGrantObservation) = LibraryGrantEntity(id, spec.sourceId, spec.uri, LibraryCodec.grant(value), value.read.name, value.acceptedPersistedFlags, value.appOwned)
    private fun sourceState(grant: LocalGrantObservation) = if (grant.read == ObservedCapability.AVAILABLE) LibrarySourceState.ACTIVE else if (grant.read == ObservedCapability.UNAVAILABLE) LibrarySourceState.NEEDS_AUTHORIZATION else LibrarySourceState.UNAVAILABLE
    private fun validateSource(spec: LocalSourceSpec, grant: LocalGrantObservation) {
        val uri = spec.uri.toUri()
        if (uri.scheme != "content" || uri.authority.isNullOrBlank() || (spec.authority != null && uri.authority != spec.authority) ||
            (spec.kind == LocalSourceKind.APP_OWNED) != grant.appOwned) fail(LibraryIssue.INVALID_INPUT)
    }
    private fun sameSourceIdentity(a: LocalSourceSpec, b: LocalSourceSpec): Boolean = a.kind == b.kind && a.authority == b.authority && a.mediaVolume == b.mediaVolume && a.providerIncarnation == b.providerIncarnation &&
        (if (a.rootObjectId != null || b.rootObjectId != null) a.rootObjectId == b.rootObjectId else a.uri == b.uri)
    private suspend fun checkedSource(sourceId: String, epoch: Long): LibrarySourceEntity {
        val source = dao.source(sourceId) ?: fail(LibraryIssue.SOURCE_UNAVAILABLE)
        if (source.authEpoch != epoch) fail(LibraryIssue.CONFLICT)
        return source
    }
    private suspend fun checkedRules(revision: Long): LibraryMetaEntity = meta().also { if (it.rulesRevision != revision) fail(LibraryIssue.CONFLICT) }
    private suspend fun checkedScan(token: ScanToken, allowStaging: Boolean = true, allowStopping: Boolean = false): LibraryScanEntity {
        val scan = dao.scan(token.scanId) ?: fail(LibraryIssue.SOURCE_CHANGED)
        val source = dao.source(token.sourceId) ?: fail(LibraryIssue.SOURCE_CHANGED)
        if (scan.token() != token || source.activeScanId != token.scanId || source.revision != token.sourceRevision || source.authEpoch != token.authEpoch || meta().rulesRevision != token.rulesRevision) fail(LibraryIssue.SOURCE_CHANGED)
        if (source.state != LibrarySourceState.ACTIVE.name || terminal(scan.phase) || (!allowStopping && scan.phase == ScanPhase.STOPPING.name) || (!allowStaging && scan.phase == ScanPhase.STAGING_ABSENCE.name)) fail(LibraryIssue.SOURCE_CHANGED)
        return scan
    }
    private suspend fun invalidateScan(source: LibrarySourceEntity, phase: ScanPhase, issue: SourceReadIssue?) {
        source.activeScanId?.let { id -> dao.scan(id)?.takeUnless { terminal(it.phase) }?.let { dao.updateScan(it.copy(phase = phase.name, issue = issue?.name)) } }
    }
    private suspend fun changeSource(sourceId: String, expectedAuthEpoch: Long, state: LibrarySourceState, issue: SourceReadIssue?): LibrarySource = access {
        database.withPreservedTransaction {
            val old = checkedSource(sourceId, expectedAuthEpoch)
            if (old.state == state.name) return@withPreservedTransaction sourceModel(old)
            invalidateScan(old, ScanPhase.CANCELLED, issue)
            val current = meta()
            val revision = increment(current.revision)
            val next = old.copy(state = state.name, revision = increment(old.revision), authEpoch = increment(old.authEpoch), activeScanId = null)
            dao.putSource(next); dao.closeSourceVersion(sourceId, revision); dao.insertSourceVersion(sourceVersion(next, revision))
            dao.updateMeta(current.copy(revision = revision))
            sourceModel(next)
        }.also { cleanVersions() }
    }
    private suspend fun rulesUnlocked(): LibraryRules {
        val current = meta()
        return LibraryRules(current.rulesRevision, current.shortAudioFilter)
    }
    private suspend fun excludedUnlocked(token: ScanToken, objectId: String, parentObjectId: String?, revision: Long): Boolean {
        val node = dao.node(token.sourceId, objectId)
        val parent = parentObjectId?.let { dao.node(token.sourceId, it) }
        return dao.scalar(LibrarySql.isExcluded(token.sourceId, node?.nodeId, parent?.nodeId, revision)) != 0L
    }
    private suspend fun expireViews() {
        dao.expireViews(sessionId, elapsedMs())
        val ids = dao.views().map { it.viewId }.toSet()
        liveViews.keys.retainAll(ids)
    }
    private fun fingerprint(view: LibraryViewToken): String = LibrarySortKeys.fingerprint(LibrarySortKeys.identity(view.revision.toString(), view.rulesRevision.toString(), view.totalCount.toString(),
        view.query.search, view.query.sourceId.orEmpty(), view.query.folderNodeId.orEmpty(), view.query.albumKey.orEmpty(), view.query.artistKey.orEmpty(), view.query.addedSinceEpochMs?.toString().orEmpty(),
        view.query.includeHidden.toString(), view.query.folderSubtree.toString(), view.query.relativePathKeyword.orEmpty(),
        view.query.readableOnly.toString(), view.sort.field.name, view.sort.descending.toString(), view.shortAudioFilter.toString(), view.owner.name,
        view.sortRuleVersion.toString(), view.statisticsEpoch.toString()))
    private suspend fun storeView(token: LibraryViewToken) {
        expireViews()
        val cap = when (token.owner) {
            LibraryViewOwner.UI -> LibraryLimits.UI_VIEWS
            LibraryViewOwner.QUEUE -> LibraryLimits.QUEUE_VIEWS
            LibraryViewOwner.TRANSFER -> LibraryLimits.TRANSFER_VIEWS
        }
        if (dao.views().count { it.owner == token.owner.name } >= cap) fail(LibraryIssue.BUSY)
        dao.insertView(LibraryViewEntity(token.viewId, sessionId, token.revision, token.totalCount, fingerprint(token), token.owner.name, expiry(), token.sortRuleVersion))
        liveViews[token.viewId] = token
    }
    private suspend fun checkView(token: LibraryViewToken) {
        val record = dao.view(token.viewId) ?: fail(LibraryIssue.VIEW_EXPIRED)
        if (record.sessionId != sessionId || record.expiresElapsedMs <= elapsedMs() || liveViews[token.viewId] != token) fail(LibraryIssue.VIEW_EXPIRED)
        if (record.fingerprint != fingerprint(token) || token.sortRuleVersion != LibraryLimits.SORT_RULE_VERSION) fail(LibraryIssue.INVALID_INPUT)
        dao.renewView(token.viewId, expiry())
    }
    private suspend fun checkPageInput(view: LibraryViewToken, cursor: LibraryCursor?, limit: Int) {
        checkView(view); pageLimit(limit)
        if (cursor != null) {
            if (cursor.viewId != view.viewId || cursor.revision != view.revision || cursor.sortRuleVersion != view.sortRuleVersion) fail(LibraryIssue.INVALID_INPUT)
            val row = dao.rows(LibrarySql.row(view, cursor.providerId, cursor.trackId)).firstOrNull() ?: fail(LibraryIssue.INVALID_INPUT)
            if (row.cursor(view) != cursor) fail(LibraryIssue.INVALID_INPUT)
        }
    }
    private suspend fun pageResult(view: LibraryViewToken, rows: List<LibraryRowRecord>,
        moreBefore: Boolean?, moreAfter: Boolean?): LibraryPage {
        val first = rows.firstOrNull()?.cursor(view)
        val last = rows.lastOrNull()?.cursor(view)
        // 未知方向仍用原存在性查询；真实已核验的游标只省去相反方向的重复探测。
        return LibraryPage(view, rows.map { it.model() },
            last?.takeIf { moreAfter ?: (dao.scalar(LibrarySql.hasBeyond(view, it, before = false)) != 0L) },
            first?.takeIf { moreBefore ?: (dao.scalar(LibrarySql.hasBeyond(view, it, before = true)) != 0L) })
    }
    private suspend fun cursorBefore(view: LibraryViewToken, cursor: LibraryCursor): LibraryCursor? = dao.rows(LibrarySql.rows(view, cursor, 1, before = true)).firstOrNull()?.cursor(view)
    private suspend fun folderUnlocked(view: LibraryViewToken, nodeId: String): LibraryFolder? = dao.nodeRows(LibrarySql.folders(view, null, null, 1, nodeId)).firstOrNull()?.let { LibraryFolder(it.nodeId, it.sourceId, it.name, it.parentNodeId) }
    private fun expiry(): Long = add(elapsedMs(), LibraryLimits.VIEW_LEASE_MS)
    private suspend fun cleanVersions() {
        expireViews()
        for (clean in listOf<suspend () -> Int>(dao::cleanTrackVersions, dao::cleanAliasVersions, dao::cleanLocationVersions, dao::cleanNodeVersions,
            dao::cleanRelations, dao::cleanRules, dao::cleanSourceVersions, dao::cleanGrants,
            database.displayDao()::cleanNames, database.displayDao()::cleanAlbums, database.displayDao()::cleanArtists)) {
            currentCoroutineContext().ensureActive()
            database.withPreservedTransaction { clean() }
        }
        val obsolete = dao.obsoleteScanId() ?: return
        for (clean in listOf<suspend () -> Int>({ dao.cleanSeen(obsolete) }, { dao.cleanFrontier(obsolete) }, { dao.cleanAbsence(obsolete) })) {
            currentCoroutineContext().ensureActive()
            database.withPreservedTransaction { clean() }
        }
        database.withPreservedTransaction { dao.deleteEmptyScan(obsolete) }
    }

    private suspend fun writeNode(candidate: ObservedCandidate, revision: Long): LibraryNodeEntity {
        val existingNode = dao.node(candidate.sourceId, candidate.objectId)
        var node = existingNode ?: LibraryNodeEntity(id(), candidate.sourceId, candidate.objectId,
            lastKnownName = candidate.displayName.takeIf { it.isNotBlank() }).also { dao.insertNode(it) }
        val parent = candidate.parentObjectId?.let { objectId ->
            if (objectId == candidate.objectId) fail(LibraryIssue.SOURCE_CHANGED)
            dao.node(candidate.sourceId, objectId) ?: LibraryNodeEntity(id(), candidate.sourceId, objectId).also { dao.insertNode(it) }
        }
        // An existing placeholder may already have children; only a newly inserted identity cannot form a cycle.
        if (parent != null && existingNode != null) {
            val cycle = dao.scalar(LibrarySql.raw("""
                WITH RECURSIVE parents(nodeId) AS (SELECT ? UNION SELECT n.parentNodeId
                    FROM local_node_versions n JOIN parents p ON n.nodeId = p.nodeId
                    WHERE n.validTo IS NULL AND n.parentNodeId IS NOT NULL)
                SELECT EXISTS (SELECT 1 FROM parents WHERE nodeId = ?)
            """.trimIndent(), arrayOf(parent.nodeId, node.nodeId)))
            if (cycle != 0L) fail(LibraryIssue.SOURCE_CHANGED)
        }
        val old = existingNode?.let { dao.currentNode(it.nodeId) }
        val version = LibraryNodeVersionEntity(if (old?.validFrom == revision) old.versionId else id(), node.nodeId, candidate.sourceId,
            parent?.nodeId, candidate.displayName, candidate.uri, candidate.directory, revision, null,
            pathName = java.text.Normalizer.normalize(candidate.displayName, java.text.Normalizer.Form.NFC))
        if (old?.validFrom == revision) dao.updateNodeVersion(version)
        else {
            if (existingNode != null) dao.closeNodeVersion(node.nodeId, revision)
            dao.insertNodeVersion(version)
        }
        // Rules must remain identifiable after an unknown-name rescan or old-version cleanup.
        val observedName = candidate.displayName.takeIf { it.isNotBlank() }
        if (observedName != null && observedName != node.lastKnownName) {
            node = node.copy(lastKnownName = observedName)
            dao.updateNode(node)
        }
        return node
    }

    /** A namespaced provider proof may unify aliases; weak timestamps never become an identity key. */
    private suspend fun writeAudio(spec: LocalSourceSpec, candidate: ObservedCandidate, nodeId: String, sequence: Long,
        revision: Long, addedSequence: Long): Pair<LibraryAliasEntity, Boolean> {
        val proof = candidate.entityProof
        val identity = if (proof != null && spec.authority != null && spec.providerIncarnation != null) {
            LibrarySortKeys.identity("PROVIDER", spec.authority, spec.providerIncarnation, proof)
        } else LibrarySortKeys.identity("SOURCE", spec.sourceId, candidate.objectId)
        val existingAlias = dao.alias(spec.sourceId, candidate.objectId)
        var location = existingAlias?.let { dao.location(it.locationId) ?: fail(LibraryIssue.RECOVERY_REQUIRED) } ?: dao.locationByIdentity(identity)
        if (location != null && location.identityKey != identity) fail(LibraryIssue.SOURCE_CHANGED)
        val freshLocation = location == null
        if (location == null) location = LibraryLocationEntity(id(), identity, 1L, candidate.providerContentToken, null, null, candidate.sizeBytes,
            candidate.modifiedEpochMs, spec.kind == LocalSourceKind.APP_OWNED)
        else {
            val currentVersion = dao.currentLocationVersion(location.locationId)
            val trustworthyUnchanged = candidate.providerContentToken != null && candidate.providerContentToken == location.contentToken &&
                (candidate.sizeBytes == null || location.sizeBytes == null || candidate.sizeBytes == location.sizeBytes)
            val sizeChanged = candidate.sizeBytes != null && location.sizeBytes != null && candidate.sizeBytes != location.sizeBytes
            val nativeEvidenceChanged = !trustworthyUnchanged && (candidate.providerContentToken != null || location.contentToken != null)
            // Weak observations clear the read proof, but a same SHA may still prove the old queue safe.
            val contentRevision = if ((sizeChanged || nativeEvidenceChanged) && currentVersion?.validFrom != revision) {
                increment(location.contentRevision)
            } else location.contentRevision
            location = location.copy(contentRevision = contentRevision, contentToken = candidate.providerContentToken,
                verifiedContentToken = location.verifiedContentToken.takeIf { trustworthyUnchanged },
                previousVerifiedContentToken = if (trustworthyUnchanged) location.previousVerifiedContentToken
                    else location.verifiedContentToken ?: location.previousVerifiedContentToken,
                sizeBytes = candidate.sizeBytes, modifiedEpochMs = candidate.modifiedEpochMs)
        }
        dao.putLocation(location)
        if (freshLocation) {
            dao.insertLocationVersion(LibraryLocationVersionEntity(id(), location.locationId, location.contentRevision,
                location.contentToken, location.verifiedContentToken, location.sizeBytes, location.modifiedEpochMs, revision, null))
        } else writeLocationVersion(location, revision)
        var added = false
        if (freshLocation || !dao.hasLocationRelation(location.locationId)) {
            val key = TrackKey.newLocal()
            val track = LibraryTrackEntity(key.trackId, key.providerId, epochMs(), addedSequence)
            dao.insertTrack(track)
            val empty = emptyTrackVersion(track, candidate.displayName, revision)
            dao.insertTrackVersion(empty)
            dao.insertArtists(listOf(LibraryArtistEntity(empty.versionId, 0, LibrarySortKeys.text(null), null)))
            added = true
            dao.insertRelation(LibraryTrackLocationEntity(id(), key.trackId, location.locationId, revision, null))
        }
        val alias = existingAlias ?: LibraryAliasEntity(id(), spec.sourceId, candidate.objectId, location.locationId).also { dao.insertAlias(it) }
        val aliasVersion = LibraryAliasVersionEntity(id(), alias.aliasId, spec.sourceId, location.locationId, nodeId, candidate.uri, candidate.displayName,
            null, sequence, location.contentRevision, candidate.providerContentToken, candidate.sizeBytes, revision, null)
        if (existingAlias == null) dao.insertAliasVersion(aliasVersion) else writeAliasVersion(aliasVersion, revision)
        return alias to added
    }

    private suspend fun writeLocationVersion(value: LibraryLocationEntity, revision: Long) {
        val old = dao.currentLocationVersion(value.locationId)
        if (old != null && old.contentRevision == value.contentRevision && old.contentToken == value.contentToken &&
            old.verifiedContentToken == value.verifiedContentToken && old.sizeBytes == value.sizeBytes && old.modifiedEpochMs == value.modifiedEpochMs) return
        val next = LibraryLocationVersionEntity(if (old?.validFrom == revision) old.versionId else id(), value.locationId, value.contentRevision,
            value.contentToken, value.verifiedContentToken, value.sizeBytes, value.modifiedEpochMs, revision, null)
        if (old?.validFrom == revision) dao.updateLocationVersion(next)
        else { dao.closeLocationVersion(value.locationId, revision); dao.insertLocationVersion(next) }
    }

    private suspend fun writeVerifiedContent(locationId: String, token: String, revision: Long) {
        if (!token.matches(Regex("sha256:[0-9a-f]{64}"))) fail(LibraryIssue.INVALID_INPUT)
        val old = dao.location(locationId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val previous = old.verifiedContentToken ?: old.previousVerifiedContentToken
        val changed = previous != null && previous != token
        val next = old.copy(contentRevision = if (changed) increment(old.contentRevision) else old.contentRevision,
            verifiedContentToken = token, previousVerifiedContentToken = null)
        dao.putLocation(next)
        writeLocationVersion(next, revision)
    }

    private suspend fun writeAliasVersion(value: LibraryAliasVersionEntity, revision: Long) {
        val old = dao.currentAlias(value.aliasId)
        val next = value.copy(versionId = if (old?.validFrom == revision) old.versionId else id(), validFrom = revision, validTo = null)
        if (old?.validFrom == revision) dao.updateAliasVersion(next)
        else { dao.closeAliasVersion(value.aliasId, revision); dao.insertAliasVersion(next) }
    }

    private fun emptyTrackVersion(track: LibraryTrackEntity, filename: String, revision: Long): LibraryTrackVersionEntity = LibraryTrackVersionEntity(
        id(), track.trackId, track.providerId, revision, null, true, track.firstAddedEpochMs, track.firstAddedSequence, filename, filename,
        null, null, "[]", null, null, null, null, null, false, null, 1, null,
        LibrarySortKeys.text(filename), LibrarySortKeys.text(null), LibrarySortKeys.text(null), LibrarySortKeys.natural(filename), "UNKNOWN",
        LibrarySortKeys.normalize(filename), LibrarySortKeys.section(filename), "UNKNOWN", "UNKNOWN", LibrarySortKeys.section(filename), 0L, 0L,
    )

    private suspend fun writeTrackVersion(old: LibraryTrackVersionEntity, metadata: LocalAudioMetadata, filename: String, issue: String?, revision: Long) {
        val current = dao.currentTrack(old.trackId) ?: fail(LibraryIssue.RECOVERY_REQUIRED)
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: filename
        val artists = metadata.artists.ifEmpty { metadata.artist?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty() }
        val primaryArtist = artists.firstOrNull()
        val version = old.copy(versionId = if (current.validFrom == revision) current.versionId else id(), validFrom = revision, validTo = null,
            filename = filename, displayTitle = title, title = metadata.title, artist = metadata.artist, artists = LibraryCodec.strings(metadata.artists),
            album = metadata.album, albumArtist = metadata.albumArtist, durationMs = metadata.durationMs, discNumber = metadata.discNumber,
            trackNumber = metadata.trackNumber, compilation = metadata.compilation, mimeType = metadata.mimeType, parserVersion = metadata.parserVersion,
            codecParameters = metadata.codecParameters?.let(LocalCodecParametersCodec::encode),
            metadataIssue = issue, titleKey = LibrarySortKeys.text(title), artistKey = LibrarySortKeys.text(primaryArtist), albumSortKey = LibrarySortKeys.text(metadata.album),
            filenameKey = LibrarySortKeys.natural(filename), albumKey = LibrarySortKeys.album(metadata.copy(artists = artists)),
            searchText = LibrarySortKeys.normalize(listOfNotNull(title, metadata.artist, artists.joinToString(" "), metadata.album, filename).joinToString("\n")),
            titleSection = LibrarySortKeys.section(title), artistSection = LibrarySortKeys.section(primaryArtist), albumSection = LibrarySortKeys.section(metadata.album),
            filenameSection = LibrarySortKeys.section(filename))
        if (current.validFrom == revision) { dao.deleteArtists(current.versionId); dao.updateTrackVersion(version) }
        else { dao.closeTrackVersion(old.trackId, revision); dao.insertTrackVersion(version) }
        val unique = artists.filter { it.isNotBlank() }.distinctBy(LibrarySortKeys::normalize)
        val projections = if (unique.isEmpty()) listOf(LibraryArtistEntity(version.versionId, 0, LibrarySortKeys.text(null), null))
        else unique.mapIndexed { index, name -> LibraryArtistEntity(version.versionId, index, LibrarySortKeys.text(name), name) }
        dao.insertArtists(projections)
    }
    private suspend fun <T> access(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        // Throw domain failures after the IO boundary has returned the retired writer result.
        runCatching {
            mutex.withLock {
                try { action() }
                catch (error: CancellationException) { throw error }
                catch (error: LibraryException) { throw error }
                catch (error: SQLiteException) {
                    val failure = error.toPlatformSQLiteException()
                    val issue = when (failure) {
                        is SQLiteFullException -> LibraryIssue.STORAGE_FULL
                        is SQLiteConstraintException -> LibraryIssue.INVALID_INPUT
                        else -> LibraryIssue.STORAGE_UNAVAILABLE
                    }
                    throw LibraryException(issue, failure)
                }
                catch (error: IllegalStateException) { throw LibraryException(LibraryIssue.RECOVERY_REQUIRED, error) }
                catch (error: JSONException) { throw LibraryException(LibraryIssue.RECOVERY_REQUIRED, error) }
                catch (error: IllegalArgumentException) { throw LibraryException(LibraryIssue.RECOVERY_REQUIRED, error) }
            }
        }
    }.getOrThrow()
    private fun pageLimit(size: Int) { if (size !in 1..LibraryLimits.PAGE_SIZE) fail(LibraryIssue.INVALID_INPUT) }
    private fun batch(size: Int) { if (size !in 1..LibraryLimits.PAGE_SIZE) fail(LibraryIssue.INVALID_INPUT) }
    private fun validateCandidate(sourceId: String, candidate: ObservedCandidate) {
        val uri = candidate.uri.toUri()
        if (candidate.sourceId != sourceId || candidate.displayName.length > 512 || uri.scheme != "content" || uri.authority.isNullOrBlank()) fail(LibraryIssue.INVALID_INPUT)
    }
    private fun terminal(phase: String) = phase in setOf(ScanPhase.COMPLETED.name, ScanPhase.CANCELLED.name, ScanPhase.FAILED.name, ScanPhase.INTERRUPTED.name, ScanPhase.PAUSED_BACKGROUND.name)
    private fun increment(value: Long) = add(value, 1L)
    private fun add(a: Long, b: Long): Long = try { Math.addExact(a, b) } catch (_: ArithmeticException) { fail(LibraryIssue.STORAGE_FULL) }
    private fun fail(issue: LibraryIssue): Nothing = throw LibraryException(issue)
    private fun id(): String = UUID.randomUUID().toString()
}
