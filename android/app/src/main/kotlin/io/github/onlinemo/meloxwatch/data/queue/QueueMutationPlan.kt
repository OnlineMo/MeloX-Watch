package io.github.onlinemo.meloxwatch.data.queue

import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueOrderPolicy
import io.github.onlinemo.meloxwatch.model.QueueSnapshot

/** 一个常量大小的变换；全队列行逐批经过它，绝不在内存展开全排列。 */
internal data class QueueMutationPlan(
    val request: QueueMutationRequest,
    val previous: QueueSnapshot?,
    val referenced: QueueEntryEntity?,
    val totalCount: Long,
) {
    private val mutation get() = request.mutation
    val mode: QueueMode get() = (mutation as? QueueMutation.SetMode)?.mode ?: previous?.mode ?: QueueMode()
    val currentRemoved: Boolean get() {
        val removal = mutation as? QueueMutation.Remove ?: return false
        return removal.entryId == previous?.currentEntryId
    }
    val keepsCurrent: Boolean get() = previous?.currentEntryId != null && !currentRemoved &&
        (mutation as? QueueMutation.InsertNext)?.startInserted != true
    val preservesCycles: Boolean get() = keepsCurrent && previous?.mode?.let {
        it.repeat == mode.repeat && it.cycleLimit == mode.cycleLimit
    } == true
    val materializesShuffle: Boolean get() = mutation is QueueMutation.SetMode && mode.shuffle && previous?.mode?.shuffle != true

    fun transform(row: QueueEntryEntity, generationId: String): QueueEntryEntity? {
        val reference = referenced
        return when (val edit = mutation) {
            is QueueMutation.InsertNext -> {
                val originalAnchor = reference?.originalOrder ?: -1L
                val playbackAnchor = reference?.playbackOrder ?: -1L
                row.copy(
                    generationId = generationId,
                    originalOrder = row.originalOrder + if (row.originalOrder > originalAnchor) edit.view.totalCount else 0L,
                    playbackOrder = row.playbackOrder + if (row.playbackOrder > playbackAnchor) edit.view.totalCount else 0L,
                )
            }
            is QueueMutation.Remove -> if (row.queueEntryId == edit.entryId) null else row.copy(
                generationId = generationId,
                originalOrder = row.originalOrder - if (row.originalOrder > requireNotNull(reference).originalOrder) 1L else 0L,
                playbackOrder = row.playbackOrder - if (row.playbackOrder > requireNotNull(reference).playbackOrder) 1L else 0L,
            )
            is QueueMutation.Move -> row.copy(
                generationId = generationId,
                originalOrder = if (previous?.mode?.shuffle == true) row.originalOrder else
                    movedOrder(row.originalOrder, requireNotNull(reference).originalOrder, edit.destinationPlaybackOrder),
                playbackOrder = movedOrder(row.playbackOrder, requireNotNull(reference).playbackOrder, edit.destinationPlaybackOrder),
            )
            is QueueMutation.SetMode -> row.copy(
                generationId = generationId,
                playbackOrder = if (edit.mode.shuffle) row.playbackOrder else row.originalOrder,
            )
        }
    }

    fun inserted(entry: io.github.onlinemo.meloxwatch.model.QueueEntry, generationId: String): QueueEntryEntity {
        val edit = mutation as? QueueMutation.InsertNext ?: error("Insertion plan required")
        return entry.copy(
            queueEntryId = insertedEntryId(generationId, entry.originalOrder),
            originalOrder = (referenced?.originalOrder ?: -1L) + 1L + entry.originalOrder,
            playbackOrder = (referenced?.playbackOrder ?: -1L) + 1L + entry.originalOrder,
        ).record(generationId).also {
            check(it.originalOrder < totalCount && it.playbackOrder < totalCount && edit.view.totalCount > 0L)
        }
    }

    fun record(generationId: String) = QueueGenerationEntity(
        generationId = generationId,
        operationId = request.operationId,
        expectedGenerationId = request.expectedGenerationId,
        expectedQueueRevision = request.expectedQueueRevision,
        viewId = "queue-edit:" + generationId,
        viewRevision = request.expectedQueueRevision + 1L,
        totalCount = totalCount,
        shuffle = mode.shuffle,
        repeatMode = mode.repeat.name,
        cycleLimit = mode.cycleLimit,
        startOriginalOrder = 0L,
        initialPositionMs = 0L,
        allowEmpty = totalCount == 0L,
        operationKind = "MUTATION",
        operationFingerprint = request.fingerprint(),
        expectedCurrentEntryId = request.expectedCurrentEntryId,
        orderPolicy = QueueOrderPolicy.PRESERVE_SUPPLIED.name,
    )
}

internal fun insertedEntryId(generationId: String, inputOrder: Long): String = generationId + ":" + inputOrder

internal fun QueueMutationRequest.fingerprint(): String = buildString {
    fun field(value: Any?) {
        val encoded = value?.toString()
        if (encoded == null) append("-;") else append(encoded.length).append(':').append(encoded).append(';')
    }
    field(expectedGenerationId)
    field(expectedQueueRevision)
    field(expectedCurrentEntryId)
    when (val edit = mutation) {
        is QueueMutation.InsertNext -> {
            field("INSERT_NEXT"); field(edit.anchorEntryId); field(edit.view.id); field(edit.view.revision); field(edit.view.totalCount); field(edit.startInserted)
        }
        is QueueMutation.Remove -> {
            field("REMOVE"); field(edit.entryId); field(edit.confirmedCurrent)
        }
        is QueueMutation.Move -> {
            field("MOVE"); field(edit.entryId); field(edit.destinationPlaybackOrder)
        }
        is QueueMutation.SetMode -> {
            field("MODE"); field(edit.mode.shuffle); field(edit.mode.repeat.name); field(edit.mode.cycleLimit)
        }
    }
}

internal fun QueueGenerationEntity.matches(request: QueueMutationRequest): Boolean =
    operationKind == "MUTATION" && operationId == request.operationId && operationFingerprint == request.fingerprint()

internal fun movedOrder(order: Long, from: Long, destination: Long): Long = when {
    order == from -> destination
    from < destination && order in (from + 1L)..destination -> order - 1L
    from > destination && order in destination until from -> order + 1L
    else -> order
}
