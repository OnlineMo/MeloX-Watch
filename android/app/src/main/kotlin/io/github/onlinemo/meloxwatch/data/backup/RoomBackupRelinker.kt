package io.github.onlinemo.meloxwatch.data.backup

import android.os.CancellationSignal
import android.os.SystemClock
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.library.LibraryArtistEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryCodec
import io.github.onlinemo.meloxwatch.data.library.LibrarySortKeys
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackLocationEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.metadata
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.record
import io.github.onlinemo.meloxwatch.data.playlists.playlistNameKey
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reassociation selects a current authorized library object; a backup URI is never an input route. */
internal class RoomBackupRelinker(
    private val barrier: BackupMutationBarrier,
    private val database: MeloXDatabase,
    private val library: RoomLocalLibraryRepository,
    private val sources: AndroidLocalSources,
    private val io: LocalProviderIo,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val dao = database.libraryDao()
    private var held: Held? = null
    private class Held(val model: BackupRelinkPreview, val hint: BackupRelinkHint, val descriptor: LocalReadDescriptor,
        val missingMetadataDigest: String, val targetMetadataDigest: String, val relationId: String, val expires: Long)

    suspend fun isMissing(hint: BackupRelinkHint): Boolean = scalar(
        "SELECT EXISTS(SELECT 1 FROM local_track_locations r JOIN local_locations l ON l.locationId=r.locationId WHERE r.trackId=? AND r.locationId=? AND r.validTo IS NULL AND l.identityKey=? AND l.verifiedContentToken IS NULL)",
        BackupCell.Text(hint.trackId), BackupCell.Text(hint.locationId),
        BackupCell.Text(LibrarySortKeys.identity("BACKUP_UNVERIFIED", hint.archiveSha256, hint.record.key))) == 1L

    suspend fun missing(journal: BackupRestoreJournal, after: String?, limit: Int): BackupRelinkPage {
        if (limit !in 1..100) throw BackupException(BackupIssue.INVALID_INPUT)
        val items = ArrayList<BackupMissingFile>()
        var cursor = after
        var more = false
        while (items.size < limit) {
            val page = physical { signal -> signal.throwIfCanceled(); journal.relinkPage(cursor) }
            if (page.isEmpty()) break
            for (hint in page) {
                currentCoroutineContext().ensureActive()
                cursor = hint.id
                if (isMissing(hint)) {
                    val track = dao.currentTrack(hint.trackId)
                    items += BackupMissingFile(hint.id, hint.trackId, track?.displayTitle ?: "Unavailable track")
                }
                if (items.size == limit) { more = true; break }
            }
            if (page.size < 100 && items.size < limit) break
        }
        return BackupRelinkPage(items, cursor.takeIf { more })
    }

    suspend fun preview(journal: BackupRestoreJournal, missingTrackId: String, target: TrackKey): BackupRelinkPreview {
        if (target.providerId != TrackKey.LOCAL_PROVIDER) throw BackupException(BackupIssue.INVALID_INPUT)
        discardAll()
        val hint = findHint(journal, missingTrackId) ?: throw BackupException(BackupIssue.STALE_PREVIEW)
        val original = dao.currentTrack(missingTrackId)
        val originalIdentity = dao.track(missingTrackId) ?: throw BackupException(BackupIssue.STALE_PREVIEW)
        val descriptor = library.resolveLocalRead(target)
        if (descriptor.location.locationId == hint.locationId) throw BackupException(BackupIssue.STALE_PREVIEW)
        validateDescriptor(descriptor)
        val actual = dao.currentTrack(target.trackId) ?: throw BackupException(BackupIssue.STALE_PREVIEW)
        val relation = text("SELECT relationId FROM local_track_locations WHERE trackId=? AND locationId=? AND validTo IS NULL LIMIT 1",
            BackupCell.Text(hint.trackId), BackupCell.Text(hint.locationId)) ?: throw BackupException(BackupIssue.STALE_PREVIEW)
        val expected = hint.record.optionalText("contentSha256")
        val hash = fullHash(descriptor)
        val match = if (expected == null || hash == null) BackupRelinkMatch.UNKNOWN_CONTENT
            else if (expected == hash) BackupRelinkMatch.VERIFIED_MATCH else BackupRelinkMatch.DIFFERENT_CONTENT
        val model = BackupRelinkPreview(UUID.randomUUID().toString(), missingTrackId, target, original?.displayTitle ?: "Unavailable track", actual.displayTitle, match)
        held = Held(model, hint, descriptor, missingMetadataDigest(originalIdentity, original), metadataDigest(actual), relation, Math.addExact(elapsedMs(), PREVIEW_MS))
        return model
    }

    suspend fun confirm(journal: BackupRestoreJournal, token: String, allowDifferent: Boolean): BackupRelinkReceipt {
        barrier.requireExclusiveOwner()
        val selected = held?.takeIf { it.model.token == token && elapsedMs() <= it.expires }
            ?: throw BackupException(BackupIssue.STALE_PREVIEW)
        if (selected.model.match != BackupRelinkMatch.VERIFIED_MATCH && !allowDifferent) throw BackupException(BackupIssue.CONFLICT)
        val hint = physical { signal -> signal.throwIfCanceled(); journal.relinkHint(selected.hint.id) }
            ?: throw BackupException(BackupIssue.STALE_PREVIEW)
        if (hint.trackId != selected.hint.trackId || hint.locationId != selected.hint.locationId ||
            hint.archiveSha256 != selected.hint.archiveSha256 || !hint.record.sameContent(selected.hint.record) || !isMissing(hint)) {
            throw BackupException(BackupIssue.STALE_PREVIEW)
        }
        val descriptor = library.resolveLocalRead(selected.model.target)
        if (descriptor != selected.descriptor) throw BackupException(BackupIssue.STALE_PREVIEW)
        validateDescriptor(descriptor)
        val result = library.libraryTransaction {
            val old = dao.currentTrack(hint.trackId)
            val originalIdentity = dao.track(hint.trackId) ?: throw BackupException(BackupIssue.STALE_PREVIEW)
            val target = dao.currentTrack(selected.model.target.trackId) ?: throw BackupException(BackupIssue.STALE_PREVIEW)
            if (missingMetadataDigest(originalIdentity, old) != selected.missingMetadataDigest || metadataDigest(target) != selected.targetMetadataDigest) {
                throw BackupException(BackupIssue.STALE_PREVIEW)
            }
            val source = dao.source(descriptor.source.spec.sourceId) ?: throw BackupException(BackupIssue.STALE_PREVIEW)
            val alias = dao.currentAlias(descriptor.aliasId) ?: throw BackupException(BackupIssue.STALE_PREVIEW)
            val location = dao.location(descriptor.location.locationId) ?: throw BackupException(BackupIssue.STALE_PREVIEW)
            if (source.state != LibrarySourceState.ACTIVE.name || source.revision != descriptor.source.revision ||
                source.authEpoch != descriptor.source.authEpoch || source.grantId != descriptor.source.grantId ||
                LibraryCodec.source(source.spec) != descriptor.source.spec || alias.sourceId != source.sourceId ||
                alias.uri != descriptor.location.uri || alias.locationId != descriptor.location.locationId ||
                alias.contentRevision != descriptor.location.contentRevision || location.contentRevision != descriptor.location.contentRevision) {
                throw BackupException(BackupIssue.STALE_PREVIEW)
            }
            if (writerScalar("SELECT COUNT(*) FROM local_track_locations WHERE relationId=? AND trackId=? AND locationId=? AND validTo IS NULL",
                    BackupCell.Text(selected.relationId), BackupCell.Text(hint.trackId), BackupCell.Text(hint.locationId)) != 1L) {
                throw BackupException(BackupIssue.STALE_PREVIEW)
            }
            val meta = dao.meta() ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            val revision = Math.addExact(meta.revision, 1L)
            execute("UPDATE local_track_locations SET validTo=? WHERE trackId=? AND locationId=? AND validTo IS NULL",
                BackupCell.Integer(revision - 1L), BackupCell.Text(hint.trackId), BackupCell.Text(hint.locationId))
            if (writerScalar("SELECT EXISTS(SELECT 1 FROM local_track_locations WHERE trackId=? AND locationId=? AND validTo IS NULL)",
                    BackupCell.Text(hint.trackId), BackupCell.Text(descriptor.location.locationId)) == 0L) {
                dao.insertRelation(LibraryTrackLocationEntity(UUID.randomUUID().toString(), hint.trackId,
                    descriptor.location.locationId, revision, null))
            }
            val totals = database.listeningDao().track(meta.statisticsEpoch, TrackKey.LOCAL_PROVIDER, hint.trackId)
            val next = target.copy(versionId = UUID.randomUUID().toString(), trackId = hint.trackId, validFrom = revision,
                validTo = null, member = old?.member ?: true, firstAddedEpochMs = originalIdentity.firstAddedEpochMs, firstAddedSequence = originalIdentity.firstAddedSequence,
                playCount = totals?.playCount ?: 0L, listeningMs = totals?.listeningMs ?: 0L, statisticsEpoch = meta.statisticsEpoch)
            dao.closeTrackVersion(hint.trackId, revision)
            dao.insertTrackVersion(next)
            val artists = LibraryCodec.strings(next.artists).ifEmpty { next.artist?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty() }
                .filter { it.isNotBlank() }.distinctBy(LibrarySortKeys::normalize)
            dao.insertArtists(if (artists.isEmpty()) listOf(LibraryArtistEntity(next.versionId, 0, LibrarySortKeys.text(null), null))
                else artists.mapIndexed { ordinal, name -> LibraryArtistEntity(next.versionId, ordinal, LibrarySortKeys.text(name), name) })
            val repaired = repairQueue(hint, descriptor, next.durationMs)
            if (dao.updateMeta(meta.copy(revision = revision)) != 1) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            execute("DELETE FROM library_views")
            BackupRelinkReceipt(hint.trackId, repaired)
        }
        // The relation is the authority. A crash before this projection ack merely hides the stale hint.
        withContext(NonCancellable) {
            physical { signal -> signal.throwIfCanceled(); journal.completeRelink(hint.id) }
            library.invalidateAfterBackupRestore()
            held = null
        }
        return result
    }

    fun discard(token: String) { if (held?.model?.token == token) held = null }
    fun discardAll() { held = null }

    private suspend fun repairQueue(hint: BackupRelinkHint, descriptor: LocalReadDescriptor, durationMs: Long?): Long {
        val queue = database.queueDao()
        val head = queue.head() ?: return 0L
        val generation = head.activeGenerationId ?: return 0L
        val count = writerScalar("SELECT COUNT(*) FROM queue_entries WHERE generationId=? AND providerId='local' AND trackId=? AND accountId IS NULL AND (locationId IS NULL OR locationId=?)",
            BackupCell.Text(generation), BackupCell.Text(hint.trackId), BackupCell.Text(hint.locationId))
        if (count == 0L) return 0L
        val revision = Math.addExact(head.queueRevision, 1L)
        val current = head.currentEntryId?.let { queue.entry(generation, it) }
        val affectsCurrent = current?.providerId == TrackKey.LOCAL_PROVIDER && current.trackId == hint.trackId &&
            (current.locationId == null || current.locationId == hint.locationId)
        execute("UPDATE queue_entries SET locationId=?,locationUri=?,grantUri=?,grantFlags=?,access=?,contentRevision=?,durationMs=? WHERE generationId=? AND providerId='local' AND trackId=? AND accountId IS NULL AND (locationId IS NULL OR locationId=?)",
            BackupCell.Text(descriptor.location.locationId), BackupCell.Text(descriptor.location.uri), BackupCell.Text(descriptor.location.grantUri),
            BackupCell.Integer(descriptor.location.grantFlags.toLong()), BackupCell.Text(descriptor.location.access.name), BackupCell.Integer(descriptor.location.contentRevision),
            backupNumber(durationMs), BackupCell.Text(generation), BackupCell.Text(hint.trackId), BackupCell.Text(hint.locationId))
        execute("UPDATE queue_generations SET committedQueueRevision=? WHERE generationId=? AND phase=2",
            BackupCell.Integer(revision), BackupCell.Text(generation))
        val position = if (affectsCurrent && durationMs != null) head.positionMs.coerceAtMost(durationMs) else head.positionMs
        execute("UPDATE queue_head SET queueRevision=?,checkpointRevision=0,positionMs=? WHERE id=1 AND activeGenerationId=?",
            BackupCell.Integer(revision), BackupCell.Integer(position), BackupCell.Text(generation))
        return count
    }

    suspend fun restoreImpacts(stage: SqliteBackupStage, scopes: Set<BackupScope>, mode: BackupMergeMode,
        inventory: BackupResourceInventory? = null, preferencesMissing: Long = 0L): List<BackupScopeImpact> {
        val impacts = ArrayList<BackupScopeImpact>()
        for (scope in scopes.sortedBy { it.ordinal }) {
            val kind = when (scope) {
                BackupScope.LIBRARY -> BackupRecordKind.TRACK
                BackupScope.FAVORITES -> BackupRecordKind.FAVORITES_MEMBER
                BackupScope.PLAYLISTS -> BackupRecordKind.PLAYLIST_HEAD
                BackupScope.QUEUE -> BackupRecordKind.QUEUE_ENTRY
                BackupScope.HISTORY -> BackupRecordKind.HISTORY_ENTRY
                BackupScope.PREFERENCES -> BackupRecordKind.PREFERENCE
                BackupScope.LYRICS -> BackupRecordKind.LYRICS_TRACK
                BackupScope.RESOURCES -> BackupRecordKind.RESOURCE
            }
            val existing = when (scope) {
                BackupScope.LIBRARY -> scalar("SELECT COUNT(*) FROM local_track_versions WHERE validTo IS NULL")
                BackupScope.FAVORITES -> scalar("SELECT COALESCE((SELECT g.memberCount FROM playlist_heads h JOIN playlist_generations g ON g.generationId=h.activeGenerationId WHERE h.playlistId='system:favorites' AND h.deleted=0),0)")
                BackupScope.PLAYLISTS -> scalar("SELECT COUNT(*) FROM playlist_heads WHERE playlistId!='system:favorites' AND deleted=0")
                BackupScope.QUEUE -> scalar("SELECT COALESCE(g.totalCount,0) FROM queue_head h LEFT JOIN queue_generations g ON g.generationId=h.activeGenerationId WHERE h.id=1")
                BackupScope.HISTORY -> scalar("SELECT COUNT(*) FROM listening_history h JOIN listening_meta m ON m.historyEpoch=h.historyEpoch WHERE m.id=1 AND h.historyId>=m.historyFloorSequence")
                BackupScope.LYRICS -> scalar("SELECT COUNT(*) FROM lyrics_track_preferences")
                BackupScope.PREFERENCES -> BackupPreferencesCodec.KEYS.size.toLong()
                BackupScope.RESOURCES -> (inventory ?: throw BackupException(BackupIssue.RECOVERY_REQUIRED)).existing
            }
            var conflicts = if (scope == BackupScope.RESOURCES) checkNotNull(inventory).conflicts else 0L
            var missing = if (scope == BackupScope.PREFERENCES) preferencesMissing else 0L
            each(stage, kind) { row ->
                when (scope) {
                    BackupScope.LIBRARY -> if (dao.track(row.key) != null) conflicts++
                    BackupScope.PLAYLISTS -> if (database.playlistDao().head(row.key) != null || scalar(
                            "SELECT COUNT(*) FROM playlist_heads h JOIN playlist_generations g ON g.generationId=h.activeGenerationId WHERE h.deleted=0 AND g.nameKey=?",
                            BackupCell.Text(playlistNameKey(row.text("name")))) > 0L) conflicts++
                    BackupScope.FAVORITES -> {
                        val key = row.optionalText("providerId")?.let { TrackKey(it, row.text("trackId")) }
                        if (key != null && database.playlistDao().favoriteContains(PlaylistReference(trackKey = key).record("preview", row.text("memberId"), 0L).referenceKey)) conflicts++
                        if (key == null || key.providerId == TrackKey.LOCAL_PROVIDER && dao.track(key.trackId) == null &&
                            (BackupScope.LIBRARY !in scopes || stage.row(BackupRecordKind.TRACK, key.trackId) == null)) missing++
                    }
                    BackupScope.QUEUE -> if (row.text("providerId") == TrackKey.LOCAL_PROVIDER) {
                        if (stage.binding(BackupBindingFamily.QUEUE, row.key)?.verified != true) missing++
                    }
                    BackupScope.LYRICS -> {
                        if (database.lyricsDao().row(row.key) != null) conflicts++
                        if (dao.currentTrack(row.key) == null && (BackupScope.LIBRARY !in scopes || stage.row(BackupRecordKind.TRACK, row.key) == null)) missing++
                        for (prefix in listOf("primary", "translation")) if (row.optionalText(prefix + "Sha256") != null &&
                            (BackupScope.RESOURCES !in scopes || row.optionalText(prefix + "ResourceId")?.let { stage.row(BackupRecordKind.RESOURCE, it) } == null)) missing++
                    }
                    else -> Unit
                }
            }
            if (scope == BackupScope.LIBRARY) each(stage, BackupRecordKind.TRACK_LOCATION) { row ->
                if (mode == BackupMergeMode.MERGE_KEEP_CURRENT && dao.currentTrack(row.text("trackId")) != null) return@each
                if (stage.binding(BackupBindingFamily.LOCATION, row.text("locationId"))?.verified != true) missing++
            }
            if (scope == BackupScope.LIBRARY) each(stage, BackupRecordKind.TRACK) { row ->
                if (!stage.hasAnyTrackLocation(row.key) && (mode == BackupMergeMode.REPLACE_SELECTED || dao.currentTrack(row.key) == null)) missing++
            }
            if (scope == BackupScope.PLAYLISTS) each(stage, BackupRecordKind.PLAYLIST_MEMBER) { row ->
                val trackId = row.optionalText("trackId")
                if (trackId == null || row.optionalText("providerId") == TrackKey.LOCAL_PROVIDER && dao.track(trackId) == null &&
                    (BackupScope.LIBRARY !in scopes || stage.row(BackupRecordKind.TRACK, trackId) == null)) missing++
            }
            if (scope == BackupScope.QUEUE && mode == BackupMergeMode.MERGE_KEEP_CURRENT && existing > 0L) conflicts = 1L
            if (scope == BackupScope.PREFERENCES && mode == BackupMergeMode.MERGE_KEEP_CURRENT) conflicts = existing
            impacts += BackupScopeImpact(scope, stage.count(kind), existing, conflicts, missing)
        }
        return impacts
    }

    private suspend fun findHint(journal: BackupRestoreJournal, trackId: String): BackupRelinkHint? {
        var after: String? = null
        while (true) {
            val page = physical { signal -> signal.throwIfCanceled(); journal.relinkPage(after) }
            if (page.isEmpty()) return null
            for (hint in page) if (hint.trackId == trackId && isMissing(hint)) return hint
            after = page.last().id
        }
    }
    private suspend fun validateDescriptor(descriptor: LocalReadDescriptor) {
        val observed = sources.validateRead(descriptor)
        if (observed.contentValidation != LocalContentValidation.VERIFIED || observed.copy(contentValidation = descriptor.contentValidation) != descriptor) {
            throw BackupException(BackupIssue.STALE_PREVIEW)
        }
    }
    private fun fullHash(descriptor: LocalReadDescriptor): String? = listOfNotNull(descriptor.verifiedContentToken, descriptor.providerContentToken)
        .firstNotNullOfOrNull { value -> value.takeIf { it.matches(Regex("sha256:[0-9a-f]{64}")) }?.removePrefix("sha256:") }
    private fun metadataDigest(value: LibraryTrackVersionEntity): String = backupStableId(value.trackId, value.providerId,
        value.member.toString(), value.firstAddedEpochMs.toString(), value.firstAddedSequence.toString(), value.filename,
        LibraryCodec.metadata(value.metadata()), value.metadataIssue.orEmpty())
    private fun missingMetadataDigest(identity: LibraryTrackEntity, version: LibraryTrackVersionEntity?): String = backupStableId(
        identity.trackId, identity.providerId, identity.firstAddedEpochMs.toString(), identity.firstAddedSequence.toString(),
        version?.let(::metadataDigest) ?: "no-current-metadata")

    private suspend fun each(stage: SqliteBackupStage, kind: BackupRecordKind, action: suspend (BackupRecord) -> Unit) {
        var after: String? = null
        while (true) {
            val page = stage.pageNow(kind, after)
            if (page.isEmpty()) break
            for (row in page) { stage.checkActive(); action(row) }
            after = page.last().key
        }
    }
    private suspend fun scalar(sql: String, vararg values: BackupCell): Long = database.useReaderConnection { reader -> reader.deferredTransaction {
        reader.usePrepared(sql) { statement -> bind(statement, values); if (!statement.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED); statement.getLong(0) }
    } }
    // A read during libraryTransaction must observe that same confined writer's uncommitted rows.
    private suspend fun writerScalar(sql: String, vararg values: BackupCell): Long = database.useWriterConnection { writer -> writer.usePrepared(sql) {
        bind(it, values); if (!it.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED); it.getLong(0)
    } }
    private suspend fun text(sql: String, vararg values: BackupCell): String? = database.useReaderConnection { reader -> reader.deferredTransaction {
        reader.usePrepared(sql) { statement -> bind(statement, values); if (!statement.step() || statement.isNull(0)) null else statement.getText(0) }
    } }
    private suspend fun execute(sql: String, vararg values: BackupCell) = database.useWriterConnection { writer -> writer.usePrepared(sql) {
        bind(it, values); it.step(); Unit
    } }
    private fun bind(statement: SQLiteStatement, values: Array<out BackupCell>) {
        values.forEachIndexed { index, value -> when (value) {
            BackupCell.Null -> statement.bindNull(index + 1)
            is BackupCell.Text -> statement.bindText(index + 1, value.value)
            is BackupCell.Integer -> statement.bindLong(index + 1, value.value)
            else -> throw BackupException(BackupIssue.INVALID_INPUT)
        } }
    }
    private suspend fun <T> physical(action: (CancellationSignal) -> T): T {
        val admitted = AtomicBoolean(false)
        try { return io.read(admitted = { admitted.set(true) }, action = action) }
        catch (failure: SourceReadException) { throw (failure.cause as? BackupException) ?: failure }
        finally { if (admitted.get()) withContext(NonCancellable) { io.awaitIdle() } }
    }
    companion object { private const val PREVIEW_MS = 10L * 60 * 1000 }
}
