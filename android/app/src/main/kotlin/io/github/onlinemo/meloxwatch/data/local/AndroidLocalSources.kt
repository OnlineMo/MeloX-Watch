package io.github.onlinemo.meloxwatch.data.local

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.icu.text.BreakIterator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import io.github.onlinemo.meloxwatch.data.imports.OwnedAudioProvider
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

interface LocalCandidateCursor : AutoCloseable {
    suspend fun nextBatch(limit: Int = LocalInputLimits.BATCH_SIZE): List<ObservedCandidate>
    val exhausted: Boolean
    suspend fun closeAndAwait() { close() }
}

interface LocalSourceAccess {
    suspend fun observe(source: LocalSourceSpec): LocalSourceObservation
    suspend fun open(
        source: LocalSourceSpec,
        parentObjectId: String? = null,
        sinceMediaGeneration: Long? = null,
    ): LocalCandidateCursor
}

/** Only explicitly selected content sources are read. No absolute-path or network fallback. */
class AndroidLocalSources(
    private val context: Context,
    private val io: LocalProviderIo,
) : LocalSourceAccess {
    private val resolver = context.contentResolver
    private val cursorLive = AtomicBoolean(false)

    /** A fresh preview can prove access now; it cannot claim the old preview's byte identity. */
    suspend fun verifyTransientRead(location: LocalLocationReference): Unit = io.read { signal ->
        if (location.contentRevision != 0L) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
        val uri = contentUri(location.uri)
        val grant = contentUri(location.grantUri)
        if (uri.authority != grant.authority) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        val kind = when {
            location.access == LocalAccess.APP_OWNED -> LocalSourceKind.APP_OWNED
            location.access == LocalAccess.MEDIA_PERMISSION -> LocalSourceKind.MEDIA_STORE
            DocumentsContract.isTreeUri(grant) -> LocalSourceKind.TREE
            else -> LocalSourceKind.DOCUMENT
        }
        val root = when (kind) {
            LocalSourceKind.TREE -> {
                val rootId = DocumentsContract.getTreeDocumentId(grant)
                if (!DocumentsContract.isTreeUri(uri) || DocumentsContract.getTreeDocumentId(uri) != rootId) {
                    throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
                }
                rootId
            }
            LocalSourceKind.MEDIA_STORE -> {
                val path = uri.pathSegments
                if (uri.authority != MediaStore.AUTHORITY || path.size != 4 || path[1] != "audio" ||
                    path[2] != "media" || path[3].toLongOrNull()?.let { it >= 0L } != true) {
                    throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
                }
                if (grant != uri && grant != MediaStore.Audio.Media.getContentUri(path[0])) {
                    throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
                }
                "media-volume:${path[0]}"
            }
            LocalSourceKind.DOCUMENT, LocalSourceKind.APP_OWNED -> {
                if (grant != uri) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
                if (kind == LocalSourceKind.APP_OWNED && uri.authority != OwnedAudioProvider.authority(context)) {
                    throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
                }
                null
            }
        }
        val source = LocalSourceSpec("transient-preview", kind, grant.toString(), location.grantFlags,
            mediaVolume = if (kind == LocalSourceKind.MEDIA_STORE) uri.pathSegments[0] else "external",
            authority = grant.authority, rootObjectId = root)
        if (location.access == LocalAccess.PERSISTED &&
            grantFacts(source).persistedRead != ObservedCapability.AVAILABLE) {
            throw SourceReadException(SourceReadIssue.PERMISSION_REVOKED)
        }
        val current = readCandidate(source, uri, signal)
        if (current.uri != location.uri) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        if (current.directory || !current.mayContainAudio) throw SourceReadException(SourceReadIssue.NOT_AUDIO)
        resolver.openAssetFileDescriptor(uri, "r", signal)?.use { opened ->
            if (!opened.fileDescriptor.valid()) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
            opened.createInputStream().use { input ->
                if (input.read() < 0) throw SourceReadException(SourceReadIssue.NOT_AUDIO)
            }
        } ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
        signal.throwIfCanceled()
    }

    /** Weak facts fence one preview; matching names and sizes never become a content or entity proof. */
    suspend fun verifyObservedCandidate(source: LocalSourceSpec, candidate: ObservedCandidate): Unit = io.read { signal ->
        verifyCandidate(source, candidate, signal, requireNativeContentToken = false)
    }

    /** Requery a reliable provider token around metadata extraction; enumeration facts can already be stale. */
    suspend fun verifyMetadataCandidate(source: LocalSourceSpec, candidate: ObservedCandidate): Unit = io.read { signal ->
        verifyCandidate(source, candidate, signal, requireNativeContentToken = true)
    }

    private fun verifyCandidate(
        source: LocalSourceSpec,
        candidate: ObservedCandidate,
        signal: CancellationSignal,
        requireNativeContentToken: Boolean,
    ) {
        val uri = contentUri(candidate.uri)
        if (candidate.sourceId != source.sourceId ||
            contentUri(source.uri).authority != uri.authority ||
            (source.authority != null && source.authority != uri.authority)) {
            throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        }
        if (source.kind == LocalSourceKind.TREE && (!DocumentsContract.isTreeUri(uri) ||
                DocumentsContract.getTreeDocumentId(uri) != source.rootObjectId)) {
            throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        }
        val expected = candidate.providerContentToken
        val nativeToken = expected?.takeUnless { it.startsWith("sha256:") }
        if (requireNativeContentToken && nativeToken == null) throw SourceReadException(SourceReadIssue.CONTENT_UNKNOWN)
        val current = readCandidate(source, uri, signal)
        if (current.objectId != candidate.objectId || current.uri != candidate.uri ||
            current.kind != candidate.kind || (requireNativeContentToken && current.directory) ||
            current.displayName != candidate.displayName || current.mimeType != candidate.mimeType ||
            current.sizeBytes != candidate.sizeBytes) {
            throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        }
        if (nativeToken != null) {
            if (current.providerContentToken == null) throw SourceReadException(SourceReadIssue.CONTENT_UNKNOWN)
            if (current.providerContentToken != nativeToken) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        }
        signal.throwIfCanceled()
    }

    /** Validate one registered access snapshot, never a query of the whole library or a URI guess. */
    suspend fun validateRead(descriptor: LocalReadDescriptor): LocalReadDescriptor = io.read { signal ->
        val source = descriptor.source.spec
        val uri = contentUri(descriptor.location.uri)
        if (source.authority != null && source.authority != uri.authority) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        if (source.kind == LocalSourceKind.TREE && (!DocumentsContract.isTreeUri(uri) ||
                DocumentsContract.getTreeDocumentId(uri) != source.rootObjectId)) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        if (descriptor.source.grant.persistedRead == ObservedCapability.AVAILABLE &&
            grantFacts(source).persistedRead != ObservedCapability.AVAILABLE) throw SourceReadException(SourceReadIssue.PERMISSION_REVOKED)
        val current = readCandidate(source, uri, signal)
        if (current.objectId != descriptor.objectId || current.uri != descriptor.location.uri || current.directory ||
            (descriptor.sizeBytes != null && current.sizeBytes != null && descriptor.sizeBytes != current.sizeBytes)) {
            throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        }
        val expectedProvider = descriptor.providerContentToken
        val providerProof = expectedProvider != null && !expectedProvider.startsWith("sha256:") &&
            current.providerContentToken == expectedProvider
        if (expectedProvider != null && !expectedProvider.startsWith("sha256:") && current.providerContentToken != null &&
            current.providerContentToken != expectedProvider) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        val baseline = descriptor.verifiedContentToken ?: expectedProvider?.takeIf { it.startsWith("sha256:") && source.kind == LocalSourceKind.APP_OWNED }
        val immutableOwned = source.kind == LocalSourceKind.APP_OWNED && descriptor.source.grant.appOwned &&
            source.authority == OwnedAudioProvider.authority(context) && source.rootObjectId == descriptor.objectId &&
            baseline?.matches(Regex("sha256:[0-9a-f]{64}")) == true &&
            descriptor.sizeBytes != null && current.sizeBytes == descriptor.sizeBytes
        val file = resolver.openAssetFileDescriptor(uri, "r", signal) ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
        val validated = file.use { opened ->
            if (!opened.fileDescriptor.valid()) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
            if (descriptor.sizeBytes != null && opened.declaredLength >= 0 && opened.declaredLength != descriptor.sizeBytes) {
                throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
            }
            opened.createInputStream().use { input ->
                if (providerProof || (immutableOwned && descriptor.sizeBytes!! > LocalInputLimits.READ_PROOF_BYTES)) {
                    // Owned objects are installed once, verified then, and have no provider write API.
                    if (input.read() < 0) throw SourceReadException(SourceReadIssue.NOT_AUDIO)
                    true
                } else if (baseline != null) {
                    val actual = ContentFingerprint.read(input, signal, current.sizeBytes ?: descriptor.sizeBytes)
                        ?: throw SourceReadException(SourceReadIssue.CONTENT_UNKNOWN)
                    if (actual != baseline) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
                    true
                } else {
                    if (input.read() < 0) throw SourceReadException(SourceReadIssue.NOT_AUDIO)
                    false
                }
            }
        }
        if (!validated) throw SourceReadException(SourceReadIssue.CONTENT_UNKNOWN)
        if (providerProof) {
            val after = readCandidate(source, uri, signal)
            if (after.objectId != current.objectId || after.providerContentToken != expectedProvider ||
                after.sizeBytes != current.sizeBytes) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        }
        signal.throwIfCanceled()
        descriptor.copy(contentValidation = LocalContentValidation.VERIFIED)
    }

    private fun readCandidate(source: LocalSourceSpec, uri: Uri, signal: CancellationSignal): ObservedCandidate {
        if (source.kind != LocalSourceKind.MEDIA_STORE) return documentNode(source, uri, null, signal)
        requireMediaPermission()
        val incarnation = mediaVersion(source.mediaVolume)
        if (source.providerIncarnation != null && incarnation != source.providerIncarnation) {
            throw SourceReadException(if (incarnation == null) SourceReadIssue.CONTENT_UNKNOWN else SourceReadIssue.SOURCE_CHANGED)
        }
        val projection = mutableListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DATE_ADDED)
        if (Build.VERSION.SDK_INT >= 29) projection += MediaStore.MediaColumns.VOLUME_NAME
        if (Build.VERSION.SDK_INT >= 30) {
            projection += MediaStore.MediaColumns.GENERATION_ADDED
            projection += MediaStore.MediaColumns.GENERATION_MODIFIED
        }
        return resolver.query(uri, projection.toTypedArray(), null, null, null, signal)?.use { row ->
            if (!row.moveToFirst()) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
            val volume = if (Build.VERSION.SDK_INT >= 29) row.text(MediaStore.MediaColumns.VOLUME_NAME) else source.mediaVolume
            if (volume.isNullOrBlank() || volume.length > 256) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
            mediaRow(source, volume, mediaVersion(volume), row)
        } ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
    }

    override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation = io.read { signal ->
        val uri = contentUri(source.uri)
        when (source.kind) {
            LocalSourceKind.MEDIA_STORE -> observeMedia(source, uri, signal)
            LocalSourceKind.TREE -> observeTree(source, uri, signal)
            LocalSourceKind.DOCUMENT, LocalSourceKind.APP_OWNED -> {
                val node = documentNode(source, uri, null, signal)
                resolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
                    if (!descriptor.fileDescriptor.valid()) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                } ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                val actual = source.copy(authority = uri.authority, rootObjectId = node.objectId)
                LocalSourceObservation(actual, sourceIdentity(actual), grantFacts(actual).copy(
                    read = ObservedCapability.AVAILABLE,
                    enumerate = ObservedCapability.UNAVAILABLE,
                ), node)
            }
        }
    }

    /** This method is called only after the user asks to retain the selected read grant. */
    suspend fun persistReadAccess(source: LocalSourceSpec): LocalSourceObservation {
        var rejected = false
        io.read { signal ->
            val uri = contentUri(source.uri)
            signal.throwIfCanceled()
            val persistable = source.grantFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
            val readGranted = source.grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
            if (persistable && readGranted && source.kind != LocalSourceKind.APP_OWNED && source.kind != LocalSourceKind.MEDIA_STORE) {
                try {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    rejected = true
                }
            }
        }
        val result = observe(source)
        val supportsPersistence = source.kind == LocalSourceKind.DOCUMENT || source.kind == LocalSourceKind.TREE
        return result.copy(access = result.access.copy(
            persistenceAttempted = supportsPersistence,
            persistenceRejected = supportsPersistence && (rejected || result.access.persistedRead != ObservedCapability.AVAILABLE),
        ))
    }

    /** A selected replacement root must identify the original source, not just share its name. */
    suspend fun verifyReauthorization(previous: LocalSourceSpec, replacement: LocalSourceSpec): LocalSourceObservation {
        if (previous.kind != replacement.kind) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        val observed = observe(replacement)
        val originalIdentity = sourceIdentity(previous)
        if (originalIdentity != observed.identity) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        return observed.copy(
            source = observed.source.copy(sourceId = previous.sourceId),
            root = observed.root.copy(sourceId = previous.sourceId),
        )
    }

    override suspend fun open(
        source: LocalSourceSpec,
        parentObjectId: String?,
        sinceMediaGeneration: Long?,
    ): LocalCandidateCursor {
        if (!cursorLive.compareAndSet(false, true)) throw SourceReadException(SourceReadIssue.BUSY)
        val signal = CancellationSignal()
        return try {
            io.read(signal, abandoned = { it.close() }, failed = { cursorLive.set(false) }) {
                val uri = contentUri(source.uri)
                val providerVersion = if (source.kind == LocalSourceKind.MEDIA_STORE) mediaVersion(source.mediaVolume) else null
                when (source.kind) {
                    LocalSourceKind.TREE -> {
                        if (!DocumentsContract.isTreeUri(uri)) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
                        val parent = parentObjectId ?: DocumentsContract.getTreeDocumentId(uri)
                        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parent)
                        val cursor = resolver.query(children, DOCUMENT_COLUMNS, null, null, null, signal)
                            ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                        ManagedCursor(cursor, signal) { row -> documentRow(source, uri, parent, row) }
                    }
                    LocalSourceKind.MEDIA_STORE -> {
                        requireMediaPermission()
                        val projection = mutableListOf(
                            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE,
                            MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DATE_ADDED,
                        )
                        if (Build.VERSION.SDK_INT >= 30) {
                            projection += MediaStore.MediaColumns.GENERATION_ADDED
                            projection += MediaStore.MediaColumns.GENERATION_MODIFIED
                        }
                        if (Build.VERSION.SDK_INT >= 29) projection += MediaStore.MediaColumns.VOLUME_NAME
                        val versions = HashMap<String, String?>()
                        val arguments = Bundle().apply {
                            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Audio.Media._ID))
                            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
                            if (Build.VERSION.SDK_INT >= 30 && sinceMediaGeneration != null) {
                                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?")
                                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(sinceMediaGeneration.toString()))
                            }
                        }
                        val cursor = resolver.query(uri, projection.toTypedArray(), arguments, signal)
                            ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                        ManagedCursor(cursor, signal) { row ->
                            val volume = if (Build.VERSION.SDK_INT >= 29) row.text(MediaStore.MediaColumns.VOLUME_NAME) else source.mediaVolume
                            val actualVolume = volume?.takeIf { it.isNotBlank() && it.length <= 256 }
                                ?: throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
                            val version = if (actualVolume == source.mediaVolume) providerVersion else {
                                // Storage volumes are bounded independently of the number of library rows.
                                if (actualVolume !in versions && versions.size >= 16) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                                if (actualVolume !in versions) versions[actualVolume] = mediaVersion(actualVolume)
                                versions[actualVolume]
                            }
                            mediaRow(source, actualVolume, version, row)
                        }
                    }
                    LocalSourceKind.DOCUMENT, LocalSourceKind.APP_OWNED -> {
                        SingleCursor(documentNode(source, uri, null, signal))
                    }
                }
            }
        } catch (error: Exception) {
            throw error
        }
    }

    private fun observeMedia(source: LocalSourceSpec, uri: Uri, signal: CancellationSignal): LocalSourceObservation {
        requireMediaPermission()
        resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }, signal)?.use { it.moveToFirst() } ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
        val version = mediaVersion(source.mediaVolume)
        val rootId = "media-volume:${source.mediaVolume}"
        val actual = source.copy(authority = uri.authority, rootObjectId = rootId, providerIncarnation = version)
        val root = ObservedCandidate(source.sourceId, rootId, uri.toString(), null, source.mediaVolume, null, LocalNodeKind.DIRECTORY)
        return LocalSourceObservation(actual, sourceIdentity(actual), LocalGrantObservation(
            read = ObservedCapability.AVAILABLE,
            enumerate = ObservedCapability.AVAILABLE,
            persistedRead = ObservedCapability.UNAVAILABLE,
            persistedWrite = ObservedCapability.UNAVAILABLE,
            returnedFlags = source.grantFlags,
        ), root, version, mediaGeneration(source.mediaVolume))
    }

    private fun observeTree(source: LocalSourceSpec, uri: Uri, signal: CancellationSignal): LocalSourceObservation {
        if (!DocumentsContract.isTreeUri(uri)) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
        val root = documentNode(source, document, null, signal)
        if (!root.directory) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, rootId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null, signal)
            ?.use { it.moveToFirst() } ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
        val flags = documentFlags(document, signal)
        val actual = source.copy(authority = uri.authority, rootObjectId = rootId)
        return LocalSourceObservation(actual, sourceIdentity(actual), grantFacts(actual).copy(
            enumerate = ObservedCapability.AVAILABLE,
            declaredWrite = flags?.let { it and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0 },
            declaredDelete = flags?.let { it and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0 },
            declaredRename = flags?.let { it and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0 },
            declaredMove = flags?.let { it and DocumentsContract.Document.FLAG_SUPPORTS_MOVE != 0 },
        ), root)
    }

    private fun documentNode(source: LocalSourceSpec, uri: Uri, parent: String?, signal: CancellationSignal): ObservedCandidate {
        val document = DocumentsContract.isDocumentUri(context, uri)
        if (document) {
            val cursor = resolver.query(uri, DOCUMENT_COLUMNS, null, null, null, signal)
                ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
            return cursor.use {
                if (!it.moveToFirst()) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                documentRow(source, uri, parent, it)
            }
        }
        val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)
        val values = cursor?.use {
            if (it.moveToFirst()) bounded(it.text(OpenableColumns.DISPLAY_NAME)) to it.number(OpenableColumns.SIZE) else null
        }
        val mime = bounded(resolver.getType(uri), 256).takeIf(String::isNotBlank)
        val owned = source.kind == LocalSourceKind.APP_OWNED
        if (owned && (uri.authority != OwnedAudioProvider.authority(context) || uri.pathSegments.size != 1 ||
                uri.query != null || uri.fragment != null)) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
        val objectId = if (owned) uri.pathSegments.single().also(OwnedAudioProvider::validateToken) else "uri:$uri"
        return ObservedCandidate(source.sourceId, objectId, uri.toString(), parent,
            values?.first.orEmpty(), mime, nodeKind(values?.first.orEmpty(), mime), values?.second?.takeIf { it >= 0 },
            entityProof = if (owned) "owned:${uri.authority}:$objectId" else null)
    }

    private fun documentRow(source: LocalSourceSpec, tree: Uri, parent: String?, cursor: Cursor): ObservedCandidate {
        val id = cursor.text(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            ?.takeIf { it.isNotBlank() && it.length <= LocalInputLimits.URI_CHARACTERS }
            ?: throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
        val uri = if (DocumentsContract.isTreeUri(tree)) DocumentsContract.buildDocumentUriUsingTree(tree, id) else tree
        val mime = cursor.text(DocumentsContract.Document.COLUMN_MIME_TYPE)?.take(256)
        val name = bounded(cursor.text(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
        return ObservedCandidate(
            source.sourceId, id, contentUri(uri.toString()).toString(), parent, name, mime,
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) LocalNodeKind.DIRECTORY else nodeKind(name, mime),
            cursor.number(DocumentsContract.Document.COLUMN_SIZE)?.takeIf { it >= 0 },
            cursor.number(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it >= 0 },
            entityProof = "document:${uri.authority}:$id",
        )
    }

    private fun mediaRow(source: LocalSourceSpec, volume: String, version: String?, cursor: Cursor): ObservedCandidate {
        val id = cursor.number(MediaStore.Audio.Media._ID)?.takeIf { it >= 0 }
            ?: throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
        val added = cursor.number(MediaStore.Audio.Media.DATE_ADDED)?.takeIf { it >= 0 }
        val generationAdded = if (Build.VERSION.SDK_INT >= 30) cursor.number(MediaStore.MediaColumns.GENERATION_ADDED) else null
        val generationModified = if (Build.VERSION.SDK_INT >= 30) cursor.number(MediaStore.MediaColumns.GENERATION_MODIFIED) else null
        val objectId = "media:$volume:$id:${generationAdded ?: added ?: "unknown"}"
        val name = bounded(cursor.text(MediaStore.Audio.Media.DISPLAY_NAME))
        val mime = cursor.text(MediaStore.Audio.Media.MIME_TYPE)?.take(256)
        return ObservedCandidate(
            source.sourceId, objectId, Uri.withAppendedPath(MediaStore.Audio.Media.getContentUri(volume), id.toString()).toString(),
            source.rootObjectId ?: "media-volume:${source.mediaVolume}", name, mime, nodeKind(name, mime),
            cursor.number(MediaStore.Audio.Media.SIZE)?.takeIf { it >= 0 },
            cursor.number(MediaStore.Audio.Media.DATE_MODIFIED)?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000),
            version, added, generationAdded, generationModified,
            providerContentToken = if (version != null && generationModified != null) "$version:$generationModified" else null,
            entityProof = version?.let { "media:$it:$objectId" },
        )
    }

    private fun grantFacts(source: LocalSourceSpec): LocalGrantObservation {
        if (source.kind == LocalSourceKind.APP_OWNED) return LocalGrantObservation(
            appOwned = true,
            offlineRead = ObservedCapability.AVAILABLE,
            persistedRead = ObservedCapability.UNAVAILABLE,
            persistedWrite = ObservedCapability.UNAVAILABLE,
        )
        val uri = contentUri(source.uri)
        val persisted = resolver.persistedUriPermissions.filter { it.uri == uri }
        val read = persisted.any { it.isReadPermission }
        val write = persisted.any { it.isWritePermission }
        val flags = (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        return LocalGrantObservation(
            persistedRead = if (read) ObservedCapability.AVAILABLE else ObservedCapability.UNAVAILABLE,
            persistedWrite = if (write) ObservedCapability.AVAILABLE else ObservedCapability.UNAVAILABLE,
            returnedFlags = source.grantFlags,
            acceptedPersistedFlags = flags,
        )
    }

    private fun documentFlags(uri: Uri, signal: CancellationSignal): Int? =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null, signal)?.use {
            if (it.moveToFirst()) it.number(DocumentsContract.Document.COLUMN_FLAGS)?.toInt() else null
        }

    private fun requireMediaPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SourceReadException(SourceReadIssue.PERMISSION_DENIED)
        }
    }

    private fun mediaVersion(volume: String): String? = try {
        when {
            Build.VERSION.SDK_INT >= 30 && volume != "external" -> MediaStore.getVersion(context, volume)
            Build.VERSION.SDK_INT >= 29 -> MediaStore.getVersion(context)
            else -> null
        }?.take(LocalInputLimits.TAG_CHARACTERS)
    } catch (_: RuntimeException) { null }

    private fun mediaGeneration(volume: String): Long? = if (Build.VERSION.SDK_INT >= 30 && volume != "external") {
        try { MediaStore.getGeneration(context, volume).takeIf { it >= 0 } } catch (_: RuntimeException) { null }
    } else null

    private inner class ManagedCursor(
        private val cursor: Cursor,
        private val signal: CancellationSignal,
        private val decode: (Cursor) -> ObservedCandidate,
    ) : LocalCandidateCursor {
        private val closed = AtomicBoolean(false)
        private val closedResult = CompletableDeferred<Unit>()
        @Volatile override var exhausted: Boolean = false
            private set

        override suspend fun nextBatch(limit: Int): List<ObservedCandidate> {
            require(limit in 1..LocalInputLimits.BATCH_SIZE)
            if (closed.get()) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
            return io.read(signal, abandoned = { close() }) { cancellation ->
                val page = ArrayList<ObservedCandidate>(limit)
                while (page.size < limit) {
                    cancellation.throwIfCanceled()
                    if (closed.get()) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                    if (!cursor.moveToNext()) { exhausted = true; break }
                    page += decode(cursor)
                }
                page
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            signal.cancel()
            io.retireCursor {
                try { cursor.close(); closedResult.complete(Unit) }
                catch (error: Exception) { closedResult.completeExceptionally(SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE, error)) }
                catch (error: OutOfMemoryError) { closedResult.completeExceptionally(SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE, error)) }
                finally { cursorLive.set(false) }
            }
        }

        override suspend fun closeAndAwait() { close(); closedResult.await() }
    }

    private inner class SingleCursor(private val node: ObservedCandidate) : LocalCandidateCursor {
        private var closed = false
        override var exhausted: Boolean = false
            private set
        override suspend fun nextBatch(limit: Int): List<ObservedCandidate> {
            require(limit in 1..LocalInputLimits.BATCH_SIZE)
            check(!closed)
            if (exhausted) return emptyList()
            exhausted = true
            return listOf(node)
        }
        override fun close() { closed = true; cursorLive.set(false) }
    }

    companion object {
        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        fun contentUri(value: String): Uri {
            if (value.length > LocalInputLimits.URI_CHARACTERS) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
            val uri = value.toUri()
            if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) {
                throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
            }
            return uri
        }

        fun sourceIdentity(source: LocalSourceSpec): String {
            val uri = contentUri(source.uri)
            val root = source.rootObjectId ?: when (source.kind) {
                LocalSourceKind.TREE -> DocumentsContract.getTreeDocumentId(uri)
                LocalSourceKind.MEDIA_STORE -> "media-volume:${source.mediaVolume}"
                else -> source.uri
            }
            return "${source.kind}:${source.authority ?: uri.authority}:$root:${source.providerIncarnation.orEmpty()}"
        }

        internal fun bounded(value: String?, maximum: Int = LocalInputLimits.NAME_CHARACTERS): String {
            val text = value.orEmpty()
            if (text.length <= maximum) return text
            val end = maximum
            val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
            return text.substring(0, iterator.preceding(end + 1).coerceAtLeast(0))
        }

        internal fun nodeKind(name: String, mime: String?): LocalNodeKind {
            val sample = ObservedCandidate("classification", "classification", "content://classification/item", null, name, mime, LocalNodeKind.OTHER)
            return if (sample.mayContainAudio) LocalNodeKind.AUDIO else LocalNodeKind.OTHER
        }
    }
}

private fun Cursor.text(column: String): String? = getColumnIndex(column).takeIf { it >= 0 }?.let {
    if (isNull(it)) null else getString(it)
}

private fun Cursor.number(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 }?.let {
    if (isNull(it)) null else getLong(it)
}
