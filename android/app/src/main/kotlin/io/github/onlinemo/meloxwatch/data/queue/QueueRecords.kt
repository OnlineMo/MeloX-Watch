package io.github.onlinemo.meloxwatch.data.queue

import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey

internal fun QueueReplacementRequest.record(generationId: String) = QueueGenerationEntity(
    generationId = generationId,
    operationId = operationId,
    expectedGenerationId = expectedGenerationId,
    expectedQueueRevision = expectedQueueRevision,
    viewId = view.id,
    viewRevision = view.revision,
    totalCount = view.totalCount,
    shuffle = mode.shuffle,
    repeatMode = mode.repeat.name,
    cycleLimit = mode.cycleLimit,
    startOriginalOrder = startOriginalOrder,
    initialPositionMs = initialPositionMs,
    allowEmpty = allowEmpty,
    orderPolicy = orderPolicy.name,
)

internal fun QueueGenerationEntity.matches(request: QueueReplacementRequest): Boolean =
    operationKind == "REPLACE" && operationId == request.operationId &&
        expectedGenerationId == request.expectedGenerationId && expectedQueueRevision == request.expectedQueueRevision &&
        viewId == request.view.id && viewRevision == request.view.revision && totalCount == request.view.totalCount &&
        shuffle == request.mode.shuffle && repeatMode == request.mode.repeat.name && cycleLimit == request.mode.cycleLimit &&
        startOriginalOrder == request.startOriginalOrder && initialPositionMs == request.initialPositionMs && allowEmpty == request.allowEmpty &&
        orderPolicy == request.orderPolicy.name

internal fun QueueEntry.record(generationId: String) = QueueEntryEntity(
    generationId = generationId,
    queueEntryId = queueEntryId,
    providerId = trackKey.providerId,
    trackId = trackKey.trackId,
    originalOrder = originalOrder,
    playbackOrder = playbackOrder,
    displayName = displayName,
    durationMs = durationMs,
    accountId = accountId,
    locationId = location?.locationId,
    locationUri = location?.uri,
    grantUri = location?.grantUri,
    grantFlags = location?.grantFlags,
    access = location?.access?.name,
    contentRevision = location?.contentRevision,
)

internal fun QueueEntryEntity.model(): QueueEntry {
    val location = if (locationId == null) {
        if (locationUri != null || grantUri != null || grantFlags != null || access != null || contentRevision != null) recoveryRequired()
        null
    } else {
        LocalLocationReference(
            locationId,
            locationUri ?: recoveryRequired(),
            grantUri ?: recoveryRequired(),
            grantFlags ?: recoveryRequired(),
            LocalAccess.valueOf(access ?: recoveryRequired()),
            contentRevision ?: recoveryRequired(),
        )
    }
    return QueueEntry(
        queueEntryId,
        TrackKey(providerId, trackId),
        originalOrder,
        playbackOrder,
        displayName,
        durationMs,
        location,
        accountId,
    )
}

internal fun QueueGenerationEntity.snapshot(head: QueueHeadEntity, current: QueueEntryEntity?): QueueSnapshot {
    if (phase != QueuePhase.PUBLISHED || committedQueueRevision != head.queueRevision || totalCount < 0L || writtenCount != totalCount ||
        head.queueRevision < 1L || head.checkpointRevision < 0L || head.positionMs < 0L || head.completedCycles < 0
    ) recoveryRequired()
    if (totalCount == 0L) {
        if (head.currentEntryId != null || head.positionMs != 0L || head.completedCycles != 0) recoveryRequired()
    } else {
        if (current == null || current.originalOrder !in 0L until totalCount || current.playbackOrder !in 0L until totalCount) {
            recoveryRequired()
        }
    }
    // 解码当前位置也校验访问快照；仅返回 URI 字符串，不尝试打开它。
    current?.model()
    return QueueSnapshot(
        generationId = generationId,
        queueRevision = head.queueRevision,
        checkpointRevision = head.checkpointRevision,
        view = FrozenQueueView(viewId, viewRevision, totalCount),
        mode = QueueMode(shuffle, QueueRepeatMode.valueOf(repeatMode), cycleLimit),
        checkpoint = QueueCheckpoint(head.currentEntryId, head.positionMs, head.completedCycles),
        currentOriginalOrder = current?.originalOrder,
        currentPlaybackOrder = current?.playbackOrder,
    )
}

internal fun recoveryRequired(): Nothing = throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED)
