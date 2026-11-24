package io.github.onlinemo.meloxwatch.data.lyrics.load

import android.Manifest
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/** Real DocumentsProvider TREE routing and file descriptors; system URI grants remain a device gate. */
class LocalLyricsTreeProviderFixture : DocumentsProvider() {
    lateinit var audio: File
    lateinit var lyrics: File
    @Volatile var deniedDocumentId: String? = null
    @Volatile var beforeOpen: ((String) -> Unit)? = null
    val openedDocumentIds = CopyOnWriteArrayList<String>()
    val openModes = CopyOnWriteArrayList<String>()
    val rootChildQueries = AtomicInteger()
    val folderChildQueries = AtomicInteger()
    val cursorCount = AtomicInteger()
    val cursorCloseCount = AtomicInteger()
    private val descriptors = CopyOnWriteArrayList<ParcelFileDescriptor>()

    val allDescriptorsClosed: Boolean
        get() = descriptors.all { descriptor ->
            try { !descriptor.fileDescriptor.valid() }
            catch (_: IOException) { true }
            catch (_: IllegalStateException) { true }
        }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
    ))

    // Robolectric calls this legacy overload directly; retain DocumentsProvider's TREE routing.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor? {
        cancellationSignal?.throwIfCanceled()
        val queryArgs = if (selection == null && selectionArgs == null && sortOrder == null) {
            null
        } else {
            Bundle().apply {
                selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
                selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
                sortOrder?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
            }
        }
        return super.query(uri, projection, queryArgs, cancellationSignal)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        rows(projection, listOf(documentId))

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val children = when (parentDocumentId) {
            ROOT -> {
                rootChildQueries.incrementAndGet()
                listOf(FOLDER)
            }
            FOLDER -> {
                folderChildQueries.incrementAndGet()
                listOf(AUDIO, LYRICS)
            }
            else -> emptyList()
        }
        return rows(projection, children)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = when (parentDocumentId) {
        ROOT -> documentId == FOLDER || documentId == AUDIO || documentId == LYRICS
        FOLDER -> documentId == AUDIO || documentId == LYRICS
        else -> false
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        openedDocumentIds += documentId
        openModes += mode
        signal?.throwIfCanceled()
        beforeOpen?.invoke(documentId)
        if (documentId == deniedDocumentId) throw SecurityException("Controlled document read denial")
        if (mode != "r") throw IllegalArgumentException("Read-only lyrics fixture")
        val file = file(documentId) ?: throw FileNotFoundException("Fixture document unavailable")
        if (!file.isFile) throw FileNotFoundException("Fixture document unavailable")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptors += it }
    }

    private fun rows(projection: Array<out String>?, ids: List<String>): Cursor {
        val columns = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS,
        )
        cursorCount.incrementAndGet()
        return object : MatrixCursor(columns, ids.size) {
            private val closed = AtomicBoolean(false)
            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    try { super.close() } finally { cursorCloseCount.incrementAndGet() }
                }
            }
        }.apply {
            for (id in ids) {
                val directory = id == ROOT || id == FOLDER
                val file = file(id)
                if (!directory && (file == null || !file.isFile)) continue
                addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file?.name ?: id
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> when {
                            directory -> DocumentsContract.Document.MIME_TYPE_DIR
                            id == AUDIO -> "audio/mpeg"
                            else -> "text/plain"
                        }
                        DocumentsContract.Document.COLUMN_SIZE -> file?.length()
                        // Deliberately weak, stable metadata cannot stand in for a complete hash.
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> if (directory) null else 1_000L
                        DocumentsContract.Document.COLUMN_FLAGS -> 0
                        else -> null
                    }
                }.toTypedArray<Any?>())
            }
        }
    }

    private fun file(documentId: String): File? = when (documentId) {
        AUDIO -> audio
        LYRICS -> lyrics
        else -> null
    }

    companion object {
        const val ROOT = "lyrics-root"
        const val FOLDER = "lyrics-folder"
        const val AUDIO = "audio-object"
        const val LYRICS = "lyrics-object"
    }
}

internal fun fixtureLyricsTreeProvider(
    context: Context,
    authority: String,
    audio: File,
    lyrics: File,
): LocalLyricsTreeProviderFixture {
    val info = ProviderInfo().apply {
        this.authority = authority
        packageName = context.packageName
        name = LocalLyricsTreeProviderFixture::class.java.name
        applicationInfo = context.applicationInfo
        exported = true
        grantUriPermissions = true
        readPermission = Manifest.permission.MANAGE_DOCUMENTS
        writePermission = Manifest.permission.MANAGE_DOCUMENTS
    }
    val provider = Robolectric.buildContentProvider(LocalLyricsTreeProviderFixture::class.java).create(info).get()
    provider.audio = audio
    provider.lyrics = lyrics
    val packageManager = Shadows.shadowOf(context.packageManager)
    packageManager.addOrUpdateProvider(info)
    packageManager.addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
    ShadowContentResolver.registerProviderInternal(authority, provider)
    return provider
}
