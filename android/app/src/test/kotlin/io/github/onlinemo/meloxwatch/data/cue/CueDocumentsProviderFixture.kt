package io.github.onlinemo.meloxwatch.data.cue

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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/** 真实TREE文档路由与descriptor；模拟grant不授予实机验收信用。 */
class CueDocumentsProviderFixture : DocumentsProvider() {
    val documents = linkedMapOf<String, File>()
    val roots = linkedSetOf<String>()
    val activeCursors = AtomicInteger()
    val descriptors = CopyOnWriteArrayList<ParcelFileDescriptor>()
    val opened = CopyOnWriteArrayList<String>()
    @Volatile var denied: String? = null
    @Volatile var beforeQueryDocument: ((String) -> Unit)? = null
    @Volatile var beforeOpenDocument: ((String) -> Unit)? = null
    @Volatile var beforeCursorClose: ((List<String>) -> Unit)? = null
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE))
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor? {
        cancellationSignal?.throwIfCanceled()
        val args = if (selection == null && selectionArgs == null && sortOrder == null) null else Bundle().apply {
            selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
            sortOrder?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
        }
        return super.query(uri, projection, args, cancellationSignal)
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        beforeQueryDocument?.invoke(documentId)
        return rows(projection, listOf(documentId))
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        rows(projection, documents.keys.filter { it.startsWith("$parentDocumentId-") })
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId in roots && documentId.startsWith("$parentDocumentId-") && documentId in documents
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        if (documentId == denied) throw SecurityException("Controlled CUE permission denial")
        require(mode == "r")
        if (documentId.endsWith("-cue")) check(activeCursors.get() == 0) { "Directory cursor must physically retire before CUE file IO" }
        val file = documents[documentId] ?: throw FileNotFoundException("Controlled missing document")
        beforeOpenDocument?.invoke(documentId)
        opened += documentId
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptors += it }
    }
    private fun rows(projection: Array<out String>?, ids: List<String>): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
        activeCursors.incrementAndGet()
        return object : MatrixCursor(columns, ids.size) {
            private var retired = false
            override fun close() {
                if (!retired) {
                    beforeCursorClose?.invoke(ids)
                    retired = true
                    try { super.close() } finally { activeCursors.decrementAndGet() }
                }
            }
        }.apply {
            for (id in ids) {
                val directory = id in roots
                val file = documents[id]
                if (!directory && file == null) continue
                addRow(columns.map { name -> when (name) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file?.name ?: id
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (directory) DocumentsContract.Document.MIME_TYPE_DIR
                        else if (id.endsWith("-cue")) "application/x-cue" else "audio/wav"
                    DocumentsContract.Document.COLUMN_SIZE -> file?.length()
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> if (directory) null else 1000L
                    DocumentsContract.Document.COLUMN_FLAGS -> 0
                    else -> null
                } }.toTypedArray<Any?>())
            }
        }
    }
}

internal fun fixtureCueDocuments(context: Context, authority: String): CueDocumentsProviderFixture {
    val info = ProviderInfo().apply {
        this.authority = authority;
        packageName = context.packageName;
        applicationInfo = context.applicationInfo
        name = CueDocumentsProviderFixture::class.java.name;
        exported = true;
        grantUriPermissions = true
        readPermission = Manifest.permission.MANAGE_DOCUMENTS;
        writePermission = Manifest.permission.MANAGE_DOCUMENTS
    }
    val provider = Robolectric.buildContentProvider(CueDocumentsProviderFixture::class.java).create(info).get()
    val packages = Shadows.shadowOf(context.packageManager)
    packages.addOrUpdateProvider(info)
    packages.addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
    ShadowContentResolver.registerProviderInternal(authority, provider)
    return provider
}
