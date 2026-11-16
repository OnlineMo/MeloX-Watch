package io.github.onlinemo.meloxwatch.data.playlistsexchange

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Process
import android.provider.DocumentsContract
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.ui.playlists.AuthorizedPlaylistDocument
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * System picker results own short-lived access tokens, never library grants.
 * The caller supplies CreateDocument provenance; registration does not prove an export committed.
 */
class PlaylistDocumentRegistry(context: Context, private val io: LocalProviderIo) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun registerImport(
        uri: Uri,
        returnedFlags: Int,
        rootBinding: PlaylistFolderBinding? = null,
    ): AuthorizedPlaylistDocument = register(uri, returnedFlags, Role.IMPORT, null, rootBinding)

    suspend fun registerExport(
        uri: Uri,
        returnedFlags: Int,
        operationId: String,
    ): AuthorizedPlaylistDocument {
        requireIdentifier(operationId)
        return register(uri, returnedFlags, Role.EXPORT, operationId, null)
    }

    /** Invalidates immediately; an already delivered borrower retains its bounded cleanup ownership. */
    suspend fun discard(selectionId: String) {
        requireIdentifier(selectionId)
        synchronized(lock) {
            entries[selectionId]?.let { entry ->
                entry.discarded = true
                removeIfRetired(entry)
            }
        }
    }

    internal suspend fun borrowImport(document: AuthorizedPlaylistDocument): DocumentLease =
        borrow(document, Role.IMPORT, null)

    internal suspend fun borrowExport(document: AuthorizedPlaylistDocument, operationId: String): DocumentLease {
        requireIdentifier(operationId)
        return borrow(document, Role.EXPORT, operationId)
    }

    private suspend fun register(
        uri: Uri,
        returnedFlags: Int,
        role: Role,
        operationId: String?,
        rootBinding: PlaylistFolderBinding?,
    ): AuthorizedPlaylistDocument {
        requireUri(uri)
        if (returnedFlags and role.permissions != role.permissions) fail(PlaylistIssue.INVALID_INPUT)
        currentCoroutineContext().ensureActive()
        val ticket = Any()
        val entry = synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) fail(PlaylistIssue.BUSY)
            val id = UUID.randomUUID().toString()
            Entry(id, uri, role, operationId, rootBinding, ticket).also { entries[id] = it }
        }
        var physicallyReturned = false
        try {
            val facts = io.read(
                abandoned = { _: Facts -> release(entry, ticket, discard = true) },
                failed = { release(entry, ticket, discard = true) },
            ) { signal -> inspect(entry, signal) }
            physicallyReturned = true
            currentCoroutineContext().ensureActive()
            return synchronized(lock) {
                if (entries[entry.id] !== entry || entry.discarded || entry.owner !== ticket) {
                    release(entry, ticket, discard = true)
                    fail(PlaylistIssue.VIEW_EXPIRED)
                }
                entry.facts = facts
                entry.owner = null
                AuthorizedPlaylistDocument(entry.id, facts.label)
            }
        } catch (error: Exception) {
            synchronized(lock) {
                entry.discarded = true
                // Cancellation cannot retire a provider that has not physically returned.
                if (physicallyReturned && entry.owner === ticket) entry.owner = null
                removeIfRetired(entry)
            }
            throw safeFailure(error)
        }
    }

    private suspend fun borrow(
        document: AuthorizedPlaylistDocument,
        role: Role,
        operationId: String?,
    ): DocumentLease {
        currentCoroutineContext().ensureActive()
        val ticket = Any()
        val entry = synchronized(lock) {
            val found = entries[document.selectionId]?.takeUnless { it.discarded }
                ?: fail(PlaylistIssue.VIEW_EXPIRED)
            if (found.role != role || found.operationId != operationId) fail(PlaylistIssue.INVALID_INPUT)
            val facts = found.facts ?: fail(PlaylistIssue.BUSY)
            if (document.label != facts.label) fail(PlaylistIssue.INVALID_INPUT)
            if (found.owner != null) fail(PlaylistIssue.BUSY)
            found.owner = ticket
            found
        }
        var received: DocumentLease? = null
        try {
            val lease = io.read(
                abandoned = { value: DocumentLease -> value.close() },
                failed = { release(entry, ticket) },
            ) { signal ->
                val actual = inspect(entry, signal)
                signal.throwIfCanceled()
                synchronized(lock) {
                    if (entries[entry.id] !== entry || entry.discarded || entry.owner !== ticket) {
                        fail(PlaylistIssue.VIEW_EXPIRED)
                    }
                    DocumentLease(entry.uri, entry.rootBinding, actual.label) { release(entry, ticket) }
                }
            }
            received = lease
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (entries[entry.id] !== entry || entry.discarded || entry.owner !== ticket) {
                    fail(PlaylistIssue.VIEW_EXPIRED)
                }
            }
            return lease
        } catch (error: Exception) {
            // Without a delivered result, failed/abandoned owns retirement after physical cleanup.
            received?.close()
            throw safeFailure(error)
        }
    }

    private fun inspect(entry: Entry, signal: CancellationSignal): Facts {
        signal.throwIfCanceled()
        requireLive(entry)
        if (!DocumentsContract.isDocumentUri(context, entry.uri)) {
            fail(if (entry.facts == null) PlaylistIssue.INVALID_INPUT else PlaylistIssue.SOURCE_UNAVAILABLE)
        }
        requirePermissions(entry)
        val before = queryFacts(entry.uri, signal)
        requireLive(entry)
        requireFacts(entry, before)
        resolver.openFileDescriptor(entry.uri, entry.role.mode, signal)?.use { descriptor ->
            signal.throwIfCanceled()
            requireLive(entry)
            if (!descriptor.fileDescriptor.valid()) fail(PlaylistIssue.SOURCE_UNAVAILABLE)
        } ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
        signal.throwIfCanceled()
        requireLive(entry)
        val after = queryFacts(entry.uri, signal)
        requireLive(entry)
        requireFacts(entry, after)
        if (!sameIdentity(before, after)) fail(PlaylistIssue.SOURCE_CHANGED)
        requirePermissions(entry)
        signal.throwIfCanceled()
        return after
    }

    private fun requireLive(entry: Entry) = synchronized(lock) {
        if (entries[entry.id] !== entry || entry.discarded) fail(PlaylistIssue.VIEW_EXPIRED)
    }

    private fun queryFacts(uri: Uri, signal: CancellationSignal): Facts {
        val requestedId = DocumentsContract.getDocumentId(uri)
        requireText(requestedId, LocalInputLimits.URI_CHARACTERS)
        return resolver.query(uri, COLUMNS, null, null, null, signal)?.use { cursor ->
            signal.throwIfCanceled()
            if (!cursor.moveToFirst()) fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            val id = cursor.requiredText(DocumentsContract.Document.COLUMN_DOCUMENT_ID, LocalInputLimits.URI_CHARACTERS)
            if (id != requestedId) fail(PlaylistIssue.SOURCE_CHANGED)
            val mime = cursor.requiredText(DocumentsContract.Document.COLUMN_MIME_TYPE, MIME_CHARACTERS)
            val labelIndex = cursor.requiredColumn(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val label = if (cursor.isNull(labelIndex)) null else {
                if (cursor.getType(labelIndex) != Cursor.FIELD_TYPE_STRING) fail(PlaylistIssue.INVALID_INPUT)
                (cursor.getString(labelIndex) ?: fail(PlaylistIssue.INVALID_INPUT)).also {
                    requireText(it, PlaylistLimits.MAX_FIELD_CHARACTERS, allowBlank = true)
                }
            }
            val flagsIndex = cursor.requiredColumn(DocumentsContract.Document.COLUMN_FLAGS)
            if (cursor.getType(flagsIndex) != Cursor.FIELD_TYPE_INTEGER) fail(PlaylistIssue.INVALID_INPUT)
            val flags = cursor.getLong(flagsIndex)
            if (flags !in 0L..Int.MAX_VALUE.toLong()) fail(PlaylistIssue.INVALID_INPUT)
            // A document query yields one object; never materialize or count an unbounded cursor.
            if (cursor.moveToNext()) fail(PlaylistIssue.INVALID_INPUT)
            signal.throwIfCanceled()
            Facts(id, mime, label, flags.toInt())
        } ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
    }

    private fun requireFacts(entry: Entry, actual: Facts) {
        entry.facts?.let { expected ->
            if (!sameIdentity(expected, actual)) fail(PlaylistIssue.SOURCE_CHANGED)
        }
        if (actual.mime.equals(DocumentsContract.Document.MIME_TYPE_DIR, ignoreCase = true)) {
            fail(PlaylistIssue.INVALID_INPUT)
        }
        if (entry.role == Role.EXPORT && actual.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE == 0) {
            fail(PlaylistIssue.SOURCE_UNAVAILABLE)
        }
    }

    private fun requirePermissions(entry: Entry) {
        for (permission in PERMISSIONS) {
            if (entry.role.permissions and permission != 0 && context.checkUriPermission(
                    entry.uri, Process.myPid(), Process.myUid(), permission,
                ) != PackageManager.PERMISSION_GRANTED) {
                fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            }
        }
    }

    private fun release(entry: Entry, ticket: Any, discard: Boolean = false) = synchronized(lock) {
        if (entries[entry.id] === entry && entry.owner === ticket) {
            if (discard) entry.discarded = true
            entry.owner = null
            removeIfRetired(entry)
        }
    }

    private fun removeIfRetired(entry: Entry) {
        if (entries[entry.id] === entry && entry.discarded && entry.owner == null) entries.remove(entry.id)
    }

    private fun Cursor.requiredColumn(column: String): Int = getColumnIndex(column).also {
        if (it < 0) fail(PlaylistIssue.INVALID_INPUT)
    }

    private fun Cursor.requiredText(column: String, limit: Int): String {
        val index = requiredColumn(column)
        if (getType(index) != Cursor.FIELD_TYPE_STRING) fail(PlaylistIssue.INVALID_INPUT)
        return (getString(index) ?: fail(PlaylistIssue.INVALID_INPUT)).also { requireText(it, limit) }
    }

    private fun requireUri(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || !uri.isHierarchical || uri.authority.isNullOrBlank() ||
            uri.toString().length > LocalInputLimits.URI_CHARACTERS) fail(PlaylistIssue.INVALID_INPUT)
    }

    private fun requireIdentifier(value: String) = requireText(value, PlaylistLimits.MAX_IDENTIFIER_CHARACTERS)

    private fun requireText(value: String, limit: Int, allowBlank: Boolean = false) {
        if (value.length > limit || !allowBlank && value.isBlank() || containsExchangeControl(value)) {
            fail(PlaylistIssue.INVALID_INPUT)
        }
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 == value.length || !Character.isLowSurrogate(value[index + 1])) {
                    fail(PlaylistIssue.INVALID_INPUT)
                }
                index++
            } else if (Character.isLowSurrogate(char)) fail(PlaylistIssue.INVALID_INPUT)
            index++
        }
    }

    private fun sameIdentity(first: Facts, second: Facts): Boolean =
        first.documentId == second.documentId && first.mime == second.mime && first.label == second.label

    private fun safeFailure(error: Exception): Exception {
        if (error is CancellationException) {
            return CancellationException("Playlist document operation cancelled").also { it.stackTrace = error.stackTrace }
        }
        val issue = when (error) {
            is PlaylistException -> error.issue
            is SourceReadException -> when (error.issue) {
                SourceReadIssue.BUSY -> PlaylistIssue.BUSY
                SourceReadIssue.INVALID_SOURCE -> PlaylistIssue.INVALID_INPUT
                SourceReadIssue.SOURCE_CHANGED -> PlaylistIssue.SOURCE_CHANGED
                else -> PlaylistIssue.SOURCE_UNAVAILABLE
            }
            is IllegalArgumentException -> PlaylistIssue.INVALID_INPUT
            else -> PlaylistIssue.SOURCE_UNAVAILABLE
        }
        val diagnostic = RuntimeException(error.javaClass.name).also { it.stackTrace = error.stackTrace }
        return PlaylistException(issue, diagnostic)
    }

    private fun fail(issue: PlaylistIssue): Nothing = throw PlaylistException(issue)

    private enum class Role(val permissions: Int, val mode: String) {
        IMPORT(Intent.FLAG_GRANT_READ_URI_PERMISSION, "r"),
        EXPORT(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, "rw"),
    }

    private data class Facts(val documentId: String, val mime: String, val label: String?, val flags: Int)
    private class Entry(
        val id: String,
        val uri: Uri,
        val role: Role,
        val operationId: String?,
        val rootBinding: PlaylistFolderBinding?,
        var owner: Any?,
    ) {
        var discarded = false
        var facts: Facts? = null
    }

    private companion object {
        const val MAX_ENTRIES = 4
        const val MIME_CHARACTERS = 256
        val PERMISSIONS = intArrayOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS)
    }
}

/** close follows the owner's final stream/descriptor cleanup, including after UI invalidation. */
internal class DocumentLease internal constructor(
    val uri: Uri,
    val rootBinding: PlaylistFolderBinding?,
    val label: String?,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}
