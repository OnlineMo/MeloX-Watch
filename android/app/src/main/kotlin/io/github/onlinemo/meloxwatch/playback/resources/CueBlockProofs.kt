package io.github.onlinemo.meloxwatch.playback.resources

import io.github.onlinemo.meloxwatch.data.cue.CueReadLimits
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID

/** 私有目录仅存派生摘要；分配该对象本身不访问磁盘。 */
internal class CueBlockProofs private constructor(private val directory: File) {
    private val lock = Any()
    private val owned = LinkedHashSet<CueBlockProofIndex>()
    private var initialized = false
    fun allocate(fileBytes: Long): CueBlockProofIndex = CueBlockProofIndex(this,
        File(directory, "${UUID.randomUUID()}.proof"), fileBytes)

    internal fun acquire(index: CueBlockProofIndex) = synchronized(lock) {
        if (!initialized) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("CUE proof directory unavailable")
            // 进程重启后没有 epoch 继续拥有这些文件；只检查至多六个名称和类型符合约定的子项。
            val stale = ArrayList<File>(MAX_FILES)
            Files.newDirectoryStream(directory.toPath()).use { entries ->
                for (entry in entries) {
                    if (stale.size == MAX_FILES) throw IOException("CUE proof directory requires cleanup")
                    val file = entry.toFile()
                    if (!file.name.matches(NAME) || Files.isSymbolicLink(entry) || !file.isFile) {
                        throw IOException("CUE proof directory contains an unknown entry")
                    }
                    stale += file
                }
            }
            for (file in stale) if (!file.delete()) throw IOException("CUE proof cleanup incomplete")
            initialized = true
        }
        if (index in owned || owned.size >= MAX_FILES ||
            owned.sumOf { it.reservedBytes } > MAX_BYTES - index.reservedBytes) {
            throw MediaTransportException(QueuePlaybackIssue.BUSY)
        }
        owned.add(index)
    }
    internal fun released(index: CueBlockProofIndex) = synchronized(lock) { check(owned.remove(index)) }

    companion object {
        const val MAX_FILES = 6
        const val MAX_BYTES = 32L * 1024L * 1024L
        private val NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.proof")
        private val stores = LinkedHashMap<String, CueBlockProofs>()
        fun privateDirectory(directory: File): CueBlockProofs = synchronized(stores) {
            stores.getOrPut(directory.absolutePath) { CueBlockProofs(directory) }
        }
        fun temporary(): CueBlockProofs = privateDirectory(File(System.getProperty("java.io.tmpdir"), "melox-cue-proof"))
    }
}

/** 源持有此staging/index对象，直到实际关闭与删除均成功。 */
internal class CueBlockProofIndex(private val owner: CueBlockProofs, private val file: File, val fileBytes: Long) : AutoCloseable {
    val blockCount = blocks(fileBytes)
    val reservedBytes = bytes(fileBytes)
    private var registered = false
    private var created = false
    private var handle: RandomAccessFile? = null
    private var written = 0
    private var sealed = false
    fun initialize() {
        check(!registered && handle == null)
        owner.acquire(this)
        registered = true
        if (!file.createNewFile()) throw IOException("CUE proof index already exists")
        created = true
        val opened = RandomAccessFile(file, "rw")
        handle = opened
        opened.writeLong(MAGIC)
        opened.writeInt(CueReadLimits.STREAM_BYTES)
        opened.writeInt(blockCount)
        opened.writeLong(fileBytes)
        opened.writeLong(0L)
    }
    fun append(digest: ByteArray) {
        check(!sealed && registered && written < blockCount)
        require(digest.size == DIGEST_BYTES)
        checkNotNull(handle).write(digest)
        written++
    }
    fun seal() {
        check(registered && !sealed && written == blockCount)
        val opened = checkNotNull(handle)
        if (opened.length() != reservedBytes) throw IOException("CUE proof index incomplete")
        opened.fd.sync()
        sealed = true
    }
    fun matches(block: Int, digest: ByteArray): Boolean {
        check(sealed && registered)
        require(block in 0 until blockCount && digest.size == DIGEST_BYTES)
        val opened = checkNotNull(handle)
        if (opened.length() != reservedBytes) throw IOException("CUE proof index changed")
        opened.seek(HEADER_BYTES + block.toLong() * DIGEST_BYTES)
        var difference = 0
        for (byte in digest) difference = difference or (opened.readUnsignedByte() xor (byte.toInt() and 255))
        return difference == 0
    }
    override fun close() {
        handle?.close()
        handle = null
        if (registered) {
            if (created && file.exists() && !file.delete()) throw IOException("CUE proof retirement incomplete")
            owner.released(this)
            registered = false
            created = false
        }
        sealed = false
    }
    companion object {
        const val HEADER_BYTES = 32L
        const val DIGEST_BYTES = 32
        private const val MAGIC = 0x4d58435545505231L
        fun blocks(fileBytes: Long): Int {
            CueReadLimits.requireWaveExtent(fileBytes)
            return (fileBytes / CueReadLimits.STREAM_BYTES + if (fileBytes % CueReadLimits.STREAM_BYTES == 0L) 0L else 1L).toInt()
        }
        fun bytes(fileBytes: Long): Long = Math.addExact(HEADER_BYTES, Math.multiplyExact(blocks(fileBytes).toLong(), DIGEST_BYTES.toLong()))
    }
}
