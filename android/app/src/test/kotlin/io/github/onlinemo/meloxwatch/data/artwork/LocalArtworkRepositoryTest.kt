package io.github.onlinemo.meloxwatch.data.artwork

import android.content.Context
import android.graphics.Point
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.local.fixtureAudioProvider
import io.github.onlinemo.meloxwatch.data.local.fixtureDescriptor
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LocalArtworkRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun systemThumbnailAndControlledEmbeddedExtractionUseTheirActualSeparateSources() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(embeddedPng(png(40, 20))) }
        val thumbnail = File(temporary.root, "thumbnail.png").apply { writeBytes(png(8, 4)) }
        val provider = fixtureAudioProvider("covers", audio).apply { this.thumbnail = thumbnail }
        val repository = repository()
        val descriptor = fixtureDescriptor("covers", audio)
        repository.acquire(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL)!!.use {
            assertEquals(8, it.bitmap.width)
            assertEquals(4, it.bitmap.height)
        }
        assertEquals(1, provider.thumbnailReads)
        assertEquals(Point(96, 96), provider.thumbnailSize)
        assertEquals(2, provider.audioReads)
        repository.acquire(descriptor, ArtworkReadPath.LOCAL_EXTRACT)!!.use {
            assertEquals(40, it.bitmap.width)
            assertEquals(20, it.bitmap.height)
        }
        assertEquals(5, provider.audioReads)
        assertEquals(1, provider.thumbnailReads)
    }

    @Test fun missingSystemThumbnailFallsBackToBoundedLocalExtractionAndDiskRemainsUsable() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(embeddedPng(png(10, 5))) }
        val provider = fixtureAudioProvider("fallback-covers", audio)
        val repository = repository()
        val descriptor = fixtureDescriptor("fallback-covers", audio)
        repository.acquire(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL)!!.close()
        assertEquals(1, provider.thumbnailReads)
        assertEquals(3, provider.audioReads)
        repository.trimUnused()
        provider.denied = true
        val cached = repository.acquire(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL)
        assertNotNull(cached)
        cached!!.close()
        assertEquals(1, provider.thumbnailReads)
        assertEquals(3, provider.audioReads)
    }

    @Test fun missingCoverIsBoundedlyCachedAndExplicitRefreshTriesAgain() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val provider = fixtureAudioProvider("missing-covers", audio)
        val repository = repository()
        val descriptor = fixtureDescriptor("missing-covers", audio)
        assertNull(repository.acquire(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL))
        assertEquals(3, provider.audioReads)
        assertNull(repository.acquire(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL))
        assertEquals(3, provider.audioReads)
        assertNull(repository.acquire(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL, refresh = true))
        assertEquals(6, provider.audioReads)
    }

    @Test fun declaredOversizedThumbnailFailsBeforeDecodeOrLocalFallback() = runBlocking {
        val audio = File(temporary.root, "audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val thumbnail = File(temporary.root, "too-large.png")
        thumbnail.outputStream().use { output ->
            val chunk = ByteArray(64 * 1024)
            repeat(ArtworkLimits.INPUT_BYTES / chunk.size) { output.write(chunk) }
            output.write(1)
        }
        val provider = fixtureAudioProvider("oversized-covers", audio).apply { this.thumbnail = thumbnail }
        val repository = repository()
        try {
            repository.acquire(fixtureDescriptor("oversized-covers", audio), ArtworkReadPath.SYSTEM_THUMBNAIL)
            throw AssertionError("Expected encoded-input bound")
        } catch (error: ArtworkException) { assertEquals(ArtworkIssue.INPUT_TOO_LARGE, error.issue) }
        assertEquals(1, provider.audioReads)
    }

    @Test fun sourceReplacementDuringThumbnailReadCannotPolluteTheOriginalDiskKey() = runBlocking {
        val original = byteArrayOf(1, 2, 3, 4)
        val audioFixture = File(temporary.root, "audio.mp3").apply { writeBytes(original) }
        val thumbnailFixture = File(temporary.root, "thumbnail.png").apply { writeBytes(png(8, 4)) }
        val provider = fixtureAudioProvider("changing-covers", audioFixture).apply {
            thumbnail = thumbnailFixture
            beforeThumbnail = {
                audioFixture.writeBytes(byteArrayOf(4, 3, 2, 1))
                thumbnailFixture.writeBytes(png(32, 16))
            }
        }
        val descriptor = fixtureDescriptor("changing-covers", audioFixture)
        try {
            repository().acquire(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL)
            throw AssertionError("Expected changed source before cache publication")
        } catch (error: ArtworkException) {
            assertEquals(ArtworkIssue.SOURCE_UNAVAILABLE, error.issue)
            assertEquals(SourceReadIssue.SOURCE_CHANGED, (error.cause as SourceReadException).issue)
        }
        assertEquals(1, provider.thumbnailReads)
        audioFixture.writeBytes(original)
        thumbnailFixture.writeBytes(png(8, 4))
        provider.beforeThumbnail = null
        repository().acquire(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL)!!.use {
            assertEquals(8, it.bitmap.width)
            assertEquals(4, it.bitmap.height)
        }
        assertEquals("a rejected image must not be recovered from disk", 2, provider.thumbnailReads)
    }

    @Test fun anUnprovedLargeWeakSourceCannotCreateACoverOrMissingCacheEntry() = runBlocking {
        val audio = File(temporary.root, "large.mp3").apply {
            outputStream().use { output ->
                val chunk = ByteArray(64 * 1024)
                repeat(LocalInputLimits.READ_PROOF_BYTES / chunk.size + 1) { output.write(chunk) }
            }
        }
        val thumbnail = File(temporary.root, "thumbnail.png").apply { writeBytes(png(8, 4)) }
        val provider = fixtureAudioProvider("unproved-covers", audio).apply { this.thumbnail = thumbnail }
        val descriptor = fixtureDescriptor("unproved-covers", audio.length())
        val repository = repository()
        repeat(2) {
            try {
                repository.acquire(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL)
                throw AssertionError("Expected unknown content proof")
            } catch (error: ArtworkException) {
                assertEquals(SourceReadIssue.CONTENT_UNKNOWN, (error.cause as SourceReadException).issue)
            }
        }
        assertEquals(0, provider.thumbnailReads)
        assertEquals(2, provider.audioReads)
    }

    @Test fun artworkKeysBindContentProofSourceRevisionPathAndOutputSize() {
        val descriptor = fixtureDescriptor("cover-keys", 10, "sha256:" + "1".repeat(64))
        val original = artworkKey(descriptor, ArtworkReadPath.LOCAL_EXTRACT, 96)
        val variants = listOf(
            artworkKey(descriptor.copy(verifiedContentToken = "sha256:" + "2".repeat(64)), ArtworkReadPath.LOCAL_EXTRACT, 96),
            artworkKey(descriptor.copy(source = descriptor.source.copy(authEpoch = 2)), ArtworkReadPath.LOCAL_EXTRACT, 96),
            artworkKey(descriptor, ArtworkReadPath.SYSTEM_THUMBNAIL, 96),
            artworkKey(descriptor, ArtworkReadPath.LOCAL_EXTRACT, 192),
        )
        assertTrue(variants.all { it != original })
        assertEquals(4, variants.distinct().size)
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()
    private fun repository(io: LocalProviderIo = LocalProviderIo()): LocalArtworkRepository =
        LocalArtworkRepository(context(), io, AndroidLocalSources(context(), io)::validateRead)
}

internal fun embeddedPng(image: ByteArray): ByteArray {
    val payload = byteArrayOf(3) + "image/png\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 0) + image
    fun syncSafe(size: Int): ByteArray = ByteArray(4) { ((size shr ((3 - it) * 7)) and 127).toByte() }
    val frame = "APIC".toByteArray(Charsets.US_ASCII) + syncSafe(payload.size) + byteArrayOf(0, 0) + payload
    return "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0) + syncSafe(frame.size) + frame
}
