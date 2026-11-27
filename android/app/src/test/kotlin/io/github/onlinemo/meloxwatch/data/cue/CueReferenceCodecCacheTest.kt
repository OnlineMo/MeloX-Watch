package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.model.CueFileBinding
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import io.github.onlinemo.meloxwatch.model.CueSegmentBinding
import io.github.onlinemo.meloxwatch.model.CueTrackReference
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CueReferenceCodecCacheTest {
    @Test fun decodingAnAlreadyReadNativeBindingStillRejectsPortableOnlyInput() {
        val native = bound("cached-native")
        val value = CueReferenceCodec.encode(native)
        assertEquals(native, CueReferenceCodec.decode(value))
        repeat(2) { expectInvalid { CueReferenceCodec.decode(value, portableOnly = true) } }
        val portable = native.portable()
        assertEquals(portable, CueReferenceCodec.decode(CueReferenceCodec.encode(portable), portableOnly = true))
    }

    @Test fun collidingStringAndModelHashesDoNotExchangeSourceOrEncodedBytes() {
        val first = pending("Aa")
        val second = pending("BB")
        val firstText = CueReferenceCodec.encode(first)
        val secondText = CueReferenceCodec.encode(second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(firstText.hashCode(), secondText.hashCode())
        assertNotEquals(firstText, secondText)
        assertNotEquals(first.trackKey, second.trackKey)
        repeat(3) {
            assertEquals(firstText, CueReferenceCodec.encode(first))
            assertEquals(secondText, CueReferenceCodec.encode(second))
            assertEquals(first, CueReferenceCodec.decode(firstText, portableOnly = true))
            assertEquals(second, CueReferenceCodec.decode(secondText, portableOnly = true))
        }
    }

    @Test fun olderValuesRetainEveryBoundaryAndHintAfterMoreThanOneMemoWindow() {
        val original = bound("original-window")
        val native = CueReferenceCodec.encode(original)
        repeat(32) { index ->
            val other = pending("window-$index")
            assertEquals(other, CueReferenceCodec.decode(CueReferenceCodec.encode(other), portableOnly = true))
        }
        assertEquals(original, CueReferenceCodec.decode(native))
        assertEquals(original.pcmWindow, CueReferenceCodec.decode(native).pcmWindow)
        expectInvalid { CueReferenceCodec.decode(native, portableOnly = true) }
    }

    @Test(timeout = 15000L) fun concurrentReadersKeepPortableAndNativeRepresentationsIndependent() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 16).map { index -> executor.submit(Callable {
                val reference = bound("reader-$index")
                val native = CueReferenceCodec.encode(reference)
                val portable = CueReferenceCodec.encode(reference.portable())
                repeat(16) {
                    assertEquals(reference, CueReferenceCodec.decode(native))
                    assertEquals(reference.portable(), CueReferenceCodec.decode(portable, portableOnly = true))
                    expectInvalid { CueReferenceCodec.decode(native, portableOnly = true) }
                }
            }) }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun pending(sourceId: String): LocalCueReference = LocalCueReference(CueTrackReference(
        sourceId, "document", 1, 1L, 0L, 75L, 1_000_000L, null,
        "sha256:" + "a".repeat(64), audioContentTokenHint = "sha256:" + "b".repeat(64),
    ))

    private fun bound(sourceId: String): LocalCueReference {
        val portable = pending(sourceId)
        fun file(id: String, token: String, bytes: Long) = CueFileBinding(
            sourceId, 1L, 1L, "grant", "alias-$id", "object-$id",
            LocalLocationReference("location-$id", "content://cue-cache/$id", "content://cue-cache/root",
                1, LocalAccess.PERSISTED, 1L), null, token, bytes,
        )
        return LocalCueReference(portable.identity, CueSegmentBinding(
            file("cue", portable.identity.cueContentToken, 512L),
            file("audio", checkNotNull(portable.identity.audioContentTokenHint), 88_244L),
            CuePcmWaveFacts(88_244L, 44L, 88_200L, 44_100, 1),
        ))
    }

    private fun expectInvalid(action: () -> Unit) {
        try { action(); throw AssertionError("Expected invalid CUE representation") }
        catch (_: IllegalArgumentException) { }
    }
}
