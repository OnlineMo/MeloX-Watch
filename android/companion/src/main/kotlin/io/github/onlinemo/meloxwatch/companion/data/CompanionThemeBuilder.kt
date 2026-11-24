package io.github.onlinemo.meloxwatch.companion.data

import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Emits the actual watch theme schema 1. ZIP32 stored entries avoid expansion-ratio surprises.
 * Entry names are derived from fixed icon roles, never from selected paths. No script/font/network
 * reference is generated. Watch validation and its local confirmation remain mandatory.
 */
internal object CompanionThemeBuilder {
    fun write(draft: CompanionThemeDraft, output: OutputStream, icons: (CompanionThemeIcon) -> InputStream, check: () -> Unit = {}) {
        draft.validatePalette()
        val manifest = manifest(draft)
        if (manifest.size > 64 * 1024) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
        ZipOutputStream(LimitedOutput(output), Charsets.UTF_8).use { archive ->
            writeEntry(archive, "theme.json", manifest, check)
            for (icon in draft.icons.sortedBy { it.role.ordinal }) {
                check()
                val bytes = icons(icon).use { readCompanionBounded(it, CompanionLimits.ICON_BYTES, check) }
                if (bytes.size.toLong() != icon.byteCount || TransferHash.digest(bytes) != icon.sha256) throw TransferException(TransferIssue.INTEGRITY_FAILED)
                writeEntry(archive, resourcePath(icon.role), bytes, check)
            }
            check()
        }
    }

    internal fun manifest(draft: CompanionThemeDraft): ByteArray {
        val colors = CompanionColor.entries.joinToString(",") { quote(it.wireName) + ":" + quote(draft.colors.getValue(it).uppercase(Locale.ROOT)) }
        val resources = draft.icons.sortedBy { it.role.ordinal }.joinToString(",") { icon ->
            "{\"id\":" + quote(resourceId(icon.role)) + ",\"path\":" + quote(resourcePath(icon.role)) +
                ",\"kind\":\"image\",\"mediaType\":\"image/png\",\"byteCount\":" + icon.byteCount +
                ",\"sha256\":" + quote(icon.sha256.forLocalStorage()) + ",\"license\":" + license(icon.licenseName, icon.licenseText) + "}"
        }
        val iconMap = draft.icons.sortedBy { it.role.ordinal }.joinToString(",") { quote(it.role.name.lowercase(Locale.ROOT)) + ":" + quote(resourceId(it.role)) }
        return ("{\"schemaVersion\":1,\"id\":" + quote(draft.id) + ",\"displayName\":" + quote(draft.name) +
            ",\"author\":" + quote(draft.author) + ",\"license\":" + license(draft.licenseName, draft.licenseText) +
            ",\"colors\":{" + colors + "},\"background\":{\"mode\":" + quote(draft.background.wireName) +
            ",\"blurLevel\":" + draft.blurLevel + ",\"darkMaskPercent\":" + draft.darkMaskPercent +
            "},\"resources\":[" + resources + "],\"icons\":{" + iconMap + "},\"fonts\":{}}").toByteArray(Charsets.UTF_8)
    }

    private fun resourceId(role: CompanionIconRole): String = "icon-" + role.name.lowercase(Locale.ROOT)
    private fun resourcePath(role: CompanionIconRole): String = "icons/" + role.name.lowercase(Locale.ROOT) + ".png"
    private fun license(name: String, text: String): String = "{\"name\":" + quote(name) + ",\"text\":" + quote(text) + "}"
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character -> when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> {
                    require(!character.isISOControl())
                    append(character)
                }
        } }
        append('"')
    }

    private fun writeEntry(output: ZipOutputStream, name: String, bytes: ByteArray, check: () -> Unit) {
        check()
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().also { it.update(bytes) }.value
            // A supported DOS date avoids platform-inserted extended-time extra fields (watch v1 rejects extras).
            time = 1577836800000L
            extra = null
            comment = null
        }
        output.putNextEntry(entry)
        var offset = 0
        while (offset < bytes.size) {
            check()
            val count = minOf(64 * 1024, bytes.size - offset)
            output.write(bytes, offset, count)
            offset += count
        }
        output.closeEntry()
    }

    private class LimitedOutput(output: OutputStream) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) {
            reserve(1)
            out.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            reserve(length)
            out.write(bytes, offset, length)
        }
        override fun close() { flush() }
        private fun reserve(length: Int) {
            if (length < 0 || length > TransferLimits.THEME_BYTES - count) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
            count += length
        }
    }
}
