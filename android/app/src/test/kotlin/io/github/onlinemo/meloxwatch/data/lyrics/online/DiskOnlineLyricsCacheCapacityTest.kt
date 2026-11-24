package io.github.onlinemo.meloxwatch.data.lyrics.online

import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskOnlineLyricsCacheCapacityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = onlineOpaqueHash("netease:101")
    private fun key(id: Int) = OnlineLyricsCacheKey.of(owner, id.toString(), OnlineLyricsOrigin.NETEASE_LRC, null)

    @Test fun replacingAFullCacheBoundsTheRealPendingEntryAndKeepsTheOriginalUntilAtomicMove(): Unit = runBlocking {
        val directory = temporary.newFolder("owned-cache")
        val userLyrics = temporary.newFile("user-original.lrc").apply { writeText("[00:01.230]keep user lyrics") }
        val userMusic = temporary.newFile("user-original.mp3").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        val userLyricsBytes = userLyrics.readBytes()
        val userMusicBytes = userMusic.readBytes()
        val cache = DiskOnlineLyricsCache(directory)
        val keys = (201..232).map(::key)
        for ((index, cacheKey) in keys.withIndex()) {
            assertTrue(cache.write(cacheKey, OnlineLyricsText("[00:01.230]original-$index".toByteArray(), null)) { true })
            assertTrue(directory.resolve(cacheKey.fileName).setLastModified(1_000L + index * 1_000L))
        }
        assertEquals(32, directory.listFiles().orEmpty().size)
        val target = directory.resolve(keys.first().fileName)
        val originalFileBytes = target.readBytes()
        val replacement = OnlineLyricsText("[offset:123]\n[00:01.230]replacement".toByteArray(), null)
        var peakEntries = 0
        var sawPending = false

        // The existing current-intent callback runs after outputStream creates the real pending file.
        assertTrue(cache.write(keys.first(), replacement) {
            val entries = checkNotNull(directory.listFiles())
            peakEntries = maxOf(peakEntries, entries.size)
            if (entries.any { it.name.endsWith(".pending") }) {
                sawPending = true
                assertTrue(target.isFile)
                assertArrayEquals(originalFileBytes, target.readBytes())
            }
            true
        })

        assertTrue("A real pending file was observed", sawPending)
        assertTrue("Directory peak was $peakEntries entries", peakEntries <= 32)
        assertEquals(31, directory.listFiles().orEmpty().size)
        assertFalse(directory.resolve(keys[1].fileName).exists())
        for (cacheKey in keys.drop(2)) assertTrue(directory.resolve(cacheKey.fileName).isFile)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".pending") })
        assertArrayEquals(replacement.primary, cache.read(keys.first())?.primary)
        assertArrayEquals(userLyricsBytes, userLyrics.readBytes())
        assertArrayEquals(userMusicBytes, userMusic.readBytes())
    }
}
