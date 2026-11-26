package io.github.onlinemo.meloxwatch.playback

import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.queue.QueueCommit
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 历史receipt不依赖原UI lease，也不重新选择资源；首次真正读页才建立QUEUE lease。
 * 稳定身份包含原视图和所选曲目；替换时也核验选曲断言，临时producer ID不参与幂等核验。
 */
internal class LibraryQueueProducer(
    private val library: LocalLibraryRepository,
    private val command: LibraryQueueCommand,
) {
    private val singleInsertion = command.action != LibraryQueueAction.REPLACE_COMPLETE_LIST && command.selected != null
    private val frozen = FrozenQueueView(
        queueViewId(command),
        command.viewRevision,
        if (singleInsertion) 1L else command.viewCount,
    )
    private val reads = Mutex()
    private var adopted: LibraryViewToken? = null
    private var delegate: QueuePageSource? = null
    private var selectedTrack: LibraryTrack? = null
    private var nextOffset = 0L
    private var closed = false

    val source = QueuePageSource { offset, limit ->
        reads.withLock {
            check(!closed)
            require(offset == nextOffset && limit in 1..io.github.onlinemo.meloxwatch.model.QueueLimits.PAGE_SIZE)
            if (adopted == null) adopt()
            val page = if (singleInsertion) {
                require(offset == 0L && limit == 1)
                val track = checkNotNull(selectedTrack)
                QueueInputPage(frozen, 0L, listOf(QueueEntry(
                    UUID.randomUUID().toString(), track.trackKey, 0L, 0L,
                    track.title, track.metadata.durationMs, track.location, cueReference = track.cueReference,
                )))
            } else checkNotNull(delegate).read(offset, limit).copy(view = frozen)
            require(page.entries.size <= frozen.totalCount - offset)
            nextOffset += page.entries.size
            page
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun commit(coordinator: QueuePlaybackCoordinator): QueueCommit =
        if (command.action == LibraryQueueAction.REPLACE_COMPLETE_LIST) {
            coordinator.replace(
                QueueReplacementRequest(
                    operationId = command.operationId,
                    expectedGenerationId = command.expectedGenerationId,
                    expectedQueueRevision = command.expectedQueueRevision,
                    view = frozen,
                    mode = command.mode,
                    startOriginalOrder = command.selectedOriginalOrder ?: 0L,
                ),
                source,
                explicitPlay = true,
            )
        } else {
            val startsInserted = command.action == LibraryQueueAction.INSERT_AND_PLAY
            coordinator.mutate(
                QueueMutationRequest(
                    command.operationId, command.expectedGenerationId, command.expectedQueueRevision,
                    command.expectedCurrentEntryId,
                    QueueMutation.InsertNext(frozen, command.expectedCurrentEntryId, startsInserted),
                ),
                source,
                explicitPlay = startsInserted,
            )
        }

    private suspend fun adopt() {
        val lease = library.openQueueView(command.viewId)
        adopted = lease
        if (lease.revision != command.viewRevision || lease.totalCount != command.viewCount) {
            throw LibraryException(LibraryIssue.SOURCE_CHANGED)
        }
        command.selected?.let { key ->
            val selected = library.locate(lease, key) ?: throw LibraryException(LibraryIssue.SOURCE_CHANGED)
            if (selected.ordinal != command.selectedOriginalOrder) throw LibraryException(LibraryIssue.SOURCE_CHANGED)
            selectedTrack = selected.track
        }
        if (!singleInsertion) delegate = library.queueSource(lease)
    }

    suspend fun close() {
        reads.withLock {
            closed = true
            delegate = null
            selectedTrack = null
            // 释放失败保留lease供重试；关闭后任何读取仍被拒绝。
            val lease = adopted ?: return
            library.closeView(lease.viewId)
            adopted = null
        }
    }

    private fun queueViewId(input: LibraryQueueCommand): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String?) {
            val bytes = value?.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
            if (bytes != null) digest.update(bytes)
        }
        field(input.viewId)
        field(input.viewRevision.toString())
        field(input.viewCount.toString())
        field(input.selected?.providerId)
        field(input.selected?.trackId)
        field(input.selectedOriginalOrder?.toString())
        field(input.mode.shuffle.toString())
        field(input.mode.repeat.name)
        field(input.mode.cycleLimit?.toString())
        field(input.action.name)
        val hex = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val prefix = if (input.action == LibraryQueueAction.REPLACE_COMPLETE_LIST) "library-replace:" else "library-insert:"
        return "$prefix$hex"
    }
}
