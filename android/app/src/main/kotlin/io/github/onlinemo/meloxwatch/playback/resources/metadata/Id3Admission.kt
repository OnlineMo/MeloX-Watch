package io.github.onlinemo.meloxwatch.playback.resources.metadata

/** Id3Peeker 先读取十字节 header；在它分配整 tag 及解析内部对象前核对各层边界。 */
internal class Id3Admission(private val budget: MetadataBudget) : ContainerAdmission {
    private data class Tag(val start: Long, val size: Int, val version: Int, val flags: Int, var entriesPeak: Int = 0)
    private class FrameBudget {
        var entries = 0
            private set
        private var copies = 0L
        fun entry() {
            admit(++entries <= MetadataLimits.ENTRIES, MetadataAdmissionIssue.RESOURCE_LIMIT)
        }
        fun copy(bytes: Int) {
            copies = boundedAdd(copies, bytes.toLong())
            admit(copies <= MetadataLimits.RETAINED_BYTES, MetadataAdmissionIssue.RESOURCE_LIMIT)
        }
    }
    private val tags = LinkedHashMap<Long, Tag>()
    private var nextHeader = 0L

    fun streamHeader(position: Long, bytes: ByteArray, length: Long) {
        nextHeader = position
        returned(position, bytes, 0, 10, false, length)
    }

    override fun returned(position: Long, bytes: ByteArray, offset: Int, count: Int, peek: Boolean, length: Long) {
        if (count == 10 && bytes.matches(offset, "ID3") && (position == nextHeader || tags.containsKey(position))) {
            val header = MetadataCursor(bytes, offset + 3, offset + count)
            val version = header.u8()
            header.skip(1)
            val flags = header.u8()
            admit(version in 2..4, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            admit(version != 2 || flags and 64 == 0, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            val size = header.syncSafe()
            admit(size <= MetadataLimits.RAW_BLOCK, MetadataAdmissionIssue.RESOURCE_LIMIT)
            val end = boundedAdd(position, size + 10L)
            admit(length < 0 || end <= length)
            val previous = tags[position]
            if (previous == null) {
                admit(tags.size < 16, MetadataAdmissionIssue.RESOURCE_LIMIT)
                budget.retain(size)
                tags[position] = Tag(position, size, version, flags)
            } else {
                admit(previous.size == size && previous.version == version && previous.flags == flags)
            }
            nextHeader = end
            return
        }
        val tag = tags.values.firstOrNull { boundedAdd(it.start, 10) == position && it.size == count } ?: return
        // header 已限制原始数组；仍须在 Id3Decoder 创建嵌套 frame 或 MLLT 数组前检查内部计数。
        validate(bytes, offset, offset + count, tag)
    }

    private fun validate(bytes: ByteArray, start: Int, end: Int, tag: Tag) {
        val cursor = MetadataCursor(bytes, start, end)
        val framesBudget = FrameBudget()
        if (tag.flags and 64 != 0) {
            admit(tag.version >= 3, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            val length = if (tag.version == 4) cursor.syncSafe() else cursor.count(MetadataLimits.RAW_BLOCK)
            if (tag.version == 4) {
                admit(length >= 4)
                cursor.skip(length - 4)
            } else {
                cursor.skip(length)
            }
        }
        val frameEnd = if (tag.version == 4 && tag.flags and 16 != 0) end - 10 else end
        admit(frameEnd >= cursor.position)
        if (tag.version < 4 && tag.flags and 128 != 0) {
            val decoded = unescape(bytes, cursor.position, frameEnd, cursor.work, framesBudget)
            frames(MetadataCursor(decoded.first, 0, decoded.second, cursor.work), tag.version, 0, framesBudget)
        } else {
            frames(MetadataCursor(bytes, cursor.position, frameEnd, cursor.work), tag.version, 0, framesBudget)
        }
        // 重复 peek 仍重新核对内容，但同一个 tag 的 retained entries 只按峰值计入。
        val additional = maxOf(0, framesBudget.entries - tag.entriesPeak)
        budget.entry(additional)
        tag.entriesPeak = maxOf(tag.entriesPeak, framesBudget.entries)
    }

    private fun frames(cursor: MetadataCursor, version: Int, depth: Int, framesBudget: FrameBudget) {
        admit(depth <= MetadataLimits.DEPTH, MetadataAdmissionIssue.RESOURCE_LIMIT)
        val header = if (version == 2) 6 else 10
        while (cursor.left >= header) {
            cursor.work.check()
            val id = if (version == 2) {
                cursor.need(3)
                val value = String(cursor.bytes, cursor.position, 3, Charsets.US_ASCII)
                cursor.skip(3)
                value
            } else cursor.fourCc()
            val size = when (version) {
                2 -> cursor.u24()
                4 -> cursor.syncSafe()
                else -> cursor.count(MetadataLimits.RAW_BLOCK)
            }
            val flags = if (version >= 3) cursor.u16() else 0
            if (id.all { it.code == 0 } && size == 0 && flags == 0) return
            cursor.need(size)
            framesBudget.entry()
            val next = cursor.position + size
            val compressedOrEncrypted = if (version == 4) flags and 12 != 0 else flags and 192 != 0
            if (!compressedOrEncrypted) {
                var payload = MetadataCursor(cursor.bytes, cursor.position, next, cursor.work)
                if (flags and (if (version == 4) 64 else 32) != 0) payload.skip(1)
                if (version == 4 && flags and 1 != 0) payload.skip(4)
                if (version == 4 && flags and 2 != 0) {
                    val decoded = unescape(payload.bytes, payload.position, payload.end, cursor.work, framesBudget)
                    payload = MetadataCursor(decoded.first, 0, decoded.second, cursor.work)
                }
                frame(id, payload, version, depth, framesBudget)
            }
            cursor.position = next
        }
    }

    private fun frame(id: String, cursor: MetadataCursor, version: Int, depth: Int, framesBudget: FrameBudget) {
        when {
            id.startsWith("T") -> {
                admit(cursor.left <= MetadataLimits.TEXT_BYTES, MetadataAdmissionIssue.RESOURCE_LIMIT)
                if (cursor.left == 0) return
                val encoding = cursor.u8()
                admit(encoding in 0..3, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
                var values = 1
                if (encoding == 0 || encoding == 3) {
                    while (cursor.left > 0) if (cursor.u8() == 0) admit(++values <= 129, MetadataAdmissionIssue.RESOURCE_LIMIT)
                } else {
                    while (cursor.left >= 2) if (cursor.u16() == 0) admit(++values <= 129, MetadataAdmissionIssue.RESOURCE_LIMIT)
                }
            }
            id.startsWith("W") || id == "COMM" || id == "COM" -> {
                admit(cursor.left <= MetadataLimits.TEXT_BYTES, MetadataAdmissionIssue.RESOURCE_LIMIT)
            }
            id == "APIC" || id == "PIC" -> {
                val encoding = cursor.u8()
                if (version == 2) cursor.skip(3) else cursor.zeroTerminated(128)
                cursor.skip(1)
                encodedTerminated(cursor, encoding)
            }
            id == "GEOB" || id == "GEO" -> {
                val encoding = cursor.u8()
                cursor.zeroTerminated(128)
                encodedTerminated(cursor, encoding)
                encodedTerminated(cursor, encoding)
            }
            id == "PRIV" -> cursor.zeroTerminated()
            id == "MLLT" -> {
                cursor.skip(8)
                val bytesBits = cursor.u8()
                val millisBits = cursor.u8()
                admit(bytesBits in 0..32 && millisBits in 0..32 && bytesBits + millisBits > 0)
                val references = cursor.left.toLong() * 8 / (bytesBits + millisBits)
                admit(references <= MetadataLimits.SEEK_POINTS, MetadataAdmissionIssue.RESOURCE_LIMIT)
            }
            id == "CHAP" -> {
                cursor.zeroTerminated()
                cursor.skip(16)
                frames(cursor, version, depth + 1, framesBudget)
            }
            id == "CTOC" -> {
                cursor.zeroTerminated()
                cursor.skip(1)
                repeat(cursor.u8()) { cursor.zeroTerminated() }
                frames(cursor, version, depth + 1, framesBudget)
            }
        }
    }

    private fun encodedTerminated(cursor: MetadataCursor, encoding: Int) {
        admit(encoding in 0..3, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
        var bytes = 0
        while (true) {
            val width = if (encoding == 0 || encoding == 3) 1 else 2
            val value = if (width == 1) cursor.u8() else cursor.u16()
            if (value == 0) return
            bytes += width
            admit(bytes <= MetadataLimits.TEXT_BYTES, MetadataAdmissionIssue.RESOURCE_LIMIT)
        }
    }

    private fun unescape(
        bytes: ByteArray,
        start: Int,
        end: Int,
        work: MetadataWork,
        framesBudget: FrameBudget,
    ): Pair<ByteArray, Int> {
        admit(end - start <= MetadataLimits.RAW_BLOCK, MetadataAdmissionIssue.RESOURCE_LIMIT)
        framesBudget.copy(end - start)
        val result = ByteArray(end - start)
        var read = start
        var written = 0
        while (read < end) {
            work.check()
            val value = bytes[read++]
            result[written++] = value
            if (value.toInt() and 255 == 255 && read < end && bytes[read].toInt() == 0) read++
        }
        return result to written
    }
}
