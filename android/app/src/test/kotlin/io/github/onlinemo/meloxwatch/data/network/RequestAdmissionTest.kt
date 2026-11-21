package io.github.onlinemo.meloxwatch.data.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RequestAdmissionTest {
    @Test fun admitsTwoActiveAndFourWaitingAndRejectsTheSeventh() = runBlocking {
        val admission = RequestAdmission()
        val active = List(2) { admission.enter() }
        val waiting = List(4) { async(start = CoroutineStart.UNDISPATCHED) { admission.enter() } }
        try {
            assertFalse(waiting.any { it.isCompleted })
            assertEquals(NetworkIssue.BUSY, assertThrows(NetworkException::class.java) { runBlocking { admission.enter() } }.issue)
        } finally {
            waiting.forEach { it.cancelAndJoin() }
            active.forEach { it.close() }
        }
        admission.enter().close()
    }

    @Test fun cancellingAQueuedCallerReleasesItsAdmissionWithoutConsumingAnActiveLease() = runBlocking {
        val admission = RequestAdmission()
        val active = List(2) { admission.enter() }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { admission.enter() }
        cancelled.cancelAndJoin()
        val waiting = List(4) { async(start = CoroutineStart.UNDISPATCHED) { admission.enter() } }
        try {
            assertFalse(waiting.any { it.isCompleted })
            active.first().close()
            val acquired = waiting.first().await()
            waiting.drop(1).forEach { it.cancelAndJoin() }
            acquired.close()
        } finally {
            waiting.forEach { it.cancelAndJoin() }
            active.forEach { it.close() }
        }
    }

    @Test fun terminalCallbackCanCloseItsLeaseTwiceWithoutCreatingAnotherActivePermit() = runBlocking {
        val admission = RequestAdmission()
        val first = admission.enter()
        first.close()
        first.close()
        val two = List(2) { admission.enter() }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { admission.enter() }
        try { assertFalse(queued.isCompleted) }
        finally {
            queued.cancelAndJoin()
            two.forEach { it.close() }
        }
    }
}
