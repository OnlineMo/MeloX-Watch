package io.github.onlinemo.meloxwatch.data.backup

import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.library.DisplayAlbumLinkEntity
import io.github.onlinemo.meloxwatch.data.library.DisplayArtistLinkEntity
import io.github.onlinemo.meloxwatch.data.library.DisplayGroupEntity
import io.github.onlinemo.meloxwatch.data.library.DisplayNameEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryAliasEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryAliasVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryArtistEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryCodec
import io.github.onlinemo.meloxwatch.data.library.LibraryGrantEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryLocationEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryLocationVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryMetaEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryNodeEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryNodeVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryRuleEntity
import io.github.onlinemo.meloxwatch.data.library.LibrarySortKeys
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceEntity
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackLocationEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.LocalCodecParametersCodec
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.currentCueMatchesPortableHint
import io.github.onlinemo.meloxwatch.data.listening.ListeningDayEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningHistoryEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningLimits
import io.github.onlinemo.meloxwatch.data.listening.ListeningMetaEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningRoundEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningTrackEntity
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.decode
import io.github.onlinemo.meloxwatch.data.lyrics.store.entity
import io.github.onlinemo.meloxwatch.data.lyrics.store.update
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataAssociationRow
import io.github.onlinemo.meloxwatch.data.metadata.MetadataAssociation
import io.github.onlinemo.meloxwatch.data.metadata.MetadataProvenance
import io.github.onlinemo.meloxwatch.data.metadata.MetadataPurpose
import io.github.onlinemo.meloxwatch.data.metadata.MetadataStoreException
import io.github.onlinemo.meloxwatch.data.metadata.decode
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistGenerationEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistHeadEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMemberEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReferenceIssue
import io.github.onlinemo.meloxwatch.data.playlists.playlistNameKey
import io.github.onlinemo.meloxwatch.data.playlists.record
import io.github.onlinemo.meloxwatch.data.queue.QueueEntryEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueGenerationEntity
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class BackupMainApplyResult(val missingReferences: Long, val bindings: Map<String, BackupCell>) {
    override fun toString(): String = "BackupMainApplyResult"
}

/**
 * Portable rows enter the real library mutex and preserved Room writer. Old MVCC rows, original
 * files, import ledgers and live grants survive; the archive supplies no operational SQL or grant.
 */
internal class RoomBackupMaterializer(
    private val packageName: String,
    private val barrier: BackupMutationBarrier,
    private val database: MeloXDatabase,
    private val library: RoomLocalLibraryRepository,
    private val permissions: BackupPermissionVerifier,
    private val restoredLyrics: (BackupRestoreJournal, String, String) -> LyricsAssociation?,
    private val restoredMetadataAsset: (BackupRestoreJournal, String, MetadataPurpose, Long) -> String? = { _, _, _, _ -> null },
) {
    private val dao = database.libraryDao()

    suspend fun apply(run: BackupRestoreRun, stage: SqliteBackupStage, journal: BackupRestoreJournal,
        prepareCommit: suspend (Map<String, BackupCell>) -> Unit): BackupMainApplyResult {
        barrier.requireExclusiveOwner()
        // This sole writer owns no other TEMP actor; configure disk storage before creating any transaction or table.
        database.useWriterConnection { writer ->
            if (writer.inTransaction()) throw BackupException(BackupIssue.BUSY)
            writer.usePrepared("PRAGMA compile_options") { options ->
                while (options.step()) if (options.getText(0).removePrefix("SQLITE_") == "TEMP_STORE=3") {
                    throw BackupException(BackupIssue.UNSUPPORTED_RESOURCE)
                }
            }
            writer.usePrepared("PRAGMA temp_store=FILE") { it.step() }
            writer.usePrepared("PRAGMA temp_store") {
                if (!it.step() || it.getLong(0) != 1L) throw BackupException(BackupIssue.UNSUPPORTED_RESOURCE)
            }
        }
        return library.libraryTransaction {
            stage.clearActualIds()
            permissions.checkFences(stage)
            val version = database.useWriterConnection { writer -> writer.usePrepared("PRAGMA user_version") {
                if (!it.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                it.getLong(0)
            } }
            if (version != BackupLimits.DATABASE_VERSION.toLong()) throw BackupException(BackupIssue.UNSUPPORTED_VERSION)
            val initial = dao.meta() ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            if (initial.revision < 0L || initial.rulesRevision < 0L || initial.nextAddedSequence < 1L || initial.statisticsEpoch < 0L) {
                throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
            withMetadataReplacement(run, stage) {
                val write = LibraryWrite(initial, increment(initial.revision), initial.nextAddedSequence, initial.statisticsEpoch)
                var missing = 0L
                val bindings = LinkedHashMap<String, BackupCell>()
                if (BackupScope.LIBRARY in run.scopes) missing = add(missing, restoreLibrary(run, stage, write))
                if (BackupScope.FAVORITES in run.scopes || BackupScope.PLAYLISTS in run.scopes) missing = add(missing, restorePlaylists(run, stage))
                if (BackupScope.QUEUE in run.scopes) missing = add(missing, restoreQueue(run, stage))
                if (BackupScope.HISTORY in run.scopes) {
                    bindings += restoreHistory(run, stage, journal, write)
                    for (kind in listOf(BackupRecordKind.HISTORY_ENTRY, BackupRecordKind.TRACK_TOTAL)) each(stage, kind) { row ->
                        if (row.text("providerId") == TrackKey.LOCAL_PROVIDER && dao.currentTrack(row.text("trackId")) == null) missing = increment(missing)
                    }
                }
                if (BackupScope.LYRICS in run.scopes) missing = add(missing, restoreLyrics(run, stage, journal, write))
                missing = add(missing, restoreMetadata(run, stage, journal, write))
                missing = add(missing, materializeMissingReferences(run, stage, journal, write))
                if (write.changed) {
                    val filter = if (BackupScope.LIBRARY in run.scopes && run.mode == BackupMergeMode.REPLACE_SELECTED)
                        stage.row(BackupRecordKind.LIBRARY_META, "library")?.flag("shortAudioFilter")
                            ?: throw BackupException(BackupIssue.INVALID_REFERENCE) else initial.shortAudioFilter
                    if (dao.updateMeta(initial.copy(revision = write.revision, rulesRevision = write.rulesRevision,
                            nextAddedSequence = write.nextAdded, shortAudioFilter = filter, statisticsEpoch = write.statisticsEpoch)) != 1) {
                        throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    }
                }
                // All stale cursors are retired in this same transaction, before their data is exposed.
                execute("DELETE FROM library_views")
                execute("DELETE FROM playlist_views")
                execute("UPDATE playlist_exchange_previews SET state='INVALIDATED' WHERE state='READY'")
                prepareCommit(bindings + ("mainMissingReferences" to BackupCell.Integer(missing)))
                BackupMainApplyResult(missing, bindings)
            }
        }
    }

    private class LibraryWrite(val initial: LibraryMetaEntity, val revision: Long, var nextAdded: Long,
        var statisticsEpoch: Long) {
        var changed = false
        var rulesRevision = initial.rulesRevision
    }

    private suspend fun restoreLibrary(run: BackupRestoreRun, stage: SqliteBackupStage, write: LibraryWrite): Long {
        write.changed = true
        write.rulesRevision = increment(write.initial.rulesRevision)
        createSelection("backup_sources")
        createSelection("backup_tracks")
        createSelection("backup_existing_tracks")
        try {
            if (run.mode == BackupMergeMode.REPLACE_SELECTED) retireCueAccess(write.revision)
            each(stage, BackupRecordKind.SOURCE_HINT) { row ->
                val binding = requiredBinding(stage, BackupBindingFamily.SOURCE, row.key)
                select("backup_sources", binding.actualId)
                if (!binding.verified) {
                    val existing = dao.source(binding.actualId)
                    if (existing == null) {
                        val wantedKind = LocalSourceKind.valueOf(row.text("kind"))
                        // Missing app-owned audio has no restored ownership ledger or bytes.
                        val kind = if (wantedKind == LocalSourceKind.APP_OWNED) LocalSourceKind.DOCUMENT else wantedKind
                        val spec = LocalSourceSpec(binding.actualId, kind, unresolvedUri("source", binding.actualId),
                            mediaVolume = row.text("mediaVolume"), authority = row.optionalText("authorityHint"))
                        val grantId = stableUuid(run.archiveSha256, "grant", row.key)
                        val unavailable = LocalGrantObservation(read = ObservedCapability.UNAVAILABLE,
                            enumerate = ObservedCapability.UNAVAILABLE, persistedRead = ObservedCapability.UNAVAILABLE,
                            persistedWrite = ObservedCapability.UNAVAILABLE, offlineRead = ObservedCapability.UNAVAILABLE,
                            write = ObservedCapability.UNAVAILABLE, delete = ObservedCapability.UNAVAILABLE,
                            rename = ObservedCapability.UNAVAILABLE, move = ObservedCapability.UNAVAILABLE)
                        val source = LibrarySourceEntity(binding.actualId, LibraryCodec.source(spec), grantId, 1L, 1L,
                            LibrarySourceState.NEEDS_AUTHORIZATION.name, null, null, null, null, null)
                        dao.putSource(source)
                        dao.insertGrant(LibraryGrantEntity(grantId, source.sourceId, spec.uri, LibraryCodec.grant(unavailable),
                            ObservedCapability.UNAVAILABLE.name, 0, false))
                        dao.insertSourceVersion(LibrarySourceVersionEntity(source.sourceId, write.revision, null,
                            source.state, source.spec, source.grantId, null, null, null))
                    } else if (run.mode == BackupMergeMode.REPLACE_SELECTED &&
                        existing.state !in setOf(LibrarySourceState.NEEDS_AUTHORIZATION.name, LibrarySourceState.REMOVED.name)) {
                        throw BackupException(BackupIssue.CONFLICT)
                    } else if (run.mode == BackupMergeMode.REPLACE_SELECTED && existing.state == LibrarySourceState.REMOVED.name) {
                        val next = existing.copy(state = LibrarySourceState.NEEDS_AUTHORIZATION.name,
                            revision = increment(existing.revision), authEpoch = increment(existing.authEpoch), activeScanId = null)
                        dao.putSource(next)
                        dao.closeSourceVersion(next.sourceId, write.revision)
                        dao.insertSourceVersion(LibrarySourceVersionEntity(next.sourceId, write.revision, null,
                            next.state, next.spec, next.grantId, null, null, null))
                    }
                }
            }
            each(stage, BackupRecordKind.LOCATION_HINT) { row ->
                val binding = requiredBinding(stage, BackupBindingFamily.LOCATION, row.key)
                if (!binding.verified && dao.location(binding.actualId) == null) {
                    val location = LibraryLocationEntity(binding.actualId,
                        LibrarySortKeys.identity("BACKUP_UNVERIFIED", run.archiveSha256, row.key), 1L,
                        null, null, null, row.optionalLong("sizeBytes"), row.optionalLong("modifiedEpochMs"), false)
                    dao.putLocation(location)
                    dao.insertLocationVersion(LibraryLocationVersionEntity(stableUuid(run.id, "location", row.key),
                        location.locationId, location.contentRevision, null, null, location.sizeBytes, location.modifiedEpochMs,
                        write.revision, null))
                }
            }
            each(stage, BackupRecordKind.NODE_HINT) { row ->
                val binding = requiredBinding(stage, BackupBindingFamily.NODE, row.key)
                if (!binding.verified && dao.nodeById(binding.actualId) == null) {
                    val sourceId = requiredBinding(stage, BackupBindingFamily.SOURCE, row.text("sourceId")).actualId
                    dao.insertNode(LibraryNodeEntity(binding.actualId, sourceId, "backup:" + binding.actualId, row.optionalText("name")))
                    dao.insertNodeVersion(LibraryNodeVersionEntity(stableUuid(run.id, "node", row.key), binding.actualId,
                        sourceId, row.optionalText("parentNodeId")?.let { requiredBinding(stage, BackupBindingFamily.NODE, it).actualId },
                        row.optionalText("name"), unresolvedUri("node", binding.actualId), row.flag("directory"), write.revision,
                        null, row.optionalText("pathName")))
                }
            }
            each(stage, BackupRecordKind.ALIAS_HINT) { row ->
                val binding = requiredBinding(stage, BackupBindingFamily.ALIAS, row.key)
                if (!binding.verified && dao.aliasById(binding.actualId) == null) {
                    val sourceId = requiredBinding(stage, BackupBindingFamily.SOURCE, row.text("sourceId")).actualId
                    val locationId = requiredBinding(stage, BackupBindingFamily.LOCATION, row.text("locationId")).actualId
                    val location = dao.location(locationId) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    dao.insertAlias(LibraryAliasEntity(binding.actualId, sourceId, "backup:" + binding.actualId, locationId))
                    dao.insertAliasVersion(LibraryAliasVersionEntity(stableUuid(run.id, "alias", row.key), binding.actualId,
                        sourceId, locationId, requiredBinding(stage, BackupBindingFamily.NODE, row.text("nodeId")).actualId,
                        unresolvedUri("alias", binding.actualId), row.text("filename"), SourceReadIssue.CONTENT_UNKNOWN.name,
                        0L, location.contentRevision, null, location.sizeBytes, write.revision, null))
                }
            }
            var afterAdded: Long? = null
            while (true) {
                val page = stage.orderedPage(BackupRecordKind.TRACK, "track-added", "library", afterAdded)
                if (page.isEmpty()) break
                for (row in page) {
                    stage.checkActive()
                    stage.setActualId(BackupRecordKind.TRACK, row.key, row.key)
                    select("backup_tracks", row.key)
                    val old = dao.currentTrack(row.key)
                    if (old != null && run.mode == BackupMergeMode.MERGE_KEEP_CURRENT) {
                        select("backup_existing_tracks", row.key)
                        continue
                    }
                    val sequence = write.nextAdded
                    write.nextAdded = increment(write.nextAdded)
                    val track = dao.track(row.key)
                    if (track == null) dao.insertTrack(LibraryTrackEntity(row.key, TrackKey.LOCAL_PROVIDER,
                        row.long("firstAddedEpochMs"), sequence))
                    else execute("UPDATE local_tracks SET firstAddedEpochMs=?,firstAddedSequence=? WHERE trackId=?",
                        BackupCell.Integer(row.long("firstAddedEpochMs")), BackupCell.Integer(sequence), BackupCell.Text(row.key))
                    val metadata = LocalAudioMetadata(row.optionalText("title"), row.optionalText("artist"),
                        BackupLogicalSchema.artists(row.text("artists")), row.optionalText("album"), row.optionalText("albumArtist"),
                        row.optionalLong("durationMs"), row.optionalLong("discNumber")?.toInt(), row.optionalLong("trackNumber")?.toInt(),
                        row.flag("compilation"), row.optionalText("mimeType"), row.long("parserVersion").toInt(),
                        codecParameters = row.optionalText("codecParameters")?.let(LocalCodecParametersCodec::decode))
                    val version = trackVersion(run, row, sequence, metadata, old, write)
                    putTrackVersion(version, old)
                }
                afterAdded = page.last().long("firstAddedSequence")
            }
            if (run.mode == BackupMergeMode.REPLACE_SELECTED) {
                eachId("SELECT trackId FROM local_track_versions WHERE validTo IS NULL AND member=1 AND trackId NOT IN (SELECT id FROM temp.backup_tracks) AND (? IS NULL OR trackId>?) ORDER BY trackId LIMIT 100") { id ->
                    val old = dao.currentTrack(id) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    putTrackVersion(old.copy(versionId = stableUuid(run.id, "retire-track", id), validFrom = write.revision,
                        validTo = null, member = false, cueReference = old.cueReference?.let {
                            CueReferenceCodec.encode(CueReferenceCodec.decode(it).portable())
                        }), old)
                }
                execute("UPDATE local_track_locations SET validTo=? WHERE validTo IS NULL", BackupCell.Integer(write.revision - 1L))
                eachId("SELECT sourceId FROM local_sources WHERE state!='REMOVED' AND sourceId NOT IN (SELECT id FROM temp.backup_sources) AND (? IS NULL OR sourceId>?) ORDER BY sourceId LIMIT 100") { id ->
                    val old = dao.source(id) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    val next = old.copy(state = LibrarySourceState.REMOVED.name, revision = increment(old.revision),
                        authEpoch = increment(old.authEpoch), activeScanId = null)
                    dao.putSource(next)
                    dao.closeSourceVersion(id, write.revision)
                    dao.insertSourceVersion(LibrarySourceVersionEntity(id, write.revision, null, next.state, next.spec,
                        next.grantId, next.absenceScanId, next.absenceScanSequence, LibraryCodec.source(next.spec).rootObjectId))
                }
                execute("UPDATE scan_rules SET validTo=? WHERE validTo IS NULL", BackupCell.Integer(write.revision - 1L))
            }
            var missing = 0L
            each(stage, BackupRecordKind.TRACK_LOCATION) { row ->
                val trackId = stage.actualId(BackupRecordKind.TRACK, row.text("trackId"))
                if (run.mode == BackupMergeMode.MERGE_KEEP_CURRENT && scalar(
                        "SELECT EXISTS(SELECT 1 FROM temp.backup_existing_tracks WHERE id=?)", BackupCell.Text(trackId)) == 1L) return@each
                val binding = requiredBinding(stage, BackupBindingFamily.LOCATION, row.text("locationId"))
                if (!binding.verified) missing = increment(missing)
                if (scalar("SELECT EXISTS(SELECT 1 FROM local_track_locations WHERE trackId=? AND locationId=? AND validTo IS NULL)",
                        BackupCell.Text(trackId), BackupCell.Text(binding.actualId)) == 0L) {
                    dao.insertRelation(LibraryTrackLocationEntity(stableUuid(run.id, "relation", row.key), trackId,
                        binding.actualId, write.revision, null))
                }
            }
            each(stage, BackupRecordKind.RULE) { row ->
                val source = requiredBinding(stage, BackupBindingFamily.SOURCE, row.text("sourceId")).actualId
                val node = requiredBinding(stage, BackupBindingFamily.NODE, row.text("nodeId")).actualId
                if (!dao.hasRule(source, node, row.flag("subtree"))) dao.insertRule(LibraryRuleEntity(stableUuid(run.id, "rule-version", row.key),
                    "restored-rule-" + backupStableId(run.archiveSha256, row.key), source, node, row.flag("subtree"), write.revision, null))
            }
            restoreDisplay(run, stage, write.revision)
            each(stage, BackupRecordKind.TRACK) { row ->
                if (row.portableCueReference() != null &&
                    (run.mode == BackupMergeMode.REPLACE_SELECTED || scalar("SELECT EXISTS(SELECT 1 FROM temp.backup_existing_tracks WHERE id=?)",
                        BackupCell.Text(row.key)) == 0L)) missing = increment(missing)
            }
            return missing
        } finally {
            execute("DROP TABLE IF EXISTS temp.backup_tracks")
            execute("DROP TABLE IF EXISTS temp.backup_sources")
            execute("DROP TABLE IF EXISTS temp.backup_existing_tracks")
        }
    }

    /** 替换在同一写事务中移除访问能力，同时保留原生标识与修订历史。 */
    private suspend fun retireCueAccess(revision: Long) {
        val cues = database.cueDao()
        eachId("SELECT trackId FROM local_track_versions WHERE validTo IS NULL AND cueReference IS NOT NULL AND (? IS NULL OR trackId>?) ORDER BY trackId LIMIT 100") { id ->
            val encoded = dao.currentTrack(id)?.cueReference ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            val reference = CueReferenceCodec.decode(encoded)
            val documents = setOf(reference.identity.sourceId to reference.identity.cueId,
                reference.actualDocument.sourceId to reference.actualDocument.cueId)
            for ((sourceId, cueId) in documents) {
                val document = cues.documentById(cueId) ?: continue
                if (document.sourceId != sourceId || cues.closeGroup(cueId, revision) !in 0..1 ||
                    cues.markReassociationPending(cueId) != 1) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
            cues.alias(reference.identity.sourceId, reference.identity.cueId)?.let { alias ->
                if (cues.closeAliasVersion(alias.aliasId, revision) !in 0..1 ||
                    cues.markAliasReassociationPending(alias.aliasId) != 1) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
        }
    }

    private fun trackVersion(run: BackupRestoreRun, row: BackupRecord, sequence: Long, metadata: LocalAudioMetadata,
        old: LibraryTrackVersionEntity?, write: LibraryWrite): LibraryTrackVersionEntity {
        val filename = row.text("filename")
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: filename
        val artists = metadata.artists.ifEmpty { metadata.artist?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty() }
        return LibraryTrackVersionEntity(stableUuid(run.id, "track-version", row.key), row.key, TrackKey.LOCAL_PROVIDER,
            write.revision, null, row.flag("member"), row.long("firstAddedEpochMs"), sequence, filename, title,
            metadata.title, metadata.artist, LibraryCodec.strings(metadata.artists), metadata.album, metadata.albumArtist,
            metadata.durationMs, metadata.discNumber, metadata.trackNumber, metadata.compilation, metadata.mimeType,
            metadata.parserVersion, row.optionalText("metadataIssue"), LibrarySortKeys.text(title), LibrarySortKeys.text(artists.firstOrNull()),
            LibrarySortKeys.text(metadata.album), LibrarySortKeys.natural(filename), LibrarySortKeys.album(metadata.copy(artists = artists)),
            LibrarySortKeys.normalize(listOfNotNull(title, metadata.artist, artists.joinToString(" "), metadata.album, filename).joinToString("\n")),
            LibrarySortKeys.section(title), LibrarySortKeys.section(artists.firstOrNull()), LibrarySortKeys.section(metadata.album),
            LibrarySortKeys.section(filename), old?.playCount ?: 0L, old?.listeningMs ?: 0L, old?.statisticsEpoch ?: write.statisticsEpoch,
            codecParameters = metadata.codecParameters?.let(LocalCodecParametersCodec::encode),
            cueReference = row.portableCueReference()?.let(CueReferenceCodec::encode))
    }

    private suspend fun putTrackVersion(next: LibraryTrackVersionEntity, old: LibraryTrackVersionEntity?) {
        if (old?.validFrom == next.validFrom) {
            dao.deleteArtists(old.versionId)
            dao.updateTrackVersion(next.copy(versionId = old.versionId))
        } else {
            dao.closeTrackVersion(next.trackId, next.validFrom)
            dao.insertTrackVersion(next)
        }
        val actualId = if (old?.validFrom == next.validFrom) old.versionId else next.versionId
        val artists = LibraryCodec.strings(next.artists).ifEmpty { next.artist?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty() }
            .filter { it.isNotBlank() }.distinctBy(LibrarySortKeys::normalize)
        dao.insertArtists(if (artists.isEmpty()) listOf(LibraryArtistEntity(actualId, 0, LibrarySortKeys.text(null), null))
            else artists.mapIndexed { index, name -> LibraryArtistEntity(actualId, index, LibrarySortKeys.text(name), name) })
    }

    private suspend fun restoreDisplay(run: BackupRestoreRun, stage: SqliteBackupStage, revision: Long) {
        val display = database.displayDao()
        if (run.mode == BackupMergeMode.REPLACE_SELECTED) {
            for (table in listOf("display_names", "display_album_links", "display_artist_links")) {
                execute("UPDATE $table SET validTo=? WHERE validTo IS NULL", BackupCell.Integer(revision - 1L))
            }
        }
        each(stage, BackupRecordKind.DISPLAY_GROUP) { row ->
            val natural = text("SELECT entityId FROM display_groups WHERE kind=? AND groupKey=?", BackupCell.Text(row.text("kind")), BackupCell.Text(row.text("groupKey")))
            val id = natural ?: if (display.group(row.key) == null) row.key else "restored-display-" + backupStableId(run.archiveSha256, row.key)
            stage.setActualId(BackupRecordKind.DISPLAY_GROUP, row.key, id)
            if (display.group(id) == null) {
                val name = row.optionalText("originalName")
                display.insertGroup(DisplayGroupEntity(id, row.text("kind"), row.text("groupKey"), name, row.optionalText("originalSubtitle"),
                    LibrarySortKeys.text(name), LibrarySortKeys.section(name), name?.let(LibrarySortKeys::normalize).orEmpty()))
            }
        }
        each(stage, BackupRecordKind.DISPLAY_NAME) { row ->
            val id = stage.actualId(BackupRecordKind.DISPLAY_GROUP, row.key)
            if (run.mode == BackupMergeMode.MERGE_KEEP_CURRENT && display.currentName(id) != null) return@each
            val name = row.text("displayName")
            display.closeName(id, revision)
            display.insertName(DisplayNameEntity(stableUuid(run.id, "display-name", row.key), id, name,
                LibrarySortKeys.text(name), LibrarySortKeys.section(name), LibrarySortKeys.normalize(name), revision, null))
            display.markEdited(id, revision)
        }
        each(stage, BackupRecordKind.DISPLAY_ALBUM_LINK) { row ->
            val trackId = stage.actualId(BackupRecordKind.TRACK, row.key)
            if (run.mode == BackupMergeMode.MERGE_KEEP_CURRENT && display.currentAlbum(trackId) != null) return@each
            val groupId = stage.actualId(BackupRecordKind.DISPLAY_GROUP, row.text("groupId"))
            display.closeAlbum(trackId, revision)
            display.insertAlbum(DisplayAlbumLinkEntity(stableUuid(run.id, "album-link", row.key), trackId, groupId, revision, null))
            display.markEdited(groupId, revision)
        }
        each(stage, BackupRecordKind.DISPLAY_ARTIST_LINK) { row ->
            val trackId = stage.actualId(BackupRecordKind.TRACK, row.text("trackId"))
            val groupId = stage.actualId(BackupRecordKind.DISPLAY_GROUP, row.text("groupId"))
            if (run.mode == BackupMergeMode.MERGE_KEEP_CURRENT && display.currentArtist(trackId, groupId) != null) return@each
            display.closeArtist(trackId, groupId, revision)
            display.insertArtist(DisplayArtistLinkEntity(stableUuid(run.id, "artist-link", row.key), trackId, groupId,
                row.flag("included"), revision, null))
            display.markEdited(groupId, revision)
        }
    }

    private suspend fun restorePlaylists(run: BackupRestoreRun, stage: SqliteBackupStage): Long {
        val playlists = database.playlistDao()
        val initial = playlists.meta() ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        val revision = increment(initial.revision)
        var nextSequence = initial.nextPlaylistSequence
        var missing = 0L
        if (nextSequence < 1L) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        createSelection("backup_playlists")
        try {
            for ((headKind, memberKind) in listOf(BackupRecordKind.FAVORITES_HEAD to BackupRecordKind.FAVORITES_MEMBER,
                    BackupRecordKind.PLAYLIST_HEAD to BackupRecordKind.PLAYLIST_MEMBER)) {
                if (headKind.scope !in run.scopes) continue
                each(stage, headKind) { row ->
                    select("backup_playlists", row.key)
                    val oldHead = playlists.head(row.key)
                    if (oldHead?.deleted == true && run.mode == BackupMergeMode.MERGE_KEEP_CURRENT) return@each
                    val old = oldHead?.activeGenerationId?.let { playlists.generation(it) }
                    val merge = run.mode == BackupMergeMode.MERGE_KEEP_CURRENT && oldHead?.deleted == false && old != null
                    val generationId = stableUuid(run.id, "playlist-generation", row.key)
                    val name = if (merge) checkNotNull(old).name else row.text("name")
                    val sourceHint = row.optionalText("bindingSourceId")
                    val nodeHint = row.optionalText("bindingNodeId")
                    val source = if (merge) old?.bindingSourceId else sourceHint?.let {
                        stage.binding(BackupBindingFamily.SOURCE, it)?.actualId ?: unresolvedBinding(run, "source", it)
                    }
                    val node = if (merge) old?.bindingNodeId else nodeHint?.let {
                        stage.binding(BackupBindingFamily.NODE, it)?.actualId ?: unresolvedBinding(run, "node", it)
                    }
                    val generation = PlaylistGenerationEntity(generationId, row.key, generationId,
                        stableUuid(run.id, "playlist-operation", row.key), backupStableId(run.archiveSha256, row.key), "STAGING",
                        increment(oldHead?.revision ?: 0L), name, playlistNameKey(name), if (merge) checkNotNull(old).kind else row.text("kind"),
                        if (merge) checkNotNull(old).createdEpochMs else row.long("createdEpochMs"),
                        if (merge) maxOf(checkNotNull(old).updatedEpochMs, row.long("updatedEpochMs")) else row.long("updatedEpochMs"),
                        0L, null, null, run.id, source, node,
                        if (merge) old?.bindingSubtree else row.optionalFlag("bindingSubtree"))
                    playlists.insertGeneration(generation)
                    var count = 0L
                    if (merge) {
                        var first = 0L
                        while (true) {
                            val page = playlists.membersPage(checkNotNull(old).dataGenerationId, first, PlaylistLimits.PAGE_SIZE)
                            if (page.isEmpty()) break
                            playlists.insertMembers(page.map { it.copy(generationId = generationId) })
                            count = add(count, page.size.toLong())
                            first = increment(page.last().ordinal)
                            stage.checkActive()
                        }
                        if (count != checkNotNull(old).memberCount) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    }
                    var afterOrdinal: Long? = null
                    while (true) {
                        val page = stage.orderedPage(memberKind, "playlist", row.key, afterOrdinal)
                        if (page.isEmpty()) break
                        val additions = ArrayList<PlaylistMemberEntity>(page.size)
                        val referenceIds = HashSet<String>()
                        val memberIds = HashSet<String>()
                        for (member in page) {
                            stage.checkActive()
                            val reference = playlistReference(stage, member)
                            val candidate = reference.record(generationId, member.text("memberId"), count)
                            if (playlists.memberByReference(generationId, candidate.referenceKey) != null ||
                                !referenceIds.add(candidate.referenceKey)) continue
                            val memberId = if (playlists.member(generationId, candidate.memberId) == null && candidate.memberId !in memberIds) candidate.memberId
                                else stableUuid(run.archiveSha256, row.key, candidate.memberId, candidate.referenceKey)
                            if (!memberIds.add(memberId)) throw BackupException(BackupIssue.DUPLICATE_RECORD)
                            additions += candidate.copy(memberId = memberId)
                            if (reference.trackKey == null || dao.currentTrack(checkNotNull(reference.trackKey).trackId) == null) missing = increment(missing)
                            count = increment(count)
                        }
                        if (additions.isNotEmpty()) playlists.insertMembers(additions)
                        afterOrdinal = page.last().long("ordinal")
                        stage.checkActive()
                    }
                    if (playlists.seal(generationId, count) != 1 || playlists.publish(generationId, revision) != 1) {
                        throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    }
                    old?.let { playlists.closeGeneration(it.generationId, revision - 1L) }
                    if (oldHead == null) {
                        playlists.insertHead(PlaylistHeadEntity(row.key, generationId, generation.revision, nextSequence))
                        nextSequence = increment(nextSequence)
                    } else execute("UPDATE playlist_heads SET activeGenerationId=?,revision=?,deleted=0 WHERE playlistId=?",
                        BackupCell.Text(generationId), BackupCell.Integer(generation.revision), BackupCell.Text(row.key))
                }
            }
            if (run.mode == BackupMergeMode.REPLACE_SELECTED && BackupScope.PLAYLISTS in run.scopes) {
                eachId("SELECT playlistId FROM playlist_heads WHERE deleted=0 AND playlistId!='system:favorites' AND playlistId NOT IN (SELECT id FROM temp.backup_playlists) AND (? IS NULL OR playlistId>?) ORDER BY playlistId LIMIT 100") { id ->
                    val head = playlists.head(id) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    head.activeGenerationId?.let { playlists.closeGeneration(it, revision - 1L) }
                    execute("UPDATE playlist_heads SET activeGenerationId=NULL,deleted=1,revision=? WHERE playlistId=?",
                        BackupCell.Integer(increment(head.revision)), BackupCell.Text(id))
                }
            }
            if (playlists.updateMeta(initial.copy(revision = revision, nextPlaylistSequence = nextSequence)) != 1) {
                throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
            return missing
        } finally { execute("DROP TABLE IF EXISTS temp.backup_playlists") }
    }

    private fun playlistReference(stage: SqliteBackupStage, row: BackupRecord): PlaylistReference = PlaylistReference(
        row.optionalText("providerId")?.let { TrackKey(it, stage.actualId(BackupRecordKind.TRACK, row.text("trackId"))) },
        row.optionalText("placeholderId"), row.optionalText("issue")?.let(PlaylistReferenceIssue::valueOf),
        row.optionalText("titleHint"), row.optionalText("artistHint"), row.optionalText("albumHint"),
        row.optionalText("relativePathHint"), row.optionalText("descriptionHint"), row.optionalLong("durationMs"),
        cueReference = row.portableCueReference())

    private suspend fun restoreQueue(run: BackupRestoreRun, stage: SqliteBackupStage): Long {
        val queue = database.queueDao()
        val old = queue.head() ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        if (run.mode == BackupMergeMode.MERGE_KEEP_CURRENT && old.activeGenerationId != null) return 0L
        val meta = stage.row(BackupRecordKind.QUEUE_META, "queue") ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
        val generationId = stableUuid(run.id, "queue")
        val revision = increment(old.queueRevision)
        val currentId = meta.optionalText("currentEntryId")
        val current = currentId?.let { stage.row(BackupRecordKind.QUEUE_ENTRY, it) ?: throw BackupException(BackupIssue.INVALID_REFERENCE) }
        queue.insertGeneration(QueueGenerationEntity(generationId, stableUuid(run.id, "queue-operation"), old.activeGenerationId,
            old.queueRevision, stableUuid(run.archiveSha256, "queue-view"), 0L, meta.long("totalCount"), meta.flag("shuffle"),
            meta.text("repeatMode"), meta.optionalLong("cycleLimit")?.toInt(), current?.long("originalOrder") ?: 0L,
            meta.long("positionMs"), true, orderPolicy = "PRESERVE_SUPPLIED"))
        var missing = 0L
        var count = 0L
        var after: String? = null
        while (true) {
            val page = stage.pageNow(BackupRecordKind.QUEUE_ENTRY, after)
            if (page.isEmpty()) break
            val entries = page.map { row ->
                stage.checkActive()
                val local = row.text("providerId") == TrackKey.LOCAL_PROVIDER
                val binding = if (local) stage.binding(BackupBindingFamily.QUEUE, row.key) else null
                val fence = binding?.takeIf { it.verified }?.record()
                if (local && fence == null) missing = increment(missing)
                QueueEntryEntity(generationId, row.key, row.text("providerId"),
                    if (local) stage.actualId(BackupRecordKind.TRACK, row.text("trackId")) else row.text("trackId"), row.long("originalOrder"),
                    row.long("playbackOrder"), row.optionalText("displayName"), row.optionalLong("durationMs"), null,
                    binding?.takeIf { it.verified }?.actualId, fence?.text("uri"), fence?.text("grantUri"),
                    fence?.long("grantFlags")?.toInt(), fence?.text("access"), fence?.long("contentRevision"),
                    cueReference = (fence?.values?.get("cueReference") as? BackupCell.Text)?.value ?:
                        row.portableCueReference()?.let(CueReferenceCodec::encode))
            }
            queue.insertEntries(entries)
            count = add(count, entries.size.toLong())
            after = page.last().key
        }
        if (count != meta.long("totalCount") || queue.advanceStaging(generationId, 0L, count) != 1 || queue.seal(generationId) != 1) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        val position = current?.optionalLong("durationMs")?.let { meta.long("positionMs").coerceAtMost(it) } ?: meta.long("positionMs")
        if (queue.publish(generationId, old.activeGenerationId, old.queueRevision, revision, currentId, position,
                meta.long("completedCycles").toInt()) != 1 || queue.markPublished(generationId, revision) != 1) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        return missing
    }

    private suspend fun restoreHistory(run: BackupRestoreRun, stage: SqliteBackupStage, journal: BackupRestoreJournal,
        write: LibraryWrite): Map<String, BackupCell> {
        val listening = database.listeningDao()
        val old = listening.meta() ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        if (old.rulesVersion != ListeningLimits.RULES_VERSION || old.statisticsEpoch != write.statisticsEpoch ||
            old.nextHistorySequence < 1L || old.historyFloorSequence < 1L || old.historyFloorSequence > old.nextHistorySequence) {
            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
        }
        val merge = run.mode == BackupMergeMode.MERGE_KEEP_CURRENT
        val appendHistory = !merge || !journal.historyDomainMerged(run.archiveSha256, old.historyEpoch)
        val appendStatistics = !merge || !journal.statisticsDomainMerged(run.archiveSha256, old.statisticsEpoch)
        if (!appendHistory && !appendStatistics) return historyBindings(old, old, false, false)
        val next = old.copy(historyEpoch = increment(old.historyEpoch), statisticsEpoch = increment(old.statisticsEpoch))
        if (merge) {
            var time: Long? = null
            var provider: String? = null
            var track: String? = null
            while (true) {
                val page = listening.trackPage(old.statisticsEpoch, time, provider, track, ListeningLimits.PAGE_SIZE)
                if (page.isEmpty()) break
                for (row in page) listening.putTrack(row.copy(statisticsEpoch = next.statisticsEpoch))
                page.last().let { time = it.listeningMs; provider = it.providerId; track = it.trackId }
                stage.checkActive()
            }
            var date: String? = null
            var zone: String? = null
            while (true) {
                val page = listening.dayPage(old.statisticsEpoch, date, zone, ListeningLimits.PAGE_SIZE)
                if (page.isEmpty()) break
                for (row in page) listening.putDay(row.copy(statisticsEpoch = next.statisticsEpoch))
                page.last().let { date = it.localDate; zone = it.zoneId }
                stage.checkActive()
            }
        }
        if (appendStatistics) {
            each(stage, BackupRecordKind.TRACK_TOTAL) { row ->
                val trackId = if (row.text("providerId") == TrackKey.LOCAL_PROVIDER) stage.actualId(BackupRecordKind.TRACK, row.text("trackId")) else row.text("trackId")
                val previous = if (merge) listening.track(next.statisticsEpoch, row.text("providerId"), trackId) else null
                listening.putTrack(ListeningTrackEntity(next.statisticsEpoch, row.text("providerId"), trackId,
                    add(previous?.playCount ?: 0L, row.long("playCount")), add(previous?.listeningMs ?: 0L, row.long("listeningMs")),
                    listOfNotNull(previous?.lastProgressEpochMs, row.optionalLong("lastProgressEpochMs")).maxOrNull(),
                    previous?.displayName ?: row.optionalText("displayName")))
            }
            each(stage, BackupRecordKind.DAY_TOTAL) { row ->
                val previous = if (merge) listening.day(next.statisticsEpoch, row.text("localDate"), row.text("zoneId")) else null
                listening.putDay(ListeningDayEntity(next.statisticsEpoch, row.text("localDate"), row.text("zoneId"),
                    add(previous?.playCount ?: 0L, row.long("playCount")), add(previous?.listeningMs ?: 0L, row.long("listeningMs"))))
            }
        }
        // The ledger is never deleted: queued callbacks keep their old epochs and cannot count again.
        execute("DROP TABLE IF EXISTS temp.backup_history")
        execute("CREATE TEMP TABLE backup_history AS SELECT historyId,roundId,providerId,trackId,displayName,firstProgressEpochMs,firstProgressZoneId,lastProgressEpochMs,listeningMs,knownDurationMs FROM listening_history WHERE historyEpoch=? AND historyId>=?",
            BackupCell.Integer(if (merge) old.historyEpoch else -1L), BackupCell.Integer(old.historyFloorSequence))
        try {
            execute("DELETE FROM listening_history")
            if (merge) execute("INSERT INTO listening_history(historyEpoch,historyId,roundId,providerId,trackId,displayName,firstProgressEpochMs,firstProgressZoneId,lastProgressEpochMs,listeningMs,knownDurationMs) SELECT ?,historyId,roundId,providerId,trackId,displayName,firstProgressEpochMs,firstProgressZoneId,lastProgressEpochMs,listeningMs,knownDurationMs FROM temp.backup_history",
                BackupCell.Integer(next.historyEpoch))
        } finally { execute("DROP TABLE IF EXISTS temp.backup_history") }
        var sequence = old.nextHistorySequence
        if (appendHistory) {
            var after: Long? = null
            while (true) {
                val page = stage.orderedPage(BackupRecordKind.HISTORY_ENTRY, "history", "history", after)
                if (page.isEmpty()) break
                for (row in page) {
                    val roundId = stableUuid(run.archiveSha256, "closed-history", row.key)
                    if (scalar("SELECT EXISTS(SELECT 1 FROM listening_history WHERE roundId=?)", BackupCell.Text(roundId)) == 1L) continue
                    val trackId = if (row.text("providerId") == TrackKey.LOCAL_PROVIDER) stage.actualId(BackupRecordKind.TRACK, row.text("trackId")) else row.text("trackId")
                    val retained = listening.round(roundId)
                    if (retained != null && (retained.rulesVersion != 0 || retained.providerId != row.text("providerId") ||
                            retained.trackId != trackId || retained.totalListeningMs != row.long("listeningMs") ||
                            retained.firstProgressEpochMs != row.long("firstProgressEpochMs") ||
                            retained.lastProgressEpochMs != row.long("lastProgressEpochMs"))) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    if (retained == null) listening.putRound(ListeningRoundEntity(roundId, row.text("providerId"), trackId,
                        row.optionalText("displayName"), next.historyEpoch, next.statisticsEpoch, 0,
                        row.long("firstProgressEpochMs"), 0L, row.text("firstProgressZoneId"), row.long("lastProgressEpochMs"), 0L,
                        row.text("firstProgressZoneId"), row.long("lastProgressEpochMs"), row.long("listeningMs"),
                        row.optionalLong("knownDurationMs"), true, null, null, sequence))
                    listening.putHistory(ListeningHistoryEntity(next.historyEpoch, sequence, roundId, row.text("providerId"), trackId,
                        row.optionalText("displayName"), row.long("firstProgressEpochMs"), row.text("firstProgressZoneId"),
                        row.long("lastProgressEpochMs"), row.long("listeningMs"), row.optionalLong("knownDurationMs")))
                    sequence = increment(sequence)
                }
                after = page.last().long("ordinal")
                stage.checkActive()
            }
        }
        val meta = stage.row(BackupRecordKind.HISTORY_META, "history") ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
        val totalCount = if (appendStatistics) add(if (merge) old.totalPlayCount else 0L, meta.long("totalPlayCount")) else old.totalPlayCount
        val totalMs = if (appendStatistics) add(if (merge) old.totalListeningMs else 0L, meta.long("totalListeningMs")) else old.totalListeningMs
        val floor = maxOf(if (merge) old.historyFloorSequence else old.nextHistorySequence,
            (sequence - ListeningLimits.HISTORY_ITEMS).coerceAtLeast(1L))
        val final = next.copy(nextHistorySequence = sequence, historyFloorSequence = floor, totalPlayCount = totalCount, totalListeningMs = totalMs)
        listening.updateMeta(final)
        write.statisticsEpoch = final.statisticsEpoch
        write.changed = true
        eachId("SELECT trackId FROM local_track_versions WHERE validTo IS NULL AND (? IS NULL OR trackId>?) ORDER BY trackId LIMIT 100") { id ->
            val oldTrack = dao.currentTrack(id) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            val totals = listening.track(final.statisticsEpoch, TrackKey.LOCAL_PROVIDER, id)
            putTrackVersion(oldTrack.copy(versionId = stableUuid(run.id, "statistics-track", id), validFrom = write.revision,
                validTo = null, statisticsEpoch = final.statisticsEpoch, playCount = totals?.playCount ?: 0L,
                listeningMs = totals?.listeningMs ?: 0L), oldTrack)
        }
        return historyBindings(old, final, appendHistory, appendStatistics)
    }

    private fun historyBindings(before: ListeningMetaEntity, after: ListeningMetaEntity, historyApplied: Boolean,
        statisticsApplied: Boolean): Map<String, BackupCell> = mapOf(
        "beforeHistoryEpoch" to BackupCell.Integer(before.historyEpoch), "beforeStatisticsEpoch" to BackupCell.Integer(before.statisticsEpoch),
        "historyEpoch" to BackupCell.Integer(after.historyEpoch), "statisticsEpoch" to BackupCell.Integer(after.statisticsEpoch),
        "historyMergeApplied" to BackupCell.Flag(historyApplied), "statisticsMergeApplied" to BackupCell.Flag(statisticsApplied))

    private suspend fun restoreLyrics(run: BackupRestoreRun, stage: SqliteBackupStage, journal: BackupRestoreJournal,
        write: LibraryWrite): Long {
        val lyrics = database.lyricsDao()
        var missing = 0L
        createSelection("backup_lyrics")
        try {
            each(stage, BackupRecordKind.LYRICS_TRACK) { row ->
                val key = TrackKey(TrackKey.LOCAL_PROVIDER, stage.actualId(BackupRecordKind.TRACK, row.key))
                select("backup_lyrics", key.trackId)
                val old = lyrics.row(key.trackId)?.decode(key)
                if (old != null && run.mode == BackupMergeMode.MERGE_KEEP_CURRENT) return@each
                if (dao.currentTrack(key.trackId) == null) missing = increment(missing)
                if (dao.track(key.trackId) == null) {
                    val sequence = write.nextAdded
                    write.nextAdded = increment(write.nextAdded)
                    dao.insertTrack(LibraryTrackEntity(key.trackId, key.providerId, 0L, sequence))
                    write.changed = true
                }
                val linkedMetadata = stage.row(BackupRecordKind.METADATA_LYRICS, row.key)?.takeIf { it.flag("primaryLinked") }
                val oldMetadata = linkedMetadata?.let { metadataBefore(run, key, MetadataPurpose.LYRICS) }
                val appliesMetadata = run.mode == BackupMergeMode.REPLACE_SELECTED || oldMetadata == null ||
                    oldMetadata.revision == 0L && oldMetadata.sha256 == null
                val linkedPrimary = linkedMetadata?.takeIf { appliesMetadata }?.let { metadata ->
                    val resourceId = checkNotNull(BackupMetadataAssociations.resourceId(metadata))
                    val assetId = restoredMetadataAsset(journal, resourceId, MetadataPurpose.LYRICS, metadata.long("bytes"))
                    val restored = restoredLyrics(journal, resourceId, LyricsEncodingChoice.UTF8.name)
                    if (assetId == null || restored == null) null else {
                        if (restored.uri != "content://$packageName.owned-lyrics/$assetId" ||
                            restored.confirmedContentSha256 != metadata.text("sha256")) throw BackupException(BackupIssue.INVALID_REFERENCE)
                        restored.copy(sourceId = "metadata-v1:$assetId")
                    }
                }
                fun association(prefix: String, previous: LyricsAssociation?): LyricsAssociation? {
                    val hash = row.optionalText(prefix + "Sha256") ?: return null
                    if (prefix == "primary" && linkedPrimary != null) return linkedPrimary
                    val resourceId = row.optionalText(prefix + "ResourceId")
                    if (BackupScope.RESOURCES in run.scopes && resourceId != null) restoredLyrics(journal, resourceId, row.text(prefix + "Encoding"))?.let { return it }
                    if (previous != null && previous.confirmedContentSha256 == hash &&
                        row.optionalText(prefix + "UriHint")?.let { it == previous.uri } != false &&
                        row.optionalText(prefix + "DocumentIdHint")?.let { it == previous.documentId } != false &&
                        row.optionalText(prefix + "SourceIdHint")?.let { it == previous.sourceId } != false &&
                        previous.encoding.name == row.text(prefix + "Encoding")) return previous
                    missing = increment(missing)
                    return previous
                }
                val next = LyricsTrackPreferences(key, increment(old?.revision ?: 0L), association("primary", old?.primary),
                    association("translation", old?.translation), row.long("userDelayMs").toInt())
                if (old == null) {
                    if (lyrics.insert(next.entity()) == -1L) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                } else if (lyrics.update(old.revision, next.entity()) != 1) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
            if (run.mode == BackupMergeMode.REPLACE_SELECTED) eachId(
                "SELECT trackId FROM lyrics_track_preferences WHERE trackId NOT IN (SELECT id FROM temp.backup_lyrics) AND (? IS NULL OR trackId>?) ORDER BY trackId LIMIT 100") { id ->
                val key = TrackKey(TrackKey.LOCAL_PROVIDER, id)
                val old = lyrics.row(id)?.decode(key) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                if (lyrics.update(old.revision, LyricsTrackPreferences(key, increment(old.revision), null, null, 0).entity()) != 1) {
                    throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                }
            }
            return missing
        } finally { execute("DROP TABLE IF EXISTS temp.backup_lyrics") }
    }

    /** FK dependants are removed only inside the real writer; revisions survive in its private temporary rows. */
    private suspend fun <T> withMetadataReplacement(run: BackupRestoreRun, stage: SqliteBackupStage, action: suspend () -> T): T {
        val purposes = MetadataPurpose.entries.filter { BackupMetadataAssociations.kind(it).scope in run.scopes }
        for (purpose in purposes) each(stage, BackupMetadataAssociations.kind(purpose)) { row ->
            BackupMetadataAssociations.decode(row)
            val old = dao.currentTrack(row.key)
            val track = dao.track(row.key)
            if (track != null && track.providerId != TrackKey.LOCAL_PROVIDER) throw BackupException(BackupIssue.CONFLICT)
            val incomingCue = row.portableCueReference()
            val currentCue = old?.cueReference?.let { native ->
                try { CueReferenceCodec.decode(native) }
                catch (failure: IllegalArgumentException) { throw BackupException(BackupIssue.RECOVERY_REQUIRED, failure) }
            }
            if (incomingCue == null && currentCue != null || incomingCue != null && old != null && currentCue == null) {
                throw BackupException(BackupIssue.CONFLICT)
            }
            if (incomingCue != null && currentCue != null) {
                val matches = if (currentCue.isBound) currentCueMatchesPortableHint(currentCue, incomingCue) else
                    CueReferenceCodec.encode(currentCue.portable()) == CueReferenceCodec.encode(incomingCue)
                if (!matches) throw BackupException(BackupIssue.CONFLICT)
            }
        }
        if (run.mode != BackupMergeMode.REPLACE_SELECTED || purposes.isEmpty()) return action()
        execute("DROP TABLE IF EXISTS temp.backup_metadata_before")
        execute("CREATE TEMP TABLE backup_metadata_before AS SELECT trackId,purpose,schemaVersion,revision,assetId,sha256,byteCount,provenance FROM local_metadata_associations WHERE 0")
        try {
            execute("CREATE UNIQUE INDEX temp.backup_metadata_before_key ON backup_metadata_before(purpose,trackId)")
            for (purpose in purposes) {
                execute("INSERT INTO temp.backup_metadata_before SELECT trackId,purpose,schemaVersion,revision,assetId,sha256,byteCount,provenance FROM local_metadata_associations WHERE purpose=?",
                    BackupCell.Text(purpose.name))
                execute("DELETE FROM local_metadata_associations WHERE purpose=?", BackupCell.Text(purpose.name))
            }
            return action()
        } finally { withContext(NonCancellable) { execute("DROP TABLE IF EXISTS temp.backup_metadata_before") } }
    }

    private suspend fun restoreMetadata(run: BackupRestoreRun, stage: SqliteBackupStage, journal: BackupRestoreJournal,
        write: LibraryWrite): Long {
        val metadata = database.localMetadataDao()
        var missing = 0L
        for (purpose in MetadataPurpose.entries) {
            val kind = BackupMetadataAssociations.kind(purpose)
            if (kind.scope !in run.scopes) continue
            val selection = if (purpose == MetadataPurpose.LYRICS) "backup_metadata_lyrics" else "backup_metadata_artwork"
            createSelection(selection)
            try {
                each(stage, kind) { row ->
                    val actualId = stage.actualId(BackupRecordKind.TRACK, row.key)
                    if (actualId != row.key) throw BackupException(BackupIssue.INVALID_REFERENCE)
                    val key = TrackKey(TrackKey.LOCAL_PROVIDER, actualId)
                    select(selection, key.trackId)
                    val old = metadataBefore(run, key, purpose)
                    if (run.mode == BackupMergeMode.MERGE_KEEP_CURRENT && old != null &&
                        (old.revision != 0L || old.sha256 != null)) return@each
                    if (dao.currentTrack(key.trackId) == null) missing = increment(missing)
                    if (dao.track(key.trackId) == null) {
                        val sequence = write.nextAdded
                        write.nextAdded = increment(write.nextAdded)
                        dao.insertTrack(LibraryTrackEntity(key.trackId, key.providerId, 0L, sequence))
                        write.changed = true
                    }
                    val incoming = BackupMetadataAssociations.decode(row, trackKey = key)
                    val assetId = incoming.sha256?.let {
                        restoredMetadataAsset(journal, checkNotNull(BackupMetadataAssociations.resourceId(row)),
                            purpose, checkNotNull(incoming.byteCount))
                    }
                    if (incoming.sha256 != null && assetId == null) missing = increment(missing)
                    val revision = nextMetadataRevision(maxOf(old?.revision ?: 0L, incoming.revision))
                    val next = incoming.copy(revision = revision, assetId = assetId)
                    if (run.mode == BackupMergeMode.REPLACE_SELECTED || old == null) {
                        if (metadata.insert(next.entity()) == -1L) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    } else if (metadata.updateIfRevision(key.trackId, purpose.name, old.revision, next.revision,
                            next.assetId, next.sha256, next.byteCount, next.provenance?.encode()) != 1) {
                        throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    }
                }
                if (run.mode == BackupMergeMode.REPLACE_SELECTED) {
                    val purposeName = if (purpose == MetadataPurpose.LYRICS) "LYRICS" else "ARTWORK"
                    eachId("SELECT trackId FROM temp.backup_metadata_before WHERE purpose='$purposeName' " +
                        "AND trackId NOT IN (SELECT id FROM temp.$selection) AND (? IS NULL OR trackId>?) ORDER BY trackId COLLATE BINARY LIMIT 100") { id ->
                        val key = TrackKey(TrackKey.LOCAL_PROVIDER, id)
                        val old = metadataBefore(run, key, purpose) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                        if (metadata.insert(MetadataAssociation(key, purpose, nextMetadataRevision(old.revision)).entity()) == -1L) {
                            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                        }
                    }
                }
            } finally { withContext(NonCancellable) { execute("DROP TABLE IF EXISTS temp.$selection") } }
        }
        return missing
    }

    private suspend fun metadataBefore(run: BackupRestoreRun, key: TrackKey, purpose: MetadataPurpose): MetadataAssociation? {
        val row = if (run.mode != BackupMergeMode.REPLACE_SELECTED) database.localMetadataDao().row(key.trackId, purpose.name) else
            database.useWriterConnection { writer -> writer.usePrepared(
                "SELECT CASE WHEN typeof(schemaVersion)='integer' AND typeof(revision)='integer' AND " +
                    "typeof(assetId) IN ('text','null') AND typeof(sha256) IN ('text','null') AND " +
                    "typeof(byteCount) IN ('integer','null') AND typeof(provenance) IN ('text','null') THEN 1 ELSE 0 END," +
                    "schemaVersion,revision,substr(assetId,1,37),substr(sha256,1,65),byteCount," +
                    "substr(provenance,1,${MetadataProvenance.PROVENANCE_BYTES + 1}) FROM temp.backup_metadata_before " +
                    "WHERE trackId=? AND purpose=? LIMIT 1") { statement ->
                statement.bindText(1, key.trackId); statement.bindText(2, purpose.name)
                if (!statement.step()) null else {
                    if (statement.getLong(0) != 1L) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    LocalMetadataAssociationRow(true, statement.getLong(1), statement.getLong(2),
                        if (statement.isNull(3)) null else statement.getText(3),
                        if (statement.isNull(4)) null else statement.getText(4),
                        if (statement.isNull(5)) null else statement.getLong(5),
                        if (statement.isNull(6)) null else statement.getText(6))
                }
            } }
        return try { row?.decode(key, purpose) }
        catch (failure: MetadataStoreException) { throw BackupException(BackupIssue.RECOVERY_REQUIRED, failure) }
    }

    private fun nextMetadataRevision(revision: Long): Long {
        if (revision == Long.MAX_VALUE) throw BackupException(BackupIssue.CONFLICT)
        return revision + 1L
    }

    private fun requiredBinding(stage: SqliteBackupStage, family: BackupBindingFamily, incoming: String): BackupStoredBinding =
        stage.binding(family, incoming) ?: throw BackupException(BackupIssue.INVALID_REFERENCE)

    /** Hidden stable rows make missing references relinkable without replacing unselected library data. */
    private suspend fun materializeMissingReferences(run: BackupRestoreRun, stage: SqliteBackupStage, journal: BackupRestoreJournal,
        write: LibraryWrite): Long {
        var after: String? = null
        var missingLibraryTracks = 0L
        while (true) {
            val page = journal.pendingRelinkPage(after)
            if (page.isEmpty()) break
            for (hint in page) {
                stage.checkActive()
                if (hint.record.key != syntheticBackupRelinkHint(run.archiveSha256, hint.trackId).record.key) continue
                if (dao.track(hint.trackId) == null) {
                    dao.insertTrack(LibraryTrackEntity(hint.trackId, TrackKey.LOCAL_PROVIDER, stage.header.createdEpochMs, write.nextAdded))
                    write.nextAdded = increment(write.nextAdded); write.changed = true
                }
                val cue = hint.cueReference
                if (cue != null && dao.currentTrack(hint.trackId) == null) {
                    val identityRow = dao.track(hint.trackId) ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    val next = library.emptyTrackVersion(identityRow, "CUE ${cue.identity.trackNumber}", write.revision).copy(
                        member = false, trackNumber = cue.identity.trackNumber, durationMs = cue.identity.durationUs / 1000L,
                        cueReference = CueReferenceCodec.encode(cue.portable()))
                    dao.insertTrackVersion(next)
                    dao.insertArtists(listOf(LibraryArtistEntity(next.versionId, 0, LibrarySortKeys.text(null), null)))
                    write.changed = true
                }
                val identity = LibrarySortKeys.identity("BACKUP_UNVERIFIED", run.archiveSha256, hint.record.key)
                val location = dao.location(hint.locationId)
                if (location == null) {
                    val next = LibraryLocationEntity(hint.locationId, identity, 1L, null, null, null, null, null, false)
                    dao.putLocation(next)
                    dao.insertLocationVersion(LibraryLocationVersionEntity(stableUuid(run.id, "missing-location", hint.trackId),
                        next.locationId, next.contentRevision, null, null, null, null, write.revision, null))
                    write.changed = true
                } else if (location.identityKey != identity || location.verifiedContentToken != null) throw BackupException(BackupIssue.CONFLICT)
                if (scalar("SELECT EXISTS(SELECT 1 FROM local_track_locations WHERE trackId=? AND locationId=? AND validTo IS NULL)",
                        BackupCell.Text(hint.trackId), BackupCell.Text(hint.locationId)) == 0L) {
                    dao.insertRelation(LibraryTrackLocationEntity(stableUuid(run.id, "missing-relation", hint.trackId),
                        hint.trackId, hint.locationId, write.revision, null))
                    write.changed = true
                }
                if (BackupScope.LIBRARY in run.scopes && stage.row(BackupRecordKind.TRACK, hint.trackId) != null && !stage.hasAnyTrackLocation(hint.trackId)) {
                    missingLibraryTracks = increment(missingLibraryTracks)
                }
            }
            after = page.last().id
        }
        return missingLibraryTracks
    }
    private fun unresolvedBinding(run: BackupRestoreRun, family: String, id: String): String =
        "restored-$family-" + backupStableId(run.archiveSha256, family.uppercase(java.util.Locale.ROOT), id)
    private fun unresolvedUri(family: String, id: String): String = "content://$packageName.unresolved/$family/$id"

    private suspend fun createSelection(name: String) {
        require(name in SELECTIONS)
        execute("DROP TABLE IF EXISTS temp.$name")
        execute("CREATE TEMP TABLE $name(id TEXT NOT NULL PRIMARY KEY) WITHOUT ROWID")
    }
    private suspend fun select(name: String, id: String) {
        require(name in SELECTIONS)
        execute("INSERT OR IGNORE INTO temp.$name(id) VALUES(?)", BackupCell.Text(id))
    }
    private suspend fun each(stage: SqliteBackupStage, kind: BackupRecordKind, action: suspend (BackupRecord) -> Unit) {
        var after: String? = null
        while (true) {
            val page = stage.pageNow(kind, after)
            if (page.isEmpty()) break
            for (row in page) { stage.checkActive(); action(row) }
            after = page.last().key
        }
    }
    private suspend fun eachId(sql: String, action: suspend (String) -> Unit) {
        var after: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = database.useWriterConnection { writer -> writer.usePrepared(sql) { statement ->
                statement.bindNullableText(1, after); statement.bindNullableText(2, after)
                buildList { while (statement.step()) add(statement.getText(0)) }
            } }
            if (page.isEmpty()) break
            for (id in page) action(id)
            after = page.last()
        }
    }
    private suspend fun execute(sql: String, vararg values: BackupCell) = database.useWriterConnection { writer -> writer.usePrepared(sql) {
        bind(it, values); it.step(); Unit
    } }
    private suspend fun scalar(sql: String, vararg values: BackupCell): Long = database.useWriterConnection { writer -> writer.usePrepared(sql) {
        bind(it, values); if (!it.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED); it.getLong(0)
    } }
    private suspend fun text(sql: String, vararg values: BackupCell): String? = database.useWriterConnection { writer -> writer.usePrepared(sql) {
        bind(it, values); if (!it.step() || it.isNull(0)) null else it.getText(0)
    } }
    private fun bind(statement: SQLiteStatement, values: Array<out BackupCell>) {
        values.forEachIndexed { index, value -> when (value) {
            BackupCell.Null -> statement.bindNull(index + 1)
            is BackupCell.Text -> statement.bindText(index + 1, value.value)
            is BackupCell.Integer -> statement.bindLong(index + 1, value.value)
            else -> throw BackupException(BackupIssue.INVALID_INPUT)
        } }
    }
    private fun increment(value: Long): Long = add(value, 1L)
    private fun add(left: Long, right: Long): Long = try { Math.addExact(left, right) }
    catch (failure: ArithmeticException) { throw BackupException(BackupIssue.STORAGE_FULL, failure) }
    companion object {
        private val SELECTIONS = setOf("backup_sources", "backup_tracks", "backup_existing_tracks", "backup_playlists", "backup_lyrics",
            "backup_metadata_lyrics", "backup_metadata_artwork")
    }
}

internal fun stableUuid(vararg fields: String): String = UUID.nameUUIDFromBytes(backupStableId(*fields).toByteArray(Charsets.US_ASCII)).toString()
