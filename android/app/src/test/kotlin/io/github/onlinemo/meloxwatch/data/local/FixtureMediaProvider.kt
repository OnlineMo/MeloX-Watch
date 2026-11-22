package io.github.onlinemo.meloxwatch.data.local

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric

/** The Android 30 MediaStore APIs obtain their real version/generation through these provider calls. */
class FixtureMediaProvider : ContentProvider() {
    lateinit var audio: File
    var identifier = 7L
    var generationModified: Long? = 17L
    var generationAdded = 4L
    var version: String? = "fixture-version"

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = "audio/mpeg"

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = when (method) {
        "get_version" -> Bundle().apply { putString(Intent.EXTRA_TEXT, version) }
        "get_generation" -> Bundle().apply { putLong(Intent.EXTRA_INDEX, generationModified ?: 0L) }
        else -> throw UnsupportedOperationException("Unexpected MediaStore call")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(MediaStore.Audio.Media._ID)
        return MatrixCursor(columns, 1).apply {
            if (audio.isFile) {
                val row: List<Any?> = columns.map { column ->
                    when (column) {
                        MediaStore.Audio.Media._ID -> identifier
                        MediaStore.Audio.Media.DISPLAY_NAME -> audio.name
                        MediaStore.Audio.Media.MIME_TYPE -> "audio/mpeg"
                        MediaStore.Audio.Media.SIZE -> audio.length()
                        MediaStore.Audio.Media.DATE_MODIFIED -> 1L
                        MediaStore.Audio.Media.DATE_ADDED -> 1L
                        MediaStore.MediaColumns.VOLUME_NAME -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                        MediaStore.MediaColumns.GENERATION_ADDED -> generationAdded
                        MediaStore.MediaColumns.GENERATION_MODIFIED -> generationModified
                        else -> null
                    }
                }
                addRow(row)
            }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || !audio.isFile || uri.lastPathSegment != identifier.toString()) {
            throw FileNotFoundException("Fixture media unavailable")
        }
        return ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}

internal fun fixtureMediaProvider(audio: File): FixtureMediaProvider {
    val info = ProviderInfo().apply { authority = "media"; exported = false }
    return Robolectric.buildContentProvider(FixtureMediaProvider::class.java).create(info).get().also { it.audio = audio }
}
