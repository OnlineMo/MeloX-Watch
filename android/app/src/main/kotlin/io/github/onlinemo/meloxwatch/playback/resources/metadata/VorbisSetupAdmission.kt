package io.github.onlinemo.meloxwatch.playback.resources.metadata

/**
 * 固定 1.8.0 的 skipBook 按二十四位 entries 循环，并把 lookup 的位数转成 int。
 * 先按实际 packet 范围和累计 work 准入这些字段；其后 floor/residue/mode 的数组数由六位等固定位宽界定。
 */
internal object VorbisSetupAdmission {
    fun books(bytes: ByteArray, end: Int) {
        admit(end in 8..MetadataLimits.CODEC_CONFIG && end <= bytes.size, MetadataAdmissionIssue.RESOURCE_LIMIT)
        admit(bytes[0].toInt() == 5 && bytes.matches(1, "vorbis"))
        val count = (bytes[7].toInt() and 255) + 1
        val bits = LittleBits(bytes, 8, end)
        var totalEntries = 0
        repeat(count) {
            admit(bits.read(24) == 0x564342)
            val dimensions = bits.read(16)
            admit(dimensions in 1..256, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            val entries = bits.read(24)
            admit(entries in 1..MetadataLimits.CODEBOOK_ENTRIES, MetadataAdmissionIssue.RESOURCE_LIMIT)
            totalEntries += entries
            admit(totalEntries <= MetadataLimits.TOTAL_CODEBOOK_ENTRIES, MetadataAdmissionIssue.RESOURCE_LIMIT)
            if (bits.read(1) == 0) {
                val sparse = bits.read(1) != 0
                if (!sparse) {
                    bits.skip(entries.toLong() * 5)
                } else {
                    repeat(entries) {
                        if (bits.read(1) != 0) bits.skip(5)
                    }
                }
            } else {
                var codeLength = bits.read(5) + 1
                var written = 0
                while (written < entries) {
                    admit(codeLength <= 32)
                    val run = bits.read(integerLog(entries - written))
                    admit(run <= entries - written)
                    written += run
                    codeLength++
                }
            }
            val lookup = bits.read(4)
            admit(lookup in 0..2, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            if (lookup != 0) {
                bits.skip(64)
                val valueBits = bits.read(4) + 1
                bits.skip(1)
                val values = if (lookup == 1) {
                    // 与固定 skipBook 的定位公式相同；真实 extent 上限先挡住其后 int cast 的溢出。
                    Math.floor(Math.pow(entries.toDouble(), 1.0 / dimensions)).toLong()
                } else {
                    entries.toLong() * dimensions
                }
                bits.skip(values * valueBits)
            }
        }
    }

    private fun integerLog(value: Int): Int = Int.SIZE_BITS - Integer.numberOfLeadingZeros(value)

    private class LittleBits(private val bytes: ByteArray, start: Int, end: Int) {
        private var position = start * 8
        private val limit = end * 8
        private val work = MetadataWork()

        fun read(count: Int): Int {
            need(count.toLong())
            admit(count in 0..32)
            var value = 0
            repeat(count) { bit ->
                val at = position + bit
                val next = (bytes[at ushr 3].toInt() ushr (at and 7)) and 1
                value = value or (next shl bit)
            }
            position += count
            return value
        }

        fun skip(count: Long) {
            need(count)
            position += count.toInt()
        }

        private fun need(count: Long) {
            work.check()
            admit(count >= 0 && count <= limit - position)
        }
    }
}
