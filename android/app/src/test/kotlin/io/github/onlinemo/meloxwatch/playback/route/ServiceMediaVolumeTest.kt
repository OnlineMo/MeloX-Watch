package io.github.onlinemo.meloxwatch.playback.route

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceMediaVolumeTest {
    @Test
    fun absoluteSetAcknowledgesOnlyFreshPlatformReadback() {
        val fixture = Fixture()
        val request = fixture.request(6)
        val result = fixture.service.setAbsolute(request)
        assertTrue(result.accepted)
        assertTrue(result.changed)
        assertTrue(result.writeAttempted)
        assertEquals(listOf(6), fixture.port.writes)
        assertEquals(6, result.snapshot?.values?.current)
        assertEquals(request.expected.route, result.snapshot?.route)
    }

    @Test
    fun changedOwnerRejectsEvenWhenBothSystemVolumeIndicesMatch() {
        val fixture = Fixture()
        val request = fixture.request(6)
        fixture.route = knownRoute()
        val result = fixture.service.setAbsolute(request)
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
        assertFalse(result.writeAttempted)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun routeChangeDuringTheFinalPlatformReadRejectsBeforeWriting() {
        val fixture = Fixture()
        val request = fixture.request(6)
        fixture.port.onRead = { fixture.route = knownRoute() }
        val result = fixture.service.setAbsolute(request)
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
        assertFalse(requireNotNull(result.snapshot).route.known)
        assertFalse(result.writeAttempted)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun lossOfActiveRouteRejectsAnAbsoluteDraftButKeepsTheRealValue() {
        val fixture = Fixture()
        val request = fixture.request(6)
        fixture.route = PlaybackRouteSessionState()
        val result = fixture.service.setAbsolute(request)
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
        assertEquals(4, result.snapshot?.values?.current)
        assertFalse(requireNotNull(result.snapshot).absoluteAdjustable)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun changedCurrentLimitsMuteOrFixedRemoteConstraintsRejectTheDraft() {
        val mutations: List<Pair<ServiceMediaVolumeIssue, (MusicVolumeValues) -> MusicVolumeValues>> = listOf(
            ServiceMediaVolumeIssue.OUTPUT_CHANGED to { it.copy(current = 3) },
            ServiceMediaVolumeIssue.OUTPUT_CHANGED to { it.copy(minimum = 1) },
            ServiceMediaVolumeIssue.OUTPUT_CHANGED to { it.copy(maximum = 12) },
            ServiceMediaVolumeIssue.OUTPUT_CHANGED to { it.copy(muted = true) },
            ServiceMediaVolumeIssue.FIXED to { it.copy(fixed = true) },
            ServiceMediaVolumeIssue.REMOTE_OUTPUT to { it.copy(remote = true) },
        )
        for ((issue, mutate) in mutations) {
            val fixture = Fixture()
            val request = fixture.request(6)
            fixture.port.values = mutate(fixture.port.values)
            val result = fixture.service.setAbsolute(request)
            assertEquals(issue, result.issue)
            assertFalse(result.writeAttempted)
            assertTrue(fixture.port.writes.isEmpty())
        }
    }

    @Test
    fun unreadableVolumeOrRouteFailsWithoutAnAttemptedWrite() {
        val fixture = Fixture()
        val request = fixture.request(6)
        fixture.port.readFailure = true
        assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.service.setAbsolute(request).issue)
        fixture.port.readFailure = false
        fixture.routeFailure = true
        assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.service.setAbsolute(request).issue)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun platformSafeLimitRejectionPublishesTheActualIndexWithoutSuccess() {
        val fixture = Fixture()
        val request = fixture.request(9)
        fixture.port.ignoreWrite = true
        val result = fixture.service.setAbsolute(request)
        assertFalse(result.accepted)
        assertFalse(result.changed)
        assertTrue(result.writeAttempted)
        assertEquals(ServiceMediaVolumeIssue.SET_REJECTED, result.issue)
        assertEquals(4, result.snapshot?.values?.current)
        assertEquals(listOf(9), fixture.port.writes)
    }

    @Test
    fun setterThrowAfterApplyingStillReportsAnAttemptAndRejectsAck() {
        val fixture = Fixture()
        val request = fixture.request(6)
        fixture.port.afterWrite = { throw SecurityException("late platform rejection") }
        val result = fixture.service.setAbsolute(request)
        assertFalse(result.accepted)
        assertTrue(result.writeAttempted)
        assertEquals(6, result.snapshot?.values?.current)
        assertEquals(ServiceMediaVolumeIssue.SET_REJECTED, result.issue)
        assertEquals(listOf(6), fixture.port.writes)
    }

    @Test
    fun routeChangeInsideSetterRejectsAckAndDoesNotPretendZeroWrites() {
        val fixture = Fixture()
        val request = fixture.request(6)
        fixture.port.afterWrite = { fixture.route = knownRoute() }
        val result = fixture.service.setAbsolute(request)
        assertFalse(result.accepted)
        assertFalse(result.changed)
        assertTrue(result.writeAttempted)
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
        assertEquals(listOf(6), fixture.port.writes)
        assertEquals(6, result.snapshot?.values?.current)
        assertEquals(fixture.route, result.snapshot?.route)
    }

    @Test
    fun postWriteConstraintChangeRejectsAckWithFreshReadback() {
        val fixture = Fixture()
        val request = fixture.request(6)
        fixture.port.afterWrite = { fixture.port.values = fixture.port.values.copy(maximum = 8) }
        val result = fixture.service.setAbsolute(request)
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
        assertTrue(result.writeAttempted)
        assertFalse(result.accepted)
        assertEquals(8, result.snapshot?.values?.maximum)
    }

    @Test
    fun unreadablePostWriteReadbackRetainsTheAttemptWithoutAcknowledgement() {
        val fixture = Fixture()
        val request = fixture.request(6)
        fixture.port.afterWrite = { fixture.port.readFailure = true }
        val result = fixture.service.setAbsolute(request)
        assertNull(result.snapshot)
        assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, result.issue)
        assertTrue(result.writeAttempted)
        assertFalse(result.accepted)
    }

    @Test
    fun pausedRelativeStepUsesTheFreshIndexAndNoHeldAbsoluteTarget() {
        val fixture = Fixture()
        val oldRead = requireNotNull(fixture.service.read().snapshot)
        fixture.route = PlaybackRouteSessionState()
        fixture.port.values = fixture.port.values.copy(current = 2)
        val result = fixture.service.adjust(1)
        assertEquals(4, oldRead.values.current)
        assertEquals(listOf(3), fixture.port.writes)
        assertEquals(3, result.snapshot?.values?.current)
        assertFalse(requireNotNull(result.snapshot).route.known)
        assertTrue(result.accepted)
    }

    @Test
    fun fixedRemoteAndZeroRangeKeepReadbackButRejectRelativeWrites() {
        val blocked = listOf(
            MusicVolumeValues(4, 0, 10, true, false, false) to ServiceMediaVolumeIssue.FIXED,
            MusicVolumeValues(4, 0, 10, false, false, true) to ServiceMediaVolumeIssue.REMOTE_OUTPUT,
            MusicVolumeValues(0, 0, 0, false, false, false) to ServiceMediaVolumeIssue.FIXED,
        )
        for ((values, issue) in blocked) {
            val fixture = Fixture()
            fixture.port.values = values
            assertEquals(issue, fixture.service.read().issue)
            assertEquals(issue, fixture.service.adjust(1).issue)
            assertEquals(values, fixture.service.read().snapshot?.values)
            assertTrue(fixture.port.writes.isEmpty())
        }
    }

    @Test
    fun relativeBoundaryDoesNotWriteOrClaimAChange() {
        for ((index, delta) in listOf(0 to -1, 10 to 1)) {
            val fixture = Fixture()
            fixture.port.values = fixture.port.values.copy(current = index)
            val result = fixture.service.adjust(delta)
            assertEquals(ServiceMediaVolumeIssue.AT_LIMIT, result.issue)
            assertFalse(result.accepted)
            assertFalse(result.writeAttempted)
            assertTrue(fixture.port.writes.isEmpty())
        }
    }

    @Test
    fun relativeArithmeticDoesNotOverflowAtMaximumIntegerIndex() {
        val fixture = Fixture()
        fixture.port.values = fixture.port.values.copy(current = Int.MAX_VALUE - 1, maximum = Int.MAX_VALUE)
        assertTrue(fixture.service.adjust(1).accepted)
        assertEquals(listOf(Int.MAX_VALUE), fixture.port.writes)
        assertEquals(ServiceMediaVolumeIssue.AT_LIMIT, fixture.service.adjust(1).issue)
    }

    @Test
    fun mutedZeroIsRealEvenWhenTheUnmutedMinimumIsPositive() {
        val fixture = Fixture()
        fixture.route = PlaybackRouteSessionState()
        fixture.port.values = fixture.port.values.copy(current = 0, minimum = 1, muted = true)
        assertEquals(0, fixture.service.read().snapshot?.values?.current)
        assertEquals(ServiceMediaVolumeIssue.AT_LIMIT, fixture.service.adjust(-1).issue)
        assertTrue(fixture.port.writes.isEmpty())
        val result = fixture.service.adjust(1)
        assertTrue(result.accepted)
        assertEquals(1, result.snapshot?.values?.current)
        assertEquals(false, result.snapshot?.values?.muted)
    }

    @Test
    fun anUnchangedAbsoluteValueDoesNotCallTheSetterOrTriggerSuccessFeedback() {
        val fixture = Fixture()
        val result = fixture.service.setAbsolute(fixture.request(4))
        assertTrue(result.accepted)
        assertFalse(result.changed)
        assertFalse(result.writeAttempted)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun invalidRelativeStepsAreRejectedBeforeAnyPlatformRead() {
        val fixture = Fixture()
        for (delta in listOf(Int.MIN_VALUE, -2, 0, 2, Int.MAX_VALUE)) {
            rejects { fixture.service.adjust(delta) }
        }
        assertEquals(0, fixture.port.reads)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun unknownRouteCannotBeEncodedAsAnAbsoluteRequest() {
        val snapshot = MediaVolumeSnapshot(MusicVolumeValues(4, 0, 10, false, false, false), PlaybackRouteSessionState())
        rejects { MediaVolumeSetRequest(snapshot, 6) }
        rejects { MediaVolumeSetRequest(snapshot.copy(route = knownRoute()), 11) }
        rejects { MusicVolumeValues(0, 1, 10, false, false, false) }
    }

    private class Fixture {
        val port = FakeMusicVolume()
        var route = knownRoute()
        var routeFailure = false
        val service = ServiceMediaVolume(port) {
            if (routeFailure) throw SecurityException("backend route unavailable")
            route
        }

        fun request(target: Int): MediaVolumeSetRequest = MediaVolumeSetRequest(requireNotNull(service.read().snapshot), target)
    }

    private class FakeMusicVolume : MusicVolumePort {
        var values = MusicVolumeValues(4, 0, 10, false, false, false)
        val writes = mutableListOf<Int>()
        var reads = 0
        var readFailure = false
        var ignoreWrite = false
        var onRead: (() -> Unit)? = null
        var afterWrite: (() -> Unit)? = null

        override fun read(): MusicVolumeValues {
            reads++
            onRead?.also { onRead = null }?.invoke()
            if (readFailure) throw SecurityException("media volume unavailable")
            return values
        }

        override fun set(index: Int) {
            writes.add(index)
            if (!ignoreWrite) values = values.copy(current = index, muted = false)
            afterWrite?.invoke()
        }
    }

    companion object {
        private fun knownRoute() = PlaybackRouteSessionState(UUID.randomUUID().toString(), true)

        private fun rejects(action: () -> Unit) {
            try { action() }
            catch (_: IllegalArgumentException) { return }
            throw AssertionError("Invalid media volume input was accepted")
        }
    }
}
