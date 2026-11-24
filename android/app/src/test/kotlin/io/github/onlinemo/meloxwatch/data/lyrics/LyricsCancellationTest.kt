package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCancellationTest {
    @Test fun cancellationAfterRealParserWorkDiscardsThePrivatePartialDocumentAndPropagates() {
        runBlocking {
            val dispatcher = LyricsQueuedDispatcher()
            val parser = LocalLyricsParser(LyricsLimits(), dispatcher)
            var published: LyricsDocument? = null
            val task = async(start = CoroutineStart.UNDISPATCHED) {
                parser.parse(numberedLyrics(400, "original").toByteArray(Charsets.UTF_8)).also { published = it }
            }
            assertTrue(dispatcher.runNext())
            assertTrue(dispatcher.pendingTaskCount > 0)
            assertFalse(task.isCompleted)
            task.cancel(CancellationException("fixture cancellation"))
            dispatcher.drain()
            expectLyricsCancellation { task.await() }
            assertTrue(task.isCancelled)
            assertNull(published)
        }
    }

    @Test fun cancellationAfterRealMatchingWorkCannotReturnAPartiallyFilledTranslationMap() {
        runBlocking {
            val primary = parseLyrics(numberedLyrics(400, "original"))
            val translated = parseLyrics(numberedLyrics(400, "translated"))
            val dispatcher = LyricsQueuedDispatcher()
            val matcher = LyricsTranslationMatcher(LyricsLimits(), dispatcher)
            var published: LyricsTranslation? = null
            val task = async(start = CoroutineStart.UNDISPATCHED) {
                matcher.match(primary, translated).also { published = it }
            }
            assertTrue(dispatcher.runNext())
            assertTrue(dispatcher.pendingTaskCount > 0)
            assertFalse(task.isCompleted)
            task.cancel(CancellationException("fixture cancellation"))
            dispatcher.drain()
            expectLyricsCancellation { task.await() }
            assertTrue(task.isCancelled)
            assertNull(published)
        }
    }
}
