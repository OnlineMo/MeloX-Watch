package io.github.onlinemo.meloxwatch.data.fileoperations

import android.net.Uri
import android.content.Intent
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import io.github.onlinemo.meloxwatch.data.library.LibraryAliasEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryAliasVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryArtistEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryCodec
import io.github.onlinemo.meloxwatch.data.library.LibraryLocationEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryLocationVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryNodeEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryNodeVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibrarySortKeys
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackLocationEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.library.metadata
import io.github.onlinemo.meloxwatch.data.library.model
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.data.storage.StorageReferencePage
import java.text.Normalizer
import java.util.UUID

/**
 * 通过真实 schema-6 音乐库所有者修改记录；物理 I/O 与访问校验在事务外。
 * 协调页可幂等重放；全部相关行和持久队列一致前，私有日志保留文件实际结果。
 */
class RoomFileOperationLibrary(
    private val library: RoomLocalLibraryRepository,
    private val cancelScan: suspend (String) -> Unit,
) : FileOperationLibrary {
    private data class StorageReference(val locationId: String, val revision: Long, val trackId: String)
    private val dao get() = library.dao
    private val database get() = library.database

    override suspend fun snapshot(selected: FileSelectedTrack, kind: FileOperationKind): FileOperationTarget {
        if (selected.trackKey.providerId != TrackKey.LOCAL_PROVIDER) fail(FileOperationIssue.INVALID_INPUT)
        val indexOnly = kind in setOf(FileOperationKind.REMOVE_FROM_INDEX, FileOperationKind.REMOVE_SOURCE)
        val descriptor = if (indexOnly) null else if (selected.locationId != null) retainedRead(selected.trackKey, selected.locationId, selected.contentRevision)
            ?: fail(FileOperationIssue.SOURCE_CHANGED) else library.resolveLocalRead(selected.trackKey, null, selected.contentRevision)
        val target = library.libraryTransaction {
            val track = dao.currentTrack(selected.trackKey.trackId) ?: fail(FileOperationIssue.SOURCE_CHANGED)
            if (track.providerId != selected.trackKey.providerId || !track.member && kind != FileOperationKind.REMOVE_SOURCE) fail(FileOperationIssue.SOURCE_CHANGED)
            if (descriptor == null) return@libraryTransaction FileOperationTarget(
                "track:${selected.trackKey.trackId}", selected.trackKey, track.versionId, null, null, null, null, displayName = track.displayTitle)
            val alias = dao.aliasById(descriptor.aliasId) ?: fail(FileOperationIssue.SOURCE_CHANGED)
            val current = dao.currentAlias(alias.aliasId) ?: fail(FileOperationIssue.SOURCE_CHANGED)
            val node = dao.nodeById(current.nodeId) ?: fail(FileOperationIssue.INCOMPLETE_SCOPE)
            val nodeVersion = dao.currentNode(node.nodeId) ?: fail(FileOperationIssue.INCOMPLETE_SCOPE)
            if (alias.locationId != descriptor.location.locationId || alias.objectId != descriptor.objectId ||
                node.objectId != descriptor.objectId || current.uri != descriptor.location.uri ||
                current.contentRevision != descriptor.location.contentRevision) fail(FileOperationIssue.SOURCE_CHANGED)
            val parentObject = nodeVersion.parentNodeId?.let { dao.nodeById(it)?.objectId }
            val ownership = if (descriptor.source.spec.kind == LocalSourceKind.APP_OWNED && descriptor.source.grant.appOwned)
                FileResourceOwnership.APP_COPY else FileResourceOwnership.EXTERNAL_ORIGINAL
            if (kind == FileOperationKind.DELETE_ORIGINAL && ownership != FileResourceOwnership.EXTERNAL_ORIGINAL ||
                kind == FileOperationKind.DELETE_APP_COPY && ownership != FileResourceOwnership.APP_COPY) fail(FileOperationIssue.INVALID_INPUT)
            FileOperationTarget("location:${alias.locationId}", selected.trackKey, track.versionId, descriptor, parentObject, ownership, null,
                displayName = track.displayTitle)
        }
        if (descriptor != null) checkIndependentAliases(target)
        return target
    }

    override suspend fun impacts(target: FileOperationTarget, after: String?): FileImpactPage = library.libraryTransaction {
        val descriptor = target.descriptor
        if (descriptor == null) {
            val key = "T:${target.trackKey.trackId}"
            if (target.removedSourceId != null) {
                val rows = prepared("""SELECT impactKey,kind,aliasId,nodeId,objectId,versionId,contentRevision FROM (
                    SELECT 'A:'||a.aliasId AS impactKey,'ALIAS' AS kind,a.aliasId,v.nodeId,a.objectId,v.versionId,v.contentRevision
                    FROM local_access_aliases a JOIN local_alias_versions v ON v.aliasId=a.aliasId AND v.validTo IS NULL
                    JOIN local_track_locations r ON r.locationId=a.locationId AND r.validTo IS NULL
                    WHERE a.sourceId=? AND r.trackId=? GROUP BY a.aliasId
                    UNION ALL SELECT ?,'TRACK',NULL,NULL,NULL,NULL,NULL
                    UNION ALL SELECT 'S:'||?,'SOURCE',NULL,NULL,NULL,NULL,NULL
                    ) WHERE (? IS NULL OR impactKey>?) ORDER BY impactKey LIMIT ?""", target.removedSourceId, target.trackKey.trackId,
                    key, target.removedSourceId, after, after, FileOperationLimits.PAGE_SIZE + 1) { statement ->
                    buildList {
                        while (statement.step()) {
                            val kind = FileImpactKind.valueOf(statement.getText(1))
                            add(FileOperationImpact(statement.getText(0), kind, target.trackKey.takeIf { kind == FileImpactKind.TRACK },
                                target.removedSourceId.takeIf { kind != FileImpactKind.TRACK }, statement.nullableText(2), statement.nullableText(3),
                                statement.nullableText(4), statement.nullableText(5), if (statement.isNull(6)) null else statement.getLong(6)))
                        }
                    }
                }
                val bounded = rows.take(FileOperationLimits.PAGE_SIZE).map { row -> if (row.kind == FileImpactKind.SOURCE)
                    row.copy(source = sourceUnlocked(checkNotNull(row.sourceId))) else row }
                return@libraryTransaction FileImpactPage(bounded, if (rows.size > FileOperationLimits.PAGE_SIZE) bounded.last().impactKey else null)
            }
            return@libraryTransaction FileImpactPage(if (after == null || key > after) listOf(
                FileOperationImpact(key, FileImpactKind.TRACK, target.trackKey, null, null, null, null)) else emptyList(), null)
        }
        val sql = """
            SELECT impactKey,kind,trackId,sourceId,aliasId,nodeId,objectId,versionId,contentRevision FROM (
                SELECT 'A:'||a.aliasId AS impactKey,'ALIAS' AS kind,NULL AS trackId,a.sourceId,a.aliasId,
                    v.nodeId,a.objectId,v.versionId,v.contentRevision
                FROM local_access_aliases a JOIN local_alias_versions v ON v.aliasId=a.aliasId AND v.validTo IS NULL
                WHERE a.locationId=?
                UNION ALL
                SELECT 'S:'||a.sourceId,'SOURCE',NULL,a.sourceId,NULL,NULL,NULL,NULL,NULL
                FROM local_access_aliases a WHERE a.locationId=? GROUP BY a.sourceId
                UNION ALL
                SELECT CASE WHEN t.trackId LIKE 'cue-v1-%' THEN 'C:' ELSE 'T:' END||t.trackId,
                    CASE WHEN t.trackId LIKE 'cue-v1-%' THEN 'CUE_SEGMENT' ELSE 'TRACK' END,
                    t.trackId,NULL,NULL,NULL,NULL,NULL,NULL
                FROM local_track_locations r JOIN local_tracks t ON t.trackId=r.trackId
                WHERE r.locationId=? AND r.validTo IS NULL GROUP BY t.trackId
            ) WHERE (? IS NULL OR impactKey>?) ORDER BY impactKey LIMIT ?
        """.trimIndent()
        val rows = prepared(sql, descriptor.location.locationId, descriptor.location.locationId, descriptor.location.locationId,
            after, after, FileOperationLimits.PAGE_SIZE + 1) { statement ->
            buildList {
                while (statement.step()) {
                    add(FileOperationImpact(statement.getText(0), FileImpactKind.valueOf(statement.getText(1)),
                        statement.nullableText(2)?.let { TrackKey(TrackKey.LOCAL_PROVIDER, it) }, statement.nullableText(3),
                        statement.nullableText(4), statement.nullableText(5), statement.nullableText(6), statement.nullableText(7),
                        if (statement.isNull(8)) null else statement.getLong(8)))
                }
            }
        }
        val bounded = rows.take(FileOperationLimits.PAGE_SIZE).map { row ->
            if (row.kind == FileImpactKind.SOURCE) row.copy(source = sourceUnlocked(checkNotNull(row.sourceId))) else row
        }
        FileImpactPage(bounded, if (rows.size > FileOperationLimits.PAGE_SIZE) bounded.last().impactKey else null)
    }

    override suspend fun source(sourceId: String): LibrarySource = library.libraryTransaction { sourceUnlocked(sourceId) }

    override suspend fun sourceSelection(sourceId: String, expectedAuthEpoch: Long): FileSourceSelection = library.libraryTransaction {
        val source = sourceUnlocked(sourceId)
        if (source.authEpoch != expectedAuthEpoch || source.state == LibrarySourceState.REMOVED) fail(FileOperationIssue.SOURCE_CHANGED)
        val count = prepared("""SELECT COUNT(DISTINCT r.trackId) FROM local_access_aliases a
            JOIN local_track_locations r ON r.locationId=a.locationId AND r.validTo IS NULL WHERE a.sourceId=?""", sourceId) {
            check(it.step())
            it.getLong(0)
        }
        FileSourceSelection(source, count)
    }

    override suspend fun sourceTrackPage(sourceId: String, afterTrackId: String?): FileSourceTrackPage = library.libraryTransaction {
        val tracks = prepared("""SELECT t.providerId,t.trackId FROM local_access_aliases a
            JOIN local_track_locations r ON r.locationId=a.locationId AND r.validTo IS NULL
            JOIN local_tracks t ON t.trackId=r.trackId WHERE a.sourceId=? AND (? IS NULL OR t.trackId>?)
            GROUP BY t.providerId,t.trackId ORDER BY t.trackId LIMIT ?""", sourceId, afterTrackId, afterTrackId, FileOperationLimits.PAGE_SIZE + 1) {
            buildList { while (it.step()) add(FileSelectedTrack(TrackKey(it.getText(0), it.getText(1)), null, null)) }
        }
        val page = tracks.take(FileOperationLimits.PAGE_SIZE)
        FileSourceTrackPage(page, if (tracks.size > FileOperationLimits.PAGE_SIZE) page.last().trackKey.trackId else null)
    }

    override suspend fun retainedRead(trackKey: TrackKey, locationId: String?, expectedContentRevision: Long?): LocalReadDescriptor? =
        library.libraryTransaction {
            checkedTrack(trackKey)
            val aliasId = prepared("""SELECT a.aliasId FROM local_track_locations r
                JOIN local_access_aliases a ON a.locationId=r.locationId
                JOIN local_alias_versions v ON v.aliasId=a.aliasId AND v.validTo IS NULL
                JOIN local_sources s ON s.sourceId=a.sourceId
                JOIN local_grants g ON g.grantId=s.grantId
                JOIN local_locations l ON l.locationId=a.locationId
                WHERE r.trackId=? AND r.validTo IS NULL AND (? IS NULL OR r.locationId=?)
                AND (? IS NULL OR l.contentRevision=?) AND s.state='ACTIVE' AND g.readCapability='AVAILABLE'
                AND v.readIssue IS NULL ORDER BY a.aliasId LIMIT 1""", trackKey.trackId, locationId, locationId,
                expectedContentRevision, expectedContentRevision) { if (it.step()) it.getText(0) else null } ?: return@libraryTransaction null
            val alias = dao.aliasById(aliasId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
            val version = dao.currentAlias(alias.aliasId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
            val location = dao.location(alias.locationId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
            val source = sourceUnlocked(alias.sourceId)
            val grant = dao.grant(source.grantId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
            val access = when {
                source.grant.appOwned && source.spec.kind == LocalSourceKind.APP_OWNED -> LocalAccess.APP_OWNED
                source.spec.kind == LocalSourceKind.MEDIA_STORE -> LocalAccess.MEDIA_PERMISSION
                source.grant.persistedRead == ObservedCapability.AVAILABLE &&
                    source.grant.acceptedPersistedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 -> LocalAccess.PERSISTED
                else -> LocalAccess.TEMPORARY
            }
            LocalReadDescriptor(trackKey, source, alias.aliasId, LocalLocationReference(location.locationId, version.uri,
                grant.uri, grant.acceptedPersistedFlags, access, location.contentRevision), alias.objectId, location.contentToken,
                location.sizeBytes, location.verifiedContentToken)
        }

    internal suspend fun publishedLocationId(document: WrittenDocument): String = library.libraryTransaction {
        dao.alias(document.sourceId, document.proof.objectId)?.locationId ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
    }

    internal suspend fun queueDisplayName(key: TrackKey): String = library.libraryTransaction { checkedTrack(key).displayTitle }

    internal suspend fun unavailableQueueLocation(key: TrackKey, locationId: String): LocalLocationReference? = library.libraryTransaction {
        checkedTrack(key)
        val related = prepared("SELECT EXISTS(SELECT 1 FROM local_track_locations WHERE trackId=? AND locationId=? AND validTo IS NULL)",
            key.trackId, locationId) { check(it.step())
                it.getLong(0) != 0L }
        if (!related) return@libraryTransaction null
        val aliasId = prepared("SELECT a.aliasId FROM local_access_aliases a JOIN local_alias_versions v ON v.aliasId=a.aliasId AND v.validTo IS NULL " +
            "WHERE a.locationId=? ORDER BY a.aliasId LIMIT 1", locationId) { if (it.step()) it.getText(0) else null } ?: return@libraryTransaction null
        val alias = dao.aliasById(aliasId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        val version = dao.currentAlias(aliasId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        val location = dao.location(locationId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        val source = sourceUnlocked(alias.sourceId)
        val grant = dao.grant(source.grantId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        val access = when {
            source.spec.kind == LocalSourceKind.APP_OWNED && source.grant.appOwned -> LocalAccess.APP_OWNED
            source.spec.kind == LocalSourceKind.MEDIA_STORE -> LocalAccess.MEDIA_PERMISSION
            source.grant.persistedRead == ObservedCapability.AVAILABLE -> LocalAccess.PERSISTED
            else -> LocalAccess.TEMPORARY
        }
        LocalLocationReference(locationId, version.uri, grant.uri, grant.acceptedPersistedFlags, access, location.contentRevision)
    }

    internal suspend fun ignoredTracks(afterTrackId: String?, limit: Int): FileIgnoredPage = library.libraryTransaction {
        require(limit in 1..FileOperationLimits.PAGE_SIZE)
        val rows = prepared("SELECT providerId,trackId,displayTitle FROM local_track_versions WHERE validTo IS NULL AND member=0 " +
            "AND (? IS NULL OR trackId>?) ORDER BY trackId LIMIT ?", afterTrackId, afterTrackId, limit + 1) { statement ->
            buildList { while (statement.step()) add(FileIgnoredTrack(TrackKey(statement.getText(0), statement.getText(1)), statement.getText(2))) }
        }
        FileIgnoredPage(rows.take(limit), if (rows.size > limit) rows[limit - 1].trackKey.trackId else null)
    }
    internal suspend fun ignoredTarget(key: TrackKey): FileOperationTarget = library.libraryTransaction {
        val track = checkedTrack(key)
        if (track.member) fail(FileOperationIssue.SOURCE_CHANGED)
        FileOperationTarget("track:${key.trackId}", key, track.versionId, null, null, null, null, displayName = track.displayTitle)
    }

    internal suspend fun storageReferencePage(afterLocationId: String?): StorageReferencePage {
        val rows = library.libraryTransaction {
            prepared("""SELECT l.locationId,l.contentRevision,MIN(r.trackId) FROM local_locations l
                JOIN local_track_locations r ON r.locationId=l.locationId AND r.validTo IS NULL
                WHERE l.appOwned=0 AND (? IS NULL OR l.locationId>?) GROUP BY l.locationId,l.contentRevision
                ORDER BY l.locationId LIMIT ?""", afterLocationId, afterLocationId, FileOperationLimits.PAGE_SIZE + 1) { statement ->
                buildList { while (statement.step()) add(StorageReference(statement.getText(0), statement.getLong(1), statement.getText(2))) }
            }
        }
        val page = rows.take(FileOperationLimits.PAGE_SIZE)
        val references = page.map { (locationId, revision, trackId) -> retainedRead(TrackKey(TrackKey.LOCAL_PROVIDER, trackId), locationId, revision) }
        return StorageReferencePage(references, if (rows.size > FileOperationLimits.PAGE_SIZE) page.last().locationId else null)
    }

    override suspend fun check(target: FileOperationTarget, destination: FileOperationDestination?, afterExternalApply: Boolean) {
        library.libraryTransaction {
            val track = dao.currentTrack(target.trackKey.trackId) ?: fail(FileOperationIssue.SOURCE_CHANGED)
            if (track.providerId != target.trackKey.providerId) fail(FileOperationIssue.SOURCE_CHANGED)
            val descriptor = target.descriptor
            if (descriptor != null) {
                val current = sourceUnlocked(descriptor.source.spec.sourceId)
                if (current.spec != descriptor.source.spec || current.authEpoch != descriptor.source.authEpoch ||
                    current.grantId != descriptor.source.grantId || current.state != LibrarySourceState.ACTIVE) fail(FileOperationIssue.SOURCE_CHANGED)
                if (!afterExternalApply) {
                    val alias = dao.aliasById(descriptor.aliasId) ?: fail(FileOperationIssue.SOURCE_CHANGED)
                    val version = dao.currentAlias(alias.aliasId) ?: fail(FileOperationIssue.SOURCE_CHANGED)
                    val location = dao.location(alias.locationId) ?: fail(FileOperationIssue.SOURCE_CHANGED)
                    if (alias.sourceId != descriptor.source.spec.sourceId || alias.objectId != descriptor.objectId ||
                        alias.locationId != descriptor.location.locationId || location.contentRevision != descriptor.location.contentRevision ||
                        version.uri != descriptor.location.uri || version.readIssue != null) fail(FileOperationIssue.SOURCE_CHANGED)
                }
            }
            if (destination != null) {
                val current = sourceUnlocked(destination.sourceId)
                if (current.authEpoch != destination.expectedAuthEpoch || current.spec.kind != LocalSourceKind.TREE ||
                    current.spec.uri != destination.treeUri || current.state != LibrarySourceState.ACTIVE) fail(FileOperationIssue.SOURCE_CHANGED)
                // 弱 SOURCE 身份不能悄悄扩成跨 SAF 根的共享身份；
                // 同根操作和跨提供方复制仍有实际正常路径。
                if (descriptor != null && descriptor.source.spec.sourceId != destination.sourceId &&
                    Uri.parse(descriptor.source.spec.uri).authority == Uri.parse(destination.treeUri).authority &&
                    (descriptor.source.spec.providerIncarnation == null || current.spec.providerIncarnation == null ||
                        descriptor.source.spec.providerIncarnation != current.spec.providerIncarnation)) fail(FileOperationIssue.INCOMPLETE_SCOPE)
            }
        }
        if (target.descriptor != null && !afterExternalApply) checkIndependentAliases(target)
    }

    override suspend fun cancelScans(sourceIds: List<String>) {
        require(sourceIds.size in 1..FileOperationLimits.PAGE_SIZE)
        for (sourceId in sourceIds.distinct()) {
            library.source(sourceId)?.activeScanId?.let { cancelScan(it) }
        }
        library.libraryTransaction {
            val meta = dao.meta() ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
            val revision = increment(meta.revision)
            for (sourceId in sourceIds.distinct()) {
                val old = dao.source(sourceId) ?: fail(FileOperationIssue.SOURCE_CHANGED)
                old.activeScanId?.let { id -> dao.scan(id)?.let { scan ->
                    if (scan.phase !in setOf(ScanPhase.COMPLETED.name, ScanPhase.CANCELLED.name, ScanPhase.FAILED.name)) {
                        dao.updateScan(scan.copy(phase = ScanPhase.CANCELLED.name, issue = SourceReadIssue.SOURCE_CHANGED.name))
                    }
                } }
                val next = old.copy(revision = increment(old.revision), activeScanId = null)
                dao.putSource(next)
                dao.closeSourceVersion(sourceId, revision)
                dao.insertSourceVersion(LibrarySourceVersionEntity(sourceId, revision, null, next.state, next.spec,
                    next.grantId, next.absenceScanId, next.absenceScanSequence, LibraryCodec.source(next.spec).rootObjectId))
            }
            dao.updateMeta(meta.copy(revision = revision))
        }
    }

    override suspend fun reconcile(item: FileOperationItem, command: FileOperationCommand, impacts: List<FileOperationImpact>,
        routes: List<FilePublishedRoute>) {
        require(impacts.size in 1..FileOperationLimits.PAGE_SIZE && routes.size <= FileOperationLimits.PAGE_SIZE + 1)
        library.libraryTransaction(finalPublication = true) {
            val meta = dao.meta() ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
            val revision = increment(meta.revision)
            if (command.kind == FileOperationKind.REMOVE_FROM_INDEX) {
                for (impact in impacts) impact.trackKey?.let { key ->
                    val old = checkedTrack(key)
                    if (old.member) writeTrack(old.copy(member = false), old.metadata(), old.filename, old.metadataIssue, revision)
                }
                dao.updateMeta(meta.copy(revision = revision))
                return@libraryTransaction
            }
            if (command.kind == FileOperationKind.REMOVE_SOURCE) return@libraryTransaction
            if (item.phase !in setOf(FileItemPhase.EXTERNAL_APPLIED, FileItemPhase.COPY_VALIDATED, FileItemPhase.COPY_INDEX_RECONCILED)) {
                fail(FileOperationIssue.RECOVERY_REQUIRED)
            }
            val descriptor = item.target.descriptor ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
            val oldLocation = dao.location(descriptor.location.locationId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
            val published = item.artifacts.published
            val isCopy = command.kind == FileOperationKind.MOVE && published != null &&
                Uri.parse(descriptor.source.spec.uri).authority != Uri.parse(published.treeUri).authority
            val location = if (published != null && isCopy) installCopyLocation(item, published, revision)
                else if (published != null) updateOriginalLocation(item, published, revision)
                else if (item.artifacts.originalDeleted) invalidateLocation(item, oldLocation, revision)
                else fail(FileOperationIssue.RECOVERY_REQUIRED)
            val routeMap = routes.associateBy { it.sourceId }
            for (impact in impacts) when (impact.kind) {
                FileImpactKind.SOURCE -> {
                    val current = sourceUnlocked(checkNotNull(impact.sourceId))
                    val expected = impact.source ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
                    if (current.spec.copy(grantFlags = expected.spec.grantFlags) != expected.spec || current.state != LibrarySourceState.ACTIVE) {
                        fail(FileOperationIssue.SOURCE_CHANGED)
                    }
                    if (current.authEpoch != expected.authEpoch || current.grantId != expected.grantId) {
                        // 重授可协调已发生变更的元数据，但不改变 physical.apply 使用的冻结票据。
                        if (published != null && (!isCopy || current.spec.sourceId != descriptor.source.spec.sourceId)) {
                            val route = routeMap[current.spec.sourceId] ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
                            requireCurrentRoute(current, route, published)
                            if (route.proof == null) fail(route.issue ?: FileOperationIssue.RECOVERY_REQUIRED)
                        }
                        else if (published == null && !item.artifacts.originalDeleted) fail(FileOperationIssue.SOURCE_CHANGED)
                    }
                }
                FileImpactKind.ALIAS -> {
                    val alias = dao.aliasById(checkNotNull(impact.aliasId)) ?: fail(FileOperationIssue.SOURCE_CHANGED)
                    if (alias.locationId != descriptor.location.locationId || alias.sourceId != impact.sourceId) fail(FileOperationIssue.SOURCE_CHANGED)
                    if (published == null || isCopy) {
                        if (item.artifacts.originalDeleted) markAliasMissing(alias, revision, increment(descriptor.location.contentRevision))
                    } else updateAlias(item, command, alias, location, published, routeMap[alias.sourceId], revision)
                }
                FileImpactKind.TRACK, FileImpactKind.CUE_SEGMENT -> {
                    val key = impact.trackKey ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
                    val old = checkedTrack(key)
                    if (isCopy) ensureRelation(item, key, location.locationId, revision)
                    if (published != null && !isCopy) {
                        // CUE 段保留逻辑标签与边界；本操作作用完整承载文件，不能假写逐段标签。
                        val metadata = if (command.kind == FileOperationKind.WRITE_TAGS && !item.artifacts.restoredOriginal &&
                            impact.kind != FileImpactKind.CUE_SEGMENT) patched(old.metadata(), checkNotNull(item.artifacts.tagValues)) else old.metadata()
                        if (old.filename != published.proof.displayName || old.metadata() != metadata) {
                            writeTrack(old, metadata, published.proof.displayName, old.metadataIssue, revision)
                        }
                    }
                }
            }
            if (published != null && (isCopy || published.sourceId != descriptor.source.spec.sourceId)) {
                installDestinationAlias(item, location, published, routeMap[published.sourceId], revision, meta.nextScanSequence)
            }
            if (isCopy && item.artifacts.originalDeleted) invalidateLocation(item, oldLocation, revision)
            dao.updateMeta(meta.copy(revision = revision, nextScanSequence = increment(meta.nextScanSequence)))
        }
    }

    override suspend fun removeSource(sourceId: String, expectedAuthEpoch: Long) {
        // 这里只改来源状态；不删除外部字节，也不释放尚未证明无其他使用者的系统 URI 权。
        library.removeSource(sourceId, expectedAuthEpoch)
    }

    override suspend fun restoreIndex(removed: FileOperationTarget) = library.libraryTransaction(finalPublication = true) {
        if (removed.descriptor != null) fail(FileOperationIssue.INVALID_INPUT)
        val old = checkedTrack(removed.trackKey)
        if (!old.member) {
            val meta = dao.meta() ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
            val revision = increment(meta.revision)
            writeTrack(old.copy(member = true), old.metadata(), old.filename, old.metadataIssue, revision)
            dao.updateMeta(meta.copy(revision = revision))
        }
    }

    private suspend fun checkIndependentAliases(target: FileOperationTarget) {
        val descriptor = target.descriptor ?: return
        val authority = descriptor.source.spec.authority ?: Uri.parse(descriptor.source.spec.uri).authority
        var after: String? = null
        do {
            val page = library.sourcesPage(after, FileOperationLimits.PAGE_SIZE)
            for (other in page.sources) if (other.spec.kind == LocalSourceKind.TREE &&
                (other.spec.authority ?: Uri.parse(other.spec.uri).authority) == authority) {
                library.libraryTransaction {
                    val alias = dao.alias(other.spec.sourceId, descriptor.objectId)
                    if (alias != null && alias.locationId != descriptor.location.locationId) fail(FileOperationIssue.INCOMPLETE_SCOPE)
                }
            }
            after = page.nextSourceId
        } while (after != null)
    }

    private suspend fun updateOriginalLocation(item: FileOperationItem, published: WrittenDocument, revision: Long): LibraryLocationEntity {
        val descriptor = checkNotNull(item.target.descriptor)
        val old = dao.location(descriptor.location.locationId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        val spec = sourceUnlocked(published.sourceId).spec
        val identity = identity(spec, published.proof.objectId)
        val collision = dao.locationByIdentity(identity)
        if (collision != null && collision.locationId != old.locationId) fail(FileOperationIssue.CONFLICT)
        val desiredRevision = if (item.artifacts.restoredOriginal) descriptor.location.contentRevision else increment(descriptor.location.contentRevision)
        if (old.contentRevision !in setOf(descriptor.location.contentRevision, desiredRevision)) fail(FileOperationIssue.SOURCE_CHANGED)
        val next = old.copy(identityKey = identity, contentRevision = desiredRevision, contentToken = null,
            verifiedContentToken = "sha256:${published.proof.sha256}", previousVerifiedContentToken = null, sizeBytes = published.proof.bytes,
            modifiedEpochMs = null)
        if (old != next) { dao.putLocation(next)
            writeLocation(next, revision) }
        return next
    }

    private suspend fun invalidateLocation(item: FileOperationItem, old: LibraryLocationEntity, revision: Long): LibraryLocationEntity {
        val expected = checkNotNull(item.target.descriptor).location.contentRevision
        val desired = increment(expected)
        if (old.contentRevision !in setOf(expected, desired)) fail(FileOperationIssue.SOURCE_CHANGED)
        val next = old.copy(contentRevision = desired, contentToken = null, previousVerifiedContentToken = null)
        if (next != old) { dao.putLocation(next)
            writeLocation(next, revision) }
        return next
    }

    private suspend fun installCopyLocation(item: FileOperationItem, published: WrittenDocument, revision: Long): LibraryLocationEntity {
        val id = stableId(item, "copy-location")
        val key = identity(sourceUnlocked(published.sourceId).spec, published.proof.objectId)
        val collision = dao.locationByIdentity(key)
        if (collision != null && collision.locationId != id) fail(FileOperationIssue.CONFLICT)
        val expected = LibraryLocationEntity(id, key, 1L, null, "sha256:${published.proof.sha256}", null,
            published.proof.bytes, null, false)
        val old = dao.location(id)
        if (old != null && old != expected) fail(FileOperationIssue.SOURCE_CHANGED)
        if (old == null) { dao.putLocation(expected)
            writeLocation(expected, revision) }
        return expected
    }

    private suspend fun ensureRelation(item: FileOperationItem, key: TrackKey, locationId: String, revision: Long) {
        val present = prepared("SELECT EXISTS(SELECT 1 FROM local_track_locations WHERE trackId=? AND locationId=? AND validTo IS NULL)",
            key.trackId, locationId) { check(it.step())
                it.getLong(0) != 0L }
        if (!present) dao.insertRelation(LibraryTrackLocationEntity(stableId(item, "copy-relation:${key.trackId}"), key.trackId, locationId, revision, null))
    }

    private suspend fun installDestinationAlias(item: FileOperationItem, location: LibraryLocationEntity, published: WrittenDocument,
        route: FilePublishedRoute?, revision: Long, sequence: Long) {
        val currentSource = sourceUnlocked(published.sourceId)
        val access = route ?: return // 最终协调前必须完成全部访问路由复核。
        requireCurrentRoute(currentSource, access, published)
        if (access.proof == null) fail(FileOperationIssue.SOURCE_UNAVAILABLE)
        val existing = dao.alias(published.sourceId, published.proof.objectId)
        if (existing != null && existing.locationId != location.locationId) fail(FileOperationIssue.CONFLICT)
        val node = ensureNode(published.sourceId, published.proof.objectId, null, published, access.proof.uri, revision)
        val alias = existing ?: LibraryAliasEntity(stableId(item, "destination-alias"), published.sourceId,
            published.proof.objectId, location.locationId).also { dao.insertAlias(it) }
        val old = dao.currentAlias(alias.aliasId)
        if (old == null || old.uri != access.proof.uri || old.contentRevision != location.contentRevision || old.readIssue != null) {
            writeAlias(LibraryAliasVersionEntity(UUID.randomUUID().toString(), alias.aliasId, alias.sourceId, location.locationId, node.nodeId,
                access.proof.uri, access.proof.displayName, null, sequence, location.contentRevision, null, published.proof.bytes, revision, null), revision)
        }
    }

    private suspend fun updateAlias(item: FileOperationItem, command: FileOperationCommand, alias: LibraryAliasEntity, location: LibraryLocationEntity,
        published: WrittenDocument, route: FilePublishedRoute?, revision: Long) {
        val old = dao.currentAlias(alias.aliasId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        val currentSource = sourceUnlocked(alias.sourceId)
        val access = route ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        requireCurrentRoute(currentSource, access, published)
        if (access.proof == null) {
            if (command.kind == FileOperationKind.MOVE && alias.sourceId != published.sourceId && access.issue == FileOperationIssue.SOURCE_CHANGED) {
                // 移出此根时保留该入口的范围失效解释，同时登记验证后的目标；
                // 仍在根内的权限失败须重授后继续协调。
                markAliasUnavailable(alias, revision, location.contentRevision, SourceReadIssue.SOURCE_CHANGED)
                return
            }
            fail(access.issue ?: FileOperationIssue.RECOVERY_REQUIRED)
        }
        if (alias.objectId != item.target.descriptor?.objectId && alias.objectId != published.proof.objectId) fail(FileOperationIssue.SOURCE_CHANGED)
        val collision = dao.alias(alias.sourceId, published.proof.objectId)
        if (collision != null && collision.aliasId != alias.aliasId) fail(FileOperationIssue.CONFLICT)
        val node = ensureNode(alias.sourceId, published.proof.objectId, old.nodeId, published, access.proof.uri, revision)
        if (alias.objectId != published.proof.objectId) execute("UPDATE local_access_aliases SET objectId=? WHERE aliasId=?", published.proof.objectId, alias.aliasId)
        val next = old.copy(nodeId = node.nodeId, uri = access.proof.uri, filename = access.proof.displayName, contentRevision = location.contentRevision,
            providerContentToken = null, sizeBytes = published.proof.bytes, readIssue = null)
        if (old != next) writeAlias(next, revision)
    }

    private suspend fun ensureNode(sourceId: String, objectId: String, retainedNodeId: String?, published: WrittenDocument,
        uri: String, revision: Long): LibraryNodeEntity {
        val collision = dao.node(sourceId, objectId)
        if (retainedNodeId != null && collision != null && collision.nodeId != retainedNodeId) fail(FileOperationIssue.CONFLICT)
        val retained = retainedNodeId?.let { dao.nodeById(it) ?: fail(FileOperationIssue.RECOVERY_REQUIRED) }
        val node = retained ?: collision ?: LibraryNodeEntity(UUID.randomUUID().toString(), sourceId, objectId, published.proof.displayName)
            .also { dao.insertNode(it) }
        val next = node.copy(objectId = objectId, lastKnownName = published.proof.displayName)
        if (node != next) dao.updateNode(next)
        val parent = dao.node(sourceId, published.parentObjectId) ?: LibraryNodeEntity(UUID.randomUUID().toString(), sourceId, published.parentObjectId)
            .also { dao.insertNode(it) }
        val old = dao.currentNode(node.nodeId)
        val normalized = Normalizer.normalize(published.proof.displayName, Normalizer.Form.NFC)
        if (old == null || old.parentNodeId != parent.nodeId || old.name != published.proof.displayName || old.uri != uri || old.directory) {
            val value = LibraryNodeVersionEntity(UUID.randomUUID().toString(), node.nodeId, sourceId, parent.nodeId, published.proof.displayName,
                uri, false, revision, null, normalized)
            if (old?.validFrom == revision) dao.updateNodeVersion(value.copy(versionId = old.versionId))
            else { if (old != null) dao.closeNodeVersion(node.nodeId, revision)
                dao.insertNodeVersion(value) }
        }
        return next
    }

    private suspend fun markAliasMissing(alias: LibraryAliasEntity, revision: Long, contentRevision: Long) {
        markAliasUnavailable(alias, revision, contentRevision, SourceReadIssue.FILE_MISSING)
    }

    private suspend fun markAliasUnavailable(alias: LibraryAliasEntity, revision: Long, contentRevision: Long, issue: SourceReadIssue) {
        val old = dao.currentAlias(alias.aliasId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        val next = old.copy(readIssue = issue.name, contentRevision = contentRevision, providerContentToken = null)
        if (next != old) writeAlias(next, revision)
    }

    private suspend fun writeAlias(value: LibraryAliasVersionEntity, revision: Long) {
        val old = dao.currentAlias(value.aliasId)
        val next = value.copy(versionId = if (old?.validFrom == revision) old.versionId else UUID.randomUUID().toString(), validFrom = revision, validTo = null)
        if (old?.validFrom == revision) dao.updateAliasVersion(next)
        else { if (old != null) dao.closeAliasVersion(value.aliasId, revision)
            dao.insertAliasVersion(next) }
    }

    private suspend fun writeLocation(value: LibraryLocationEntity, revision: Long) {
        val old = dao.currentLocationVersion(value.locationId)
        val next = LibraryLocationVersionEntity(if (old?.validFrom == revision) old.versionId else UUID.randomUUID().toString(), value.locationId,
            value.contentRevision, value.contentToken, value.verifiedContentToken, value.sizeBytes, value.modifiedEpochMs, revision, null)
        if (old?.validFrom == revision) dao.updateLocationVersion(next)
        else { if (old != null) dao.closeLocationVersion(value.locationId, revision)
            dao.insertLocationVersion(next) }
    }

    private suspend fun writeTrack(old: LibraryTrackVersionEntity, metadata: LocalAudioMetadata, filename: String, issue: String?, revision: Long) {
        val current = dao.currentTrack(old.trackId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        val title = metadata.title?.takeIf(String::isNotBlank) ?: filename
        val artists = metadata.artists.ifEmpty { metadata.artist?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty() }
        val primary = artists.firstOrNull()
        val next = old.copy(versionId = if (current.validFrom == revision) current.versionId else UUID.randomUUID().toString(), validFrom = revision, validTo = null,
            filename = filename, displayTitle = title, title = metadata.title, artist = metadata.artist, artists = LibraryCodec.strings(metadata.artists), album = metadata.album,
            albumArtist = metadata.albumArtist, durationMs = metadata.durationMs, discNumber = metadata.discNumber, trackNumber = metadata.trackNumber,
            compilation = metadata.compilation, mimeType = metadata.mimeType, parserVersion = metadata.parserVersion, metadataIssue = issue,
            titleKey = LibrarySortKeys.text(title), artistKey = LibrarySortKeys.text(primary), albumSortKey = LibrarySortKeys.text(metadata.album),
            filenameKey = LibrarySortKeys.natural(filename), albumKey = LibrarySortKeys.album(metadata.copy(artists = artists)),
            searchText = LibrarySortKeys.normalize(listOfNotNull(title, metadata.artist, artists.joinToString(" "), metadata.album, filename).joinToString("\n")),
            titleSection = LibrarySortKeys.section(title), artistSection = LibrarySortKeys.section(primary), albumSection = LibrarySortKeys.section(metadata.album),
            filenameSection = LibrarySortKeys.section(filename))
        if (current.validFrom == revision) { dao.deleteArtists(current.versionId)
            dao.updateTrackVersion(next) }
        else { dao.closeTrackVersion(old.trackId, revision)
            dao.insertTrackVersion(next) }
        val unique = artists.filter(String::isNotBlank).distinctBy(LibrarySortKeys::normalize)
        dao.insertArtists(if (unique.isEmpty()) listOf(LibraryArtistEntity(next.versionId, 0, LibrarySortKeys.text(null), null)) else
            unique.mapIndexed { ordinal, name -> LibraryArtistEntity(next.versionId, ordinal, LibrarySortKeys.text(name), name) })
    }

    private fun patched(old: LocalAudioMetadata, values: Map<AudioTagField, List<String>?>): LocalAudioMetadata {
        fun one(field: AudioTagField) = values[field]?.firstOrNull()
        fun number(field: AudioTagField) = one(field)?.substringBefore('/')?.toIntOrNull()
        val artists = values[AudioTagField.ARTISTS].orEmpty()
        return old.copy(title = one(AudioTagField.TITLE), artist = artists.firstOrNull(), artists = artists,
            album = one(AudioTagField.ALBUM), albumArtist = one(AudioTagField.ALBUM_ARTIST),
            trackNumber = number(AudioTagField.TRACK_NUMBER), discNumber = number(AudioTagField.DISC_NUMBER))
    }

    private suspend fun sourceUnlocked(id: String): LibrarySource {
        val source = dao.source(id) ?: fail(FileOperationIssue.SOURCE_UNAVAILABLE)
        return source.model(dao.grant(source.grantId) ?: fail(FileOperationIssue.RECOVERY_REQUIRED))
    }
    private suspend fun checkedTrack(key: TrackKey): LibraryTrackVersionEntity = (dao.currentTrack(key.trackId)
        ?: fail(FileOperationIssue.SOURCE_CHANGED)).also { if (it.providerId != key.providerId) fail(FileOperationIssue.SOURCE_CHANGED) }
    private fun requireCurrentRoute(source: LibrarySource, route: FilePublishedRoute, document: WrittenDocument) {
        if (route.sourceId != source.spec.sourceId || route.authEpoch != source.authEpoch || route.grantId != source.grantId) fail(FileOperationIssue.SOURCE_CHANGED)
        route.proof?.let { SafDocumentFileAccess.requireSameObjectContents(document.proof, it) }
    }
    private fun identity(spec: LocalSourceSpec, objectId: String): String = if (spec.authority != null && spec.providerIncarnation != null)
        LibrarySortKeys.identity("PROVIDER", spec.authority, spec.providerIncarnation, "document:${spec.authority}:$objectId") else
        LibrarySortKeys.identity("SOURCE", spec.sourceId, objectId)
    private fun stableId(item: FileOperationItem, role: String) = UUID.nameUUIDFromBytes(
        "melox-file-operation:v1:${item.operationId}:${item.target.unitKey}:$role".toByteArray(Charsets.UTF_8)).toString()
    private fun increment(value: Long): Long = try { Math.addExact(value, 1L) }
        catch (failure: ArithmeticException) { throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED, failure) }
    private suspend fun execute(sql: String, vararg arguments: Any?) { prepared(sql, *arguments) { it.step() } }
    private suspend fun <T> prepared(sql: String, vararg arguments: Any?, action: (SQLiteStatement) -> T): T = database.useWriterConnection { writer ->
        writer.usePrepared(sql) { statement ->
            arguments.forEachIndexed { index, value -> when (value) {
                null -> statement.bindNull(index + 1)
                is String -> statement.bindText(index + 1, value)
                is Long -> statement.bindLong(index + 1, value)
                is Int -> statement.bindLong(index + 1, value.toLong())
                else -> fail(FileOperationIssue.INVALID_INPUT)
            } }
            action(statement)
        }
    }
    private fun SQLiteStatement.nullableText(index: Int): String? = if (isNull(index)) null else getText(index)
    private fun fail(issue: FileOperationIssue): Nothing = throw FileOperationException(issue)
}
