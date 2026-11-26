package io.github.onlinemo.meloxwatch.data.metadata

import android.content.Context
import io.github.onlinemo.meloxwatch.data.artwork.LocalArtworkRepository
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.data.backup.withBackupMutation
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocumentCopies
import io.github.onlinemo.meloxwatch.data.lyrics.active.LocalLyricsContext
import io.github.onlinemo.meloxwatch.data.lyrics.active.OwnedLyricsProvider
import io.github.onlinemo.meloxwatch.data.lyrics.active.RoomLyricsContextResolver
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadBinding
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackWriteFence
import io.github.onlinemo.meloxwatch.data.lyrics.store.decode
import io.github.onlinemo.meloxwatch.data.lyrics.store.entity
import io.github.onlinemo.meloxwatch.data.lyrics.store.update
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

internal data class MetadataLookupTarget(
    val purpose: MetadataPurpose,
    val context: LocalLyricsContext,
    val fence: LyricsTrackWriteFence,
    val trackVersionId: String,
    val associationRevision: Long,
    val lyricsRevision: Long,
    val displayTitle: String,
    val title: String?,
    val artist: String?,
) {
    override fun toString(): String = "MetadataLookupTarget(redacted)"
}

internal data class MetadataCommitReceipt(val target: MetadataLookupTarget, val association: MetadataAssociation)

internal data class MetadataClearTarget(
    val trackKey: TrackKey, val purpose: MetadataPurpose, val generationId: String, val queueRevision: Long,
    val entryId: String, val trackVersionId: String, val associationRevision: Long, val lyricsRevision: Long,
    val displayTitle: String, val title: String?, val artist: String?,
) {
    override fun toString(): String = "MetadataClearTarget(redacted)"
}

internal data class MetadataClearReceipt(val target: MetadataClearTarget, val association: MetadataAssociation)

/** Files are installed first; a short Room transaction then commits the source and effective association together. */
internal class LocalMetadataRepository(
    context: Context,
    private val database: MeloXDatabase,
    private val library: RoomLocalLibraryRepository,
    private val contexts: RoomLyricsContextResolver,
    private val sources: AndroidLocalSources,
    private val queues: QueueStore,
    private val copies: LyricsDocumentCopies,
    private val artworkFiles: LocalMetadataArtworkFiles,
    private val barrier: BackupMutationBarrier,
    private val verifyAudio: suspend (LocalLyricsContext) -> LocalReadDescriptor = { current ->
        if (current.cueReference != null) sources.validateCueRead(current.audio) else sources.validateRead(current.audio)
    },
) : LocalMetadataStore {
    private val application = context.applicationContext
    private val dao get() = database.localMetadataDao()
    private val lyrics get() = database.lyricsDao()

    override fun changes(trackKey: TrackKey, purpose: MetadataPurpose): Flow<Unit> =
        combine(library.cueRevisions(), dao.revisions(trackKey.trackId, purpose.name),
            dao.lyricsRevisions(trackKey.trackId)) { _, _, _ -> Unit }

    override suspend fun captureClear(purpose: MetadataPurpose): MetadataClearTarget {
        val queue = queues.snapshot() ?: fail(MetadataStoreIssue.TRACK_MISSING)
        val entryId = queue.currentEntryId ?: fail(MetadataStoreIssue.TRACK_MISSING)
        val window = queues.window(queue.generationId, entryId)
        if (window.queueRevision != queue.queueRevision || window.current.queueEntryId != entryId) fail(MetadataStoreIssue.SOURCE_CHANGED)
        val key = window.current.trackKey
        if (key.providerId != TrackKey.LOCAL_PROVIDER) fail(MetadataStoreIssue.INVALID_INPUT)
        return database.withPreservedTransaction {
            val track = database.libraryDao().currentTrack(key.trackId) ?: fail(MetadataStoreIssue.TRACK_MISSING)
            val current = readUnlocked(key, purpose)
            val preference = lyrics.row(key.trackId)?.decode(key) ?: LyricsTrackPreferences(key)
            val target = MetadataClearTarget(key, purpose, queue.generationId, queue.queueRevision, entryId,
                track.versionId, current.revision, preference.revision, track.displayTitle, track.title, track.artist)
            if (!matchesClear(target)) fail(MetadataStoreIssue.SOURCE_CHANGED)
            target
        }
    }

    override suspend fun clearStillCurrent(target: MetadataClearTarget): Boolean = database.withPreservedTransaction {
        matchesClear(target) && readUnlocked(target.trackKey, target.purpose).revision == target.associationRevision &&
            (target.purpose != MetadataPurpose.LYRICS ||
                (lyrics.row(target.trackKey.trackId)?.decode(target.trackKey)?.revision ?: 0L) == target.lyricsRevision)
    }

    override suspend fun capture(purpose: MetadataPurpose): MetadataLookupTarget {
        val queue = queues.snapshot() ?: fail(MetadataStoreIssue.TRACK_MISSING)
        val current = queue.currentEntryId ?: fail(MetadataStoreIssue.TRACK_MISSING)
        val window = queues.window(queue.generationId, current)
        if (window.queueRevision != queue.queueRevision || window.current.queueEntryId != current) fail(MetadataStoreIssue.SOURCE_CHANGED)
        if (window.current.trackKey.providerId != TrackKey.LOCAL_PROVIDER) fail(MetadataStoreIssue.INVALID_INPUT)
        val context = contexts.resolve(window.current)
        val audio = context.audio
        val binding = LyricsLoadBinding(audio.trackKey, audio.location.locationId, audio.location.contentRevision,
            audio.source.spec.sourceId, audio.source.revision, audio.source.authEpoch, 0L, 0L)
        val fence = LyricsTrackWriteFence(binding, queue.generationId, queue.queueRevision, current)
        return database.withPreservedTransaction {
            val track = database.libraryDao().currentTrack(audio.trackKey.trackId) ?: fail(MetadataStoreIssue.TRACK_MISSING)
            val association = readUnlocked(audio.trackKey, purpose)
            val preferences = lyrics.row(audio.trackKey.trackId)?.decode(audio.trackKey) ?: LyricsTrackPreferences(audio.trackKey)
            val target = MetadataLookupTarget(purpose, context, fence, track.versionId, association.revision,
                preferences.revision, track.displayTitle, track.title, track.artist)
            if (!matches(target)) fail(MetadataStoreIssue.SOURCE_CHANGED)
            target
        }
    }

    override suspend fun read(trackKey: TrackKey, purpose: MetadataPurpose): MetadataAssociation =
        database.withPreservedTransaction { readUnlocked(trackKey, purpose) }

    override suspend fun isCurrent(target: MetadataLookupTarget): Boolean = database.withPreservedTransaction {
        matches(target) && readUnlocked(target.context.audio.trackKey, target.purpose).revision == target.associationRevision &&
            (target.purpose != MetadataPurpose.LYRICS ||
                (lyrics.row(target.context.audio.trackKey.trackId)?.decode(target.context.audio.trackKey)?.revision ?: 0L) == target.lyricsRevision)
    }

    override suspend fun apply(target: MetadataLookupTarget, bytes: ByteArray, provenance: MetadataProvenance,
        stillCurrent: () -> Boolean): MetadataCommitReceipt =
        barrier.withBackupMutation({ MetadataStoreException(MetadataStoreIssue.BUSY) }) {
            if (bytes.size.toLong() !in 1..target.purpose.inputBytes) fail(MetadataStoreIssue.INPUT_LIMIT)
            if (!stillCurrent() || !isCurrent(target) || !contexts.isCurrent(target.context)) fail(MetadataStoreIssue.SOURCE_CHANGED)
            val verified = verifyAudio(target.context)
            if (verified.contentValidation != LocalContentValidation.VERIFIED ||
                verified.copy(contentValidation = target.context.audio.contentValidation) != target.context.audio) fail(MetadataStoreIssue.SOURCE_CHANGED)
            currentCoroutineContext().ensureActive()
            if (!stillCurrent()) fail(MetadataStoreIssue.SOURCE_CHANGED)
            val sha256 = metadataSha256(bytes)
            when (target.purpose) {
                MetadataPurpose.LYRICS -> {
                    val copied = copies.createConfirmedMetadata(bytes, sha256)
                    try {
                        val assetId = OwnedLyricsProvider.token(application, AndroidLocalSources.contentUri(copied.uri))
                            ?: fail(MetadataStoreIssue.INVALID_RECORD)
                        val source = sources.observe(LocalSourceSpec("metadata-v1:$assetId", LocalSourceKind.DOCUMENT, copied.uri))
                        // The anonymous API encodes its confirmed text as UTF-8; backup links preserve that exact format.
                        val association = LyricsAssociation(source.root.objectId, source.source.sourceId, copied.uri, 0,
                            LyricsEncodingChoice.UTF8, sha256)
                        copies.withReferenceWrite {
                            commit(target, assetId, sha256, bytes.size.toLong(), provenance, association, stillCurrent)
                        }
                    } finally { copied.release() }
                }
                MetadataPurpose.ARTWORK -> {
                    val asset = artworkFiles.installConfirmed(bytes, sha256)
                    try {
                        artworkFiles.withReferenceWrite {
                            commit(target, asset.assetId, sha256, asset.byteCount, provenance, null, stillCurrent)
                        }
                    } finally { asset.release() }
                }
            }
        }

    override suspend fun clear(target: MetadataClearTarget, stillCurrent: () -> Boolean): MetadataClearReceipt =
        barrier.withBackupMutation({ MetadataStoreException(MetadataStoreIssue.BUSY) }) {
            when (target.purpose) {
                MetadataPurpose.LYRICS -> copies.withReferenceWrite { clearTransaction(target, stillCurrent) }
                MetadataPurpose.ARTWORK -> artworkFiles.withReferenceWrite { clearTransaction(target, stillCurrent) }
            }
        }

    private suspend fun clearTransaction(target: MetadataClearTarget, stillCurrent: () -> Boolean): MetadataClearReceipt =
        database.withPreservedTransaction {
            currentCoroutineContext().ensureActive()
            if (!stillCurrent() || !matchesClear(target)) fail(MetadataStoreIssue.SOURCE_CHANGED)
            val current = readUnlocked(target.trackKey, target.purpose)
            if (current.revision != target.associationRevision) fail(MetadataStoreIssue.CONFLICT)
            if (current.sha256 == null) return@withPreservedTransaction MetadataClearReceipt(target, current)
            if (current.revision == Long.MAX_VALUE) fail(MetadataStoreIssue.INVALID_RECORD)
            var lyricsRevision = target.lyricsRevision
            if (target.purpose == MetadataPurpose.LYRICS) {
                val oldRow = lyrics.row(target.trackKey.trackId)
                val old = oldRow?.decode(target.trackKey) ?: LyricsTrackPreferences(target.trackKey)
                if (old.revision != target.lyricsRevision) fail(MetadataStoreIssue.CONFLICT)
                val uri = current.assetId?.let { OwnedLyricsProvider.uri(application, it).toString() }
                val primary = old.primary
                if (uri != null && primary != null && primary.uri == uri && primary.confirmedContentSha256 == current.sha256 &&
                    primary.sourceId == "metadata-v1:${current.assetId}") {
                    if (old.revision == Long.MAX_VALUE) fail(MetadataStoreIssue.INVALID_RECORD)
                    val replacement = old.copy(revision = old.revision + 1L, primary = null)
                    if (oldRow == null || lyrics.update(old.revision, replacement.entity()) != 1) fail(MetadataStoreIssue.CONFLICT)
                    lyricsRevision = replacement.revision
                }
            }
            val next = MetadataAssociation(target.trackKey, target.purpose, current.revision + 1L)
            if (dao.updateIfRevision(target.trackKey.trackId, target.purpose.name, current.revision, next.revision,
                    null, null, null, null) != 1) fail(MetadataStoreIssue.CONFLICT)
            bumpLibraryRevision()
            if (!stillCurrent() || !matchesClear(target)) fail(MetadataStoreIssue.SOURCE_CHANGED)
            currentCoroutineContext().ensureActive()
            MetadataClearReceipt(target.copy(associationRevision = next.revision, lyricsRevision = lyricsRevision), next)
        }

    suspend fun acquireArtwork(descriptor: LocalReadDescriptor, targetPx: Int, images: LocalArtworkRepository): ThemeBitmapLease? {
        val current = read(descriptor.trackKey, MetadataPurpose.ARTWORK)
        val assetId = current.assetId ?: return null
        val sha256 = current.sha256 ?: fail(MetadataStoreIssue.INVALID_RECORD)
        val key = metadataSha256("metadata-artwork-v1:${descriptor.trackKey.trackId}:${current.revision}:$sha256:$targetPx".toByteArray())
        var pending: ThemeBitmapLease? = null
        try {
            var ready = images.derived(key, targetPx)
            pending = ready
            if (ready == null) {
                val bytes = artworkFiles.readVerified(assetId, checkNotNull(current.byteCount), sha256)
                currentCoroutineContext().ensureActive()
                if (read(descriptor.trackKey, MetadataPurpose.ARTWORK) != current) fail(MetadataStoreIssue.SOURCE_CHANGED)
                ready = images.derived(key, targetPx, bytes)
                pending = ready
            }
            if (read(descriptor.trackKey, MetadataPurpose.ARTWORK) != current) fail(MetadataStoreIssue.SOURCE_CHANGED)
            currentCoroutineContext().ensureActive()
            return ready.also { pending = null }
        } finally { pending?.close() }
    }

    suspend fun isLyricsReferenced(uri: String): Boolean {
        val assetId = OwnedLyricsProvider.token(application, AndroidLocalSources.contentUri(uri)) ?: return false
        return dao.recoveryBlocked(MetadataPurpose.LYRICS.name) || dao.isAnyAssetReferenced(assetId)
    }

    suspend fun recoverLyrics() {
        var blocked: Boolean? = null
        copies.recover { uri ->
            val deny = blocked ?: dao.recoveryBlocked(MetadataPurpose.LYRICS.name).also { blocked = it }
            deny || lyrics.isDocumentUriReferenced(uri) ||
                OwnedLyricsProvider.token(application, AndroidLocalSources.contentUri(uri))?.let {
                    dao.isAnyAssetReferenced(it)
                } == true
        }
    }

    suspend fun recoverArtwork() {
        var blocked: Boolean? = null
        artworkFiles.recover {
            // The file owner holds its reference gate throughout this sweep, so this global check is stable.
            val deny = blocked ?: dao.recoveryBlocked(MetadataPurpose.ARTWORK.name).also { blocked = it }
            deny || dao.isAnyAssetReferenced(it)
        }
    }

    private suspend fun commit(target: MetadataLookupTarget, assetId: String, sha256: String, byteCount: Long,
        provenance: MetadataProvenance, primary: LyricsAssociation?, stillCurrent: () -> Boolean): MetadataCommitReceipt =
        database.withPreservedTransaction {
            currentCoroutineContext().ensureActive()
            if (!stillCurrent() || !matches(target)) fail(MetadataStoreIssue.SOURCE_CHANGED)
            val key = target.context.audio.trackKey
            val before = readUnlocked(key, target.purpose)
            if (before.revision != target.associationRevision) fail(MetadataStoreIssue.CONFLICT)
            val oldLyricsRow = if (target.purpose == MetadataPurpose.LYRICS) lyrics.row(key.trackId) else null
            val oldLyrics = oldLyricsRow?.decode(key) ?: LyricsTrackPreferences(key)
            if (target.purpose == MetadataPurpose.LYRICS && oldLyrics.revision != target.lyricsRevision) fail(MetadataStoreIssue.CONFLICT)
            if (before.revision == Long.MAX_VALUE) fail(MetadataStoreIssue.INVALID_RECORD)
            var nextLyricsRevision = target.lyricsRevision
            if (target.purpose == MetadataPurpose.LYRICS) {
                val changedLyrics = oldLyrics.copy(primary = primary ?: fail(MetadataStoreIssue.INVALID_INPUT))
                if (changedLyrics != oldLyrics) {
                    if (oldLyrics.revision == Long.MAX_VALUE) fail(MetadataStoreIssue.INVALID_RECORD)
                    val replacement = changedLyrics.copy(revision = oldLyrics.revision + 1L)
                    val changed = if (oldLyricsRow == null) lyrics.insert(replacement.entity()) != -1L
                        else lyrics.update(oldLyrics.revision, replacement.entity()) == 1
                    if (!changed) fail(MetadataStoreIssue.CONFLICT)
                    nextLyricsRevision = replacement.revision
                }
            }
            val replacement = MetadataAssociation(key, target.purpose, before.revision + 1L, assetId, sha256, byteCount, provenance)
            val row = replacement.entity()
            val changed = if (dao.row(key.trackId, target.purpose.name) == null) dao.insert(row) != -1L else
                dao.updateIfRevision(key.trackId, target.purpose.name, before.revision, replacement.revision,
                    assetId, sha256, byteCount, row.provenance) == 1
            if (!changed) fail(MetadataStoreIssue.CONFLICT)
            bumpLibraryRevision()
            // Throwing after DML rolls back both tables when a new current item or source wins.
            if (!stillCurrent() || !matches(target)) fail(MetadataStoreIssue.SOURCE_CHANGED)
            currentCoroutineContext().ensureActive()
            MetadataCommitReceipt(target.copy(associationRevision = replacement.revision, lyricsRevision = nextLyricsRevision), replacement)
        }

    private suspend fun readUnlocked(key: TrackKey, purpose: MetadataPurpose): MetadataAssociation {
        if (key.providerId != TrackKey.LOCAL_PROVIDER || !lyrics.localTrackExists(key.trackId)) fail(MetadataStoreIssue.TRACK_MISSING)
        return dao.row(key.trackId, purpose.name)?.decode(key, purpose) ?: MetadataAssociation(key, purpose)
    }

    private suspend fun matches(target: MetadataLookupTarget): Boolean {
        val fence = target.fence
        val audio = fence.audio
        if (database.libraryDao().currentTrack(audio.trackKey.trackId)?.versionId != target.trackVersionId ||
            !lyrics.fenceMatches(audio.trackKey.trackId, audio.locationId, audio.contentRevision, audio.sourceId,
                audio.sourceRevision, audio.authorizationEpoch, fence.queueGenerationId, fence.queueRevision, fence.queueEntryId)) return false
        val cue = target.context.cueReference ?: return true
        // This unlocked resolver reads persisted cue/alias/segment rows only; provider checks stay outside Room.
        return library.resolveCueReadUnlocked(audio.trackKey, cue).reference == cue
    }

    private suspend fun matchesClear(target: MetadataClearTarget): Boolean =
        database.libraryDao().currentTrack(target.trackKey.trackId)?.versionId == target.trackVersionId &&
            dao.selectionMatches(target.trackKey.trackId, target.generationId, target.queueRevision, target.entryId)

    private suspend fun bumpLibraryRevision() {
        val before = database.libraryDao().meta() ?: fail(MetadataStoreIssue.INVALID_RECORD)
        if (before.revision == Long.MAX_VALUE) fail(MetadataStoreIssue.INVALID_RECORD)
        if (database.libraryDao().updateMeta(before.copy(revision = before.revision + 1L)) != 1) fail(MetadataStoreIssue.CONFLICT)
    }

    private fun fail(issue: MetadataStoreIssue): Nothing = throw MetadataStoreException(issue)
}

internal fun metadataSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
