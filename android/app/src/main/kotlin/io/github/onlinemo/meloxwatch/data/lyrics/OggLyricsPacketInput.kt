package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.yield

/**
 * One reusable, CRC-verified page and one comment-packet cursor. No page resynchronization,
 * packet-sized vendor buffer, audio scan or whole-packet assembly is needed for metadata.
 */
internal class OggLyricsPacketInput(
    private val input: EmbeddedLyricsInput,
    private val budget: EmbeddedLyricsBudget,
) {
    private val header: ByteArray
    private val laces: ByteArray
    private val body: ByteArray
    private val crcTable: IntArray
    private var serial = 0L
    private var sequence = 0L
    private var laceCount = 0
    private var bodyLength = 0
    private var bodyCursor = 0
    private var available = 0
    private var endLace = -1
    private var container = EmbeddedLyricsContainer.OGG_VORBIS
    private var bytesSinceYield = 0

    init {
        budget.rawAllocation(27)
        budget.rawAllocation(255)
        budget.rawAllocation(65_025)
        budget.charge(32L + 256L * 4L)
        input.check()
        header = ByteArray(27)
        laces = ByteArray(255)
        body = ByteArray(65_025)
        crcTable = IntArray(256)
        for (index in crcTable.indices) {
            if (index % 16 == 0) input.check()
            var value = index shl 24
            repeat(8) { value = (value shl 1) xor (if (value < 0) 0x04c11db7 else 0) }
            crcTable[index] = value
        }
    }

    val identificationSize: Int get() = bodyLength

    suspend fun identificationPage(prefix: ByteArray) {
        page(first = true, prefix = prefix)
        if (flags != 2 || granule != 0L || laceCount == 0) invalid()
        // The initial packet must complete alone on the BOS page, including a zero terminator
        // when its size is a multiple of 255. It cannot borrow bytes from the comment page.
        for (index in 0 until laceCount) {
            val last = index == laceCount - 1
            if (lace(index) == 255 && last || lace(index) != 255 && !last) invalid()
        }
    }

    fun identificationByte(index: Int): Int {
        if (index !in 0 until bodyLength) invalid()
        return body[index].toInt() and 0xff
    }

    fun identificationMatches(offset: Int, expected: String): Boolean {
        if (offset < 0 || offset > bodyLength - expected.length) return false
        return expected.indices.all { identificationByte(offset + it) == expected[it].code }
    }

    fun identificationUint32(offset: Int): Long {
        if (offset < 0 || offset > bodyLength - 4) invalid()
        return uint32(body, offset)
    }

    suspend fun beginComments(source: EmbeddedLyricsContainer) {
        require(source == EmbeddedLyricsContainer.OGG_VORBIS || source == EmbeddedLyricsContainer.OGG_OPUS)
        container = source
        page(first = false)
        if (laceCount == 0) invalid()
        commentPage(continued = false)
    }

    /** Future bytes stay within the physical read budget even while the packet end is unknown. */
    fun requireLength(length: Long) {
        if (length < 0L) invalid()
        if (endLace >= 0 && length > available.toLong()) embeddedLyricsFailure(EmbeddedLyricsIssue.TRUNCATED_METADATA)
        input.validateSpan((length - available.toLong()).coerceAtLeast(0L))
    }

    suspend fun byte(): Int {
        window()
        if (available == 0) embeddedLyricsFailure(EmbeddedLyricsIssue.TRUNCATED_METADATA)
        val value = body[bodyCursor++].toInt() and 0xff
        available--
        worked(1)
        return value
    }

    suspend fun uint32(): Long {
        var value = 0L
        repeat(4) { index -> value = value or (byte().toLong() shl (index * 8)) }
        return value
    }

    suspend fun skip(length: Long) {
        requireLength(length)
        var remaining = length
        while (remaining != 0L) {
            window()
            if (available == 0) embeddedLyricsFailure(EmbeddedLyricsIssue.TRUNCATED_METADATA)
            val count = minOf(remaining, available.toLong(), budget.limits.ioChunkBytes.toLong()).toInt()
            bodyCursor += count
            available -= count
            remaining -= count
            worked(count)
        }
    }

    suspend fun payload(length: Int): ByteArray {
        requireLength(length.toLong())
        budget.payload(length)
        if (length <= available) {
            budget.rawAllocation(length)
            return ByteArray(length).also { read(it) }
        }

        // Untrusted declarations do not authorize a large allocation. Retain only actual bytes
        // from verified pages, then assemble after the complete field was received. Chunk and
        // final arrays, list storage and growth remain cumulatively charged together.
        budget.charge(64L)
        val chunks = ArrayList<ByteArray>(0)
        var remaining = length
        while (remaining != 0) {
            window()
            if (available == 0) embeddedLyricsFailure(EmbeddedLyricsIssue.TRUNCATED_METADATA)
            val count = minOf(remaining, available, budget.limits.ioChunkBytes)
            budget.rawAllocation(count)
            budget.charge(64L)
            val chunk = ByteArray(count)
            read(chunk)
            chunks += chunk
            remaining -= count
        }
        budget.rawAllocation(length)
        val result = ByteArray(length)
        var offset = 0
        for (chunk in chunks) {
            input.check()
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
            worked(chunk.size)
        }
        return result
    }

    suspend fun skipTail() {
        while (true) {
            window()
            if (available == 0) return
            skip(available.toLong())
        }
    }

    suspend fun finish(requirePageBoundary: Boolean) {
        // A packet exactly divisible by 255 still needs its zero lace, possibly on a new page.
        window()
        if (available != 0 || endLace < 0 || requirePageBoundary && endLace != laceCount - 1) invalid()
        input.check()
    }

    private suspend fun read(destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            window()
            if (available == 0) embeddedLyricsFailure(EmbeddedLyricsIssue.TRUNCATED_METADATA)
            val count = minOf(destination.size - offset, available, budget.limits.ioChunkBytes)
            body.copyInto(destination, offset, bodyCursor, bodyCursor + count)
            bodyCursor += count
            available -= count
            offset += count
            worked(count)
        }
    }

    private suspend fun window() {
        input.check()
        while (available == 0 && endLace < 0) {
            page(first = false)
            commentPage(continued = true)
            // Empty pages do not terminate a pending packet or change its continuation state.
        }
    }

    private fun commentPage(continued: Boolean) {
        if ((flags and 2) != 0) invalid()
        if (laceCount != 0 && ((flags and 1) != 0) != continued) invalid()
        bodyCursor = 0
        available = 0
        endLace = -1
        for (index in 0 until laceCount) {
            available += lace(index)
            if (lace(index) < 255) {
                endLace = index
                break
            }
        }
        if ((flags and 4) != 0 && endLace < 0) embeddedLyricsFailure(EmbeddedLyricsIssue.TRUNCATED_METADATA)
        val expectedGranule = if (container == EmbeddedLyricsContainer.OGG_OPUS && endLace < 0) -1L else 0L
        if (granule != expectedGranule) invalid()
    }

    private suspend fun page(first: Boolean, prefix: ByteArray? = null) {
        input.check()
        budget.structure()
        if (prefix != null) {
            require(prefix.size == 4)
            prefix.copyInto(header)
            input.exactly(header, offset = 4, length = 1)
        } else input.exactly(header, length = 5)
        if (header[0] != 'O'.code.toByte() || header[1] != 'g'.code.toByte() ||
            header[2] != 'g'.code.toByte() || header[3] != 'S'.code.toByte()) invalid()
        if (header[4] != 0.toByte()) embeddedLyricsFailure(EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
        input.exactly(header, offset = 5, length = 22)
        laceCount = header[26].toInt() and 0xff
        input.exactly(laces, length = laceCount)
        bodyLength = 0
        for (index in 0 until laceCount) bodyLength += lace(index)
        input.exactly(body, length = bodyLength)
        if ((checksum().toLong() and 0xffff_ffffL) != uint32(header, 22)) {
            embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_OGG_CHECKSUM)
        }
        if ((flags and 0xf8) != 0) invalid()
        val nextSerial = uint32(header, 14)
        val nextSequence = uint32(header, 18)
        if (!first) {
            if (nextSerial != serial) embeddedLyricsFailure(EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
            if (nextSequence != ((sequence + 1L) and 0xffff_ffffL)) invalid()
        }
        serial = nextSerial
        sequence = nextSequence
    }

    private suspend fun checksum(): Int {
        var crc = 0
        for (index in header.indices) crc = update(crc, if (index in 22..25) 0 else header[index].toInt() and 0xff)
        for (index in 0 until laceCount) crc = update(crc, lace(index))
        for (index in 0 until bodyLength) {
            if (index % 256 == 0) input.check()
            if (index != 0 && index % 4_096 == 0) yield()
            crc = update(crc, body[index].toInt() and 0xff)
        }
        input.check()
        return crc
    }

    private fun update(crc: Int, byte: Int): Int = (crc shl 8) xor crcTable[((crc ushr 24) xor byte) and 0xff]
    private fun lace(index: Int): Int = laces[index].toInt() and 0xff
    private val flags: Int get() = header[5].toInt() and 0xff
    private val granule: Long get() {
        var value = 0L
        for (index in 0..7) value = value or ((header[6 + index].toLong() and 0xffL) shl (index * 8))
        return value
    }

    private suspend fun worked(count: Int) {
        input.check()
        bytesSinceYield += count
        if (bytesSinceYield >= 4_096) {
            bytesSinceYield = 0
            yield()
        }
    }

    private fun uint32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0..3) value = value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
        return value
    }

    private fun invalid(): Nothing = embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
}
