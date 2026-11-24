package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.playback.PlaybackEngineReleaseGuard
import io.github.onlinemo.meloxwatch.playback.PlaybackReleaseUnconfirmedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 只验证 lease/关闭围栏状态合同；从不全局重置 process quarantine 或构造 native 释放证据。 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationPlaybackFenceTest {
    @Before fun installMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun restoreMain() { Dispatchers.resetMain() }

    @Test fun confirmedClosureOwnsUnusedReservationUntilAcceptedWorkReturns() = runBlocking {
        val host = FileOperationPlaybackHost()
        val guard = PlaybackEngineReleaseGuard()
        host.updateRecoveryBlock(false)
        var closed = false
        val owner = Any()
        assertTrue(host.attach(owner) { closed = true
            host.detach(owner) })
        ServiceClosingFileOperationGate(host, guard).withClosedResources { reservation ->
            assertTrue(closed)
            reservation.requireClosedResources()
            assertFalse(host.mayAllocate())
            assertTrue(guard.blocksCreation())
        }
        assertTrue(host.mayAllocate())
        assertFalse(guard.blocksCreation())
    }

    @Test fun failedServiceClosureCannotBeClearedByJournalReadyRefresh() = runBlocking {
        val host = FileOperationPlaybackHost()
        val guard = PlaybackEngineReleaseGuard()
        host.updateRecoveryBlock(false)
        val owner = Any()
        assertTrue(host.attach(owner) { throw FileOperationException(FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED) })
        try {
            ServiceClosingFileOperationGate(host, guard).withClosedResources { throw AssertionError("Unsafe physical entry") }
            throw AssertionError("Expected unconfirmed closure")
        } catch (failure: FileOperationException) {
            assertEquals(FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED, failure.issue)
        }
        host.updateRecoveryBlock(false)
        assertFalse(host.mayAllocate())
        assertTrue(host.isReadBlocked())
    }

    @Test fun unconfirmedNativeReleaseStaysQuarantinedAcrossNewFileGates() = runBlocking {
        val guard = PlaybackEngineReleaseGuard()
        val nativeLease = guard.acquire()
        guard.markReleaseStarted(nativeLease)
        guard.quarantine(nativeLease)
        repeat(2) {
            val host = FileOperationPlaybackHost()
            host.updateRecoveryBlock(false)
            try {
                ServiceClosingFileOperationGate(host, guard).withClosedResources { throw AssertionError("Quarantined physical entry") }
                throw AssertionError("Expected permanent native quarantine")
            } catch (failure: FileOperationException) {
                assertEquals(FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED, failure.issue)
            }
        }
        try {
            guard.confirmReleased(nativeLease)
            throw AssertionError("A second facade confirmation is not native proof")
        } catch (_: PlaybackReleaseUnconfirmedException) {
            assertTrue(guard.hasUnconfirmedRelease())
            assertTrue(guard.isRetained(nativeLease))
        }
    }
}
