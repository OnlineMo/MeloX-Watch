package io.github.onlinemo.meloxwatch.playback.resources.metadata

/** WavHeaderReader 在 peek chunk header 后立即分配 fmt extraData，必须先核对长度。 */
internal class WaveAdmission : ContainerAdmission {
    private var nextChunk: Long? = null
    private var fmtStart: Long? = null
    private var fmtBytes = 0
    private var audio = false
    private var chunks = 0
    private var type = 0
    private var channels = 0
    private var bits = 0
    private var block = 0

    override fun returned(position: Long, bytes: ByteArray, offset: Int, count: Int, peek: Boolean, length: Long) {
        if (audio) return
        if (position == 0L && count == 8 && (bytes.matches(offset, "RIFF") || bytes.matches(offset, "RF64"))) {
            nextChunk = 12
            return
        }
        if (count == 8 && position == nextChunk) {
            val cursor = MetadataCursor(bytes, offset, offset + count)
            val id = cursor.fourCc()
            val size = cursor.u32(little = true)
            admit(++chunks <= MetadataLimits.BLOCKS, MetadataAdmissionIssue.RESOURCE_LIMIT)
            if (id == "data") {
                audio = true
                return
            }
            if (id == "fmt ") {
                admit(size in 16L..65_536L, MetadataAdmissionIssue.RESOURCE_LIMIT)
                fmtStart = boundedAdd(position, 8)
                fmtBytes = size.toInt()
            }
            if (id == "ds64") admit(size in 28L..65_536L, MetadataAdmissionIssue.RESOURCE_LIMIT)
            val end = boundedAdd(position, boundedAdd(8, size))
            admit(length < 0 || end <= length)
            nextChunk = boundedAdd(end, size and 1L)
            return
        }
        if (position == fmtStart && count == 16) {
            val cursor = MetadataCursor(bytes, offset, offset + count)
            type = cursor.u16(little = true)
            channels = cursor.u16(little = true)
            val rate = cursor.u32(little = true)
            cursor.skip(4)
            block = cursor.u16(little = true)
            bits = cursor.u16(little = true)
            admit(rate in 1L..192_000L && channels in 1..2, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            admit(type in setOf(1, 3, 17, 0xfffe), MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            if (type == 17) admit(bits == 4 && block >= channels * 4 && fmtBytes >= 20)
            else admit(bits in setOf(8, 16, 24, 32) && block == channels * (bits / 8))
            if (type == 3) admit(bits == 32, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            return
        }
        if (fmtStart != null && position == boundedAdd(checkNotNull(fmtStart), 16) && count == fmtBytes - 16) {
            val cursor = MetadataCursor(bytes, offset, offset + count)
            if (type == 17) {
                cursor.skip(2)
                val samples = cursor.u16(little = true)
                val expected = (block - channels * 4) * 8 / (bits * channels) + 1
                admit(samples > 0 && samples == expected)
            }
        }
    }
}
