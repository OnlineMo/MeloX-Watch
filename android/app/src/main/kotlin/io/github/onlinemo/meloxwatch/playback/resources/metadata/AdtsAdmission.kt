package io.github.onlinemo.meloxwatch.playback.resources.metadata

/** ADTS frame 最多 8191 字节；夹带 ID3 在完整 metadata sample 提交前检查。 */
internal class AdtsAdmission : ContainerAdmission {
    private val header = ByteArray(10)
    private var headerSize = 0
    private var expected = 0L
    private var headerStart = 0L
    private var skip = 0
    private var search = 0
    private var tag: ByteArray? = null
    private var tagSize = 0
    private var tagGuard: Id3Admission? = null

    override fun seek(position: Long) {
        headerSize = 0
        expected = position
        skip = 0
        search = 0
        tag = null
        tagGuard = null
        tagSize = 0
    }
    override fun returned(position: Long, bytes: ByteArray, offset: Int, count: Int, peek: Boolean, length: Long) {
        if (peek) return
        if (position != expected) seek(position)
        var read = offset
        val end = offset + count
        val work = MetadataWork()
        while (read < end) {
            work.check()
            val pending = tag
            if (pending != null) {
                val size = minOf(end - read, pending.size - tagSize)
                bytes.copyInto(pending, tagSize, read, read + size)
                read += size
                tagSize += size
                if (tagSize == pending.size) {
                    checkNotNull(tagGuard).returned(headerStart + 10, pending, 10, pending.size - 10, false, length)
                    tag = null
                    tagGuard = null
                    headerSize = 0
                    search = 0
                }
            } else if (skip > 0) {
                val size = minOf(skip, end - read)
                skip -= size
                read += size
            } else {
                if (headerSize == 0) headerStart = position + read - offset
                header[headerSize++] = bytes[read++]
                if (headerSize == 3 && !header.matches(0, "ID3") &&
                    !(header[0].toInt() and 255 == 255 && header[1].toInt() and 246 == 240)) {
                    admit(++search <= 8192, MetadataAdmissionIssue.RESOURCE_LIMIT)
                    header[0] = header[1]
                    header[1] = header[2]
                    headerSize = 2
                    headerStart++
                }
                if (headerSize >= 3 && header.matches(0, "ID3")) {
                    if (headerSize == 10) {
                        val guard = Id3Admission(MetadataBudget())
                        guard.streamHeader(headerStart, header, length)
                        val size = MetadataCursor(header, 6, 10).syncSafe()
                        tag = ByteArray(size + 10).also { header.copyInto(it, 0, 0, 10) }
                        tagGuard = guard
                        tagSize = 10
                        if (size == 0) {
                            guard.returned(headerStart + 10, checkNotNull(tag), 10, 0, false, length)
                            tag = null
                            tagGuard = null
                            headerSize = 0
                            search = 0
                        }
                    }
                } else if (headerSize >= 7) {
                    val protection = header[1].toInt() and 1
                    val headerBytes = if (protection == 0) 9 else 7
                    if (headerSize == headerBytes) {
                        val rateIndex = (header[2].toInt() shr 2) and 15
                        val channels = ((header[2].toInt() and 1) shl 2) or ((header[3].toInt() ushr 6) and 3)
                        admit(rateIndex in 0..12 && channels in 1..2, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
                        val size = ((header[3].toInt() and 3) shl 11) or ((header[4].toInt() and 255) shl 3) or ((header[5].toInt() ushr 5) and 7)
                        admit(size >= headerBytes && size <= 8191)
                        skip = size - headerBytes
                        headerSize = 0
                        search = 0
                    }
                }
            }
        }
        expected = boundedAdd(position, count.toLong())
    }
    override fun release() {
        tag = null
        tagGuard = null
    }
}
