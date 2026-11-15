package io.github.onlinemo.meloxwatch.data.artwork

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArtworkRequestGateTest {
    @Test fun currentAndVisibleWorkPrecedeRefreshWithFifoWithinEachPriority() = runBlocking {
        withTimeout(5_000) {
            val gate = ArtworkRequestGate()
            val active = gate.enter(ArtworkPriority.REFRESH)
            val background = async(start = CoroutineStart.UNDISPATCHED) { gate.enter(ArtworkPriority.REFRESH) }
            val visible = async(start = CoroutineStart.UNDISPATCHED) { gate.enter(ArtworkPriority.VISIBLE) }
            val current1 = async(start = CoroutineStart.UNDISPATCHED) { gate.enter(ArtworkPriority.CURRENT) }
            val current2 = async(start = CoroutineStart.UNDISPATCHED) { gate.enter(ArtworkPriority.CURRENT) }
            gate.exit(active)
            val first = current1.await()
            assertFalse(current2.isCompleted)
            assertFalse(visible.isCompleted)
            gate.exit(first)
            val second = current2.await()
            assertFalse(visible.isCompleted)
            gate.exit(second)
            val third = visible.await()
            assertFalse(background.isCompleted)
            gate.exit(third)
            gate.exit(background.await())
        }
    }

    @Test fun waitListBoundAndCancellationBothReleaseTheirExactOwnership() = runBlocking {
        withTimeout(5_000) {
            val gate = ArtworkRequestGate()
            val active = gate.enter(ArtworkPriority.CURRENT)
            val waiting = List(ArtworkLimits.WAITING_REQUESTS) {
                async(start = CoroutineStart.UNDISPATCHED) { gate.enter(ArtworkPriority.VISIBLE) }
            }
            try { gate.enter(ArtworkPriority.REFRESH); throw AssertionError("Expected bounded wait list") }
            catch (error: ArtworkException) { assertEquals(ArtworkIssue.BUSY, error.issue) }
            waiting.first().cancelAndJoin()
            val extra = async(start = CoroutineStart.UNDISPATCHED) { gate.enter(ArtworkPriority.REFRESH) }
            gate.exit(active)
            for (request in waiting.drop(1)) gate.exit(request.await())
            gate.exit(extra.await())
            val final = gate.enter(ArtworkPriority.CURRENT)
            gate.exit(final)
            gate.exit(final)
        }
    }

    @Test fun cancellationBetweenPromotionAndDeliveryDoesNotLeakTheActiveTicket() = runBlocking {
        withTimeout(5_000) {
            val gate = ArtworkRequestGate()
            val active = gate.enter(ArtworkPriority.CURRENT)
            val first = async(start = CoroutineStart.UNDISPATCHED) { gate.enter(ArtworkPriority.VISIBLE) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { gate.enter(ArtworkPriority.VISIBLE) }
            gate.exit(active)
            first.cancelAndJoin()
            gate.exit(second.await())
        }
    }
}
