package io.github.onlinemo.meloxwatch.playback

enum class GaplessPlaybackIssue {
    PREFERENCE_DISABLED,
    BACKEND_UNSUPPORTED,
    TIMER_ACTIVE,
    QUEUE_BOUNDARY,
    MODE_REQUIRES_SETTLEMENT,
    SOURCE_UNSUPPORTED,
    SUCCESSOR_UNAVAILABLE,
    PREPARING,
    HANDOVER_PENDING,
}

/** 实际已放行的单个相邻边界。available 不代替编码矩阵或连续 PCM 的验收证据。 */
data class GaplessPlaybackState(
    val available: Boolean = false,
    val issue: GaplessPlaybackIssue? = GaplessPlaybackIssue.PREPARING,
)
