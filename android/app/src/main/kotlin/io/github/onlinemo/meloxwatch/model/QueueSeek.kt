package io.github.onlinemo.meloxwatch.model

/** 确认进度时绑定真实时长、实例/内容代次以及本次 Service 和后端，预览不触碰音频。 */
data class QueueSeekBinding(
    val previewId: String,
    val sessionId: String,
    val backendGeneration: Long,
    val generationId: String,
    val queueRevision: Long,
    val queueEntryId: String,
    val locationId: String?,
    val contentRevision: Long,
    val durationMs: Long,
) {
    init {
        require(previewId.isNotBlank() && sessionId.isNotBlank() && generationId.isNotBlank() && queueEntryId.isNotBlank())
        require(backendGeneration >= 0L && queueRevision >= 0L && contentRevision >= 0L && durationMs > 0L)
        require(locationId == null || locationId.isNotBlank())
    }
}

data class QueueSeekRequest(
    val binding: QueueSeekBinding,
    val positionMs: Long,
    val explicitPlay: Boolean = false,
)
