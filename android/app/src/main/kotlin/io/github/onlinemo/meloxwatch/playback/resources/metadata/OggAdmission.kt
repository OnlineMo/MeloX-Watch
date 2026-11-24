package io.github.onlinemo.meloxwatch.playback.resources.metadata

/** 在 OggPacket.ensureCapacity 之前累计 lacing，包含同一 packet 跨多页的部分。 */
internal class OggAdmission(private val budget: MetadataBudget) : ContainerAdmission {
    private var packetPageScratch: ByteArray? = null
    private var pageStart = -1L
    private var pageSegments = 0
    private var pageFlags = 0
    private var serial = -1L
    private var carried = 0L
    private val starts = LongArray(255)
    private val sizes = IntArray(255)
    private val complete = BooleanArray(255)
    private var chunks = 0
    private var chunk = 0
    private var codec = 0
    private var headerPackets = 0
    private var audio = false
    private var pagesWithoutPacket = 0

    override fun returned(position: Long, bytes: ByteArray, offset: Int, count: Int, peek: Boolean, length: Long) {
        if (peek && count == 27 && bytes.matches(offset, "OggS")) {
            if (packetPageScratch == null) packetPageScratch = bytes
            // DefaultOggSeeker 有另一份 page scratch；随机 probe 不修改 OggPacket 仍保留的 packet 状态。
            if (packetPageScratch !== bytes) return
            admit(++pagesWithoutPacket <= MetadataLimits.BLOCKS, MetadataAdmissionIssue.RESOURCE_LIMIT)
            val cursor = MetadataCursor(bytes, offset + 4, offset + count)
            admit(cursor.u8() == 0, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            pageFlags = cursor.u8()
            cursor.skip(8)
            val nextSerial = cursor.u32(little = true)
            cursor.skip(8)
            pageSegments = cursor.u8()
            if (serial != nextSerial) {
                admit(carried == 0L)
                serial = nextSerial
            }
            if (pageFlags and 1 == 0) admit(carried == 0L)
            pageStart = position
            chunks = 0
            chunk = 0
            return
        }
        if (peek && bytes === packetPageScratch && position == boundedAdd(pageStart, 27) && count == pageSegments) {
            var packetBytes = carried
            var chunkBytes = 0
            var next = boundedAdd(pageStart, 27L + pageSegments)
            val work = MetadataWork()
            for (index in 0 until count) {
                work.check()
                val lace = bytes[offset + index].toInt() and 255
                packetBytes = boundedAdd(packetBytes, lace.toLong())
                admit(packetBytes <= MetadataLimits.RAW_BLOCK, MetadataAdmissionIssue.RESOURCE_LIMIT)
                chunkBytes += lace
                if (lace < 255 || index == count - 1) {
                    starts[chunks] = next
                    sizes[chunks] = chunkBytes
                    complete[chunks] = lace < 255
                    chunks++
                    next = boundedAdd(next, chunkBytes.toLong())
                    chunkBytes = 0
                    if (lace < 255) {
                        if (packetBytes > 0) pagesWithoutPacket = 0
                        packetBytes = 0
                    }
                }
            }
            carried = packetBytes
            admit(length < 0 || next <= length)
            return
        }
        while (chunk < chunks && sizes[chunk] == 0) chunk++
        if (!peek && chunk < chunks && position == starts[chunk] && count == sizes[chunk]) {
            val finished = complete[chunk++]
            if (finished) packet(bytes, offset + count)
        }
    }
    override fun skipped(position: Long, count: Int) {
        val end = boundedAdd(position, count.toLong())
        while (chunk < chunks && boundedAdd(starts[chunk], sizes[chunk].toLong()) <= end) chunk++
    }
    override fun seek(position: Long) {
        carried = 0
        chunks = 0
        chunk = 0
        pageStart = -1
        pagesWithoutPacket = 0
    }
    override fun release() { packetPageScratch = null }

    private fun packet(bytes: ByteArray, end: Int) {
        admit(end in 1..MetadataLimits.RAW_BLOCK, MetadataAdmissionIssue.RESOURCE_LIMIT)
        if (audio) return
        if (codec != 0) {
            val header = when (codec) {
                1 -> (bytes[0].toInt() == 3 || bytes[0].toInt() == 5) && bytes.matches(1, "vorbis")
                2 -> bytes.matches(0, "OpusTags")
                else -> bytes[0].toInt() and 255 != 255
            }
            if (!header) {
                audio = true
                return
            }
        }
        admit(++headerPackets <= MetadataLimits.BLOCKS, MetadataAdmissionIssue.RESOURCE_LIMIT)
        budget.retain(end)
        if (codec == 0) {
            codec = when {
                bytes[0].toInt() == 1 && bytes.matches(1, "vorbis") -> 1
                bytes.matches(0, "OpusHead") -> 2
                bytes[0].toInt() and 255 == 127 && bytes.matches(1, "FLAC") -> 3
                else -> throw MetadataAdmissionException(MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            }
            when (codec) {
                1 -> {
                    val cursor = MetadataCursor(bytes, 7, end)
                    admit(cursor.u32(little = true) == 0L, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
                    val channels = cursor.u8()
                    val rate = cursor.u32(little = true)
                    admit(channels in 1..2 && rate in 1L..192_000L, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
                    cursor.skip(12)
                    val block = cursor.u8()
                    admit((block and 15) in 6..13 && (block shr 4) in 6..13 && (block and 15) <= (block shr 4))
                    admit(cursor.u8() and 1 != 0)
                }
                2 -> {
                    admit(end in 19..256)
                    val version = bytes[8].toInt() and 255
                    val channels = bytes[9].toInt() and 255
                    admit(version <= 15 && channels in 1..2, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
                }
                3 -> {
                    admit(end == 51 && bytes.matches(9, "fLaC"))
                    VorbisAdmission.streamInfo(bytes, 17, 51)
                }
            }
            return
        }
        when (codec) {
            1 -> when {
                bytes[0].toInt() == 3 && bytes.matches(1, "vorbis") -> VorbisAdmission.comments(bytes, 7, end, budget)
                bytes[0].toInt() == 5 && bytes.matches(1, "vorbis") -> VorbisSetupAdmission.books(bytes, end)
                else -> audio = true
            }
            2 -> if (bytes.matches(0, "OpusTags")) VorbisAdmission.comments(bytes, 8, end, budget) else audio = true
            3 -> if (bytes[0].toInt() and 255 == 255) audio = true else {
                val cursor = MetadataCursor(bytes, 0, end)
                val type = cursor.u8() and 127
                val size = cursor.u24()
                admit(size == cursor.left)
                when (type) {
                    3 -> VorbisAdmission.seekTable(bytes, 0, end)
                    4 -> VorbisAdmission.comments(bytes, 4, end, budget)
                    6 -> VorbisAdmission.picture(bytes, 4, end)
                }
            }
        }
    }
}
