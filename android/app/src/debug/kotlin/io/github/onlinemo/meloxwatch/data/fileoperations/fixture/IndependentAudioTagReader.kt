package io.github.onlinemo.meloxwatch.data.fileoperations.fixture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

internal data class IndependentTagRecord(val key: String, val raw: ByteArray)
internal data class IndependentTagFile(val fields: Map<String, List<String>>, val audio: ByteArray,
    val retained: List<IndependentTagRecord>, val vendor: ByteArray?)

/** 独立 oracle 不调用生产标签 codec、字段映射、摘要或解析工具；只读取本套件小型完整文件。 */
internal object IndependentAudioTagReader {
    fun read(bytes: ByteArray): IndependentTagFile = when {
        bytes.take(4).toByteArray().contentEquals("fLaC".toByteArray(Charsets.US_ASCII)) -> flac(bytes)
        bytes.take(3).toByteArray().contentEquals("ID3".toByteArray(Charsets.US_ASCII)) -> id3(bytes)
        else -> IndependentTagFile(emptyMap(), bytes, emptyList(), null)
    }

    private fun flac(bytes: ByteArray): IndependentTagFile {
        val fields = LinkedHashMap<String, MutableList<String>>()
        val retained = ArrayList<IndependentTagRecord>()
        var position = 4
        var vendor: ByteArray? = null
        while (true) {
            check(position + 4 <= bytes.size)
            val flag = bytes[position].toInt() and 255
            val length = ((bytes[position + 1].toInt() and 255) shl 16) or
                ((bytes[position + 2].toInt() and 255) shl 8) or (bytes[position + 3].toInt() and 255)
            val start = position + 4
            val end = Math.addExact(start, length)
            check(end <= bytes.size)
            val body = bytes.copyOfRange(start, end)
            if (flag and 127 == 4) {
                val input = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                fun blob(): ByteArray {
                    val count = input.int
                    check(count >= 0 && count <= input.remaining())
                    return ByteArray(count).also(input::get)
                }
                vendor = blob()
                val count = input.int
                check(count in 0..4096)
                repeat(count) {
                    val raw = blob()
                    val entry = String(raw, Charsets.UTF_8)
                    val separator = entry.indexOf('=')
                    check(separator > 0)
                    val key = entry.substring(0, separator).uppercase(Locale.ROOT)
                    if (key in setOf("TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "TRACKNUMBER", "DISCNUMBER")) {
                        fields.getOrPut(key, ::ArrayList).add(entry.substring(separator + 1))
                    } else retained += IndependentTagRecord("FLAC-COMMENT:$key", raw)
                }
                check(!input.hasRemaining())
            } else retained += IndependentTagRecord("FLAC:${flag and 127}", body)
            position = end
            if (flag and 128 != 0) break
        }
        check(position < bytes.size)
        return IndependentTagFile(fields, bytes.copyOfRange(position, bytes.size), retained, vendor)
    }

    private fun id3(bytes: ByteArray): IndependentTagFile {
        check(bytes.size >= 10 && bytes[3].toInt() == 4 && bytes[5].toInt() == 0)
        val end = 10 + syncsafe(bytes, 6)
        check(end <= bytes.size)
        val fields = LinkedHashMap<String, List<String>>()
        val retained = ArrayList<IndependentTagRecord>()
        var position = 10
        while (position < end && bytes[position].toInt() != 0) {
            check(position + 10 <= end)
            val id = String(bytes, position, 4, Charsets.US_ASCII)
            val size = syncsafe(bytes, position + 4)
            val next = Math.addExact(position + 10, size)
            check(size > 0 && next <= end)
            val name = when (id) {
                "TIT2" -> "TITLE"
                "TPE1" -> "ARTIST"
                "TALB" -> "ALBUM"
                "TPE2" -> "ALBUMARTIST"
                "TRCK" -> "TRACKNUMBER"
                "TPOS" -> "DISCNUMBER"
                else -> null
            }
            if (name == null) retained += IndependentTagRecord("ID3:$id", bytes.copyOfRange(position, next))
            else {
                check(bytes[position + 10].toInt() == 3)
                fields[name] = String(bytes, position + 11, size - 1, Charsets.UTF_8).split('\u0000')
            }
            position = next
        }
        check(bytes.copyOfRange(position, end).all { it.toInt() == 0 })
        return IndependentTagFile(fields, bytes.copyOfRange(end, bytes.size), retained, null)
    }

    private fun syncsafe(bytes: ByteArray, at: Int): Int {
        var value = 0
        for (index in at until at + 4) {
            val octet = bytes[index].toInt() and 255
            check(octet < 128)
            value = value * 128 + octet
        }
        return value
    }

    fun requirePreserved(before: IndependentTagFile, after: IndependentTagFile) {
        check(before.audio.contentEquals(after.audio))
        check(before.vendor?.contentEquals(after.vendor ?: byteArrayOf()) ?: (after.vendor == null))
        check(before.retained.size == after.retained.size)
        before.retained.zip(after.retained).forEach { (old, current) ->
            check(old.key == current.key && old.raw.contentEquals(current.raw))
        }
    }
}
