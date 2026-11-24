package io.github.onlinemo.meloxwatch.data.online

import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.playback.OnlineQueueAction
import io.github.onlinemo.meloxwatch.playback.OnlineQueueCommand
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackCoordinator
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackSourceIntent
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A single ticket keeps its frozen view until the real service operation and cleanup have completed. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class OnlineQueueDirectory {
    private class Slot(val command: OnlineQueueCommand, val lease: OnlineCatalogQueueLease) {
        var claimed = false
        var cancelOwner: (() -> Unit)? = null
    }
    private val memory = Any()
    private var slot: Slot? = null
    private var acceptedCommand: OnlineQueueCommand? = null

    /** A repeated accepted parcel acknowledges the durable operation without rereading its released lease. */
    fun wasAccepted(command: OnlineQueueCommand): Boolean = synchronized(memory) {
        val previous = acceptedCommand ?: return false
        if (previous.operationId != command.operationId) return false
        if (previous != command) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
        true
    }

    fun register(lease: OnlineCatalogQueueLease, operationId: String, selectedOrder: Long,
        action: OnlineQueueAction, expected: QueueSnapshot?, quality: StreamingQuality): OnlineQueueTicket {
        require(selectedOrder in 0 until lease.view.totalCount)
        lease.checkCurrent()
        val command = OnlineQueueCommand(UUID.randomUUID().toString(), operationId, expected?.generationId,
            expected?.queueRevision ?: 0L, expected?.currentEntryId, selectedOrder, action, quality, expected?.mode ?: io.github.onlinemo.meloxwatch.model.QueueMode())
        val created = Slot(command, lease)
        synchronized(memory) {
            if (slot != null) throw OnlineCatalogException(OnlineCatalogIssue.BUSY)
            slot = created
        }
        return OnlineQueueTicket(command) {
            val release = synchronized(memory) {
                if (slot !== created || created.claimed) false else {
                    slot = null
                    true
                }
            }
            if (release) lease.close()
        }
    }

    fun claim(command: OnlineQueueCommand, cancelOwner: () -> Unit): ClaimedOnlineQueue {
        val claimed = synchronized(memory) {
            val current = slot ?: throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
            if (current.command != command || current.claimed) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
            current.lease.checkCurrent()
            current.claimed = true
            current.cancelOwner = cancelOwner
            current
        }
        return ClaimedOnlineQueue(command, claimed.lease,
            accepted = { synchronized(memory) { acceptedCommand = command } }) {
            try { claimed.lease.close() }
            finally { synchronized(memory) { if (slot === claimed) slot = null } }
        }
    }

    fun cancel(operationId: String): Boolean {
        val cancellation = synchronized(memory) {
            val current = slot?.takeIf { it.command.operationId == operationId } ?: return false
            current.lease.cancel()
            current.cancelOwner
        }
        cancellation?.invoke()
        return true
    }
}

internal class OnlineQueueTicket(val command: OnlineQueueCommand, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

@UnstableApi
internal class ClaimedOnlineQueue(private val command: OnlineQueueCommand, private val lease: OnlineCatalogQueueLease,
    private val accepted: () -> Unit, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    suspend fun commit(coordinator: QueuePlaybackCoordinator) {
        currentCoroutineContext().ensureActive()
        lease.checkCurrent()
        when (command.action) {
            OnlineQueueAction.REPLACE_COMPLETE_LIST -> coordinator.replace(QueueReplacementRequest(command.operationId,
                command.expectedGenerationId, command.expectedQueueRevision, lease.view, command.mode, command.selectedOrder),
                lease, true, PlaybackSourceIntent.ONLINE_ALLOWED, command.quality)
            OnlineQueueAction.INSERT_NEXT, OnlineQueueAction.INSERT_AND_PLAY -> {
                val generation = command.expectedGenerationId ?: throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
                val item = lease.read(command.selectedOrder, 1).entries.single()
                val view = FrozenQueueView("online-single:" + command.operationId, lease.view.revision, 1L)
                val source = QueuePageSource { offset, limit ->
                    currentCoroutineContext().ensureActive()
                    lease.checkCurrent()
                    require(offset == 0L && limit in 1..100)
                    QueueInputPage(view, 0L, listOf(item.copy(originalOrder = 0L, playbackOrder = 0L)))
                }
                coordinator.mutate(QueueMutationRequest(command.operationId, generation, command.expectedQueueRevision,
                    command.expectedCurrentEntryId,
                    QueueMutation.InsertNext(view, command.expectedCurrentEntryId, startInserted = command.action == OnlineQueueAction.INSERT_AND_PLAY)),
                    source, explicitPlay = command.action == OnlineQueueAction.INSERT_AND_PLAY,
                    sourceIntent = PlaybackSourceIntent.ONLINE_ALLOWED, streamingQuality = command.quality)
            }
        }
        accepted()
    }
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}
