package io.github.onlinemo.meloxwatch.playback.resources.metadata

import android.util.Base64

/** 在 VorbisUtil 的 String[count] 与 PictureFrame 图片数组创建前，核对内部长度和计数。 */
internal object VorbisAdmission {
    fun comments(bytes: ByteArray, start: Int, end: Int, budget: MetadataBudget) {
        val cursor = MetadataCursor(bytes, start, end)
        cursor.textBytes(cursor.count(MetadataLimits.TEXT_BYTES, little = true))
        val count = cursor.count(MetadataLimits.ENTRIES, little = true)
        admit(count.toLong() * 4 <= cursor.left)
        budget.entry(count)
        repeat(count) {
            val size = cursor.count(MetadataLimits.RAW_BLOCK, little = true)
            cursor.need(size)
            val pictureKey = "METADATA_BLOCK_PICTURE="
            if (bytes.matches(cursor.position, pictureKey)) {
                val offset = cursor.position + pictureKey.length
                val length = size - pictureKey.length
                admit(length >= 0)
                val decoded = try { Base64.decode(bytes, offset, length, Base64.DEFAULT) }
                catch (_: IllegalArgumentException) { throw MetadataAdmissionException(MetadataAdmissionIssue.MALFORMED) }
                picture(decoded, 0, decoded.size)
            } else admit(size <= MetadataLimits.TEXT_BYTES, MetadataAdmissionIssue.RESOURCE_LIMIT)
            cursor.skip(size)
        }
    }

    fun picture(bytes: ByteArray, start: Int, end: Int) {
        val cursor = MetadataCursor(bytes, start, end)
        cursor.skip(4)
        cursor.textBytes(cursor.count(128))
        cursor.textBytes(cursor.count(MetadataLimits.TEXT_BYTES))
        val width = cursor.u32()
        val height = cursor.u32()
        admit(width <= 16_000_000 && height <= 16_000_000 && width * height <= 16_000_000,
            MetadataAdmissionIssue.RESOURCE_LIMIT)
        cursor.skip(8)
        val size = cursor.count(MetadataLimits.RAW_BLOCK)
        cursor.need(size)
        // PictureFrame 原解析器信任内部 uint32；很小的外层 block 也必须校验真实可用字节。
    }

    fun streamInfo(bytes: ByteArray, start: Int, end: Int) {
        admit(end - start == 34)
        val cursor = MetadataCursor(bytes, start, end)
        val minimum = cursor.u16()
        val maximum = cursor.u16()
        admit(minimum in 16..65_535 && maximum in minimum..65_535)
        val minFrame = cursor.u24()
        val maxFrame = cursor.u24()
        admit(minFrame <= MetadataLimits.AUDIO_FRAME && maxFrame <= MetadataLimits.AUDIO_FRAME,
            MetadataAdmissionIssue.RESOURCE_LIMIT)
        val first = cursor.u8()
        val second = cursor.u8()
        val third = cursor.u8()
        val fourth = cursor.u8()
        val rate = (first shl 12) or (second shl 4) or (third shr 4)
        val channels = ((third shr 1) and 7) + 1
        val bits = ((third and 1) shl 4) + (fourth shr 4) + 1
        admit(rate in 1..192_000 && channels in 1..2 && bits in 4..32, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
    }

    fun seekTable(bytes: ByteArray, start: Int, end: Int) {
        val cursor = MetadataCursor(bytes, start, end)
        cursor.skip(1)
        val size = cursor.u24()
        admit(size % 18 == 0 && size <= cursor.left)
        admit(size / 18 <= MetadataLimits.SEEK_POINTS, MetadataAdmissionIssue.RESOURCE_LIMIT)
    }
}
