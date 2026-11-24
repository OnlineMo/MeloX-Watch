package io.github.onlinemo.meloxwatch.model

enum class RotaryAction { MEDIA_VOLUME, SEEK }
enum class RotaryOrientation { NORMAL, REVERSED }
enum class ProgressGestureMode { DRAG_TO_COMMIT, TAP_TO_SEEK }

/** Saved controls never start playback or a timer; those require a separate current-session command. */
data class PlaybackControlsPreferences(
    val rotaryEnabled: Boolean = false,
    val rotaryAction: RotaryAction = RotaryAction.MEDIA_VOLUME,
    val rotaryOrientation: RotaryOrientation = RotaryOrientation.NORMAL,
    val rotaryHaptics: Boolean = false,
    val showMainProgress: Boolean = true,
    val progressGesture: ProgressGestureMode = ProgressGestureMode.DRAG_TO_COMMIT,
    val sleepTimerSummary: Boolean = false,
)
