package io.github.onlinemo.meloxwatch.playback.resources.metadata

/** Native FLAC reader 先 peek 四字节 block header，再分配并读取整个被选择的 block。 */
internal class FlacAdmission(private val budget: MetadataBudget) : ContainerAdmission {
    private data class Block(val start: Long, val bytes: Int, val type: Int, val last: Boolean)
    private var nextHeader: Long? = null
    private var block: Block? = null
    private var blocks = 0
    private var span = 0L

    override fun returned(position: Long, bytes: ByteArray, offset: Int, count: Int, peek: Boolean, length: Long) {
        if (!peek && count == 4 && bytes.matches(offset, "fLaC")) {
            nextHeader = boundedAdd(position, 4)
            return
        }
        if (peek && count == 4 && position == nextHeader) {
            val cursor = MetadataCursor(bytes, offset, offset + count)
            val flags = cursor.u8()
            val size = cursor.u24()
            val type = flags and 127
            admit(++blocks <= MetadataLimits.BLOCKS, MetadataAdmissionIssue.RESOURCE_LIMIT)
            span = boundedAdd(span, size + 4L)
            admit(span <= 32L * 1024 * 1024, MetadataAdmissionIssue.RESOURCE_LIMIT)
            val end = boundedAdd(position, size + 4L)
            admit(length < 0 || end <= length)
            if (type in setOf(0, 3, 4, 6)) {
                admit(size <= MetadataLimits.RAW_BLOCK, MetadataAdmissionIssue.RESOURCE_LIMIT)
                budget.retain(size)
            }
            if (type == 0) admit(size == 34)
            if (type == 3) admit(size % 18 == 0 && size / 18 <= MetadataLimits.SEEK_POINTS,
                MetadataAdmissionIssue.RESOURCE_LIMIT)
            block = Block(position, size + 4, type, flags and 128 != 0)
            return
        }
        val current = block ?: return
        if (!peek && position == current.start && count == current.bytes) {
            when (current.type) {
                0 -> VorbisAdmission.streamInfo(bytes, offset + 4, offset + count)
                3 -> VorbisAdmission.seekTable(bytes, offset, offset + count)
                4 -> VorbisAdmission.comments(bytes, offset + 4, offset + count, budget)
                6 -> {
                    budget.entry()
                    VorbisAdmission.picture(bytes, offset + 4, offset + count)
                }
            }
            completed(current)
        }
    }
    override fun skipped(position: Long, count: Int) {
        val current = block ?: return
        if (position == current.start && count == current.bytes) completed(current)
    }
    private fun completed(current: Block) {
        nextHeader = if (current.last) null else boundedAdd(current.start, current.bytes.toLong())
        block = null
    }
}
