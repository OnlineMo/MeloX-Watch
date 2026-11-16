package io.github.onlinemo.meloxwatch.data.preferences

import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import kotlinx.coroutines.flow.StateFlow

enum class PreferencesField {
    SCHEMA_VERSION,
    REVISION,
    RESTORE_POSITION,
    SCREEN_SHAPE,
    LIBRARY_LAYOUT,
    ARTWORK_READ_PATH,
    LIBRARY_SORTS,
    SONG_TAP_POLICY,
    MEDIA_BUTTONS,
    AUDIO_FOCUS,
    PLAYBACK_BACKEND,
    PLAYBACK_SPEED,
    GAPLESS,
}

enum class PreferencesRecoveryReason {
    CORRUPT_FILE,
    UNRECOGNIZED_SCHEMA,
    INVALID_FIELDS,
    NEWER_SCHEMA,
}

data class PreferencesRecovery(
    val reason: PreferencesRecoveryReason,
    val fields: Set<PreferencesField>,
    val originalSchemaVersion: Int? = null,
)

enum class PreferencesFailure {
    IO,
    UNSUPPORTED_SCHEMA,
    INVALID_REVISION,
    REVISION_EXHAUSTED,
}

sealed interface PreferencesState {
    data object Loading : PreferencesState

    data class Ready(
        val preferences: UserPreferences,
        val recovery: PreferencesRecovery? = null,
        val canWrite: Boolean = true,
    ) : PreferencesState

    /** lastKnown 是最后确认的值，不能当作本次读取或写入成功。 */
    data class Unavailable(
        val lastKnown: UserPreferences?,
        val failure: PreferencesFailure,
    ) : PreferencesState
}

enum class ResetConfirmation {
    CANCELLED,
    CONFIRMED,
}

sealed interface PreferencesWriteResult {
    data class Saved(val preferences: UserPreferences) : PreferencesWriteResult
    data class Unchanged(val preferences: UserPreferences) : PreferencesWriteResult
    data class Conflict(val current: UserPreferences) : PreferencesWriteResult
    data class Cancelled(val current: UserPreferences?) : PreferencesWriteResult
    data class Failed(val current: UserPreferences?, val failure: PreferencesFailure) : PreferencesWriteResult
}

interface UserPreferencesRepository {
    val state: StateFlow<PreferencesState>

    /**
     * 在 DataStore 的原子 transform 内核验 [expectedRevision]，旧页面不能覆盖已提交的新设置。
     * 已规范化且与 [draft] 相同的重复保存返回 Unchanged，不递增 revision 或重写文件。
     * 协程取消向调用方传播；若原子提交已经落盘，取消不保证回滚，观察流仍呈现确认的值。
     */
    suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult

    /** 确认只覆盖此偏好存储，不能重置曲库、队列、授权、凭据或音频。 */
    suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult

    /** IO 失败等待明确重试或已成功读取存储的显式操作，不后台循环重试。 */
    fun retryRead()
}
