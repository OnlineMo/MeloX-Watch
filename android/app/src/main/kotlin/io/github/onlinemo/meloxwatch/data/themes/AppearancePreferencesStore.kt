package io.github.onlinemo.meloxwatch.data.themes

import kotlinx.coroutines.flow.StateFlow

/** 只保存 LP-12 显示偏好；形状仍由原 UserPreferences.screenShape 单独负责。 */
interface AppearancePreferencesStore {
    val state: StateFlow<AppearanceSnapshot>
    suspend fun read(): AppearanceSnapshot

    /** 原子 transform 内比较 revision；取消不虚构已落盘提交回滚。 */
    suspend fun compareAndSet(expectedRevision: Long, replacement: AppearanceOptions): AppearanceSaveResult

    /** Explicit recovery replaces only the exact observed invalid record, never a newer schema. */
    suspend fun recoverDefaults(expectedRecoveryToken: String): AppearanceSaveResult
}
