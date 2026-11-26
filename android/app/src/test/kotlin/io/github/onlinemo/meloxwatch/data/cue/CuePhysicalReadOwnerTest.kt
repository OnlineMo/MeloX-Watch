package io.github.onlinemo.meloxwatch.data.cue

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalProviderReadOwner
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class CuePhysicalReadOwnerTest {
    @Test(timeout = 15_000L)
    fun rejectedDispatchKeepsItsCancellationWorkerAndExactSlotUntilActualRetirement() = runBlocking {
        val rejectFirst = AtomicInteger()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (rejectFirst.incrementAndGet() == 1) throw RejectedExecutionException("Controlled rejected read")
                Dispatchers.IO.dispatch(context, block)
            }
        }
        val io = LocalProviderIo(dispatcher)
        val input = ControlledInput()
        val owner = CuePhysicalReadOwner(input, CancellationSignal())
        try {
            assertThrows(RejectedExecutionException::class.java) {
                runBlocking { io.read(physicalOwner = owner) { throw AssertionError("Rejected action must not run") } }
            }
            assertTrue(input.cancelEntered.await(1L, TimeUnit.SECONDS))
            assertTrue(owner.retainedAfterFailure)
            assertBusy(io)
            input.releaseCancel.countDown()
            withTimeout(5000L) { io.awaitIdle() }
            assertFalse(owner.retainedAfterFailure)
            assertEquals(42, io.read { 42 })
            assertEquals(3, rejectFirst.get())
        } finally { input.releaseCancel.countDown() }
    }

    @Test(timeout = 15_000L)
    fun cancellationAfterFinishBeforeExitCannotMakeASuccessfullyRetiredSlotPermanentlyBusy() = runBlocking {
        val io = LocalProviderIo()
        val input = ControlledInput()
        val owner = CuePhysicalReadOwner(input, CancellationSignal())
        val exposed = object : LocalProviderReadOwner {
            override val retainedAfterFailure get() = owner.retainedAfterFailure
            override fun requestCancellation() = owner.requestCancellation()
            override fun operationExited(actualRetired: () -> Unit): Boolean {
                owner.requestCancellation()
                return owner.operationExited(actualRetired)
            }
        }
        try {
            val result = io.read(physicalOwner = exposed) {
                owner.begin(1L)
                owner.input.initialize()
                owner.finish()
                7
            }
            assertEquals(7, result)
            assertTrue(input.cancelEntered.await(1L, TimeUnit.SECONDS))
            assertBusy(io)
            input.releaseCancel.countDown()
            withTimeout(5000L) { io.awaitIdle() }
            assertEquals(9, io.read { 9 })
            assertFalse(owner.retainedAfterFailure)
        } finally { input.releaseCancel.countDown() }
    }

    @Test(timeout = 15_000L)
    fun failedPhysicalCloseKeepsTheOwnerAndOnlyActualSuccessfulCleanupReleasesItsSlot() = runBlocking {
        val io = LocalProviderIo()
        val input = ControlledInput().apply {
            failClose = true;
            releaseCancel.countDown()
        }
        val owner = CuePhysicalReadOwner(input, CancellationSignal())
        try {
            assertEquals(CueIssue.READ_CLOSE_FAILED, assertThrows(CueException::class.java) {
                runBlocking { io.read(physicalOwner = owner) {
                    owner.begin(1L)
                    try { owner.input.initialize() } finally { owner.finish() }
                } }
            }.issue)
            assertTrue(owner.retainedAfterFailure)
            assertBusy(io)
            input.failClose = false
            withContext(Dispatchers.IO) { owner.finish() }
            withTimeout(5000L) { io.awaitIdle() }
            assertFalse(owner.retainedAfterFailure)
            assertEquals(11, io.read { 11 })
        } finally {
            input.failClose = false;
            input.releaseCancel.countDown()
        }
    }

    @Test(timeout = 15_000L)
    fun anOriginalCancellationFailureSurvivesSuccessfulPhysicalCloseAndDoesNotPoisonTheRetiredSlot() = runBlocking {
        val io = LocalProviderIo()
        val input = ControlledInput().apply { releaseCancel.countDown() }
        val original = IOException("Controlled original cancellation failure")
        input.cancelFailure = original
        val owner = CuePhysicalReadOwner(input, CancellationSignal())
        owner.requestCancellation()
        assertTrue(input.cancelEntered.await(1L, TimeUnit.SECONDS))
        val failure = assertThrows(CueException::class.java) {
            runBlocking { io.read(physicalOwner = owner) {
                owner.begin(1L)
                try { owner.input.initialize() } finally { owner.finish() }
            } }
        }
        assertEquals(CueIssue.READ_CLOSE_FAILED, failure.issue)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original })
        withTimeout(5000L) { io.awaitIdle() }
        assertFalse(owner.retainedAfterFailure)
        assertEquals(13, io.read { 13 })
    }

    private fun assertBusy(io: LocalProviderIo) {
        assertEquals(SourceReadIssue.BUSY, assertThrows(SourceReadException::class.java) {
            runBlocking { io.read { throw AssertionError("A retained owner must block the replacement") } }
        }.issue)
    }
    private class ControlledInput : CueOwnedInput {
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        @Volatile var failClose = false
        @Volatile var cancelFailure: IOException? = null
        override val length = 44L
        override fun initialize() = Unit
        override fun seek(position: Long) = Unit
        override fun read(buffer: ByteArray, offset: Int, count: Int) = -1
        override fun cancel() {
            cancelEntered.countDown()
            check(releaseCancel.await(10L, TimeUnit.SECONDS))
            cancelFailure?.let { throw it }
        }
        override fun close() { if (failClose) throw IOException("Controlled exact-owner close failure") }
    }
}
