package io.github.onlinemo.meloxwatch.data.lyrics.load

import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LocalLyricsResolverDispatcherTest {
    @Test fun allDirectoryMatchingChecksRunOnTheWorkDispatcherAcrossMultipleProviderBatches() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val sibling = fixture.sibling("selected", name = "Écho.lrc")
        fixture.directory = listOf(fixture.audioRow.copy(displayName = "Écho.mp3")) +
            List(205) { index -> sibling.copy(objectId = "other-$index", uri = "content://lyrics.fixture/other-$index",
                displayName = "other-$index-${"E\u0301".repeat(100)}.txt") } + sibling
        val callerThread = Thread.currentThread().name
        val callbackThreads = CopyOnWriteArrayList<String>()
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "lyrics-directory-work") }.asCoroutineDispatcher().use { dispatcher ->
            val resolver = LocalLyricsSourceResolver(fixture.access(), LocalLyricsLoadLimits(), dispatcher)
            val result = resolver.resolve(fixture.audio.copy(contentValidation = LocalContentValidation.VERIFIED),
                LyricsSiblingScope("folder", "Écho.mp3")) {
                currentCoroutineContext().ensureActive()
                callbackThreads += Thread.currentThread().name
            }
            assertEquals(LyricsSiblingStatus.MATCHED, result.status)
            assertEquals(sibling, result.candidate)
        }
        assertTrue(callbackThreads.size > fixture.directory.size)
        assertTrue(callbackThreads.all { it == "lyrics-directory-work" && it != callerThread })
        assertTrue(fixture.batchLimits.size >= 3)
        assertTrue(fixture.batchLimits.all { it in 1..100 })
        assertEquals(1, fixture.cursorCloses.get())
    }

    @Test fun cancellationOnTheWorkDispatcherAwaitsTheSharedCursorRetirement() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        fixture.directory = listOf(fixture.audioRow, fixture.sibling("selected"))
        val reachedCandidate = CompletableDeferred<Unit>()
        val remainSuspended = CompletableDeferred<Unit>()
        val checks = AtomicInteger()
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "lyrics-cancel-work") }.asCoroutineDispatcher().use { dispatcher ->
            val resolver = LocalLyricsSourceResolver(fixture.access(), LocalLyricsLoadLimits(), dispatcher)
            val task = async {
                resolver.resolve(fixture.audio.copy(contentValidation = LocalContentValidation.VERIFIED), LyricsSiblingScope("folder", "Track.mp3")) {
                    currentCoroutineContext().ensureActive()
                    if (checks.incrementAndGet() == 3) {
                        reachedCandidate.complete(Unit)
                        remainSuspended.await()
                    }
                }
            }
            reachedCandidate.await()
            task.cancel()
            task.join()
            assertTrue(task.isCancelled)
            assertEquals(1, fixture.cursorOpens.get())
            assertEquals(1, fixture.cursorCloses.get())
            assertTrue(fixture.io.read { true })
        }
    }
}
