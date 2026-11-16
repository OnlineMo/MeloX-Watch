package io.github.onlinemo.meloxwatch.playback.input

import io.github.onlinemo.meloxwatch.model.PlaybackControlsPreferences
import io.github.onlinemo.meloxwatch.model.ProgressGestureMode
import io.github.onlinemo.meloxwatch.model.RotaryAction
import io.github.onlinemo.meloxwatch.model.RotaryOrientation

fun PlaybackControlsPreferences.asRotaryInputSettings() = RotaryInputSettings(
    enabled = rotaryEnabled,
    target = when (rotaryAction) {
        RotaryAction.MEDIA_VOLUME -> RotaryTarget.MEDIA_VOLUME
        RotaryAction.SEEK -> RotaryTarget.SEEK
    },
    direction = when (rotaryOrientation) {
        RotaryOrientation.NORMAL -> RotaryDirection.NORMAL
        RotaryOrientation.REVERSED -> RotaryDirection.REVERSED
    },
)

fun PlaybackControlsPreferences.asSeekProgressSettings() = SeekProgressSettings(
    showMainProgress = showMainProgress,
    mode = when (progressGesture) {
        ProgressGestureMode.DRAG_TO_COMMIT -> SeekGestureMode.DRAG_TO_COMMIT
        ProgressGestureMode.TAP_TO_SEEK -> SeekGestureMode.TAP_TO_SEEK
    },
)
