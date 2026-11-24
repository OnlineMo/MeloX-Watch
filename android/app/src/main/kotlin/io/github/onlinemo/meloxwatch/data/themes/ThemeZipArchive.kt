package io.github.onlinemo.meloxwatch.data.themes

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal data class ThemeZipEntry(
    val name: String,
    val key: String,
    val directory: Boolean,
    val method: Int,
    val compressedBytes: Long,
    val unpackedBytes: Long,
    val crc32: Long,
    val dataOffset: Long,
)

internal class ThemeZipIndex(entries: List<ThemeZipEntry>, val unpackedBytes: Long) {
    val entries: List<ThemeZipEntry> = Collections.unmodifiableList(entries.toList())
    val manifest: ThemeZipEntry = entries.singleOrNull { !it.directory && it.name == MANIFEST_NAME }
        ?: themeFail(ThemeIssue.MISSING_RESOURCE)

    companion object {
        const val MANIFEST_NAME = "theme.json"
    }
}

/**
 * v1 接受单卷 ZIP32 stored/deflate 与带签名的32位 descriptor。
 * 不接受 ZIP64、加密、额外字段、注释或自解压前缀，不把平台 ZIP 实现的隐式路径规则当授权。
 */
internal class ThemeZipArchive(private val archive: File) {
    fun inspect(checkCancellation: () -> Unit): ThemeZipIndex {
        requireThemeRegularFile(archive)
        val length = archive.length()
        if (length > ThemeLimits.PACKAGE_BYTES) themeFail(ThemeIssue.PACKAGE_TOO_LARGE)
        if (length < END_BYTES) themeFail(ThemeIssue.INVALID_ARCHIVE)
        try {
            RandomAccessFile(archive, "r").use { file ->
                val end = length - END_BYTES
                file.seek(end)
                if (file.u32() != END_SIGNATURE) themeFail(ThemeIssue.UNSUPPORTED_ARCHIVE)
                val disk = file.u16()
                val centralDisk = file.u16()
                val onDisk = file.u16()
                val count = file.u16()
                val centralBytes = file.u32()
                val centralOffset = file.u32()
                val comment = file.u16()
                if (disk != 0 || centralDisk != 0 || onDisk != count || count == 0xffff ||
                    centralBytes == 0xffffffffL || centralOffset == 0xffffffffL || comment != 0) {
                    themeFail(ThemeIssue.UNSUPPORTED_ARCHIVE)
                }
                if (count !in 1..ThemeLimits.ZIP_ENTRIES) themeFail(ThemeIssue.TOO_MANY_RESOURCES)
                if (centralOffset > end || centralBytes != end - centralOffset) themeFail(ThemeIssue.INVALID_ARCHIVE)
                file.seek(centralOffset)
                val headers = ArrayList<Header>(count)
                val names = HashSet<String>()
                var unpacked = 0L
                repeat(count) {
                    checkCancellation()
                    if (file.filePointer > end - CENTRAL_BYTES || file.u32() != CENTRAL_SIGNATURE) {
                        themeFail(ThemeIssue.INVALID_ARCHIVE)
                    }
                    file.u16() // 创建者版本不授予可执行权限；外部属性仍须独立拒绝链接及特殊文件。
                    val version = file.u16()
                    val flags = file.u16()
                    val method = file.u16()
                    file.u16()
                    file.u16()
                    val crc = file.u32()
                    val compressed = file.u32()
                    val expanded = file.u32()
                    val nameBytes = file.u16()
                    val extraBytes = file.u16()
                    val commentBytes = file.u16()
                    val startDisk = file.u16()
                    file.u16()
                    val external = file.u32()
                    val offset = file.u32()
                    validateProfile(version, flags, method)
                    if (extraBytes != 0 || commentBytes != 0 || startDisk != 0 || offset == 0xffffffffL ||
                        compressed == 0xffffffffL || expanded == 0xffffffffL) themeFail(ThemeIssue.UNSUPPORTED_ARCHIVE)
                    if (nameBytes !in 1..ThemeLimits.PATH_CHARACTERS || nameBytes > end - file.filePointer) {
                        themeFail(ThemeIssue.INVALID_ARCHIVE)
                    }
                    val rawName = ByteArray(nameBytes)
                    file.readFully(rawName)
                    val name = decodeName(rawName, flags)
                    val directory = name.endsWith('/')
                    val key = themePathKey(name, directory)
                    if (!names.add(key)) themeFail(ThemeIssue.DUPLICATE_PATH)
                    validateAttributes(external, directory)
                    if (directory && (expanded != 0L || crc != 0L)) themeFail(ThemeIssue.INVALID_ARCHIVE)
                    if (compressed > ThemeLimits.PACKAGE_BYTES) themeFail(ThemeIssue.PACKAGE_TOO_LARGE)
                    if (expanded > ThemeLimits.UNPACKED_BYTES - unpacked) themeFail(ThemeIssue.EXPANDED_TOO_LARGE)
                    if (expanded > compressed * ThemeLimits.EXPANSION_RATIO) themeFail(ThemeIssue.EXPANSION_RATIO)
                    if (method == STORED && compressed != expanded) themeFail(ThemeIssue.INVALID_ARCHIVE)
                    unpacked += expanded
                    headers += Header(name, key, rawName, directory, version, flags, method, crc, compressed, expanded, offset)
                }
                if (file.filePointer != end || unpacked > length * ThemeLimits.EXPANSION_RATIO) {
                    themeFail(ThemeIssue.INVALID_ARCHIVE)
                }
                val entries = ArrayList<ThemeZipEntry>(count)
                var nextOffset = 0L
                for (header in headers.sortedBy { it.offset }) {
                    checkCancellation()
                    if (header.offset != nextOffset || header.offset > centralOffset - LOCAL_BYTES) {
                        themeFail(ThemeIssue.INVALID_ARCHIVE)
                    }
                    file.seek(header.offset)
                    if (file.u32() != LOCAL_SIGNATURE || file.u16() != header.version ||
                        file.u16() != header.flags || file.u16() != header.method) themeFail(ThemeIssue.INVALID_ARCHIVE)
                    file.u16()
                    file.u16()
                    val crc = file.u32()
                    val compressed = file.u32()
                    val expanded = file.u32()
                    val nameBytes = file.u16()
                    val extraBytes = file.u16()
                    if (extraBytes != 0 || nameBytes != header.rawName.size || nameBytes > centralOffset - file.filePointer) {
                        themeFail(ThemeIssue.INVALID_ARCHIVE)
                    }
                    val rawName = ByteArray(nameBytes)
                    file.readFully(rawName)
                    if (!rawName.contentEquals(header.rawName)) themeFail(ThemeIssue.INVALID_ARCHIVE)
                    val descriptor = header.flags and DESCRIPTOR_FLAG != 0
                    if (descriptor) {
                        if (crc !in setOf(0L, header.crc32) || compressed !in setOf(0L, header.compressedBytes) ||
                            expanded !in setOf(0L, header.unpackedBytes)) themeFail(ThemeIssue.INVALID_ARCHIVE)
                    } else if (crc != header.crc32 || compressed != header.compressedBytes || expanded != header.unpackedBytes) {
                        themeFail(ThemeIssue.INVALID_ARCHIVE)
                    }
                    val dataOffset = file.filePointer
                    if (header.compressedBytes > centralOffset - dataOffset) themeFail(ThemeIssue.INVALID_ARCHIVE)
                    nextOffset = dataOffset + header.compressedBytes
                    if (descriptor) {
                        if (nextOffset > centralOffset - DESCRIPTOR_BYTES) themeFail(ThemeIssue.INVALID_ARCHIVE)
                        file.seek(nextOffset)
                        if (file.u32() != DESCRIPTOR_SIGNATURE || file.u32() != header.crc32 ||
                            file.u32() != header.compressedBytes || file.u32() != header.unpackedBytes) {
                            themeFail(ThemeIssue.INVALID_ARCHIVE)
                        }
                        nextOffset += DESCRIPTOR_BYTES
                    }
                    entries += ThemeZipEntry(
                        header.name, header.key, header.directory, header.method, header.compressedBytes,
                        header.unpackedBytes, header.crc32, dataOffset,
                    )
                }
                if (nextOffset != centralOffset) themeFail(ThemeIssue.INVALID_ARCHIVE)
                return ThemeZipIndex(entries, unpacked)
            }
        } catch (error: IOException) {
            throw ThemeException(ThemeIssue.INVALID_ARCHIVE, error)
        }
    }

    fun readManifest(index: ThemeZipIndex, checkCancellation: () -> Unit): ByteArray {
        val entry = index.manifest
        if (entry.unpackedBytes !in 1..ThemeLimits.MANIFEST_BYTES.toLong()) themeFail(ThemeIssue.MALFORMED_MANIFEST)
        val result = ByteArrayOutputStream(entry.unpackedBytes.toInt())
        consume(entry, checkCancellation) { bytes, count -> result.write(bytes, 0, count) }
        return result.toByteArray()
    }

    fun validateDeclarations(index: ThemeZipIndex, manifest: ThemeManifest) {
        val resources = manifest.resources.associateBy { themeAssetPath(it.path) }
        val found = HashSet<String>()
        for (entry in index.entries) {
            if (entry.directory) {
                if (manifest.resources.none { it.path.startsWith(entry.name) }) themeFail(ThemeIssue.UNDECLARED_RESOURCE)
                continue
            }
            if (entry.name == ThemeZipIndex.MANIFEST_NAME) continue
            val resource = resources[entry.key] ?: themeFail(ThemeIssue.UNDECLARED_RESOURCE)
            if (entry.name != resource.path || entry.unpackedBytes != resource.byteCount) {
                themeFail(ThemeIssue.INTEGRITY_FAILED)
            }
            if (entry.unpackedBytes > themeResourceLimit(resource.kind)) themeFail(ThemeIssue.EXPANDED_TOO_LARGE)
            found += entry.key
        }
        if (found.size != resources.size) themeFail(ThemeIssue.MISSING_RESOURCE)
    }

    /** 所有输出都位于调用者新建的隔离目录；每个实际写入块先取得空间准入。 */
    fun extract(
        index: ThemeZipIndex,
        manifest: ThemeManifest,
        payload: File,
        beforeWrite: (Long) -> Unit,
        checkCancellation: () -> Unit,
    ) {
        validateDeclarations(index, manifest)
        for (entry in index.entries) {
            checkCancellation()
            if (entry.directory) {
                consume(entry, checkCancellation) { _, count ->
                    if (count != 0) themeFail(ThemeIssue.INVALID_ARCHIVE)
                }
                continue
            }
            try {
                val file = themeContainedFile(payload, entry.name)
                createParents(payload, entry.name)
                if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) themeFail(ThemeIssue.DUPLICATE_PATH)
                FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS).use { output ->
                    consume(entry, checkCancellation) { bytes, count ->
                        beforeWrite(count.toLong())
                        try {
                            val buffer = ByteBuffer.wrap(bytes, 0, count)
                            while (buffer.hasRemaining()) {
                                checkCancellation()
                                if (output.write(buffer) <= 0) throw IOException("Theme resource write did not advance")
                            }
                        } catch (error: IOException) {
                            throw ThemeException(ThemeIssue.SAVE_FAILED, error)
                        }
                    }
                    checkCancellation()
                    output.force(true)
                }
            } catch (error: IOException) {
                throw ThemeException(ThemeIssue.SAVE_FAILED, error)
            }
        }
    }

    private fun consume(entry: ThemeZipEntry, checkCancellation: () -> Unit, write: (ByteArray, Int) -> Unit) {
        requireThemeRegularFile(archive)
        try {
            RandomAccessFile(archive, "r").use { file ->
                if (entry.dataOffset > file.length() || entry.compressedBytes > file.length() - entry.dataOffset) {
                    themeFail(ThemeIssue.INTEGRITY_FAILED)
                }
                file.seek(entry.dataOffset)
                val crc = CRC32()
                var written = 0L
                fun accept(bytes: ByteArray, count: Int) {
                    checkCancellation()
                    if (count > entry.unpackedBytes - written) themeFail(ThemeIssue.INTEGRITY_FAILED)
                    crc.update(bytes, 0, count)
                    write(bytes, count)
                    written += count
                }
                val input = ByteArray(ThemeLimits.IO_BYTES)
                var remaining = entry.compressedBytes
                if (entry.method == STORED) {
                    while (remaining > 0) {
                        checkCancellation()
                        val count = minOf(remaining, input.size.toLong()).toInt()
                        file.readFully(input, 0, count)
                        accept(input, count)
                        remaining -= count
                    }
                } else {
                    val inflater = Inflater(true)
                    val output = ByteArray(ThemeLimits.IO_BYTES)
                    try {
                        while (!inflater.finished()) {
                            checkCancellation()
                            if (inflater.needsInput()) {
                                if (remaining == 0L) themeFail(ThemeIssue.INTEGRITY_FAILED)
                                val count = minOf(remaining, input.size.toLong()).toInt()
                                file.readFully(input, 0, count)
                                inflater.setInput(input, 0, count)
                                remaining -= count
                            }
                            val count = inflater.inflate(output)
                            if (count > 0) accept(output, count)
                            else if (inflater.needsDictionary() || !inflater.needsInput() && !inflater.finished()) {
                                themeFail(ThemeIssue.INTEGRITY_FAILED)
                            }
                        }
                        if (remaining != 0L || inflater.remaining != 0) themeFail(ThemeIssue.INTEGRITY_FAILED)
                    } catch (error: DataFormatException) {
                        throw ThemeException(ThemeIssue.INTEGRITY_FAILED, error)
                    } finally {
                        inflater.end()
                    }
                }
                checkCancellation()
                if (written != entry.unpackedBytes || crc.value != entry.crc32) themeFail(ThemeIssue.INTEGRITY_FAILED)
            }
        } catch (error: IOException) {
            throw ThemeException(ThemeIssue.INTEGRITY_FAILED, error)
        }
    }

    private fun createParents(root: File, name: String) {
        var current = root
        for (segment in name.split('/').dropLast(1)) {
            current = File(current, segment)
            val relative = root.toPath().relativize(current.toPath()).toString().replace(File.separatorChar, '/')
            themeContainedFile(root, relative)
            if (Files.exists(current.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
                }
            } else Files.createDirectory(current.toPath())
        }
    }

    private fun validateProfile(version: Int, flags: Int, method: Int) {
        if (version !in 10..20 || method !in setOf(STORED, DEFLATED) || (flags and ALLOWED_FLAGS.inv()) != 0 ||
            method == DEFLATED && version != 20 || method == STORED && (flags and 6) != 0) {
            themeFail(ThemeIssue.UNSUPPORTED_ARCHIVE)
        }
    }

    private fun validateAttributes(attributes: Long, directory: Boolean) {
        val mode = (attributes ushr 16).toInt()
        val type = mode and 0xf000
        val expected = if (directory) 0x4000 else 0x8000
        if (type != 0 && type != expected || (attributes and 0x408) != 0L ||
            !directory && (attributes and 0x10) != 0L || !directory && (mode and 0x49) != 0) {
            themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
        }
    }

    private fun decodeName(bytes: ByteArray, flags: Int): String {
        if ((flags and UTF8_FLAG) == 0 && bytes.any { (it.toInt() and 255) > 127 }) themeFail(ThemeIssue.INVALID_PATH)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: IOException) {
            throw ThemeException(ThemeIssue.INVALID_PATH, error)
        }
    }

    private data class Header(
        val name: String,
        val key: String,
        val rawName: ByteArray,
        val directory: Boolean,
        val version: Int,
        val flags: Int,
        val method: Int,
        val crc32: Long,
        val compressedBytes: Long,
        val unpackedBytes: Long,
        val offset: Long,
    )

    private fun RandomAccessFile.u16(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
    private fun RandomAccessFile.u32(): Long = u16().toLong() or (u16().toLong() shl 16)

    companion object {
        private const val END_BYTES = 22L
        private const val CENTRAL_BYTES = 46L
        private const val LOCAL_BYTES = 30L
        private const val DESCRIPTOR_BYTES = 16L
        private const val END_SIGNATURE = 0x06054b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val DESCRIPTOR_SIGNATURE = 0x08074b50L
        private const val STORED = 0
        private const val DEFLATED = 8
        private const val DESCRIPTOR_FLAG = 8
        private const val UTF8_FLAG = 0x0800
        private const val ALLOWED_FLAGS = UTF8_FLAG or DESCRIPTOR_FLAG or 6
    }
}
