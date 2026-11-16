package io.github.onlinemo.meloxwatch.playback.sleep

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStore
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreCodec
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreIssue
import io.github.onlinemo.meloxwatch.data.sleep.createSleepTimerDataStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServiceSleepTimerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private var storageOrdinal = 0

    @Test(timeout = 30000L) fun commandsCannotRunBeforeInitializationOrAfterDetach() = runBlocking {
        withStorage {
            val runtime = runtime()
            assertFalse(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            assertFalse(runtime.timer.cancel(null))
            assertFalse(runtime.timer.retryPersistence(null))
            assertFalse(runtime.timer.beforePlay())
            assertTrue(runtime.timer.beforeNaturalAdvance(TRACK))
            assertNull(runtime.scheduler.deadline)
            runtime.timer.initialize()
            assertEquals(1, runtime.pauses)
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            runtime.awaitWrites()
            val accepted = runtime.timer.state.value.timer
            runtime.timer.detach()
            assertFalse(runtime.timer.arm(2, SleepTimerEndMode.PAUSE_IMMEDIATELY, runtime.timer.generationId))
            assertFalse(runtime.timer.cancel(runtime.timer.generationId))
            assertFalse(runtime.timer.retryPersistence(runtime.timer.generationId))
            assertEquals(accepted, runtime.timer.state.value.timer)
            assertTrue(runtime.scheduler.closed)
            assertNull(runtime.scheduler.deadline)
            assertNull(runtime.scheduler.summary)
        }
    }

    @Test(timeout = 30000L) fun staleArmCancelAndRetryCannotMutateAReplacementGeneration() = runBlocking {
        withStorage {
            val runtime = runtime()
            runtime.timer.initialize()
            assertFalse(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, GENERATION))
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            runtime.awaitWrites()
            val first = requireNotNull(runtime.timer.generationId)
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.AFTER_CURRENT_TRACK, first))
            runtime.awaitWrites()
            val replacement = runtime.timer.state.value
            val next = requireNotNull(runtime.timer.generationId)
            assertNotEquals(first, next)
            val acceptedBytes = file.readBytes()
            assertFalse(runtime.timer.arm(3, SleepTimerEndMode.PAUSE_IMMEDIATELY, first))
            assertFalse(runtime.timer.cancel(first))
            assertFalse(runtime.timer.retryPersistence(first))
            assertFalse(runtime.timer.cancel(null))
            assertEquals(replacement, runtime.timer.state.value)
            assertArrayEquals(acceptedBytes, file.readBytes())
            assertTrue(runtime.timer.cancel(next))
            runtime.awaitWrites()
            assertEquals(SleepTimerPersistence.SAVED, runtime.timer.state.value.persistence)
            assertEquals(next, runtime.timer.state.value.timer.lastStop?.token?.generation)
            assertFalse(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            assertEquals(runtime.timer.state.value.timer, stored().state)
        }
    }

    @Test(timeout = 30000L) fun aLateSavedReceiptCannotReactivateTheTimerCancelledDuringItsWrite() = runBlocking {
        val gate = ReceiptGate()
        withStorage(gate = gate) {
            val runtime = runtime()
            runtime.timer.initialize()
            runtime.timer.setSummaryEnabled(true)
            assertTrue(runtime.timer.arm(2, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            val armed = runtime.timer.state.value.timer
            val active = requireNotNull(armed.active)
            val oldDeadline = requireNotNull(runtime.scheduler.deadline)
            val oldSummary = requireNotNull(runtime.scheduler.summary)
            assertEquals(armed, SleepTimerStoreCodec.decode(gate.awaitCommit()).state)
            assertTrue(runtime.timer.cancel(active.token.generation))
            val cancelled = runtime.timer.state.value.timer
            assertEquals(SleepTimerStopReason.USER_CANCELLED, cancelled.lastStop?.reason)
            assertNull(runtime.scheduler.deadline)
            assertNull(runtime.scheduler.summary)
            runtime.clock = clock(active.token.deadlineElapsedMs)
            val notifications = runtime.notification.attempts.size
            oldDeadline.task()
            oldSummary.task()
            assertEquals(1, runtime.pauses)
            assertEquals(notifications, runtime.notification.attempts.size)
            assertEquals(cancelled, runtime.timer.state.value.timer)
            gate.release()
            runtime.awaitWrites()
            assertEquals(cancelled, runtime.timer.state.value.timer)
            assertEquals(cancelled, stored().state)
            assertEquals(2L, stored().revision)
            assertEquals(SleepTimerPersistence.SAVED, runtime.timer.state.value.persistence)
        }
    }

    @Test(timeout = 30000L) fun aLateReceiptCoalescesReplacementsAndOldTasksCannotUseAnIdenticalDeadline() = runBlocking {
        val gate = ReceiptGate()
        withStorage(gate = gate) {
            val runtime = runtime()
            runtime.timer.initialize()
            runtime.timer.setSummaryEnabled(true)
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            val first = requireNotNull(runtime.timer.state.value.timer.active)
            val oldDeadline = requireNotNull(runtime.scheduler.deadline)
            val oldSummary = requireNotNull(runtime.scheduler.summary)
            gate.awaitCommit()
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.AFTER_CURRENT_TRACK, first.token.generation))
            val middle = requireNotNull(runtime.timer.state.value.timer.active)
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, middle.token.generation))
            val latest = requireNotNull(runtime.timer.state.value.timer.active)
            val latestDeadline = requireNotNull(runtime.scheduler.deadline)
            assertNotEquals(first.token.generation, latest.token.generation)
            assertNotEquals(middle.token.generation, latest.token.generation)
            assertEquals(first.token.deadlineElapsedMs, latest.token.deadlineElapsedMs)
            gate.release()
            runtime.awaitWrites()
            assertEquals(latest, runtime.timer.state.value.timer.active)
            assertEquals(runtime.timer.state.value.timer, stored().state)
            assertEquals(2L, stored().revision)
            runtime.clock = clock(latest.token.deadlineElapsedMs)
            val notifications = runtime.notification.attempts.size
            oldDeadline.task()
            oldSummary.task()
            assertEquals(latest, runtime.timer.state.value.timer.active)
            assertEquals(1, runtime.pauses)
            assertEquals(notifications, runtime.notification.attempts.size)
            latestDeadline.task()
            assertEquals(2, runtime.pauses)
            assertEquals(latest.token, runtime.timer.state.value.timer.lastStop?.token)
            assertEquals(SleepTimerStopReason.DEADLINE_REACHED, runtime.timer.state.value.timer.lastStop?.reason)
        }
    }

    @Test(timeout = 30000L) fun aCommittedWriteWithoutAReceiptNeedsReopenAndNeverRestoresItsOldActiveState() = runBlocking {
        val gate = ReceiptGate(throwAfterCommit = true)
        withStorage(gate = gate) {
            val runtime = runtime()
            runtime.timer.initialize()
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            val generation = requireNotNull(runtime.timer.generationId)
            val committed = SleepTimerStoreCodec.decode(gate.awaitCommit()).state
            assertTrue(runtime.timer.cancel(generation))
            val cancelled = runtime.timer.state.value.timer
            gate.release()
            runtime.awaitWrites()
            assertEquals(SleepTimerPersistence.FAILED, runtime.timer.state.value.persistence)
            assertEquals(SleepTimerStoreIssue.IO, runtime.timer.state.value.issue)
            assertEquals(committed, stored().state)
            assertNotNull(stored().state.active)
            assertEquals(cancelled, runtime.timer.state.value.timer)
            assertFalse(runtime.timer.arm(2, SleepTimerEndMode.PAUSE_IMMEDIATELY, generation))
            assertFalse(runtime.timer.retryPersistence(null))
            assertTrue(runtime.timer.retryPersistence(generation))
            runtime.awaitWrites()
            assertEquals(cancelled, runtime.timer.state.value.timer)
            assertEquals(cancelled, stored().state)
            assertEquals(2L, stored().revision)
            assertEquals(SleepTimerPersistence.SAVED, runtime.timer.state.value.persistence)
            assertNull(runtime.timer.state.value.issue)
            assertEquals(1, runtime.pauses)
        }
    }

    @Test(timeout = 30000L) fun cancellingAWriterAfterTheRealCommitDoesNotClaimSavedOrRollback() = runBlocking {
        val gate = ReceiptGate()
        withStorage(gate = gate) {
            val runtime = runtime()
            runtime.timer.initialize()
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            val committed = SleepTimerStoreCodec.decode(gate.awaitCommit()).state
            val writer = runtime.ownerJob.children.single()
            writer.cancel()
            withTimeout(10_000L) { writer.join() }
            assertTrue(gate.exited.isCompleted)
            assertEquals(SleepTimerPersistence.FAILED, runtime.timer.state.value.persistence)
            assertEquals(SleepTimerStoreIssue.IO, runtime.timer.state.value.issue)
            assertEquals(committed, stored().state)
            val generation = requireNotNull(runtime.timer.generationId)
            assertTrue(runtime.timer.cancel(generation))
            val cancelled = runtime.timer.state.value.timer
            assertTrue(runtime.timer.retryPersistence(generation))
            runtime.awaitWrites()
            assertEquals(cancelled, stored().state)
            assertNull(runtime.timer.state.value.timer.active)
            assertEquals(SleepTimerPersistence.SAVED, runtime.timer.state.value.persistence)
        }
    }

    @Test(timeout = 30000L) fun nonCooperativeCancelledIoKeepsTheWriterSlotUntilItActuallyReturns() = runBlocking {
        val gate = ReceiptGate(nonCooperative = true)
        withStorage(gate = gate) {
            val runtime = runtime()
            runtime.timer.initialize()
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            val committed = SleepTimerStoreCodec.decode(gate.awaitCommit()).state
            val generation = requireNotNull(runtime.timer.generationId)
            val writer = runtime.ownerJob.children.single()
            writer.cancel()
            assertFalse(writer.isCompleted)
            assertFalse(gate.exited.isCompleted)
            assertTrue(runtime.timer.cancel(generation))
            val cancelled = runtime.timer.state.value.timer
            assertEquals(1, runtime.ownerJob.children.count())
            assertFalse(runtime.timer.retryPersistence(generation))
            assertEquals(committed, stored().state)
            assertEquals(cancelled, runtime.timer.state.value.timer)
            gate.release()
            runtime.awaitWrites()
            assertEquals(SleepTimerPersistence.FAILED, runtime.timer.state.value.persistence)
            assertEquals(SleepTimerStoreIssue.IO, runtime.timer.state.value.issue)
            assertTrue(runtime.timer.retryPersistence(generation))
            runtime.awaitWrites()
            assertEquals(cancelled, stored().state)
            assertEquals(SleepTimerPersistence.SAVED, runtime.timer.state.value.persistence)
            assertEquals(1, runtime.pauses)
        }
    }

    @Test(timeout = 30000L) fun detachFencesCallbacksAndLateTeardownCannotRetireTheNewServiceLease() = runBlocking {
        val gate = ReceiptGate()
        withStorage(gate = gate) {
            val previous = runtime()
            previous.timer.initialize()
            previous.timer.setSummaryEnabled(true)
            assertTrue(previous.timer.arm(2, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            val saved = SleepTimerStoreCodec.decode(gate.awaitCommit()).state
            val deadline = requireNotNull(previous.scheduler.deadline)
            val summary = requireNotNull(previous.scheduler.summary)
            previous.timer.detach()
            val notifications = previous.notification.attempts.size
            previous.clock = clock(requireNotNull(saved.active).token.deadlineElapsedMs)
            deadline.task()
            summary.task()
            assertEquals(1, previous.pauses)
            assertEquals(notifications, previous.notification.attempts.size)
            assertEquals(saved, previous.timer.state.value.timer)
            val next = runtime()
            val opening = async(start = CoroutineStart.UNDISPATCHED) { next.timer.initialize() }
            assertFalse(opening.isCompleted)
            gate.release()
            withTimeout(10_000L) { opening.await() }
            assertEquals(saved, next.timer.state.value.timer)
            assertEquals(1, next.pauses)
            previous.timer.close()
            assertTrue(next.timer.cancel(next.timer.generationId))
            next.awaitWrites()
            assertEquals(SleepTimerPersistence.SAVED, next.timer.state.value.persistence)
            assertNull(next.timer.state.value.issue)
            assertEquals(next.timer.state.value.timer, stored().state)
        }
    }

    @Test(timeout = 30000L) fun aNewOwnerRejectsOldRuntimeWritesAndOldClosePreservesItsSaveAuthority() = runBlocking {
        withStorage {
            val previous = runtime()
            previous.timer.initialize()
            assertTrue(previous.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            previous.awaitWrites()
            val accepted = stored().state
            val next = runtime()
            next.timer.initialize()
            assertTrue(previous.timer.cancel(previous.timer.generationId))
            previous.awaitWrites()
            assertEquals(SleepTimerPersistence.FAILED, previous.timer.state.value.persistence)
            assertEquals(SleepTimerStoreIssue.RETIRED_OWNER, previous.timer.state.value.issue)
            assertEquals(accepted, stored().state)
            assertFalse(previous.timer.arm(2, SleepTimerEndMode.PAUSE_IMMEDIATELY, previous.timer.generationId))
            assertFalse(previous.timer.retryPersistence(previous.timer.generationId))
            previous.timer.close()
            assertTrue(next.timer.cancel(next.timer.generationId))
            next.awaitWrites()
            assertEquals(SleepTimerPersistence.SAVED, next.timer.state.value.persistence)
            assertEquals(next.timer.state.value.timer, stored().state)
        }
    }

    @Test(timeout = 30000L) fun aRevisionConflictKeepsCurrentRuntimeStateUntilAnExplicitReopenRetry() = runBlocking {
        withStorage {
            val runtime = runtime()
            runtime.timer.initialize()
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            runtime.awaitWrites()
            val generation = requireNotNull(runtime.timer.generationId)
            val external = durableTimer(generation = NEXT_GENERATION)
            val committed = raw.updateData { current ->
                SleepTimerStoreCodec.encode(SleepTimerStoreCodec.decode(current).revision + 1L, external)
            }
            val externalRevision = SleepTimerStoreCodec.decode(committed).revision
            assertTrue(runtime.timer.cancel(generation))
            val cancelled = runtime.timer.state.value.timer
            runtime.awaitWrites()
            assertTrue(runtime.timer.state.value.conflict)
            assertEquals(SleepTimerPersistence.FAILED, runtime.timer.state.value.persistence)
            assertEquals(cancelled, runtime.timer.state.value.timer)
            assertEquals(external, stored().state)
            assertFalse(runtime.timer.retryPersistence(NEXT_GENERATION))
            assertTrue(runtime.timer.retryPersistence(generation))
            runtime.awaitWrites()
            assertFalse(runtime.timer.state.value.conflict)
            assertEquals(SleepTimerPersistence.SAVED, runtime.timer.state.value.persistence)
            assertEquals(cancelled, runtime.timer.state.value.timer)
            assertEquals(cancelled, stored().state)
            assertEquals(externalRevision + 1L, stored().revision)
        }
    }

    @Test(timeout = 30000L) fun verifiedFutureRecoveryRetainsTheDeadlineButAlwaysPausesPlayback() = runBlocking {
        val saved = durableTimer()
        withStorage(seed = saved) {
            val original = file.readBytes()
            val runtime = runtime(clock(2_000L))
            runtime.timer.initialize()
            runtime.timer.initialize()
            runtime.awaitWrites()
            assertEquals(1, runtime.pauses)
            assertEquals(0, runtime.resumeCancellations)
            assertEquals(saved, runtime.timer.state.value.timer)
            assertEquals(SleepTimerPersistence.SAVED, runtime.timer.state.value.persistence)
            assertEquals(requireNotNull(saved.active).token, runtime.scheduler.deadline?.token)
            assertNull(runtime.scheduler.summary)
            assertArrayEquals(original, file.readBytes())
            assertEquals(7L, stored().revision)
        }
    }

    @Test(timeout = 30000L) fun expiredInterruptedAndUnverifiedRecoveriesSettleDurablyWithoutStartingATimer() = runBlocking {
        val cases = listOf(
            RestoreCase("expired", durableTimer(), clock(61_000L), SleepTimerStopReason.RECOVERED_EXPIRED),
            RestoreCase("reboot", durableTimer(), clock(2_000L, "next-boot"), SleepTimerStopReason.DEVICE_RESTARTED),
            RestoreCase("missing current boot", durableTimer(), clock(2_000L, null), SleepTimerStopReason.BOOT_UNVERIFIED),
            RestoreCase("missing saved boot", durableTimer(boot = null), clock(2_000L), SleepTimerStopReason.BOOT_UNVERIFIED),
            RestoreCase("rewound elapsed", durableTimer(), clock(999L), SleepTimerStopReason.INVALID_MONOTONIC_CLOCK),
            RestoreCase("waiting traversal", durableTimer(mode = SleepTimerEndMode.AFTER_CURRENT_TRACK, waiting = TRACK),
                clock(61_000L), SleepTimerStopReason.RECOVERED_EXPIRED),
        )
        cases.forEach { case ->
            withStorage(seed = case.saved) {
                val runtime = runtime(case.clock)
                runtime.timer.initialize()
                runtime.awaitWrites()
                assertEquals(case.name, 1, runtime.pauses)
                assertNull(case.name, runtime.timer.state.value.timer.active)
                assertEquals(case.name, case.reason, runtime.timer.state.value.timer.lastStop?.reason)
                assertEquals(case.name, case.saved.active?.token, runtime.timer.state.value.timer.lastStop?.token)
                assertNull(case.name, runtime.scheduler.deadline)
                assertNull(case.name, runtime.scheduler.summary)
                assertEquals(case.name, runtime.timer.state.value.timer, stored().state)
                assertEquals(case.name, 8L, stored().revision)
                assertEquals(case.name, SleepTimerPersistence.SAVED, runtime.timer.state.value.persistence)
            }
        }
    }

    @Test(timeout = 30000L) fun beforePlayChecksTheFreshDeadlineAndTheLaterNaturalBoundaryStillBlocks() = runBlocking {
        withStorage {
            val runtime = runtime()
            runtime.timer.initialize()
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            val deadline = requireNotNull(runtime.scheduler.deadline)
            runtime.clock = clock(deadline.atElapsedMs)
            assertFalse(runtime.timer.beforePlay())
            assertEquals(1, runtime.resumeCancellations)
            assertEquals(1, runtime.pauses)
            assertEquals(SleepTimerStopReason.DEADLINE_REACHED, runtime.timer.state.value.timer.lastStop?.reason)
            assertTrue(runtime.timer.beforeNaturalAdvance(TRACK))
            assertEquals(2, runtime.resumeCancellations)
            deadline.task()
            assertEquals(1, runtime.pauses)
        }
    }

    @Test(timeout = 30000L) fun naturalEndAtTheExactDeadlineBlocksRepeatBeforeALateDeadlineTaskInBothModes() = runBlocking {
        SleepTimerEndMode.entries.forEach { mode ->
            withStorage {
                val runtime = runtime()
                runtime.timer.initialize()
                assertTrue(runtime.timer.arm(1, mode, null))
                val deadline = requireNotNull(runtime.scheduler.deadline)
                runtime.clock = clock(deadline.atElapsedMs)
                assertTrue(mode.name, runtime.timer.beforeNaturalAdvance(TRACK))
                val expected = if (mode == SleepTimerEndMode.AFTER_CURRENT_TRACK)
                    SleepTimerStopReason.CURRENT_TRACK_ENDED else SleepTimerStopReason.DEADLINE_REACHED
                assertEquals(mode.name, expected, runtime.timer.state.value.timer.lastStop?.reason)
                assertEquals(mode.name, 1, runtime.resumeCancellations)
                assertEquals(mode.name, 1, runtime.pauses)
                deadline.task()
                assertEquals(mode.name, 1, runtime.pauses)
                assertTrue(mode.name, runtime.timer.beforeNaturalAdvance(TRACK))
            }
        }
    }

    @Test(timeout = 30000L) fun aSettledDeadlineRetainsItsBlockAcrossQueueEditsButNotANewPlaybackCycle() = runBlocking {
        withStorage {
            val runtime = runtime()
            runtime.timer.initialize()
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            val deadline = requireNotNull(runtime.scheduler.deadline)
            runtime.clock = clock(deadline.atElapsedMs)
            deadline.task()
            assertEquals(2, runtime.pauses)
            assertNull(runtime.timer.state.value.timer.active)
            assertTrue(runtime.timer.beforeNaturalAdvance(TRACK))
            assertEquals(2, runtime.pauses)
            assertEquals(1, runtime.resumeCancellations)
            val retained = TRACK.copy(queueGenerationId = "edited-queue-generation")
            runtime.timer.onRetainedTrackBinding(TRACK, retained)
            runtime.track = retained
            assertFalse(runtime.timer.beforeNaturalAdvance(TRACK))
            assertTrue(runtime.timer.beforeNaturalAdvance(retained))
            assertEquals(2, runtime.resumeCancellations)
            val nextCycle = retained.copy(playbackCycle = TRACK.playbackCycle + 1L)
            runtime.timer.onRetainedTrackBinding(retained, nextCycle)
            runtime.track = nextCycle
            assertFalse(runtime.timer.beforeNaturalAdvance(nextCycle))
        }
    }

    @Test(timeout = 30000L) fun anEarlyOrStaleNaturalBoundaryCannotStopTheCurrentTraversal() = runBlocking {
        withStorage {
            val runtime = runtime()
            runtime.timer.initialize()
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.AFTER_CURRENT_TRACK, null))
            val active = runtime.timer.state.value.timer.active
            runtime.clock = clock(60_999L)
            assertFalse(runtime.timer.beforeNaturalAdvance(TRACK))
            assertEquals(active, runtime.timer.state.value.timer.active)
            val current = TRACK.copy(playbackCycle = TRACK.playbackCycle + 1L)
            runtime.track = current
            runtime.clock = clock(61_000L)
            assertFalse(runtime.timer.beforeNaturalAdvance(TRACK))
            assertEquals(active, runtime.timer.state.value.timer.active)
            assertTrue(runtime.timer.beforeNaturalAdvance(current))
            assertEquals(SleepTimerStopReason.CURRENT_TRACK_ENDED, runtime.timer.state.value.timer.lastStop?.reason)
            assertEquals(1, runtime.resumeCancellations)
            assertEquals(1, runtime.pauses)
        }
    }

    @Test(timeout = 30000L) fun waitingTransfersAcrossQueueGenerationsOnlyForTheRetainedTraversal() = runBlocking {
        withStorage {
            val runtime = runtime()
            runtime.timer.initialize()
            runtime.timer.setSummaryEnabled(true)
            assertTrue(runtime.timer.arm(1, SleepTimerEndMode.AFTER_CURRENT_TRACK, null))
            val deadline = requireNotNull(runtime.scheduler.deadline)
            runtime.clock = clock(deadline.atElapsedMs)
            deadline.task()
            assertEquals(TRACK, runtime.timer.state.value.timer.active?.waitingFor)
            assertEquals(1, runtime.pauses)
            assertNull(runtime.scheduler.deadline)
            assertNull(runtime.scheduler.summary)
            val retained = TRACK.copy(queueGenerationId = "edited-queue-generation")
            runtime.timer.onRetainedTrackBinding(TRACK, retained)
            runtime.track = retained
            assertEquals(retained, runtime.timer.state.value.timer.active?.waitingFor)
            assertFalse(runtime.timer.beforeNaturalAdvance(TRACK))
            assertEquals(retained, runtime.timer.state.value.timer.active?.waitingFor)
            assertTrue(runtime.timer.beforeNaturalAdvance(retained))
            assertEquals(SleepTimerStopReason.CURRENT_TRACK_ENDED, runtime.timer.state.value.timer.lastStop?.reason)
            assertEquals(1, runtime.resumeCancellations)
            assertEquals(1, runtime.pauses)
            runtime.awaitWrites()
            assertEquals(runtime.timer.state.value.timer, stored().state)
        }
    }

    @Test(timeout = 30000L) fun waitingCannotTransferToAnotherSessionEntryOrPlaybackCycle() = runBlocking {
        val replacements = listOf(TRACK.copy(sessionId = "next-session"),
            TRACK.copy(queueEntryId = "next-entry"), TRACK.copy(playbackCycle = TRACK.playbackCycle + 1L))
        replacements.forEach { replacement ->
            withStorage {
                val runtime = runtime()
                runtime.timer.initialize()
                assertTrue(runtime.timer.arm(1, SleepTimerEndMode.AFTER_CURRENT_TRACK, null))
                val deadline = requireNotNull(runtime.scheduler.deadline)
                runtime.clock = clock(deadline.atElapsedMs)
                deadline.task()
                runtime.timer.onRetainedTrackBinding(TRACK, replacement)
                assertEquals(TRACK, runtime.timer.state.value.timer.active?.waitingFor)
                runtime.track = replacement
                assertFalse(runtime.timer.beforeNaturalAdvance(replacement))
                assertEquals(SleepTimerStopReason.WAITING_TRACK_LOST, runtime.timer.state.value.timer.lastStop?.reason)
                assertEquals(1, runtime.pauses)
                assertEquals(0, runtime.resumeCancellations)
            }
        }
    }

    @Test(timeout = 30000L) fun manualPauseAndTrackChangeCancelWaitingModeBeforeAndAfterTheDeadline() = runBlocking {
        val actions: List<Pair<SleepTimerStopReason, (ServiceSleepTimer) -> Unit>> = listOf(
            SleepTimerStopReason.USER_PAUSED to { it.onUserPause() },
            SleepTimerStopReason.USER_CHANGED_TRACK to { it.onManualTrackChange() },
        )
        actions.forEach { (reason, action) ->
            listOf(false, true).forEach { waiting ->
                withStorage {
                    val runtime = runtime()
                    runtime.timer.initialize()
                    assertTrue(runtime.timer.arm(1, SleepTimerEndMode.AFTER_CURRENT_TRACK, null))
                    val first = requireNotNull(runtime.timer.generationId)
                    val deadline = requireNotNull(runtime.scheduler.deadline)
                    if (waiting) {
                        runtime.clock = clock(deadline.atElapsedMs)
                        deadline.task()
                        assertEquals(TRACK, runtime.timer.state.value.timer.active?.waitingFor)
                    }
                    action(runtime.timer)
                    assertEquals(reason, runtime.timer.state.value.timer.lastStop?.reason)
                    runtime.clock = clock(deadline.atElapsedMs)
                    deadline.task()
                    assertEquals(1, runtime.pauses)
                    assertTrue(runtime.timer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, first))
                    val active = runtime.timer.state.value.timer.active
                    action(runtime.timer)
                    assertEquals(active, runtime.timer.state.value.timer.active)
                }
            }
        }
    }

    @Test(timeout = 30000L) fun notificationPermissionAndPostRefusalStopOnlySummaryTasksUntilExplicitRefresh() = runBlocking {
        withStorage {
            val runtime = runtime()
            runtime.timer.initialize()
            assertTrue(runtime.timer.arm(2, SleepTimerEndMode.PAUSE_IMMEDIATELY, null))
            runtime.awaitWrites()
            val active = runtime.timer.state.value.timer.active
            runtime.notification.allowed = false
            runtime.timer.setSummaryEnabled(true)
            assertFalse(runtime.timer.state.value.notificationAllowed)
            assertNull(runtime.scheduler.summary)
            assertNotNull(runtime.scheduler.deadline)
            assertEquals(active, runtime.timer.state.value.timer.active)
            runtime.notification.allowed = true
            runtime.timer.refresh()
            assertTrue(runtime.timer.state.value.notificationAllowed)
            val summary = requireNotNull(runtime.scheduler.summary)
            assertEquals(61_000L, summary.atElapsedMs)
            assertEquals(2, runtime.notification.shown?.remainingMinutes)
            assertEquals(active?.token, runtime.notification.shown?.token)
            assertEquals(active?.endMode, runtime.notification.shown?.endMode)
            runtime.notification.postsAccepted = false
            runtime.clock = clock(summary.atElapsedMs)
            summary.task()
            assertFalse(runtime.timer.state.value.notificationAllowed)
            assertNull(runtime.scheduler.summary)
            assertNotNull(runtime.scheduler.deadline)
            assertEquals(active, runtime.timer.state.value.timer.active)
            assertEquals(1, runtime.pauses)
            runtime.notification.postsAccepted = true
            runtime.timer.refresh()
            assertTrue(runtime.timer.state.value.notificationAllowed)
            assertEquals(1, runtime.notification.shown?.remainingMinutes)
            assertEquals(121_000L, runtime.scheduler.summary?.atElapsedMs)
            assertEquals(1L, stored().revision)
        }
    }

    @Test(timeout = 30000L) fun disablingSummaryPreservesTheTimerFileAndCannotRescheduleItsOldMinuteTask() = runBlocking {
        withStorage {
            val runtime = runtime()
            runtime.timer.initialize()
            assertTrue(runtime.timer.arm(2, SleepTimerEndMode.AFTER_CURRENT_TRACK, null))
            runtime.awaitWrites()
            val accepted = runtime.timer.state.value.timer
            val bytes = file.readBytes()
            runtime.timer.setSummaryEnabled(true)
            val summary = requireNotNull(runtime.scheduler.summary)
            assertEquals(61_000L, summary.atElapsedMs)
            runtime.timer.setSummaryEnabled(false)
            assertNull(runtime.notification.shown)
            assertNull(runtime.scheduler.summary)
            assertEquals(accepted.active?.token, runtime.scheduler.deadline?.token)
            assertEquals(accepted, runtime.timer.state.value.timer)
            val notifications = runtime.notification.attempts.size
            runtime.clock = clock(summary.atElapsedMs)
            summary.task()
            assertNull(runtime.scheduler.summary)
            assertEquals(notifications, runtime.notification.attempts.size)
            assertEquals(accepted, runtime.timer.state.value.timer)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(1L, stored().revision)
        }
    }

    private suspend fun withStorage(
        gate: ReceiptGate? = null,
        seed: SleepTimerState? = null,
        block: suspend StorageFixture.() -> Unit,
    ) {
        val storageJob = SupervisorJob()
        val file = File(temporaryFolder.root, "timer-${storageOrdinal++}.preferences_pb")
        val raw = createSleepTimerDataStore(CoroutineScope(storageJob + Dispatchers.IO)) { file }
        val fixture = StorageFixture(currentCoroutineContext(), file, raw, SleepTimerStore(gate?.wrap(raw) ?: raw))
        try {
            if (seed != null) raw.updateData { SleepTimerStoreCodec.encode(7L, seed) }
            fixture.block()
        } finally {
            gate?.release()
            withContext(NonCancellable) {
                try {
                    withTimeout(10_000L) {
                        fixture.runtimes.asReversed().forEach { runtime ->
                            try { runtime.timer.close() } finally { runtime.ownerJob.cancelAndJoin() }
                        }
                    }
                } finally {
                    storageJob.cancelAndJoin()
                }
            }
        }
    }

    private class StorageFixture(
        private val context: CoroutineContext,
        val file: File,
        val raw: DataStore<Preferences>,
        private val store: SleepTimerStore,
    ) {
        val runtimes = mutableListOf<Runtime>()
        fun runtime(sample: SleepTimerClockSample = clock(1_000L)): Runtime =
            Runtime(context, store, sample).also { runtimes.add(it) }

        suspend fun stored() = SleepTimerStoreCodec.decode(raw.data.first())
    }

    private class Runtime(context: CoroutineContext, store: SleepTimerStore, sample: SleepTimerClockSample) {
        val ownerJob: Job = SupervisorJob()
        var clock = sample
        var track: SleepTimerTrackBinding? = TRACK
        val scheduler = ControlledScheduler()
        val notification = ControlledNotification()
        var pauses = 0
            private set
        var resumeCancellations = 0
            private set
        val timer = ServiceSleepTimer(store, CoroutineScope(context + ownerJob), { clock }, { track },
            scheduler, notification, { pauses++ }, { resumeCancellations++ })

        suspend fun awaitWrites() = withTimeout(10_000L) {
            while (true) {
                val pending = ownerJob.children.toList()
                if (pending.isEmpty()) break
                pending.joinAll()
            }
        }
    }

    private data class ScheduledTask(val token: SleepTimerToken, val atElapsedMs: Long, val task: () -> Unit)

    private class ControlledScheduler : SleepTimerScheduler {
        var deadline: ScheduledTask? = null
            private set
        var summary: ScheduledTask? = null
            private set
        var closed = false
            private set

        override fun deadline(token: SleepTimerToken, atElapsedMs: Long, task: () -> Unit) {
            check(!closed)
            deadline = ScheduledTask(token, atElapsedMs, task)
        }
        override fun summary(token: SleepTimerToken, atElapsedMs: Long, task: () -> Unit) {
            check(!closed)
            summary = ScheduledTask(token, atElapsedMs, task)
        }
        override fun clearDeadline() { deadline = null }
        override fun clearSummary() { summary = null }
        override fun close() {
            closed = true
            deadline = null
            summary = null
        }
    }

    private class ControlledNotification : SleepTimerNotification {
        var allowed = true
        var postsAccepted = true
        var shown: SleepTimerSummary? = null
            private set
        val attempts = mutableListOf<SleepTimerSummary?>()
        override fun isAllowed() = allowed
        override fun show(summary: SleepTimerSummary?): Boolean {
            attempts.add(summary)
            if (summary != null && !postsAccepted) return false
            shown = summary
            return true
        }
    }

    /** The real atomic DataStore commit precedes the gate; the fixture never supplies a fake receipt. */
    private class ReceiptGate(private val nonCooperative: Boolean = false, private val throwAfterCommit: Boolean = false) {
        private val firstWrite = AtomicBoolean(true)
        private val committed = CompletableDeferred<Preferences>()
        private val released = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()

        fun wrap(raw: DataStore<Preferences>): DataStore<Preferences> = object : DataStore<Preferences> by raw {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val accepted = raw.updateData(transform)
                if (!firstWrite.compareAndSet(true, false)) return accepted
                committed.complete(accepted)
                try {
                    if (nonCooperative) withContext(NonCancellable) { released.await() } else released.await()
                    if (throwAfterCommit) throw IllegalStateException("Committed timer receipt unavailable")
                    return accepted
                } finally {
                    exited.complete(Unit)
                }
            }
        }

        suspend fun awaitCommit(): Preferences = withTimeout(10_000L) { committed.await() }
        fun release() { released.complete(Unit) }
    }

    private data class RestoreCase(val name: String, val saved: SleepTimerState,
        val clock: SleepTimerClockSample, val reason: SleepTimerStopReason)

    private companion object {
        const val GENERATION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val NEXT_GENERATION = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val TRACK = SleepTimerTrackBinding("session", "queue-generation", "queue-entry", 3L)
        fun clock(elapsedMs: Long, boot: String? = "fixture-boot") = SleepTimerClockSample(boot, elapsedMs)
        fun durableTimer(generation: String = GENERATION, boot: String? = "fixture-boot",
            mode: SleepTimerEndMode = SleepTimerEndMode.PAUSE_IMMEDIATELY,
            waiting: SleepTimerTrackBinding? = null) = SleepTimerState(active = ActiveSleepTimer(
            SleepTimerToken(generation, boot, 61_000L), 1_000L, 1, mode, waiting,
        ))
    }
}
