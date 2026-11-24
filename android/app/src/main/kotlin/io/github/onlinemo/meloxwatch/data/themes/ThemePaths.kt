package io.github.onlinemo.meloxwatch.data.themes

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** ZIP 名称不是文件系统授权；同时拒绝跨平台别名，避免云端与 Android 解释不同。 */
internal fun themePathKey(name: String, directory: Boolean = false): String {
    if (name.isEmpty() || name.length > ThemeLimits.PATH_CHARACTERS || name.startsWith('/') ||
        name.contains('\\') || name.contains(':') || name.contains('%')) themeFail(ThemeIssue.INVALID_PATH)
    val value = if (directory) name.removeSuffix("/") else name
    if (directory != name.endsWith('/') || value.isEmpty()) themeFail(ThemeIssue.INVALID_PATH)
    val segments = value.split('/')
    val directories = if (directory) segments.size else segments.size - 1
    if (directories > ThemeLimits.DIRECTORY_DEPTH) themeFail(ThemeIssue.INVALID_PATH)
    for (segment in segments) {
        if (!segment.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) || segment.endsWith('.')) {
            themeFail(ThemeIssue.INVALID_PATH)
        }
        val stem = segment.substringBefore('.').lowercase(Locale.ROOT)
        if (stem in RESERVED_NAMES) themeFail(ThemeIssue.INVALID_PATH)
    }
    return Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
}

internal fun themeAssetPath(name: String): String {
    val key = themePathKey(name)
    if (!name.startsWith("assets/") || name.endsWith('/')) themeFail(ThemeIssue.INVALID_PATH)
    return key
}

internal fun requireThemeRegularFile(file: File) {
    if (Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
    }
}

/** 实际 payload 必须等于清单；额外字体/文件不能绕过声明的字节计量或随安装被保留。 */
internal fun verifyThemePayloadTree(payload: File, manifest: ThemeManifest, checkCancellation: () -> Unit) {
    val root = payload.toPath()
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
        payload.absoluteFile != payload.canonicalFile) themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
    val files = (manifest.resources.map { it.path } + ThemeZipIndex.MANIFEST_NAME).toSet()
    val directories = HashSet<String>()
    for (file in files) {
        var parent = file.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            directories += parent
            parent = parent.substringBeforeLast('/', "")
        }
    }
    val seen = HashSet<String>()
    var nodes = 0
    Files.walk(root, ThemeLimits.DIRECTORY_DEPTH + 1).use { entries ->
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            checkCancellation()
            val path = iterator.next()
            if (++nodes > 1 + files.size + directories.size) themeFail(ThemeIssue.UNDECLARED_RESOURCE)
            if (Files.isSymbolicLink(path)) themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
            if (path == root) continue
            val name = root.relativize(path).joinToString("/") { it.toString() }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                themePathKey("$name/", directory = true)
                if (name !in directories) themeFail(ThemeIssue.UNDECLARED_RESOURCE)
            } else {
                requireThemeRegularFile(path.toFile())
                themePathKey(name)
                if (name !in files || !seen.add(name)) themeFail(ThemeIssue.UNDECLARED_RESOURCE)
            }
        }
    }
    checkCancellation()
    if (seen != files) themeFail(ThemeIssue.MISSING_RESOURCE)
}

/** 以不跟随链接的句柄计量后分配；读取期间文件变长或变短都不能扩大内存边界。 */
internal fun readBoundedThemeFile(
    file: File,
    maximum: Int,
    invalidIssue: ThemeIssue,
    checkCancellation: () -> Unit = {},
): ByteArray {
    requireThemeRegularFile(file)
    FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        val length = channel.size()
        if (length !in 1..maximum.toLong()) themeFail(invalidIssue)
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            checkCancellation()
            val buffer = ByteBuffer.wrap(bytes, offset, minOf(ThemeLimits.IO_BYTES, bytes.size - offset))
            val count = channel.read(buffer)
            if (count <= 0) themeFail(invalidIssue)
            offset += count
        }
        checkCancellation()
        if (channel.read(ByteBuffer.allocate(1)) != -1) themeFail(invalidIssue)
        return bytes
    }
}

internal fun digestBoundedThemeFile(
    file: File,
    expectedBytes: Long,
    maximum: Long,
    checkCancellation: () -> Unit,
): String {
    requireThemeRegularFile(file)
    if (expectedBytes !in 1..maximum) themeFail(ThemeIssue.INTEGRITY_FAILED)
    FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        if (channel.size() != expectedBytes) themeFail(ThemeIssue.INTEGRITY_FAILED)
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(ThemeLimits.IO_BYTES)
        var total = 0L
        while (total < expectedBytes) {
            checkCancellation()
            val count = channel.read(ByteBuffer.wrap(bytes, 0, minOf(bytes.size.toLong(), expectedBytes - total).toInt()))
            if (count <= 0) themeFail(ThemeIssue.INTEGRITY_FAILED)
            digest.update(bytes, 0, count)
            total += count
        }
        checkCancellation()
        if (channel.read(ByteBuffer.allocate(1)) != -1) themeFail(ThemeIssue.INTEGRITY_FAILED)
        return themeHex(digest.digest())
    }
}

/** 每一级都检查，不能只检查最后的文件而跟随中间目录的链接。 */
internal fun themeContainedFile(root: File, name: String): File {
    themePathKey(name)
    val canonicalRoot = root.canonicalFile
    if (Files.isSymbolicLink(root.toPath()) || root.absoluteFile != canonicalRoot) {
        themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
    }
    var current = canonicalRoot
    for (segment in name.split('/')) {
        current = File(current, segment)
        if (Files.isSymbolicLink(current.toPath()) || current.canonicalFile.parentFile != current.parentFile) {
            themeFail(ThemeIssue.LINK_OR_SPECIAL_FILE)
        }
    }
    if (!current.toPath().normalize().startsWith(canonicalRoot.toPath())) themeFail(ThemeIssue.INVALID_PATH)
    return current
}

private val RESERVED_NAMES = setOf(
    "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
    "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
)
