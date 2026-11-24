package io.github.onlinemo.meloxwatch.data.lyrics.online

import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseException
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseIssue
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real temporary owned bytes, quota and cancellation boundaries; no user file or device is read. */
class DiskOnlineLyricsCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = onlineOpaqueHash("netease:101")
    private fun key(id: String, account: String = owner) = OnlineLyricsCacheKey.of(account, id, OnlineLyricsOrigin.NETEASE_LRC, null)

    @Test fun exactOriginalAndTranslationBytesSurviveANewCacheOwner() = runBlocking {
        val directory = temporary.newFolder()
        val text = OnlineLyricsText("[offset:123]\n[00:01.230]原文".toByteArray(), "[00:01.230]translation".toByteArray())
        assertTrue(DiskOnlineLyricsCache(directory).write(key("201"), text) { true })
        val restored = checkNotNull(DiskOnlineLyricsCache(directory).read(key("201")))
        assertArrayEquals(text.primary, restored.primary)
        assertArrayEquals(text.translation, restored.translation)
        assertEquals(1, directory.listFiles().orEmpty().size)
        assertFalse(directory.listFiles().orEmpty().single().name.contains("netease:"))
    }

    @Test fun insertionEvictsOldOwnedEntriesWithinBothDiskBytesAndEntryCount() = runBlocking {
        val directory = temporary.newFolder()
        val cache = DiskOnlineLyricsCache(directory, maxBytes = 200L, maxEntries = 2)
        assertTrue(cache.write(key("201"), OnlineLyricsText(ByteArray(20) { 65 }, null)) { true })
        assertTrue(directory.resolve(key("201").fileName).setLastModified(1_000L))
        assertTrue(cache.write(key("202"), OnlineLyricsText(ByteArray(20) { 66 }, null)) { true })
        assertTrue(directory.resolve(key("202").fileName).setLastModified(2_000L))
        assertTrue(cache.write(key("203"), OnlineLyricsText(ByteArray(32) { 67 }, null)) { true })
        assertNull(cache.read(key("201")))
        assertArrayEquals(ByteArray(20) { 66 }, cache.read(key("202"))?.primary)
        assertArrayEquals(ByteArray(32) { 67 }, cache.read(key("203"))?.primary)
        assertTrue(directory.listFiles().orEmpty().size <= 2)
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 200L)
    }

    @Test fun retiredReplacementLeavesTheEarlierExactBytesAndRemovesItsPendingFile() = runBlocking {
        val directory = temporary.newFolder()
        val cache = DiskOnlineLyricsCache(directory)
        val old = OnlineLyricsText("[00:01]original".toByteArray(), null)
        assertTrue(cache.write(key("201"), old) { true })
        var checks = 0
        try {
            cache.write(key("201"), OnlineLyricsText("[00:01]replacement".toByteArray(), null)) { ++checks <= 3 }
            error("Retired cache write succeeded")
        } catch (_: CancellationException) { }
        assertArrayEquals(old.primary, cache.read(key("201"))?.primary)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".pending") })
    }

    @Test fun checksumFailureIsReportedAndATightReadLimitRejectsBeforeAllocatingTextArrays() = runBlocking {
        val directory = temporary.newFolder()
        val cache = DiskOnlineLyricsCache(directory)
        assertTrue(cache.write(key("201"), OnlineLyricsText(ByteArray(300) { 65 }, ByteArray(300) { 66 })) { true })
        try { cache.read(key("201"), maxTextBytes = 599L); error("Oversized cache allocation was admitted") }
        catch (error: LyricsParseException) { assertEquals(LyricsParseIssue.MEMORY_LIMIT, error.issue) }
        val originalFile = directory.resolve(key("201").fileName)
        RandomAccessFile(originalFile, "rw").use { file -> file.seek(file.length() - 1L); file.writeByte(67) }
        try { cache.read(key("201")); error("Corrupt original was displayed") }
        catch (_: IOException) { }
        assertTrue(originalFile.exists())
    }

    @Test fun clearingOneAccountRemovesOnlyItsDerivedFilesAndNeverTouchesTheLibrary() = runBlocking {
        val directory = temporary.newFolder()
        val unrelated = temporary.newFile("library-sentinel.txt").apply { writeText("keep library") }
        val other = onlineOpaqueHash("netease:102")
        val cache = DiskOnlineLyricsCache(directory)
        assertTrue(cache.write(key("201"), OnlineLyricsText("first".toByteArray(), null)) { true })
        assertTrue(cache.write(key("201", other), OnlineLyricsText("other account".toByteArray(), null)) { true })
        cache.clearOwner(owner)
        assertNull(cache.read(key("201")))
        assertEquals("other account", cache.read(key("201", other))?.primary?.toString(Charsets.UTF_8))
        assertEquals("keep library", unrelated.readText())
    }
}
