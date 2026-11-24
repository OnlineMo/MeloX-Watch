package io.github.onlinemo.meloxwatch.playback.resources.metadata

/** 在 BoxParser/MetadataUtil 创建数组、字符串和 CSD 前，核对完整 leaf 的内部尺寸。 */
internal object Mp4LeafAdmission {
    fun validate(
        type: String,
        bytes: ByteArray,
        start: Int,
        end: Int,
        track: Mp4TrackBudget?,
        tables: Mp4TableBudget,
        budget: MetadataBudget,
    ) {
        val cursor = MetadataCursor(bytes, start + 8, end)
        when (type) {
            "keys" -> {
                cursor.skip(4)
                val count = cursor.count(MetadataLimits.ENTRIES)
                cursor.need(count * 8)
                repeat(count) {
                    val size = cursor.count(MetadataLimits.TEXT_BYTES + 8)
                    admit(size >= 8)
                    cursor.skip(4)
                    cursor.textBytes(size - 8)
                }
            }
            "udta", "ilst" -> boxes(cursor, type, budget, 0)
            "hdlr" -> {
                cursor.skip(8)
                val handler = cursor.fourCc()
                if (track != null) admit(handler == "soun", MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            }
            "stsd" -> sampleDescriptions(cursor, budget)
            "stsz" -> {
                cursor.skip(4)
                val fixed = cursor.count(MetadataLimits.AUDIO_FRAME)
                val count = cursor.count(MetadataLimits.SAMPLES)
                if (fixed == 0) {
                    cursor.need(count * 4)
                    repeat(count) { cursor.count(MetadataLimits.AUDIO_FRAME) }
                }
                tables.samples(requiredTrack(track), count)
            }
            "stz2" -> {
                cursor.skip(7)
                val width = cursor.u8()
                admit(width in setOf(4, 8, 16))
                val count = cursor.count(MetadataLimits.SAMPLES)
                cursor.need(((count.toLong() * width + 7) / 8).toInt())
                tables.samples(requiredTrack(track), count)
            }
            "stco", "co64" -> {
                cursor.skip(4)
                val count = cursor.count(MetadataLimits.SAMPLES)
                cursor.need(count * if (type == "co64") 8 else 4)
            }
            "stts", "ctts" -> {
                cursor.skip(4)
                val count = cursor.count(MetadataLimits.SAMPLES)
                cursor.need(count * 8)
                var samples = 0L
                repeat(count) {
                    samples = boundedAdd(samples, cursor.count(MetadataLimits.SAMPLES).toLong())
                    cursor.skip(4)
                }
                admit(samples <= MetadataLimits.SAMPLES, MetadataAdmissionIssue.RESOURCE_LIMIT)
                if (type == "stts") {
                    val current = requiredTrack(track)
                    current.timestamps = samples.toInt()
                    if (current.samples > 0) admit(current.samples == samples.toInt())
                }
            }
            "stsc" -> {
                cursor.skip(4)
                val count = cursor.count(MetadataLimits.SAMPLES)
                cursor.need(count * 12)
                var previous = 0
                repeat(count) {
                    val first = cursor.count(MetadataLimits.SAMPLES)
                    admit(first > previous)
                    previous = first
                    admit(cursor.count(MetadataLimits.SAMPLES) > 0)
                    admit(cursor.count(32) > 0)
                }
            }
            "stss" -> {
                cursor.skip(4)
                val count = cursor.count(MetadataLimits.SAMPLES)
                cursor.need(count * 4)
            }
            "elst" -> editList(cursor, requiredTrack(track), tables)
            "trun" -> trackRun(cursor, requiredTrack(track), tables)
            "tfhd" -> {
                val flags = cursor.u32().toInt() and 0x00ff_ffff
                val current = requiredTrack(track)
                current.id = cursor.count(Int.MAX_VALUE)
                admit(checkNotNull(current.id) > 0)
                if (flags and 1 != 0) cursor.skip(8)
                if (flags and 2 != 0) admit(cursor.count(32) > 0)
                if (flags and 8 != 0) cursor.u32()
                if (flags and 16 != 0) cursor.count(MetadataLimits.AUDIO_FRAME)
                if (flags and 32 != 0) cursor.skip(4)
                tables.peak(current)
            }
            "trex" -> {
                cursor.skip(4)
                admit(cursor.count(Int.MAX_VALUE) > 0)
                admit(cursor.count(32) > 0)
                cursor.skip(4)
                cursor.count(MetadataLimits.AUDIO_FRAME)
                cursor.skip(4)
            }
            "sidx" -> {
                val version = cursor.u8()
                cursor.skip(3)
                admit(version in 0..1, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
                cursor.skip(8 + if (version == 0) 8 else 16)
                cursor.skip(2)
                val count = cursor.u16()
                admit(count <= MetadataLimits.SEEK_POINTS, MetadataAdmissionIssue.RESOURCE_LIMIT)
                cursor.need(count * 12)
            }
            "uuid" -> {
                cursor.need(16)
                val piff = byteArrayOf(-94, 57, 79, 82, 90, -101, 79, 20, -94, 68, 108, 66, 124, 100, -115, -12)
                admit(!piff.indices.all { cursor.bytes[cursor.position + it] == piff[it] }, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            }
        }
    }

    private fun requiredTrack(track: Mp4TrackBudget?): Mp4TrackBudget = track ?: throw MetadataAdmissionException(MetadataAdmissionIssue.MALFORMED)

    private fun editList(cursor: MetadataCursor, track: Mp4TrackBudget, tables: Mp4TableBudget) {
        admit(!track.editListSeen)
        track.editListSeen = true
        val version = cursor.u8()
        cursor.skip(3)
        admit(version in 0..1, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
        val count = cursor.count(MetadataLimits.ENTRIES)
        cursor.need(count * if (version == 1) 20 else 12)
        var nonempty = 0
        repeat(count) {
            cursor.skip(if (version == 1) 8 else 4)
            val mediaBytes = if (version == 1) 8 else 4
            cursor.need(mediaBytes)
            val empty = (0 until mediaBytes).all { cursor.bytes[cursor.position + it].toInt() and 255 == 255 }
            if (!empty) nonempty++
            cursor.skip(mediaBytes)
            admit(cursor.u16() == 1)
            cursor.skip(2)
        }
        track.edits = maxOf(1, nonempty)
        tables.edits(track)
    }

    private fun trackRun(cursor: MetadataCursor, track: Mp4TrackBudget, tables: Mp4TableBudget) {
        val flags = cursor.u32().toInt() and 0x00ff_ffff
        val count = cursor.count(MetadataLimits.SAMPLES)
        if (flags and 1 != 0) cursor.skip(4)
        if (flags and 4 != 0) cursor.skip(4)
        val fields = listOf(256, 512, 1024, 2048).count { flags and it != 0 }
        cursor.need((count.toLong() * fields * 4).toInt())
        repeat(count) {
            cursor.work.check()
            if (flags and 256 != 0) cursor.u32()
            if (flags and 512 != 0) cursor.count(MetadataLimits.AUDIO_FRAME)
            if (flags and 1024 != 0) cursor.skip(4)
            if (flags and 2048 != 0) cursor.skip(4)
        }
        tables.samples(track, count)
    }

    private fun boxes(cursor: MetadataCursor, parent: String, budget: MetadataBudget, depth: Int) {
        admit(depth <= MetadataLimits.DEPTH, MetadataAdmissionIssue.RESOURCE_LIMIT)
        var children = 0
        while (cursor.left > 0) {
            admit(++children <= MetadataLimits.ATOMS, MetadataAdmissionIssue.RESOURCE_LIMIT)
            val start = cursor.position
            val size = cursor.count(MetadataLimits.RAW_BLOCK)
            val type = cursor.fourCc()
            admit(size >= 8 && size - 8 <= cursor.left)
            val end = start + size
            val child = MetadataCursor(cursor.bytes, cursor.position, end, cursor.work)
            if (parent == "ilst") budget.entry()
            when {
                parent == "ilst" -> boxes(child, type, budget, depth + 1)
                type == "meta" -> {
                    if (child.left >= 8 && !child.bytes.matches(child.position + 4, "hdlr")) child.skip(4)
                    boxes(child, "meta", budget, depth + 1)
                }
                type == "ilst" -> boxes(child, "ilst", budget, depth + 1)
                type == "smta" -> {
                    child.skip(4)
                    boxes(child, "smta", budget, depth + 1)
                }
                type == "data" -> {
                    admit(size >= 16)
                    child.skip(8)
                    child.need(size - 16)
                    if (parent != "covr") admit(child.left <= MetadataLimits.TEXT_BYTES, MetadataAdmissionIssue.RESOURCE_LIMIT)
                }
                type == "mean" || type == "name" -> {
                    child.skip(4)
                    child.textBytes(child.left)
                }
                type == "keys" -> validate(type, cursor.bytes, start, end, null, Mp4TableBudget(), budget)
            }
            cursor.position = end
        }
    }

    private fun sampleDescriptions(cursor: MetadataCursor, budget: MetadataBudget) {
        cursor.skip(4)
        val count = cursor.count(32)
        cursor.need(count * 8)
        repeat(count) {
            val start = cursor.position
            val size = cursor.count(MetadataLimits.RAW_BLOCK)
            val type = cursor.fourCc()
            admit(size >= 36 && size - 8 <= cursor.left)
            admit(type in setOf("mp4a", "alac", "fLaC", "Opus", ".mp3"), MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            val end = start + size
            val entry = MetadataCursor(cursor.bytes, start + 16, end, cursor.work)
            val version = entry.u16()
            entry.skip(6)
            admit(version in 0..1, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            admit(entry.u16() in 1..2, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            entry.skip(6)
            admit(entry.u32() shr 16 > 0)
            if (version == 1) entry.skip(16)
            audioChildren(entry, budget, 0)
            cursor.position = end
        }
    }

    private fun audioChildren(cursor: MetadataCursor, budget: MetadataBudget, depth: Int) {
        admit(depth <= MetadataLimits.DEPTH, MetadataAdmissionIssue.RESOURCE_LIMIT)
        var children = 0
        while (cursor.left > 0) {
            admit(++children <= MetadataLimits.ENTRIES, MetadataAdmissionIssue.RESOURCE_LIMIT)
            val start = cursor.position
            val size = cursor.count(MetadataLimits.CODEC_CONFIG + 12)
            val type = cursor.fourCc()
            admit(size >= 8 && size - 8 <= cursor.left)
            val end = start + size
            val child = MetadataCursor(cursor.bytes, cursor.position, end, cursor.work)
            when (type) {
                "esds" -> esds(child)
                "wave" -> audioChildren(child, budget, depth + 1)
                "alac" -> {
                    child.skip(4)
                    admit(child.left >= 24)
                    val frames = child.count(65_536)
                    admit(frames > 0)
                    child.skip(1)
                    admit(child.u8() in setOf(16, 24, 32))
                    child.skip(3)
                    admit(child.u8() in 1..2)
                    child.skip(2)
                    child.count(MetadataLimits.AUDIO_FRAME)
                    child.skip(4)
                    admit(child.u32() in 1L..192_000L)
                }
                "dfLa" -> {
                    child.skip(4)
                    admit(child.left == 38)
                    admit(child.u8() and 127 == 0 && child.u24() == 34)
                    VorbisAdmission.streamInfo(child.bytes, child.position, child.end)
                }
                "dOps" -> {
                    admit(child.left in 11..256)
                    child.skip(1)
                    admit(child.u8() in 1..2)
                }
                "chan" -> {
                    child.skip(12)
                    val count = child.count(8)
                    child.need(count * 20)
                }
                "srat" -> {
                    child.skip(4)
                    admit(child.u32() in 1L..192_000L)
                }
                "sinf", "iacb", "mhaC", "mhaP", "pcmC", "dmlp", "ddts", "dac3", "dec3", "dac4", "damr" ->
                    throw MetadataAdmissionException(MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            }
            cursor.position = end
        }
    }

    private fun descriptor(cursor: MetadataCursor, tag: Int): MetadataCursor {
        admit(cursor.u8() == tag)
        var length = 0
        var finished = false
        repeat(4) {
            if (!finished) {
                val value = cursor.u8()
                length = (length shl 7) or (value and 127)
                finished = value and 128 == 0
            }
        }
        admit(finished, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
        admit(length <= MetadataLimits.CODEC_CONFIG, MetadataAdmissionIssue.RESOURCE_LIMIT)
        cursor.need(length)
        return MetadataCursor(cursor.bytes, cursor.position, cursor.position + length, cursor.work)
    }

    private fun esds(cursor: MetadataCursor) {
        cursor.skip(4)
        val es = descriptor(cursor, 3)
        es.skip(2)
        val flags = es.u8()
        if (flags and 128 != 0) es.skip(2)
        if (flags and 64 != 0) es.skip(es.u8())
        if (flags and 32 != 0) es.skip(2)
        val config = descriptor(es, 4)
        val objectType = config.u8()
        admit(objectType in setOf(0x40, 0x66, 0x67, 0x68, 0x69, 0x6b), MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
        if (objectType == 0x69 || objectType == 0x6b) return
        config.skip(12)
        val specific = descriptor(config, 5)
        admit(specific.left >= 2)
    }
}
