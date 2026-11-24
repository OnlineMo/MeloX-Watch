package io.github.onlinemo.meloxwatch.model

enum class SongTapPolicy { ASK, INSERT_AND_PLAY, REPLACE_COMPLETE_LIST }

enum class PlaybackBackendKind { MEDIA3, SYSTEM_MEDIA_PLAYER }

object PlaybackPreferenceLimits {
    const val MIN_SPEED_TENTHS = 5
    const val MAX_SPEED_TENTHS = 20
    const val DEFAULT_SPEED_TENTHS = 10
}
