package io.github.onlinemo.meloxwatch.companion.data

import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CompanionThemeBuilderTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4bx7/HwAGYwKVVoyqJAAAAABJRU5ErkJggg==")
    private fun icon(role: CompanionIconRole, number: Long = 1L, hash: TransferHash = TransferHash.digest(png), bytes: Long = png.size.toLong()): CompanionThemeIcon =
        CompanionThemeIcon(role, UUID(0L, number).toString(), hash, bytes, "Fixture license", "Fixture notice")
    private class ObservedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    private class ObservedOutput : ByteArrayOutputStream() {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    @Test fun realSchema1ZipHasFixedStoredPathsExactHashesAndNoExtraOrCommentFields() {
        val icons = listOf(icon(CompanionIconRole.QUEUE, 2L), icon(CompanionIconRole.PLAY))
        val draft = CompanionThemeDraft(id = "phone.fixture", icons = icons)
        val inputs = ArrayList<ObservedInput>()
        val output = ObservedOutput()
        CompanionThemeBuilder.write(draft, output, { ObservedInput(png).also(inputs::add) })
        assertTrue(inputs.all { it.closed })
        assertFalse(output.closed)
        val bytes = output.toByteArray()
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                names += entry.name
                assertEquals(ZipEntry.STORED, entry.method)
                assertTrue(entry.extra == null || entry.extra.isEmpty())
                assertNull(entry.comment)
                val body = archive.readBytes()
                assertEquals(body.size.toLong(), entry.size)
                assertEquals(body.size.toLong(), entry.compressedSize)
                assertEquals(CRC32().also { it.update(body) }.value, entry.crc)
                if (entry.name == "theme.json") {
                    val text = body.toString(Charsets.UTF_8)
                    assertTrue(text.contains("\"schemaVersion\":1"))
                    assertTrue(text.contains("\"id\":\"phone.fixture\""))
                    assertTrue(text.contains("\"sha256\":\"${TransferHash.digest(png).forLocalStorage()}\""))
                    assertTrue(text.contains("\"fonts\":{}"))
                } else assertArrayEquals(png, body)
                archive.closeEntry()
            }
        }
        assertEquals(listOf("theme.json", "icons/play.png", "icons/queue.png"), names)
        inspectZipHeaders(bytes, names)
    }

    @Test fun sameDraftProducesIdenticalZipBytes() {
        val draft = CompanionThemeDraft(id = "phone.fixture", icons = listOf(icon(CompanionIconRole.PLAY)))
        fun build(): ByteArray = ByteArrayOutputStream().also { CompanionThemeBuilder.write(draft, it, { ByteArrayInputStream(png) }) }.toByteArray()
        assertArrayEquals(build(), build())
    }

    @Test fun licenseQuotesBackslashesAndNewlinesAreEscapedWithoutAddingPaths() {
        val draft = CompanionThemeDraft(id = "phone.fixture", name = "Fixture \"music\"", licenseName = "Fixture\\license",
            licenseText = "First line\nSecond\tline")
        val manifest = CompanionThemeBuilder.manifest(draft).toString(Charsets.UTF_8)
        assertTrue(manifest.contains("\"displayName\":\"Fixture \\\"music\\\"\""))
        assertTrue(manifest.contains("\"name\":\"Fixture\\\\license\""))
        assertTrue(manifest.contains("\"text\":\"First line\\nSecond\\tline\""))
        assertFalse(manifest.contains('\n'))
        assertFalse(manifest.contains("file:"))
        assertTrue(manifest.contains("\"resources\":[]"))
    }

    @Test fun mismatchedIconHashOrSizeClosesItsSelectedInputAndRejectsPackage() {
        for (candidate in listOf(icon(CompanionIconRole.PLAY, hash = TransferHash.digest(byteArrayOf(5))), icon(CompanionIconRole.PLAY, bytes = png.size + 1L))) {
            val input = ObservedInput(png)
            issue(TransferIssue.INTEGRITY_FAILED) { CompanionThemeBuilder.write(CompanionThemeDraft(id = "phone.fixture", icons = listOf(candidate)), ByteArrayOutputStream(), { input }) }
            assertTrue(input.closed)
        }
    }

    @Test fun oversizeIconInputIsBoundedAndClosed() {
        val candidate = icon(CompanionIconRole.PLAY, bytes = CompanionLimits.ICON_BYTES.toLong())
        val input = ObservedInput(ByteArray(CompanionLimits.ICON_BYTES + 1))
        issue(TransferIssue.QUOTA_EXCEEDED) { CompanionThemeBuilder.write(CompanionThemeDraft(id = "phone.fixture", icons = listOf(candidate)), ByteArrayOutputStream(), { input }) }
        assertTrue(input.closed)
    }

    @Test fun cancelledIconReadRetainsCancellationAndClosesItsInput() {
        val input = ObservedInput(png)
        var opened = false
        try {
            CompanionThemeBuilder.write(CompanionThemeDraft(id = "phone.fixture", icons = listOf(icon(CompanionIconRole.PLAY))), ByteArrayOutputStream(),
                { opened = true; input }, { if (opened) throw CancellationException("Fixture cancellation") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(opened)
        assertTrue(input.closed)
    }

    @Test fun unreadablePaletteFailsBeforeWritingOrOpeningResources() {
        val colors = CompanionColor.entries.associateWith { it.defaultHex }.toMutableMap()
        colors[CompanionColor.ACCENT] = "#111114"
        val output = ByteArrayOutputStream()
        var opened = false
        issue(TransferIssue.INVALID_INPUT) {
            CompanionThemeBuilder.write(CompanionThemeDraft(id = "phone.fixture", colors = colors, icons = listOf(icon(CompanionIconRole.PLAY))), output,
                { opened = true; ByteArrayInputStream(png) })
        }
        assertEquals(0, output.size())
        assertFalse(opened)
    }

    @Test fun manifestQuotaIsCheckedBeforeOpeningAnyResource() {
        val notice = "许".repeat(16 * 1024)
        val icons = CompanionIconRole.entries.mapIndexed { index, role ->
            CompanionThemeIcon(role, UUID(0L, 20L + index).toString(), TransferHash.digest(png), png.size.toLong(), "Fixture license", notice)
        }
        val output = ByteArrayOutputStream()
        var opened = false
        issue(TransferIssue.QUOTA_EXCEEDED) {
            CompanionThemeBuilder.write(CompanionThemeDraft(id = "phone.fixture", icons = icons), output, { opened = true; ByteArrayInputStream(png) })
        }
        assertEquals(0, output.size())
        assertFalse(opened)
    }

    /** Parse standard ZIP header lengths independently of ZipInputStream's local-entry view. */
    private fun inspectZipHeaders(bytes: ByteArray, expectedNames: List<String>) {
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        val localNames = ArrayList<String>()
        while (view.getInt(offset) == 0x04034b50) {
            val flags = view.getShort(offset + 6).toInt() and 0xffff
            val method = view.getShort(offset + 8).toInt() and 0xffff
            val size = view.getInt(offset + 18)
            val nameBytes = view.getShort(offset + 26).toInt() and 0xffff
            val extraBytes = view.getShort(offset + 28).toInt() and 0xffff
            assertEquals(0, flags and 8)
            assertEquals(0, method)
            assertEquals(0, extraBytes)
            localNames += String(bytes, offset + 30, nameBytes, Charsets.UTF_8)
            offset += 30 + nameBytes + extraBytes + size
        }
        assertEquals(expectedNames, localNames)
        val centralNames = ArrayList<String>()
        while (view.getInt(offset) == 0x02014b50) {
            val nameBytes = view.getShort(offset + 28).toInt() and 0xffff
            val extraBytes = view.getShort(offset + 30).toInt() and 0xffff
            val commentBytes = view.getShort(offset + 32).toInt() and 0xffff
            assertEquals(0, extraBytes)
            assertEquals(0, commentBytes)
            centralNames += String(bytes, offset + 46, nameBytes, Charsets.UTF_8)
            offset += 46 + nameBytes + extraBytes + commentBytes
        }
        assertEquals(expectedNames, centralNames)
        assertEquals(0x06054b50, view.getInt(offset))
        assertEquals(0, view.getShort(offset + 20).toInt())
        assertEquals(bytes.size, offset + 22)
    }

    private fun issue(expected: TransferIssue, action: () -> Unit) {
        try { action(); fail("Expected a theme package issue") } catch (error: TransferException) { assertEquals(expected, error.issue) }
    }
}
