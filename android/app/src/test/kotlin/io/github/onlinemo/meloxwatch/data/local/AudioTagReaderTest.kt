package io.github.onlinemo.meloxwatch.data.local

import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class AudioTagReaderTest {
    @Test fun id3KeepsVersion23SlashAndVersion24ProvenValues() {
        val v23 = id3(3, frame(3, "TPE1", byteArrayOf(3) + "AC/DC".toByteArray()))
        assertEquals(listOf("AC/DC"), AudioTagReader.readText(ByteArrayInputStream(v23)).artists)
        val v24 = id3(4, frame(4, "TPE1", byteArrayOf(3) + "Artist A\u0000Artist B".toByteArray()))
        assertEquals(listOf("Artist A", "Artist B"), AudioTagReader.readText(ByteArrayInputStream(v24)).artists)
    }

    @Test fun flacRepeatedArtistsKeepOrderAndDoNotSplitNames() {
        val comments = listOf("ARTIST=AC/DC", "ARTIST=Guest", "TITLE=Title", "ALBUMARTIST=Band")
        val output = ByteArrayOutputStream()
        fun number(value: Int) { output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        val vendor = "test".toByteArray()
        number(vendor.size); output.write(vendor); number(comments.size)
        for (comment in comments) { val bytes = comment.toByteArray(); number(bytes.size); output.write(bytes) }
        val block = output.toByteArray()
        val input = "fLaC".toByteArray() + byteArrayOf(0x84.toByte(), (block.size shr 16).toByte(), (block.size shr 8).toByte(), block.size.toByte()) + block
        val parsed = AudioTagReader.readText(ByteArrayInputStream(input))
        assertEquals(listOf("AC/DC", "Guest"), parsed.artists)
        assertEquals("Band", parsed.albumArtist)
        assertEquals("Title", parsed.title)
    }

    @Test fun frontCoverWinsOverEarlierBackCover() {
        fun picture(type: Int, bytes: ByteArray): ByteArray = byteArrayOf(3) + "image/png\u0000".toByteArray() + byteArrayOf(type.toByte(), 0) + bytes
        val expected = byteArrayOf(1, 2, 3, 4)
        val input = id3(4, frame(4, "APIC", picture(4, byteArrayOf(9))) + frame(4, "APIC", picture(3, expected)))
        assertArrayEquals(expected, AudioTagReader.readPicture(ByteArrayInputStream(input)))
    }

    @Test fun oversizedPictureHeaderFailsBeforeReadingPayload() {
        val size = AudioTagReader.MAX_PICTURE_BYTES + 1
        val header = "APIC".toByteArray() + syncSafe(size) + byteArrayOf(0, 0)
        val bytes = "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncSafe(size + 10) + header
        val stream = ByteArrayInputStream(bytes)
        expect(SourceReadIssue.METADATA_UNREADABLE) { AudioTagReader.readPicture(stream) }
        assertEquals(0, stream.available())
    }

    @Test fun corruptTextAndCancelledReadsDoNotBecomeEmptySuccessfulTags() {
        val truncated = id3(4, frame(4, "TIT2", byteArrayOf(3) + "Title".toByteArray())).dropLast(2).toByteArray()
        try { AudioTagReader.readText(ByteArrayInputStream(truncated)); throw AssertionError("Expected truncated tag") }
        catch (_: java.io.EOFException) { }
        val signal = CancellationSignal().apply { cancel() }
        try { AudioTagReader.readPicture(ByteArrayInputStream(byteArrayOf(0, 1, 2, 3)), signal); throw AssertionError("Expected cancellation") }
        catch (_: OperationCanceledException) { }
    }

    @Test fun ignoredFramesAreStreamedInBoundedChunks() {
        val bytes = id3(4, frame(4, "PRIV", ByteArray(200_000)))
        var largest = 0
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largest = maxOf(largest, length)
                return super.read(buffer, offset, length)
            }
        }
        AudioTagReader.readText(stream)
        assertTrue(largest <= 64 * 1024)
    }

    private fun expect(issue: SourceReadIssue, action: () -> Unit) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: SourceReadException) { assertEquals(issue, error.issue) }
    }

    private fun id3(version: Int, frames: ByteArray): ByteArray =
        "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncSafe(frames.size) + frames
    private fun frame(version: Int, name: String, payload: ByteArray): ByteArray = name.toByteArray() +
        (if (version == 4) syncSafe(payload.size) else ByteBuffer.allocate(4).putInt(payload.size).array()) + byteArrayOf(0, 0) + payload
    private fun syncSafe(value: Int): ByteArray = ByteArray(4) { index -> ((value shr ((3 - index) * 7)) and 127).toByte() }
}
