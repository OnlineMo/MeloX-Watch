package io.github.onlinemo.meloxwatch.playback.resources.metadata

import io.github.onlinemo.meloxwatch.playback.resources.metadata.AudioMetadataFixtures.aacSample
import io.github.onlinemo.meloxwatch.playback.resources.metadata.AudioMetadataFixtures.be16
import io.github.onlinemo.meloxwatch.playback.resources.metadata.AudioMetadataFixtures.be32
import io.github.onlinemo.meloxwatch.playback.resources.metadata.AudioMetadataFixtures.box
import io.github.onlinemo.meloxwatch.playback.resources.metadata.AudioMetadataFixtures.concatenate
import java.nio.ByteBuffer

/** 完整音频 track/表与 fragment 关系；恶意变体只改变声明字段，交给真实 extractor 处理。 */
internal object M4aMetadataFixtures {
    val ftyp: ByteArray = box("ftyp", "M4A ".toByteArray() + be32(0) + "isommp42".toByteArray())

    fun movie(
        sampleCount: Int = 2,
        metadata: ByteArray = M4aMetadataFixtures.metadata(),
        sampleSizeBox: ByteArray? = null,
        editList: ByteArray = byteArrayOf(),
        duplicateTable: ByteArray = byteArrayOf(),
        esds: ByteArray = M4aMetadataFixtures.esds(),
    ): ByteArray {
        fun moov(offset: Int): ByteArray = box("moov", mvhd(sampleCount * 1024) +
            track(1, sampleCount, offset, sampleSizeBox, editList, duplicateTable, esds) + metadata)
        val first = moov(0)
        val ready = moov(ftyp.size + first.size + 8)
        return ftyp + ready + box("mdat", concatenate(List(minOf(sampleCount, 2)) { aacSample }))
    }

    fun movieWithTwoClaimedTracks(samples: Int): ByteArray = ftyp + box("moov", mvhd(samples * 1024) +
        track(1, samples, 0) + track(2, samples, 0)) + box("mdat", aacSample)

    fun fragmented(frames: Int = 3, trunCount: Int = 1, extraFragment: ByteArray = byteArrayOf()): ByteArray {
        val initial = init()
        val fragments = concatenate(List(frames) { sequence -> fragment(sequence, trunCount) })
        return initial + fragments + extraFragment
    }

    fun init(trackCount: Int = 1): ByteArray {
        val tracks = concatenate(List(trackCount) { track(it + 1, 0, 0) })
        val defaults = concatenate(List(trackCount) { index ->
            box("trex", be32(0) + be32(index + 1) + be32(1) + be32(1024) + be32(aacSample.size) + be32(0))
        })
        return ftyp + box("moov", mvhd(0) + tracks + box("mvex", defaults))
    }

    fun fragment(
        sequence: Int,
        count: Int = 1,
        track: Int = 1,
        uuid: ByteArray = byteArrayOf(),
        materializeSamples: Boolean = false,
    ): ByteArray {
        fun moof(offset: Int): ByteArray = box("moof", box("mfhd", be32(0) + be32(sequence + 1)) +
            traf(track, sequence, count, offset, uuid))
        val first = moof(0)
        val samples = if (materializeSamples) ByteArray(count * aacSample.size) { aacSample[it % aacSample.size] } else aacSample
        return moof(first.size + 8) + box("mdat", samples)
    }

    fun twoTrackFragment(count: Int): ByteArray = box("moof", box("mfhd", be32(0) + be32(1)) +
        traf(1, 0, count, 0) + traf(2, 0, count, 0))

    private fun traf(track: Int, sequence: Int, count: Int, offset: Int, uuid: ByteArray = byteArrayOf()): ByteArray = box("traf",
        box("tfhd", be32(0x020018) + be32(track) + be32(1024) + be32(aacSample.size)) +
            box("tfdt", be32(0) + be32(sequence * 1024)) +
            box("trun", be32(1) + be32(count) + be32(offset)) + uuid)

    fun metadata(keysCount: Int = 1, value: ByteArray = "Guarded sample".toByteArray()): ByteArray {
        val key = "com.apple.quicktime.title".toByteArray()
        val keys = box("keys", be32(0) + be32(keysCount) + be32(key.size + 8) + "mdta".toByteArray() + key)
        val data = box("data", be32(1) + be32(0) + value)
        val item = be32(data.size + 8) + be32(1) + data
        return box("meta", be32(0) + hdlr("mdta") + keys + box("ilst", item))
    }

    fun freeformMetadata(): ByteArray {
        val mean = box("mean", be32(0) + "com.apple.iTunes".toByteArray())
        val name = box("name", be32(0) + "replaygain_track_gain".toByteArray())
        val data = box("data", be32(1) + be32(0) + "-3 dB".toByteArray())
        return box("udta", box("meta", be32(0) + hdlr("mdir") + box("ilst", box("----", mean + name + data))))
    }

    fun coverWithBadChildLength(): ByteArray {
        val data = be32(MetadataLimits.RAW_BLOCK) + "data".toByteArray() + be32(13) + be32(0)
        return box("udta", box("meta", be32(0) + hdlr("mdir") + box("ilst", box("covr", data))))
    }

    fun stsz(count: Int, fixed: Int = aacSample.size): ByteArray = box("stsz", be32(0) + be32(fixed) + be32(count))

    fun edits(count: Int): ByteArray = box("edts", box("elst", be32(0) + be32(count) +
        concatenate(List(minOf(count, 4)) { be32(1024) + be32(0) + be16(1) + be16(0) })))

    fun esds(hugeSpecific: Boolean = false): ByteArray {
        val specific = if (hugeSpecific) {
            byteArrayOf(5, 0x81.toByte(), 0x80.toByte(), 0x80.toByte(), 0)
        } else {
            descriptor(5, byteArrayOf(0x12, 0x10))
        }
        val config = descriptor(4, byteArrayOf(0x40, 0x15) + ByteArray(3) + be32(128_000) + be32(128_000) + specific)
        val elementary = descriptor(3, be16(1) + byteArrayOf(0) + config + descriptor(6, byteArrayOf(2)))
        return box("esds", be32(0) + elementary)
    }

    private fun descriptor(tag: Int, bytes: ByteArray): ByteArray {
        check(bytes.size < 128)
        return byteArrayOf(tag.toByte(), bytes.size.toByte()) + bytes
    }

    private fun track(
        id: Int,
        samples: Int,
        offset: Int,
        sampleSizeBox: ByteArray? = null,
        editList: ByteArray = byteArrayOf(),
        duplicateTable: ByteArray = byteArrayOf(),
        esds: ByteArray = M4aMetadataFixtures.esds(),
    ): ByteArray {
        val description = box("mp4a", ByteArray(6) + be16(1) + ByteArray(8) +
            be16(2) + be16(16) + be16(0) + be16(0) + be32(44_100 shl 16) + esds)
        val stsd = box("stsd", be32(0) + be32(1) + description)
        val stts = box("stts", be32(0) + be32(if (samples == 0) 0 else 1) +
            if (samples == 0) byteArrayOf() else be32(samples) + be32(1024))
        val stsc = box("stsc", be32(0) + be32(if (samples == 0) 0 else 1) +
            if (samples == 0) byteArrayOf() else be32(1) + be32(samples) + be32(1))
        val stco = box("stco", be32(0) + be32(if (samples == 0) 0 else 1) +
            if (samples == 0) byteArrayOf() else be32(offset))
        val stbl = box("stbl", stsd + stts + stsc + (sampleSizeBox ?: stsz(samples)) + duplicateTable + stco)
        val url = box("url ", be32(1))
        val dinf = box("dinf", box("dref", be32(0) + be32(1) + url))
        val minf = box("minf", box("smhd", be32(0) + be16(0) + be16(0)) + dinf + stbl)
        val mdhd = box("mdhd", be32(0) + ByteArray(8) + be32(44_100) + be32(samples * 1024) + be16(0x55c4) + be16(0))
        return box("trak", tkhd(id, samples * 1024) + editList + box("mdia", mdhd + hdlr("soun") + minf))
    }

    private fun hdlr(type: String): ByteArray = box("hdlr", be32(0) + be32(0) + type.toByteArray() +
        ByteArray(12) + "MeloX fixtures\u0000".toByteArray())

    private fun mvhd(duration: Int): ByteArray = box("mvhd", ByteBuffer.allocate(100).apply {
        putInt(0)
        putLong(0)
        putInt(44_100)
        putInt(duration)
        putInt(0x00010000)
        putShort(0x0100)
        putShort(0)
        putLong(0)
        matrix().forEach(::putInt)
        put(ByteArray(24))
        putInt(3)
    }.array())

    private fun tkhd(id: Int, duration: Int): ByteArray = box("tkhd", ByteBuffer.allocate(84).apply {
        putInt(7)
        putLong(0)
        putInt(id)
        putInt(0)
        putInt(duration)
        putLong(0)
        putShort(0)
        putShort(0)
        putShort(0x0100)
        putShort(0)
        matrix().forEach(::putInt)
        putInt(0)
        putInt(0)
    }.array())

    private fun matrix(): IntArray = intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)
}
