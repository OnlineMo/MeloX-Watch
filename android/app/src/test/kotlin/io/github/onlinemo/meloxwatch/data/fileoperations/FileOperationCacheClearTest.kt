package io.github.onlinemo.meloxwatch.data.fileoperations

import android.graphics.Bitmap
import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkDiskCache
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.AudioWriteFixtures
import io.github.onlinemo.meloxwatch.data.lyrics.online.DiskOnlineLyricsCache
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsCacheKey
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsText
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowStatFs

class FileOperationCacheClearTest : FileOperationPhysicalTestFixture() {
    @Test fun actualCacheOwnersClearTheirDataAndRebuildIndexesWithoutDeletingUserFiles() = runBlocking {
        val parent = temporaryFolder.root
        val artworkDirectory = File(parent, "artwork-cache")
        val lyricsDirectory = File(parent, "lyrics-cache")
        val originals = listOf("original.mp3", "imported.audio", "saved.lrc", "favorite.data", "font.ttf", "theme.zip", "file-operations-v1.db", "recover.part")
            .associateWith { name -> File(parent, name).apply { writeBytes("preserve:$name".toByteArray()) } }
        // Robolectric 的 StatFs 使用固定 4096 字节单位；数量来自本测试真实 host FileStore，不证明设备空间。
        val store = Files.getFileStore(parent.toPath())
        fun blocks(bytes: Long): Int = (bytes / 4096L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        ShadowStatFs.registerStats(parent.absolutePath, blocks(store.totalSpace), blocks(store.unallocatedSpace), blocks(store.usableSpace))
        val artwork = ArtworkDiskCache(artworkDirectory)
        val key = "a".repeat(64)
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        try { artwork.put(key, bitmap, CancellationSignal()) } finally { bitmap.recycle() }
        val lyrics = DiskOnlineLyricsCache(lyricsDirectory)
        val lyricKey = OnlineLyricsCacheKey("b".repeat(64), "c".repeat(64))
        assertTrue(lyrics.write(lyricKey, OnlineLyricsText("[00:00.00]cached\n".toByteArray(), null)) { true })
        val audioId = tree.putAudio("physical.mp3", AudioWriteFixtures.mp3(), "audio/mpeg")
        val audioBefore = tree.bytes(audioId)
        assertTrue(artwork.measure(CancellationSignal()).bytes?.let { it > 0L } == true)
        assertTrue(lyrics.measureStorage().bytes?.let { it > 0L } == true)
        artwork.clear(CancellationSignal())
        lyrics.clearStorage()
        assertNull(artwork.load(key, CancellationSignal()))
        assertNull(lyrics.read(lyricKey))
        assertEquals(0L, artwork.occupiedBytes())
        for ((name, file) in originals) assertEquals("preserve:$name", file.readText())
        assertArrayEquals(audioBefore, tree.bytes(audioId))
        val next = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        try { artwork.put(key, next, CancellationSignal()) } finally { next.recycle() }
        assertNotNull(ArtworkDiskCache(artworkDirectory).load(key, CancellationSignal()))
        assertEquals(0, tree.deletes.get())
    }
}
