package io.github.onlinemo.meloxwatch.playback.resources.metadata

/**
 * 固定 Sniffer 先 peek header，再 reset ftyp scratch 和兼容品牌数组。
 * moov 可扩大搜索范围；generic peek 上限在 reset 之后才触发，因此另在 header 返回前准入。
 */
internal class Mp4SniffAdmission(start: Long) : ContainerAdmission {
    private var expectedHeader = start
    private var largeStart = -1L
    private var largeType: String? = null
    private var largeScratch: ByteArray? = null
    private var atoms = 0

    override fun returned(position: Long, bytes: ByteArray, offset: Int, count: Int, peek: Boolean, length: Long) {
        if (!peek) return
        if (count == 8 && offset == 0 && bytes.size >= 64 && position == expectedHeader) {
            val cursor = MetadataCursor(bytes, 0, 8)
            val size = cursor.u32()
            val type = cursor.fourCc()
            if (size == 1L) {
                largeStart = position
                largeType = type
                largeScratch = bytes
                return
            }
            val actual = if (size == 0L && length >= 0) length - position else size
            atom(position, type, actual, 8, length)
        } else if (count == 8 && offset == 8 && bytes === largeScratch && position == largeStart + 8) {
            val cursor = MetadataCursor(bytes, 8, 16)
            val high = cursor.u32()
            val low = cursor.u32()
            admit(high <= 0x7fff_ffffL)
            atom(largeStart, checkNotNull(largeType), (high shl 32) or low, 16, length)
            largeType = null
            largeScratch = null
        }
    }

    private fun atom(start: Long, type: String, size: Long, header: Int, length: Long) {
        admit(size >= header)
        val end = boundedAdd(start, size)
        admit(length < 0 || end <= length)
        admit(++atoms <= MetadataLimits.ATOMS, MetadataAdmissionIssue.RESOURCE_LIMIT)
        if (type == "ftyp") {
            admit(size <= 4096, MetadataAdmissionIssue.RESOURCE_LIMIT)
        }
        if (type == "moov") {
            admit(size <= Int.MAX_VALUE - 4096L, MetadataAdmissionIssue.RESOURCE_LIMIT)
            expectedHeader = boundedAdd(start, header.toLong())
        } else {
            expectedHeader = end
        }
    }
}
