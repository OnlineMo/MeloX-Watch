package io.github.onlinemo.meloxwatch.data.codec

/** Byte/frame mapping for Media3 WavExtractor, including unaligned bounded DataSpec reads. */
internal class PcmWaveStream(private val decoder: PcmFrameDecoder) : AutoCloseable {
    val format: LocalCodecFormat get() = decoder.format
    private val header = PcmWaveHeader(format.pcm, format.pcmFrames)
    val length: Long get() = header.streamBytes
    var position = 0L
        private set
    private val pending = ByteArray(512 * format.pcm.frameBytes)
    private var pendingStart = 0
    private var pendingEnd = 0

    fun seek(position: Long) {
        codecRequire(position in 0..length)
        this.position = position
        pendingStart = 0
        pendingEnd = 0
        val bodyPosition = maxOf(0, position - header.bytes.size)
        decoder.seekFrame(bodyPosition / format.pcm.frameBytes)
        val partial = (bodyPosition % format.pcm.frameBytes).toInt()
        if (partial > 0) {
            val frames = decoder.readFrames(pending, 0, 1)
            codecRequire(frames == 1)
            pendingStart = partial
            pendingEnd = format.pcm.frameBytes
        }
    }

    fun read(bytes: ByteArray, offset: Int, count: Int): Int {
        codecRequire(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        if (count == 0) return 0
        if (position == length) return -1
        if (position < header.bytes.size) {
            val read = minOf(count, header.bytes.size - position.toInt())
            header.bytes.copyInto(bytes, offset, position.toInt(), position.toInt() + read)
            position += read
            return read
        }
        if (pendingStart == pendingEnd) {
            val frames = decoder.readFrames(pending, 0, 512)
            codecRequire(frames > 0)
            pendingStart = 0
            pendingEnd = frames * format.pcm.frameBytes
        }
        val read = minOf(count, pendingEnd - pendingStart, (length - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        codecRequire(read > 0)
        pending.copyInto(bytes, offset, pendingStart, pendingStart + read)
        pendingStart += read
        position += read
        return read
    }

    override fun close() { decoder.close(); pending.fill(0); pendingStart = 0; pendingEnd = 0 }
}
