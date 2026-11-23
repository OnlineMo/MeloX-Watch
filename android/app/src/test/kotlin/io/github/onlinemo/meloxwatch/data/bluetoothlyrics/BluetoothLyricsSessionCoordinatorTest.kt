package io.github.onlinemo.meloxwatch.data.bluetoothlyrics

import java.security.SecureRandom
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlled transport timing never replaces real Android sockets or two-APK acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothLyricsSessionCoordinatorTest {
    @Test fun aHiddenScreenCannotCreateAListenerOrReplayStartIntent(): Unit = runTest {
        var creations = 0
        val session = BluetoothLyricsSessionCoordinator(BluetoothLyricsRole.PHONE, backgroundScope,
            { true }, { creations++; FakeTransport() }, { testScheduler.currentTime })
        assertFalse(session.listen())
        assertEquals(0, creations)
        session.uiVisible(true)
        assertTrue(session.listen())
        assertFalse(session.listen())
        assertEquals(1, creations)
        session.stop()
        runCurrent()
    }

    @Test fun bothLocalApprovalAndPeerConfirmationPrecedeReceivingContent(): Unit = runTest {
        val transport = FakeTransport(holdPeerConfirmation = true)
        val session = BluetoothLyricsSessionCoordinator(BluetoothLyricsRole.PHONE, backgroundScope,
            { true }, { transport }, { testScheduler.currentTime })
        session.uiVisible(true)
        assertTrue(session.listen())
        runCurrent()
        assertEquals(BluetoothLyricsPhase.COMPARING, session.state.value.phase)
        assertNull(session.payload.value)
        assertEquals(0, transport.reads)
        val comparison = checkNotNull(session.state.value.comparison)
        assertTrue(session.approve(comparison))
        runCurrent()
        assertEquals(0, transport.reads)
        assertNull(session.payload.value)
        transport.peerConfirmation.complete(Unit)
        runCurrent()
        assertEquals(BluetoothLyricsPhase.CONNECTED, session.state.value.phase)
        assertEquals(1, transport.reads)
        session.stop()
        runCurrent()
    }

    @Test fun hidingAnUnconfirmedScreenClosesItsTicketWithoutAutomaticReconnection(): Unit = runTest {
        val transport = FakeTransport()
        var creations = 0
        val session = BluetoothLyricsSessionCoordinator(BluetoothLyricsRole.PHONE, backgroundScope,
            { true }, { creations++; transport }, { testScheduler.currentTime })
        session.uiVisible(true)
        session.listen()
        runCurrent()
        val comparison = checkNotNull(session.state.value.comparison)
        session.uiVisible(false)
        assertFalse(session.approve(comparison))
        runCurrent()
        assertEquals(BluetoothLyricsPhase.OFF, session.state.value.phase)
        assertNull(session.payload.value)
        assertNull(session.state.value.comparison)
        session.uiVisible(true)
        runCurrent()
        assertEquals(1, creations)
        assertEquals(BluetoothLyricsPhase.OFF, session.state.value.phase)
    }

    @Test fun nativeDisconnectDuringComparisonDoesNotWaitForTheTwoMinuteDecision(): Unit = runTest {
        val transport = FakeTransport()
        val session = BluetoothLyricsSessionCoordinator(BluetoothLyricsRole.PHONE, backgroundScope,
            { true }, { transport }, { testScheduler.currentTime })
        session.uiVisible(true)
        session.listen()
        runCurrent()
        transport.close(BluetoothLyricsIssue.CONNECTION_LOST)
        runCurrent()
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(BluetoothLyricsPhase.FAILED, session.state.value.phase)
        assertEquals(BluetoothLyricsIssue.CONNECTION_LOST, session.state.value.issue)
        assertNull(session.state.value.comparison)
        assertNull(session.payload.value)
    }

    @Test fun authenticatedReceptionCanContinueWithItsDisplayHiddenUntilExplicitStop(): Unit = runTest {
        val transport = FakeTransport()
        val session = BluetoothLyricsSessionCoordinator(BluetoothLyricsRole.PHONE, backgroundScope,
            { true }, { transport }, { testScheduler.currentTime })
        session.uiVisible(true)
        session.listen()
        runCurrent()
        session.approve(checkNotNull(session.state.value.comparison))
        runCurrent()
        session.uiVisible(false)
        val current = fixturePayload(1L, "First line")
        transport.messages.send(BluetoothLyricsMessage.Snapshot(current))
        runCurrent()
        assertSame(current, session.payload.value)
        assertEquals(BluetoothLyricsPhase.CONNECTED, session.state.value.phase)
        session.stop()
        assertNull(session.payload.value)
        runCurrent()
        assertEquals(BluetoothLyricsPhase.OFF, session.state.value.phase)
    }

    @Test fun reconnectUsesANewTicketAndCannotRetainThePriorPayload(): Unit = runTest {
        val transports = ArrayDeque(listOf(FakeTransport(), FakeTransport()))
        val first = transports.first()
        val session = BluetoothLyricsSessionCoordinator(BluetoothLyricsRole.PHONE, backgroundScope,
            { true }, { transports.removeFirst() }, { testScheduler.currentTime })
        session.uiVisible(true)
        session.listen()
        runCurrent()
        val oldTicket = checkNotNull(session.state.value.comparison)
        session.approve(oldTicket)
        runCurrent()
        first.messages.send(BluetoothLyricsMessage.Snapshot(fixturePayload(3L, "Old fixture line")))
        runCurrent()
        assertTrue(session.payload.value != null)
        first.close(BluetoothLyricsIssue.CONNECTION_LOST)
        runCurrent()
        assertNull(session.payload.value)
        assertTrue(session.listen())
        runCurrent()
        assertFalse(session.approve(oldTicket))
        assertNull(session.payload.value)
        assertEquals(BluetoothLyricsPhase.COMPARING, session.state.value.phase)
        session.stop()
        runCurrent()
        transports.forEach { it.close() }
    }

    @Test fun failedNativeRetirementBlocksReplacementAndKeepsTheOriginalOutcome(): Unit = runTest {
        val transport = FakeTransport(failRetirement = true)
        var creations = 0
        val session = BluetoothLyricsSessionCoordinator(BluetoothLyricsRole.PHONE, backgroundScope,
            { true }, { creations++; transport }, { testScheduler.currentTime })
        session.uiVisible(true)
        session.listen()
        runCurrent()
        session.stop()
        runCurrent()
        assertTrue(session.state.value.retirementRequired)
        assertFalse(session.listen())
        assertEquals(1, creations)
        transport.failRetirement = false
        session.retryRetirement()
        assertEquals(1, transport.retirementRetries)
        assertEquals(BluetoothLyricsPhase.OFF, session.state.value.phase)
        var originalStillFailed = false
        try { transport.awaitClosed() }
        catch (error: BluetoothLyricsException) {
            assertEquals(BluetoothLyricsIssue.RETIREMENT_REQUIRED, error.issue)
            originalStillFailed = true
        }
        assertTrue(originalStillFailed)
    }

    @Test fun unconfirmedSessionExpiresAndCannotReplayALateApproval(): Unit = runTest {
        val transport = FakeTransport()
        val session = BluetoothLyricsSessionCoordinator(BluetoothLyricsRole.PHONE, backgroundScope,
            { true }, { transport }, { testScheduler.currentTime })
        session.uiVisible(true)
        session.listen()
        runCurrent()
        val comparison = checkNotNull(session.state.value.comparison)
        advanceTimeBy(BluetoothLyricsLimits.COMPARE_MS)
        runCurrent()
        assertEquals(BluetoothLyricsPhase.FAILED, session.state.value.phase)
        assertEquals(BluetoothLyricsIssue.EXPIRED, session.state.value.issue)
        assertFalse(session.approve(comparison))
        assertNull(session.state.value.comparison)
        assertNull(session.payload.value)
    }

    @Test fun newerSnapshotsReplaceTheSingleReceivedBody(): Unit = runTest {
        val transport = FakeTransport()
        val session = BluetoothLyricsSessionCoordinator(BluetoothLyricsRole.PHONE, backgroundScope,
            { true }, { transport }, { testScheduler.currentTime })
        session.uiVisible(true)
        session.listen()
        runCurrent()
        session.approve(checkNotNull(session.state.value.comparison))
        runCurrent()
        val old = fixturePayload(1L, "First line")
        val current = fixturePayload(2L, "Second line")
        transport.messages.send(BluetoothLyricsMessage.Snapshot(old))
        runCurrent()
        transport.messages.send(BluetoothLyricsMessage.Snapshot(current))
        runCurrent()
        assertSame(current, session.payload.value)
        session.stop()
        runCurrent()
    }
}

private class FakeTransport(holdPeerConfirmation: Boolean = false, var failRetirement: Boolean = false) : BluetoothLyricsTransport {
    override var physicallyRetired = false
    override var closed = false
    override var authenticated = false
    override val comparison = BluetoothLyricsComparison(String.format(Locale.ROOT, "%08d",
        SecureRandom().nextInt(100_000_000)).toCharArray())
    override val peerLabel = "Fixture peer"
    override var closureIssue: BluetoothLyricsIssue? = null
    private val closing = CompletableDeferred<BluetoothLyricsIssue>()
    private val originalRetirement = CompletableDeferred<Unit>()
    val peerConfirmation = CompletableDeferred<Unit>().apply { if (!holdPeerConfirmation) complete(Unit) }
    val messages = Channel<BluetoothLyricsMessage>(1)
    var reads = 0
    var retirementRetries = 0
    override suspend fun openWatch(target: BluetoothLyricsTarget) = Unit
    override suspend fun acceptPhone() = Unit
    override suspend fun confirm(remainingMs: Long) {
        peerConfirmation.await()
        authenticated = true
        comparison.close()
    }
    override suspend fun write(value: BluetoothLyricsPayload) = Unit
    override suspend fun heartbeat() = Unit
    override suspend fun read(): BluetoothLyricsMessage { reads++; return messages.receive() }
    override suspend fun awaitClosing(): BluetoothLyricsIssue = closing.await()
    override fun close(issue: BluetoothLyricsIssue): Deferred<Unit> {
        if (!closed) {
            closed = true
            authenticated = false
            closureIssue = issue
            comparison.close()
            closing.complete(issue)
            if (failRetirement) originalRetirement.completeExceptionally(
                BluetoothLyricsException(BluetoothLyricsIssue.RETIREMENT_REQUIRED))
            else { physicallyRetired = true; originalRetirement.complete(Unit) }
        }
        return originalRetirement
    }
    override suspend fun awaitClosed(waitMs: Long) { originalRetirement.await() }
    override suspend fun retryRetirement(waitMs: Long) {
        retirementRetries++
        if (failRetirement) throw BluetoothLyricsException(BluetoothLyricsIssue.RETIREMENT_REQUIRED)
        physicallyRetired = true
    }
    override fun toString(): String = "<controlled-bluetooth-transport>"
}

private fun fixturePayload(epoch: Long, line: String) = BluetoothLyricsPayload(epoch, 1000L, 1000L,
    BluetoothLyricsPlayback.PLAYING, "Fixture title", "", line, "", isPlaying = true)
