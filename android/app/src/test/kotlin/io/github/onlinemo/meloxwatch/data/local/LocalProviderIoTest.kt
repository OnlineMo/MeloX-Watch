package io.github.onlinemo.meloxwatch.data.local

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LocalProviderIoTest {
    @Test fun sequentialCallsObserveAReleasedPhysicalChannel() = runBlocking {
        val io = LocalProviderIo()
        repeat(100) { index -> assertEquals(index, io.read { index }) }
    }

    @Test fun callerCancellationDoesNotFreeAnIgnoringPhysicalWorker() = runBlocking {
        withTimeout(10_000) {
            val io = LocalProviderIo()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val caller = async(Dispatchers.Default) {
                io.read { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "late" }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                caller.cancel()
                caller.join()
                try { io.read { "second" }; throw AssertionError("Expected busy channel") }
                catch (error: SourceReadException) { assertEquals(SourceReadIssue.BUSY, error.issue) }
                release.countDown()
                io.awaitIdle()
                assertEquals("new", io.read { "new" })
            } finally { release.countDown(); caller.cancel() }
        }
    }

    @Test fun nativeAllocationFailureResumesCallerAndReleasesOwnership() = runBlocking {
        val io = LocalProviderIo()
        try { io.read<String> { throw OutOfMemoryError("controlled native allocation") }; throw AssertionError("Expected failure") }
        catch (error: SourceReadException) { assertEquals(SourceReadIssue.METADATA_UNREADABLE, error.issue) }
        assertEquals("available", io.read { "available" })
    }
}
