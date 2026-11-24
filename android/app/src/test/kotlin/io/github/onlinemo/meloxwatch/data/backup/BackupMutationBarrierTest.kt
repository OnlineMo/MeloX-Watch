package io.github.onlinemo.meloxwatch.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMutationBarrierTest {
    @Test(timeout = 30_000L)
    fun ordinaryWritersStayClosedUntilTheActualStartupInspectionCompletes() = runBlocking {
        val barrier = BackupMutationBarrier()
        var inspected = false
        try { barrier.requireReadyForFileMutation(); throw AssertionError("Fresh storage has not been inspected") }
        catch (failure: BackupException) { assertEquals(BackupIssue.RECOVERY_REQUIRED, failure.issue) }
        barrier.initializeStorage { inspected = true; assertEquals(BackupAccessState.EXCLUSIVE_OPERATION, barrier.state.value) }
        assertTrue(inspected)
        barrier.requireReadyForFileMutation()
        assertEquals(BackupAccessState.READY, barrier.state.value)
    }

    @Test(timeout = 30_000L)
    fun verifiedNativePublicationSurvivesALateObserverFailureAndAppearsOnlyAfterCoherentExit() = runBlocking {
        val barrier = BackupMutationBarrier()
        barrier.initializeStorage {}
        val owner = Any()
        var visible = "before"
        barrier.exclusively {
            assertFalse(barrier.publish(owner, { visible = "committed" }))
            assertFalse(barrier.publish(owner, { visible = "late error" }, confirmed = false))
            assertEquals("before", visible)
        }
        assertEquals("committed", visible)
        assertEquals(BackupAccessState.READY, barrier.state.value)
    }

    @Test(timeout = 30_000L)
    fun incompleteRecoveryRetainsVerifiedValuesAndDoesNotPublishAPartialGeneration() = runBlocking {
        val barrier = BackupMutationBarrier()
        barrier.initializeStorage {}
        val owner = Any()
        var visible = 0
        barrier.exclusively {
            barrier.publish(owner, { visible = 1 })
            barrier.requireRecovery()
        }
        assertEquals(0, visible)
        assertEquals(BackupAccessState.RECOVERY_REQUIRED, barrier.state.value)
        barrier.initializeStorage { barrier.publish(owner, { visible = 2 }) }
        assertEquals(2, visible)
        assertEquals(BackupAccessState.READY, barrier.state.value)
    }

    @Test(timeout = 30_000L)
    fun projectionReaderWaitsOutsideItsDatabaseWorkAndReadsOnlyTheFinalCommittedState() = runBlocking {
        val barrier = BackupMutationBarrier()
        barrier.initializeStorage {}
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var databaseValue = 1
        var reads = 0
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.exclusively { databaseValue = 2; entered.complete(Unit); release.await(); databaseValue = 3 }
        }
        entered.await()
        val reader = async(start = CoroutineStart.UNDISPATCHED) { barrier.readProjection { reads++; databaseValue } }
        assertEquals(0, reads)
        assertFalse(reader.isCompleted)
        release.complete(Unit); writer.join()
        assertEquals(3, reader.await())
        assertEquals(1, reads)
    }
}
