/*
 *  21.04.2004 Original verion. davagin@udm.ru.
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

/* APE range fixture encoding follows JMAC BitArray at 0cedfd83a3005c1c4e4dd5fa7cb3796c79b765f3.
 * The original license notice is retained above; the modified test source remains GPL-2.0-or-later.
 * Modified 2026-11-22: tiny synthetic fixtures only; no external recording or production encoder. */
package io.github.onlinemo.meloxwatch.data.codec

import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

internal class MemoryCodecInput(private val bytes: ByteArray) : CodecInput {
    override val length = bytes.size.toLong()
    override var position = 0L
        private set
    var cancelled = false
    var reads = 0
    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
        checkCancellation(); reads++
        if (count == 0) return 0
        if (position == length) return -1
        val read = minOf(count.toLong(), length - position).toInt()
        this.bytes.copyInto(bytes, offset, position.toInt(), position.toInt() + read)
        position += read
        return read
    }
    override fun seek(position: Long) { checkCancellation(); require(position in 0..length); this.position = position }
    override fun checkCancellation() { if (cancelled) throw InterruptedIOException("Fixture cancelled") }
    override fun close() = Unit
}

internal object CodecFixtures {
    fun dsdTone(rate: Int, bits: Int = 32768): List<ByteArray> {
        val bytes = ByteArray((bits + 7) / 8)
        var first = 0.0
        var second = 0.0
        var output = 0.0
        repeat(bits) { bit ->
            val sample = 0.35 * sin(2 * PI * rate * bit / 2_822_400.0)
            first += sample - output
            second += first - output
            output = if (second >= 0) 1.0 else -1.0
            if (output > 0) bytes[bit / 8] = (bytes[bit / 8].toInt() or (1 shl (bit and 7))).toByte()
        }
        return listOf(bytes, ByteArray(bytes.size) { (bytes[it].toInt() xor 255).toByte() })
    }

    fun dsf(channels: List<ByteArray>, samples: Long = channels[0].size * 8L, msb: Boolean = false): ByteArray {
        val block = 4096
        val blocks = (samples + 8L * block - 1) / (8L * block)
        val dataBytes = blocks * block * channels.size
        val header = dsfHeader(samples, channels.size, dataBytes, msb)
        val data = ByteArray(dataBytes.toInt())
        for (group in 0 until blocks.toInt()) for (channel in channels.indices) {
            val start = group * block
            val count = minOf(block, channels[channel].size - start).coerceAtLeast(0)
            repeat(count) { within ->
                var value = channels[channel][start + within].toInt() and 255
                if ((start + within).toLong() == (samples - 1) / 8 && samples and 7L != 0L) {
                    value = value and ((1 shl (samples and 7L).toInt()) - 1)
                }
                data[group * block * channels.size + channel * block + within] =
                    if (msb) (Integer.reverse(value) ushr 24).toByte() else value.toByte()
            }
        }
        return header + data
    }

    fun dsfHeader(samples: Long, channels: Int, dataBytes: Long, msb: Boolean = false): ByteArray = ByteBuffer.allocate(92)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("DSD ".toByteArray()); putLong(28); putLong(92 + dataBytes); putLong(0)
            put("fmt ".toByteArray()); putLong(52); putInt(1); putInt(0); putInt(channels); putInt(channels)
            putInt(2_822_400); putInt(if (msb) 8 else 1); putLong(samples); putInt(4096); putInt(0)
            put("data".toByteArray()); putLong(12 + dataBytes)
        }.array()

    fun dff(channels: List<ByteArray>, compression: String = "DSD "): ByteArray {
        val properties = "SND ".toByteArray() + chunk("FS  ", big32(2_822_400)) +
            chunk("CHNL", ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(channels.size.toShort()).array() +
                if (channels.size == 2) "SLFTSRGT".toByteArray() else "C   ".toByteArray()) +
            chunk("CMPR", compression.toByteArray() + byteArrayOf(0))
        val audio = ByteArray(channels[0].size * channels.size) { index ->
            (Integer.reverse(channels[index % channels.size][index / channels.size].toInt() and 255) ushr 24).toByte()
        }
        val body = "DSD ".toByteArray() + chunk("FVER", big32(0x01050000)) + chunk("PROP", properties) + chunk("DSD ", audio)
        return "FRM8".toByteArray() + ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(body.size.toLong()).array() + body
    }

    private fun chunk(id: String, bytes: ByteArray): ByteArray = id.toByteArray() +
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(bytes.size.toLong()).array() + bytes +
        if (bytes.size and 1 != 0) byteArrayOf(0) else byteArrayOf()
    private fun big32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    /** Exact first predictor states allow a one-block nonzero golden independent of the decoder. */
    fun apeOne(first: Short = 1, second: Short? = null, compression: Int = 2000): ByteArray {
        val pcm = ByteBuffer.allocate(if (second == null) 2 else 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(first); second?.let(::putShort)
        }.array()
        val y = if (second == null) 0 else second.toInt() - first.toInt()
        val x = first.toInt() + y / 2
        val residues = if (second == null) listOf(first.toInt()) else listOf(y, x)
        val range = TinyApeRangeEncoder()
        residues.forEach { range.firstResidual(it) }
        val crc = CRC32().apply { update(pcm) }.value ushr 1
        val logical = range.finish()
        val packed = packWords(logical)
        val data = little32(crc.toInt()) + packed
        return apeFile(listOf(data), 1, 1, if (second == null) 1 else 2, compression)
    }

    fun apeSilence(blocks: Int = 1024, last: Int = 257, frames: Int = 2, channels: Int = 2): ByteArray {
        val data = List(frames) { index ->
            val amount = if (index == frames - 1) last else blocks
            val crc = CRC32().apply { update(ByteArray(amount * channels * 2)) }.value ushr 1
            little32((crc or 0x8000_0000L).toInt()) + little32(if (channels == 2) 3 else 1) +
                packWords(byteArrayOf(0, 1, 0, 0, 0))
        }
        return apeFile(data, blocks, last, channels, 2000)
    }

    fun apeTags(audio: ByteArray, fields: List<Pair<String, String>>): ByteArray {
        val data = ByteArrayOutputStream().apply {
            for ((key, value) in fields) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                write(little32(bytes.size)); write(little32(0)); write(key.toByteArray(Charsets.US_ASCII)); write(0); write(bytes)
            }
        }.toByteArray()
        val footer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("APETAGEX".toByteArray(Charsets.US_ASCII)); putInt(2000); putInt(data.size + 32); putInt(fields.size)
            putInt(0); putLong(0)
        }.array()
        return audio + data + footer
    }

    private fun apeFile(data: List<ByteArray>, blocks: Int, last: Int, channels: Int, compression: Int): ByteArray {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(compression.toShort()); putShort(32); putInt(blocks); putInt(last); putInt(data.size)
            putShort(16); putShort(channels.toShort()); putInt(44_100)
        }.array()
        var next = 76 + data.size * 4
        val seeks = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            data.forEach { putInt(next); next += it.size }
        }.array()
        val audio = data.fold(byteArrayOf()) { before, value -> before + value }
        val md5 = MessageDigest.getInstance("MD5").apply { update(audio); update(header); update(seeks) }.digest()
        val descriptor = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("MAC ".toByteArray()); putShort(3990); putShort(0); putInt(52); putInt(24); putInt(seeks.size)
            putInt(0); putInt(audio.size); putInt(0); putInt(0); put(md5)
        }.array()
        return descriptor + header + seeks + audio
    }

    private fun little32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun packWords(logical: ByteArray): ByteArray = ByteArray((logical.size + 3) / 4 * 4).also { out ->
        logical.indices.forEach { index -> out[index / 4 * 4 + 3 - index % 4] = logical[index] }
    }

    /** A test-only range writer for initial small residuals, sourced from the pinned GPL codec. */
    private class TinyApeRangeEncoder {
        private var low = 0L
        private var range = 1L shl 31
        private var buffer = 0
        private var follow = 0
        private val output = ByteArrayOutputStream()
        fun firstResidual(value: Int) {
            val encoded = if (value > 0) value * 2 - 1 else -value * 2
            require(encoded in 0..511)
            normalize()
            range = (range ushr 16) * 19578
            normalize()
            range /= 512
            low += range * encoded
        }
        private fun normalize() {
            var steps = 0
            while (range <= 1L shl 23) {
                check(++steps <= 4)
                if (low < 255L shl 23) {
                    output.write(buffer); repeat(follow) { output.write(255) }; follow = 0
                    buffer = ((low ushr 23) and 255).toInt()
                } else if (low and (1L shl 31) != 0L) {
                    output.write(buffer + 1); repeat(follow) { output.write(0) }; follow = 0
                    buffer = ((low ushr 23) and 255).toInt()
                } else follow++
                low = (low shl 8) and ((1L shl 31) - 1)
                range = range shl 8
            }
        }
        fun finish(): ByteArray {
            normalize()
            val final = (low ushr 23) + 1
            output.write(if (final > 255) buffer + 1 else buffer)
            repeat(follow) { output.write(if (final > 255) 0 else 255) }
            output.write((final and 255).toInt()); repeat(3) { output.write(0) }
            return output.toByteArray()
        }
    }
}
