package io.github.onlinemo.meloxwatch.data.fileoperations.fixture

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationTarget
import io.github.onlinemo.meloxwatch.data.fileoperations.FileResourceOwnership
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** 根据格式规范构造小型静音输入；是否可被 Android 解码由独立设备用例判断。 */
internal object AudioWriteFixtures {
    fun flac(): ByteArray {
        val streamInfo = ByteArray(34)
        streamInfo[1] = 64
        streamInfo[3] = 64
        val pcm = ByteArray(128)
        val sampleDescription = (44_100L shl 44) or (15L shl 36) or 64L
        ByteBuffer.wrap(streamInfo, 10, 8).putLong(sampleDescription)
        MessageDigest.getInstance("MD5").digest(pcm).copyInto(streamInfo, 18)
        val comment = ByteArrayOutputStream().apply {
            val vendor = "independent-lp10-fixture".toByteArray(Charsets.UTF_8)
            little(vendor.size)
            write(vendor)
            val entries = listOf("TITLE=Before", "ARTIST=Before artist", "ALBUM=Keep album", "X-TEST=opaque=保留")
            little(entries.size)
            for (entry in entries) {
                val bytes = entry.toByteArray(Charsets.UTF_8)
                little(bytes.size)
                write(bytes)
            }
        }.toByteArray()
        val frame = ByteArrayOutputStream().apply {
            val header = byteArrayOf(0xff.toByte(), 0xf8.toByte(), 0x69, 0x08, 0, 63)
            write(header)
            write(crc(header, 8, 0x07))
            write(2)
            write(pcm)
            val checksum = crc(toByteArray(), 16, 0x8005)
            write(checksum ushr 8)
            write(checksum)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.US_ASCII))
            flacBlock(0, streamInfo, false)
            flacBlock(2, "TEST".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 1, 0x7f, 0xff.toByte()), false)
            flacBlock(4, comment, false)
            flacBlock(1, ByteArray(8), true)
            write(frame)
        }.toByteArray()
    }

    fun mp3(tagged: Boolean = true): ByteArray {
        val frame = ByteArray(417).apply {
            byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0xc4.toByte()).copyInto(this)
        }
        val audio = ByteArrayOutputStream().apply { repeat(12) { write(frame) } }.toByteArray()
        if (!tagged) return audio
        val payload = ByteArrayOutputStream().apply {
            id3Frame("TIT2", byteArrayOf(3) + "Before".toByteArray(Charsets.UTF_8))
            id3Frame("TPE1", byteArrayOf(3) + "Before artist".toByteArray(Charsets.UTF_8))
            id3Frame("TALB", byteArrayOf(3) + "Keep album".toByteArray(Charsets.UTF_8))
            id3Frame("PRIV", "independent-lp10\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0x7f, 0xff.toByte()))
        }.toByteArray()
        return byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0) + syncsafe(payload.size) + payload + audio
    }

    private fun ByteArrayOutputStream.flacBlock(type: Int, body: ByteArray, last: Boolean) {
        write(type or if (last) 0x80 else 0)
        write(body.size ushr 16)
        write(body.size ushr 8)
        write(body.size)
        write(body)
    }
    private fun ByteArrayOutputStream.id3Frame(id: String, body: ByteArray) {
        write(id.toByteArray(Charsets.US_ASCII))
        write(syncsafe(body.size))
        write(0)
        write(0)
        write(body)
    }
    private fun ByteArrayOutputStream.little(value: Int) { repeat(4) { write(value ushr (8 * it)) } }
    private fun syncsafe(value: Int): ByteArray = byteArrayOf((value ushr 21).toByte(), (value ushr 14 and 127).toByte(),
        (value ushr 7 and 127).toByte(), (value and 127).toByte())
    private fun crc(bytes: ByteArray, width: Int, polynomial: Int): Int {
        val mask = (1 shl width) - 1
        var value = 0
        for (byte in bytes) {
            value = value xor ((byte.toInt() and 255) shl (width - 8))
            repeat(8) {
                value = if (value and (1 shl (width - 1)) != 0) (value shl 1) xor polynomial else value shl 1
                value = value and mask
            }
        }
        return value
    }
}

internal fun fixtureFileTarget(context: Context, authority: String, tree: FixtureDocumentTree, documentId: String): FileOperationTarget {
    val treeUri = DocumentsContract.buildTreeDocumentUri(authority, FixtureDocumentTree.ROOT_ID)
    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    context.grantUriPermission(context.packageName, treeUri, flags)
    val grant = LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE,
        write = ObservedCapability.AVAILABLE, delete = ObservedCapability.AVAILABLE, rename = ObservedCapability.AVAILABLE,
        move = ObservedCapability.AVAILABLE, returnedFlags = flags)
    val spec = LocalSourceSpec("fixture:$authority", LocalSourceKind.TREE, treeUri.toString(), flags,
        authority = authority, rootObjectId = FixtureDocumentTree.ROOT_ID, providerIncarnation = "fixture-instance")
    val source = LibrarySource(spec, grant, "fixture-grant", 1L, 1L, LibrarySourceState.ACTIVE, null, null)
    val key = TrackKey(TrackKey.LOCAL_PROVIDER, "fixture:$documentId")
    val location = LocalLocationReference("fixture-location:$documentId", uri.toString(), treeUri.toString(), flags,
        LocalAccess.TEMPORARY, 1L)
    val descriptor = LocalReadDescriptor(key, source, "fixture-alias:$documentId", location, documentId, null, tree.file(documentId).length())
    return FileOperationTarget("fixture-unit:$documentId", key, "fixture-version", descriptor, FixtureDocumentTree.ROOT_ID,
        FileResourceOwnership.EXTERNAL_ORIGINAL, null)
}
