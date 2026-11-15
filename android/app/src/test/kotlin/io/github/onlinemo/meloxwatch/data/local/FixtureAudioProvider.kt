package io.github.onlinemo.meloxwatch.data.local

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric

/** A file-backed provider fixture; tests can expose weak facts and actual access failures. */
class FixtureAudioProvider : ContentProvider() {
    lateinit var audio: File
    var thumbnail: File? = null
    var denied = false
    var openDenied = false
    var audioReads = 0
    var queryReads = 0
    var thumbnailReads = 0
    var thumbnailSize: Point? = null
    var orientation = 0
    var mimeType = "audio/mpeg"
    var beforeThumbnail: (() -> Unit)? = null
    var beforeQuery: (() -> Unit)? = null

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = mimeType
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (denied || openDenied) throw SecurityException("Controlled fixture denial")
        if (mode != "r" || uri.path != "/audio" || !audio.isFile) throw FileNotFoundException("Fixture unavailable")
        audioReads++
        return ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Suppress("DEPRECATION")
    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor {
        signal?.throwIfCanceled()
        if (denied) throw SecurityException("Controlled fixture denial")
        // Ordinary resolver reads use */*; only the explicit image request selects the thumbnail.
        if (mimeTypeFilter != "image/*") {
            return super.openTypedAssetFile(uri, mimeTypeFilter, opts, signal)
                ?: throw FileNotFoundException("Fixture unavailable")
        }
        beforeThumbnail?.invoke()
        thumbnailReads++
        thumbnailSize = opts?.getParcelable(android.content.ContentResolver.EXTRA_SIZE)
        val selected = thumbnail ?: throw FileNotFoundException("No fixture thumbnail")
        return AssetFileDescriptor(ParcelFileDescriptor.open(selected, ParcelFileDescriptor.MODE_READ_ONLY), 0, selected.length(),
            Bundle().apply { putInt(DocumentsContract.EXTRA_ORIENTATION, orientation) })
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        queryReads++
        beforeQuery?.invoke()
        if (denied) throw SecurityException("Controlled fixture denial")
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            if (audio.isFile) {
                val row: List<Any?> = columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> audio.name
                        OpenableColumns.SIZE -> audio.length()
                        else -> null
                    }
                }
                addRow(row)
            }
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

internal fun fixtureAudioProvider(authority: String, audio: File): FixtureAudioProvider {
    val info = ProviderInfo().apply { this.authority = authority; exported = false }
    return Robolectric.buildContentProvider(FixtureAudioProvider::class.java).create(info).get().also { it.audio = audio }
}

internal fun fixtureDescriptor(authority: String, sizeBytes: Long?, verifiedToken: String? = null): LocalReadDescriptor {
    val uri = "content://$authority/audio"
    val objectId = "uri:$uri"
    val spec = LocalSourceSpec("source:$authority", LocalSourceKind.DOCUMENT, uri, authority = authority, rootObjectId = objectId)
    val source = LibrarySource(spec, LocalGrantObservation(read = ObservedCapability.AVAILABLE), "grant", 1, 1,
        LibrarySourceState.ACTIVE, null, null)
    return LocalReadDescriptor(TrackKey.newLocal(), source, "alias", LocalLocationReference("location", uri, contentRevision = 1),
        objectId, null, sizeBytes, verifiedContentToken = verifiedToken)
}

/** Only bounded complete fixture bytes form a content proof, matching the production weak-source contract. */
internal fun fixtureDescriptor(authority: String, file: File): LocalReadDescriptor {
    val token = file.inputStream().use { ContentFingerprint.read(it, CancellationSignal(), file.length()) }
    return fixtureDescriptor(authority, file.length(), requireNotNull(token))
}
