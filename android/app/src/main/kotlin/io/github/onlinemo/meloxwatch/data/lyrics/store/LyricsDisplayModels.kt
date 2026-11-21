package io.github.onlinemo.meloxwatch.data.lyrics.store

import kotlinx.coroutines.flow.StateFlow

object LyricsDisplayLimits {
    const val SCHEMA_VERSION = 1
    const val MIN_TEXT_SP = 14f
    const val MAX_TEXT_SP = 32f
    const val MIN_BLOCK_DP = 4f
    const val MAX_BLOCK_DP = 24f
    const val MIN_ANCHOR_FRACTION = 0.25f
    const val MAX_ANCHOR_FRACTION = 0.75f
    const val MIN_MARGIN_DP = 0f
    const val MAX_MARGIN_DP = 24f
}

enum class LyricsDisplayAlignment { START, CENTER }

/** Only persisted display choices; actual system font scale and round-safe layout remain in UI. */
data class LyricsDisplayPreferences(
    val revision: Long = 0L,
    val textSizeSp: Float = 20f,
    val blockSpacingDp: Float = 12f,
    val alignment: LyricsDisplayAlignment = LyricsDisplayAlignment.CENTER,
    val anchorFraction: Float = 0.5f,
    val horizontalMarginDp: Float = 0f,
    val showTranslation: Boolean = true,
    val emphasizeWords: Boolean = false,
    /** Old display-file value survives round trips; saved appearance settings alone control F-103. */
    val legacyKeepScreenOn: Boolean = false,
) {
    init {
        require(revision >= 0L)
        require(textSizeSp.isFinite() && textSizeSp in LyricsDisplayLimits.MIN_TEXT_SP..LyricsDisplayLimits.MAX_TEXT_SP)
        require(blockSpacingDp.isFinite() && blockSpacingDp in LyricsDisplayLimits.MIN_BLOCK_DP..LyricsDisplayLimits.MAX_BLOCK_DP)
        require(anchorFraction.isFinite() && anchorFraction in LyricsDisplayLimits.MIN_ANCHOR_FRACTION..LyricsDisplayLimits.MAX_ANCHOR_FRACTION)
        require(horizontalMarginDp.isFinite() && horizontalMarginDp in LyricsDisplayLimits.MIN_MARGIN_DP..LyricsDisplayLimits.MAX_MARGIN_DP)
    }
}

enum class LyricsDisplayIssue {
    IO, CORRUPT_FILE, UNSUPPORTED_SCHEMA, INVALID_FIELDS, INVALID_REVISION, REVISION_EXHAUSTED, BUSY, LOW_MEMORY,
}

sealed interface LyricsDisplayState {
    data object Loading : LyricsDisplayState
    /** persisted=false is an actually empty file/store, not an asserted successful save. */
    data class Ready(val preferences: LyricsDisplayPreferences, val persisted: Boolean) : LyricsDisplayState
    /** Last confirmed values may be rendered read-only; unavailable never authorizes a repair write. */
    data class Unavailable(val lastKnown: LyricsDisplayPreferences?, val issue: LyricsDisplayIssue) : LyricsDisplayState
}

enum class LyricsDisplayResetConfirmation { CANCELLED, CONFIRMED }

sealed interface LyricsDisplayWriteResult {
    data class Saved(val preferences: LyricsDisplayPreferences) : LyricsDisplayWriteResult
    data class Unchanged(val preferences: LyricsDisplayPreferences) : LyricsDisplayWriteResult
    data class Conflict(val current: LyricsDisplayPreferences) : LyricsDisplayWriteResult
    data class Cancelled(val current: LyricsDisplayPreferences?) : LyricsDisplayWriteResult
    data class Failed(val current: LyricsDisplayPreferences?, val issue: LyricsDisplayIssue) : LyricsDisplayWriteResult
}

interface LyricsDisplayStore {
    val state: StateFlow<LyricsDisplayState>

    /** Atomic DataStore CAS; next retains expectedRevision, changed success increments once. */
    suspend fun save(expectedRevision: Long, next: LyricsDisplayPreferences): LyricsDisplayWriteResult

    /** Resets only this display file, leaving fonts, lyric associations, music and grants intact. */
    suspend fun reset(expectedRevision: Long, confirmation: LyricsDisplayResetConfirmation): LyricsDisplayWriteResult

    /** Wakes a single failed observation; it does not queue retries or rewrite a corrupt file. */
    fun retryRead()
}
