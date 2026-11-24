package io.github.onlinemo.meloxwatch.data.online

import io.github.onlinemo.meloxwatch.data.online.cache.OnlineCatalogRowEntity
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.playback.OnlineQueueAction
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class OnlineQueueDirectoryTest {
    @Test fun anUnclaimedTicketClosesItsLeaseAndCannotBeClaimedLater() {
        val directory = OnlineQueueDirectory()
        var released = 0
        val ticket = directory.register(lease { released++ }, UUID.randomUUID().toString(), 0L,
            OnlineQueueAction.REPLACE_COMPLETE_LIST, null, StreamingQuality.STANDARD)
        ticket.close()
        ticket.close()
        assertEquals(1, released)
        assertEquals(OnlineCatalogIssue.STALE_VIEW, assertThrows(OnlineCatalogException::class.java) { directory.claim(ticket.command) {} }.issue)
    }

    @Test fun aClaimedCancellationKeepsTheSlotUntilTheServiceCleanupActuallyClosesIt() {
        val directory = OnlineQueueDirectory()
        val owner = Job()
        var released = 0
        val ticket = directory.register(lease { released++ }, UUID.randomUUID().toString(), 0L,
            OnlineQueueAction.REPLACE_COMPLETE_LIST, null, StreamingQuality.STANDARD)
        val claimed = directory.claim(ticket.command) { owner.cancel() }
        assertTrue(directory.cancel(ticket.command.operationId))
        assertTrue(owner.isCancelled)
        ticket.close()
        assertEquals(0, released)
        val replacement = lease { released++ }
        try {
            assertEquals(OnlineCatalogIssue.BUSY, assertThrows(OnlineCatalogException::class.java) {
                directory.register(replacement, UUID.randomUUID().toString(), 0L, OnlineQueueAction.REPLACE_COMPLETE_LIST, null, StreamingQuality.STANDARD)
            }.issue)
        } finally { replacement.close() }
        claimed.close()
        claimed.close()
        assertEquals(2, released)
        val next = directory.register(lease {}, UUID.randomUUID().toString(), 0L, OnlineQueueAction.REPLACE_COMPLETE_LIST, null, StreamingQuality.STANDARD)
        next.close()
    }

    @Test fun aModifiedParcelCannotClaimARegisteredProducer() {
        val directory = OnlineQueueDirectory()
        val ticket = directory.register(lease {}, UUID.randomUUID().toString(), 0L,
            OnlineQueueAction.REPLACE_COMPLETE_LIST, null, StreamingQuality.STANDARD)
        try {
            assertEquals(OnlineCatalogIssue.STALE_VIEW, assertThrows(OnlineCatalogException::class.java) {
                directory.claim(ticket.command.copy(quality = StreamingQuality.HIGH)) {}
            }.issue)
            directory.claim(ticket.command) {}.close()
        } finally { ticket.close() }
    }

    @Test fun aChangedAccountCannotClaimTheOldTicket() {
        val current = AtomicBoolean(true)
        val directory = OnlineQueueDirectory()
        val ticket = directory.register(lease(current::get) {}, UUID.randomUUID().toString(), 0L,
            OnlineQueueAction.REPLACE_COMPLETE_LIST, null, StreamingQuality.STANDARD)
        try {
            current.set(false)
            assertEquals(OnlineCatalogIssue.LOGIN_REQUIRED, assertThrows(OnlineCatalogException::class.java) { directory.claim(ticket.command) {} }.issue)
        } finally { ticket.close() }
    }

    @Test fun explicitCancellationPreventsAnyFurtherQueuePageRead() = runBlocking {
        val source = lease {}
        try {
            source.cancel()
            var cancelled = false
            try { source.read(0L, 1) } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
        } finally { source.close() }
    }

    @Test fun anUnknownCancellationCannotAffectTheRegisteredTicket() {
        val directory = OnlineQueueDirectory()
        val ticket = directory.register(lease {}, UUID.randomUUID().toString(), 0L,
            OnlineQueueAction.REPLACE_COMPLETE_LIST, null, StreamingQuality.STANDARD)
        try {
            assertFalse(directory.cancel(UUID.randomUUID().toString()))
            directory.claim(ticket.command) {}.close()
        } finally { ticket.close() }
    }

    private fun lease(current: () -> Boolean = { true }, release: () -> Unit): OnlineCatalogQueueLease {
        val view = FrozenQueueView("fixture-view:" + UUID.randomUUID(), 1L, 1L)
        return OnlineCatalogQueueLease(view, "101", current, { first, limit ->
            if (first == 0L && limit > 0) listOf(OnlineCatalogRowEntity(view.id, 0L, "TRACK", "201")) else emptyList()
        }, release)
    }
}
