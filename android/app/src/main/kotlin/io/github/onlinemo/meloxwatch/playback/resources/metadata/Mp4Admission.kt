package io.github.onlinemo.meloxwatch.playback.resources.metadata

internal class Mp4TrackBudget(val fragment: Boolean) {
    var id: Int? = null
    var samples = 0
    var edits = 1
    var timestamps: Int? = null
    var sampleTableSeen = false
    var editListSeen = false
    var expandedSamples = 0
}

/** 预算对应当前 retained 表；历史 fragment 的流式包数量不永久累积。 */
internal class Mp4TableBudget {
    private var movieSamples = 0
    private var expandedMovieSamples = 0
    private var fragmentSamples = 0
    private val fragmentPeaks = LinkedHashMap<Int, Int>()
    fun fragment() { fragmentSamples = 0 }
    fun samples(track: Mp4TrackBudget, count: Int) {
        admit(count in 0..MetadataLimits.SAMPLES, MetadataAdmissionIssue.RESOURCE_LIMIT)
        if (track.fragment) {
            admit(count <= MetadataLimits.SAMPLES - fragmentSamples, MetadataAdmissionIssue.RESOURCE_LIMIT)
            fragmentSamples += count
            track.samples += count
            peak(track)
        } else {
            admit(!track.sampleTableSeen)
            track.sampleTableSeen = true
            movieSamples += count
            track.samples = count
            admit(movieSamples in 0..MetadataLimits.SAMPLES, MetadataAdmissionIssue.RESOURCE_LIMIT)
            edits(track)
            track.timestamps?.let { admit(it == count) }
        }
    }
    fun edits(track: Mp4TrackBudget) {
        val expanded = track.samples.toLong() * track.edits
        admit(expanded <= MetadataLimits.SAMPLES, MetadataAdmissionIssue.RESOURCE_LIMIT)
        if (!track.fragment) {
            expandedMovieSamples += expanded.toInt() - track.expandedSamples
            track.expandedSamples = expanded.toInt()
            admit(expandedMovieSamples in 0..MetadataLimits.SAMPLES, MetadataAdmissionIssue.RESOURCE_LIMIT)
        }
    }
    fun peak(track: Mp4TrackBudget) {
        val id = track.id ?: return
        admit(fragmentPeaks.containsKey(id) || fragmentPeaks.size < MetadataLimits.TRACKS, MetadataAdmissionIssue.RESOURCE_LIMIT)
        fragmentPeaks[id] = maxOf(fragmentPeaks[id] ?: 0, track.samples)
        admit(fragmentPeaks.values.sumOf(Int::toLong) <= MetadataLimits.SAMPLES, MetadataAdmissionIssue.RESOURCE_LIMIT)
    }
}

/** 固定 1.8.0 用十六字节 scratch 读 atom header；其 leaf 分配前先检查刚返回的长度。 */
internal class Mp4Admission(private val budget: MetadataBudget, private val fragmented: Boolean) : ContainerAdmission {
    private data class Node(val type: String, val end: Long, val track: Mp4TrackBudget?)
    private data class Atom(val start: Long, val type: String, val size: Long, val header: Int, val track: Mp4TrackBudget?)
    private val stack = ArrayList<Node>()
    private val tables = Mp4TableBudget()
    private var headerScratch: ByteArray? = null
    private var pendingStart = -1L
    private var pendingType: String? = null
    private var leaf: Atom? = null
    private var atoms = 0
    private var tracks = 0
    private var graphBytes = 0L

    override fun returned(position: Long, bytes: ByteArray, offset: Int, count: Int, peek: Boolean, length: Long) {
        if (!peek && count == 8 && offset == 0 && bytes.size == 16 &&
            (bytes === headerScratch || headerScratch == null && position == 0L)) {
            headerScratch = bytes
            val cursor = MetadataCursor(bytes, offset, offset + count)
            val size = cursor.u32()
            val type = cursor.fourCc()
            if (size == 1L) {
                pendingStart = position
                pendingType = type
                return
            }
            val extent = if (size == 0L) {
                val end = stack.lastOrNull()?.end ?: length
                admit(end >= 0, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
                end - position
            } else size
            atom(position, type, extent, 8, length)
            return
        }
        if (!peek && bytes === headerScratch && offset == 8 && count == 8 && pendingType != null) {
            val cursor = MetadataCursor(bytes, offset, offset + count)
            val high = cursor.u32()
            val low = cursor.u32()
            admit(high <= 0x7fff_ffffL)
            atom(pendingStart, checkNotNull(pendingType), (high shl 32) or low, 16, length)
            pendingType = null
            return
        }
        val current = leaf ?: return
        if (!peek && position == boundedAdd(current.start, current.header.toLong()) && count.toLong() == current.size - current.header) {
            Mp4LeafAdmission.validate(current.type, bytes, offset - current.header, offset + count,
                current.track, tables, budget)
            leaf = null
        }
    }

    private fun atom(start: Long, type: String, size: Long, header: Int, length: Long) {
        admit(size >= header)
        val end = boundedAdd(start, size)
        admit(length < 0 || end <= length)
        while (stack.isNotEmpty() && start >= stack.last().end) stack.removeAt(stack.lastIndex)
        stack.lastOrNull()?.let { admit(end <= it.end) }
        if (stack.isEmpty() && type in setOf("moov", "moof")) {
            atoms = 0
            graphBytes = 0
            if (type == "moof") tables.fragment()
        }
        admit(++atoms <= MetadataLimits.ATOMS, MetadataAdmissionIssue.RESOURCE_LIMIT)
        if (fragmented) admit(type !in ENCRYPTION, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
        val containers = if (fragmented) FRAGMENTED_CONTAINERS else CONTAINERS
        if (type in containers) {
            admit(stack.size < 16, MetadataAdmissionIssue.RESOURCE_LIMIT)
            val track = when (type) {
                "trak" -> {
                    admit(++tracks <= MetadataLimits.TRACKS, MetadataAdmissionIssue.RESOURCE_LIMIT)
                    Mp4TrackBudget(false)
                }
                "traf" -> Mp4TrackBudget(true)
                else -> stack.lastOrNull()?.track
            }
            stack += Node(type, end, track)
        } else if (type in (if (fragmented) FRAGMENTED_LEAVES else LEAVES)) {
            // 这两份固定 extractor 本身也不解析带扩展 header 的 leaf。
            admit(header == 8, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
            admit(size <= MetadataLimits.RAW_BLOCK, MetadataAdmissionIssue.RESOURCE_LIMIT)
            graphBytes = boundedAdd(graphBytes, size)
            admit(graphBytes <= MetadataLimits.RETAINED_BYTES, MetadataAdmissionIssue.RESOURCE_LIMIT)
            if (type == "ftyp") admit(size <= 4096, MetadataAdmissionIssue.RESOURCE_LIMIT)
            val track = if (type == "hdlr" && stack.lastOrNull()?.type == "meta") null else stack.lastOrNull()?.track
            leaf = Atom(start, type, size, header, track)
        }
    }
    override fun release() {
        headerScratch = null
        stack.clear()
        leaf = null
    }

    private companion object {
        val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "meta", "axte")
        val FRAGMENTED_CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "moof", "traf", "mvex", "edts", "meta")
        val LEAVES = setOf("mdhd", "mvhd", "hdlr", "stsd", "stts", "stss", "ctts", "elst", "stsc", "stsz", "stz2", "stco", "co64",
            "tkhd", "ftyp", "udta", "keys", "ilst")
        val FRAGMENTED_LEAVES = LEAVES + setOf("sidx", "tfdt", "tfhd", "trex", "trun", "mehd", "emsg", "uuid")
        val ENCRYPTION = setOf("pssh", "saiz", "saio", "senc", "sbgp", "sgpd")
    }
}
