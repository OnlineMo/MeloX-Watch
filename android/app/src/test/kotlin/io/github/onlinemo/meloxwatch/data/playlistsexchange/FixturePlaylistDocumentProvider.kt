package io.github.onlinemo.meloxwatch.data.playlistsexchange

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/** Real DocumentsProvider routing and file-backed descriptors; no URI-permission override. */
class FixturePlaylistDocumentProvider : DocumentsProvider() {
    lateinit var document: File
    @Volatile var displayName: String? = "fixture.m3u8"
    @Volatile var returnedDocumentId: String = DOCUMENT_ID
    @Volatile var mimeType = "audio/x-mpegurl"
    @Volatile var documentFlags: Any? = DocumentsContract.Document.FLAG_SUPPORTS_WRITE
    @Volatile var missingColumn: String? = null
    @Volatile var empty = false
    @Volatile var extraRow = false
    @Volatile var writable = true
    @Volatile var queryFailure: Exception? = null
    @Volatile var openFailure: Exception? = null
    @Volatile var beforeQuery: (() -> Unit)? = null
    @Volatile var beforeCursorClose: (() -> Unit)? = null
    @Volatile var beforeOpen: (() -> Unit)? = null
    val queryCount = AtomicInteger()
    val cursorCount = AtomicInteger()
    val cursorCloseCount = AtomicInteger()
    val childQueryCount = AtomicInteger()
    val deleteCount = AtomicInteger()
    val openModes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val descriptors: MutableList<ParcelFileDescriptor> = Collections.synchronizedList(mutableListOf())

    val allDescriptorsClosed: Boolean
        get() = synchronized(descriptors) {
            descriptors.all { descriptor ->
                try { !descriptor.fileDescriptor.valid() }
                catch (_: IOException) { true }
                catch (_: IllegalStateException) { true }
            }
        } && PlaylistDocumentFixtureOsShadow.allKnownDescriptorsClosed()

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
    ))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        queryCount.incrementAndGet()
        // This hook deliberately ignores cancellation, like an uncooperative remote provider.
        beforeQuery?.invoke()
        queryFailure?.let { throw it }
        val columns = (projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_FLAGS)).filter { it != missingColumn }.toTypedArray()
        cursorCount.incrementAndGet()
        return object : MatrixCursor(columns, 2) {
            private val retired = AtomicBoolean(false)
            override fun close() {
                if (retired.compareAndSet(false, true)) {
                    try { beforeCursorClose?.invoke() }
                    finally { super.close(); cursorCloseCount.incrementAndGet() }
                }
            }
        }.apply {
            if (!empty && documentId == DOCUMENT_ID && document.isFile) {
                val row = columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> returnedDocumentId
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeType
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> displayName
                        DocumentsContract.Document.COLUMN_FLAGS -> documentFlags
                        else -> null
                    }
                }.toTypedArray()
                addRow(row)
                if (extraRow) addRow(row)
            }
        }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        childQueryCount.incrementAndGet()
        return MatrixCursor(projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        openModes.add(mode)
        beforeOpen?.invoke()
        signal?.throwIfCanceled()
        openFailure?.let { throw it }
        val openedFile = document
        if (documentId != DOCUMENT_ID || !openedFile.isFile) throw FileNotFoundException("Fixture document unavailable")
        if (mode == "rw" && !writable) throw SecurityException("Fixture write access denied")
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
            else -> throw IllegalArgumentException("Unexpected fixture mode")
        }
        val descriptor = ParcelFileDescriptor.open(openedFile, flags)
        var delivered = false
        try {
            PlaylistDocumentFixtureOsShadow.register(descriptor, writable = mode == "rw")
            descriptors.add(descriptor)
            delivered = true
            return descriptor
        } finally {
            if (!delivered) descriptor.close()
        }
    }

    override fun deleteDocument(documentId: String) {
        deleteCount.incrementAndGet()
        throw UnsupportedOperationException("Fixture deletion is unsupported")
    }

    companion object { const val DOCUMENT_ID = "playlist-fixture" }
}

internal fun fixturePlaylistDocumentProvider(
    context: Context,
    authority: String,
    document: File,
): FixturePlaylistDocumentProvider {
    val info = ProviderInfo().apply {
        this.authority = authority
        packageName = context.packageName
        name = FixturePlaylistDocumentProvider::class.java.name
        applicationInfo = context.applicationInfo
        exported = true
        grantUriPermissions = true
        readPermission = Manifest.permission.MANAGE_DOCUMENTS
        writePermission = Manifest.permission.MANAGE_DOCUMENTS
    }
    val provider = Robolectric.buildContentProvider(FixturePlaylistDocumentProvider::class.java).create(info).get()
    provider.document = document
    val packageManager = Shadows.shadowOf(context.packageManager)
    packageManager.addOrUpdateProvider(info)
    packageManager.addIntentFilterForProvider(ComponentName(info.packageName, info.name),
        IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
    ShadowContentResolver.registerProviderInternal(authority, provider)
    return provider
}
