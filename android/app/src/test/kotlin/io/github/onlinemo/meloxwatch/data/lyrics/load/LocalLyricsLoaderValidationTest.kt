package io.github.onlinemo.meloxwatch.data.lyrics.load

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LocalLyricsLoaderValidationTest {
    @Test fun callerBudgetIsEnforcedBeforeTheFirstTextPayloadReadAndDoesNotOccupyTheNextLoad(): Unit = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val input = fixture.document("[00:01]original")
        val request = fixture.request(LyricsLoadSelection.TextDocument(input), input.documentId)
        val loader = fixture.loader()
        var reads = 0
        fixture.streams[input.candidate.uri] = {
            object : FilterInputStream(ByteArrayInputStream("[00:01]original".toByteArray())) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    return super.read(buffer, offset, length)
                }
            }
        }
        assertEquals(LyricsLoadIssue.MEMORY_LIMIT, failure(loader.load(request.copy(maxAccountedBytes = 1L))).issue)
        assertEquals(0, reads)
        assertEquals(1, fixture.closedStreams.get())
        assertEquals("original", loaded(loader.load(request)).document.timeline.line(0).text)
        assertTrue(reads > 0)
    }

    @Test fun aCallerCannotWidenTheLoadersFrozenBudgetForEmbeddedLyrics(): Unit = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val lyrics = io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits(maxAccountedBytes = 1L)
        val loader = fixture.loader(LocalLyricsLoadLimits(lyrics = lyrics))
        assertEquals(LyricsLoadIssue.MEMORY_LIMIT, failure(loader.load(fixture.request())).issue)
        assertTrue(fixture.openedUris.isEmpty())
    }

    @Test fun validationFingerprintsTheShownTextWithoutReadingAnotherTimelineOrChangingItsDocument(): Unit = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val input = fixture.document("[00:01]original")
        val request = fixture.request(LyricsLoadSelection.TextDocument(input), input.documentId)
        val loader = fixture.loader()
        val result = loaded(loader.load(request))
        val document = result.document
        val opened = fixture.openedUris.size
        assertTrue(loader.validate(request, result))
        assertSame(document, result.document)
        assertEquals(opened + 1, fixture.openedUris.size)
        assertEquals(fixture.openedUris.size, fixture.closedStreams.get())
        assertEquals("original", document.timeline.line(0).text)
    }

    @Test fun sameSizeTextReplacementAfterDisplayRejectsSoundConfirmationAndLeavesTheShownDocumentIntact(): Unit = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val original = "[00:01]original"
        val changed = "[00:01]modified"
        assertEquals(original.toByteArray().size, changed.toByteArray().size)
        val input = fixture.document(original)
        val request = fixture.request(LyricsLoadSelection.TextDocument(input), input.documentId)
        val loader = fixture.loader()
        val result = loaded(loader.load(request))
        fixture.streams[input.candidate.uri] = { ByteArrayInputStream(changed.toByteArray()) }
        assertFalse(loader.validate(request, result))
        assertEquals("original", result.document.timeline.line(0).text)
        fixture.streams[input.candidate.uri] = { ByteArrayInputStream(original.toByteArray()) }
        assertTrue(loader.validate(request, result))
    }

    @Test fun aNewSameNameAmbiguityIsRecheckedBeforeAnyOldSiblingBytesCanAuthorizePlayback(): Unit = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val sibling = fixture.sibling("[00:01]sibling")
        fixture.directory = listOf(fixture.audioRow, sibling)
        val request = fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))
        val loader = fixture.loader()
        val result = loaded(loader.load(request))
        val opened = fixture.openedUris.size
        val duplicate = fixture.sibling("[00:01]other", name = "TRACK.LRC", objectId = "other-sibling")
        fixture.directory = listOf(fixture.audioRow, sibling, duplicate)
        assertFalse(loader.validate(request, result))
        assertEquals(opened, fixture.openedUris.size)
        assertEquals(2, fixture.cursorCloses.get())
        fixture.directory = listOf(fixture.audioRow, sibling)
        assertTrue(loader.validate(request, result))
    }

    @Test fun embeddedConfirmationReliesOnTheVerifiedWholeAudioIdentityAndNeverReparsesTags(): Unit = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val request = fixture.request()
        val loader = fixture.loader()
        val result = loaded(loader.load(request))
        val opened = fixture.openedUris.size
        assertTrue(loader.validate(request, result))
        assertEquals(opened, fixture.openedUris.size)
        fixture.audio = fixture.audio.copy(verifiedContentToken = "different-content-proof")
        assertFalse(loader.validate(request, result))
        assertEquals(opened, fixture.openedUris.size)
    }

    @Test fun retirementDuringTheLastAudioProofCannotReturnAValidConfirmation(): Unit = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val input = fixture.document("[00:01]original")
        val request = fixture.request(LyricsLoadSelection.TextDocument(input), input.documentId)
        val loader = fixture.loader()
        val result = loaded(loader.load(request))
        val before = fixture.validationCalls.get()
        fixture.validationHook = {
            if (fixture.validationCalls.get() == before + 2) fixture.current = false
        }
        assertFalse(loader.validate(request, result))
        assertEquals(before + 2, fixture.validationCalls.get())
        fixture.current = true
        fixture.validationHook = null
        assertTrue(loader.validate(request, result))
    }

    @Test fun ignoredFingerprintCloseCancellationRetainsAdmissionUntilActualCleanup(): Unit = runBlocking {
        withTimeout(10_000L) {
            val fixture = LocalLyricsLoadFixture()
            val bytes = "[00:01]original".toByteArray()
            val input = fixture.document(String(bytes, Charsets.UTF_8))
            val request = fixture.request(LyricsLoadSelection.TextDocument(input), input.documentId)
            val loader = fixture.loader()
            val result = loaded(loader.load(request))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.streams[input.candidate.uri] = {
                object : FilterInputStream(ByteArrayInputStream(bytes)) {
                    override fun close() {
                        entered.countDown()
                        check(release.await(5L, TimeUnit.SECONDS))
                        super.close()
                    }
                }
            }
            val caller = async(Dispatchers.Default) { loader.validate(request, result) }
            try {
                assertTrue(entered.await(5L, TimeUnit.SECONDS))
                caller.cancel()
                assertFalse(caller.isCompleted)
                assertEquals(LyricsLoadIssue.BUSY, failure(loader.load(request)).issue)
                assertFalse(loader.validate(request, result))
                release.countDown()
                caller.join()
                fixture.io.awaitIdle()
                fixture.streams[input.candidate.uri] = { ByteArrayInputStream(bytes) }
                assertTrue(loader.validate(request, result))
            } finally {
                release.countDown()
                caller.cancel()
                caller.join()
            }
        }
    }
}
