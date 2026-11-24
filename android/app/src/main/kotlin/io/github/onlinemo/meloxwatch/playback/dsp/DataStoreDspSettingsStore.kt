package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.data.backup.publishForBackup
import io.github.onlinemo.meloxwatch.data.backup.publishFailureForBackup
import io.github.onlinemo.meloxwatch.data.backup.withBackupMutation
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 取消调用者后仍持有 admission 和互斥锁，直到 DataStore actor 的实际操作退出。 */
class DataStoreDspSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val backupBarrier: BackupMutationBarrier? = null,
) : DspSettingsStore {
    private val mutations = Mutex()
    private val mutableState = MutableStateFlow(DspSnapshot(null))
    override val state = mutableState.asStateFlow()

    override suspend fun read(): DspSnapshot = try {
        backupBarrier.withBackupMutation({ DspStorageException(DspSettingsIssue.BUSY) }) {
            mutations.withLock {
                try {
                    // identity update 回读真实文件，避免把失败 atomic move 留下的内存提案当作已保存值。
                    DspSettingsCodec.decode(completedUpdate { it })
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) { DspSnapshot(state.value.saved, DspSettingsIssue.STORAGE_UNAVAILABLE).also(::publish) }
                catch (_: Exception) { DspSnapshot(null, DspSettingsIssue.UNRECOVERABLE_SETTINGS).also(::publish) }
                catch (_: OutOfMemoryError) { DspSnapshot(null, DspSettingsIssue.SETTINGS_TOO_LARGE).also(::publish) }
            }
        }
    } catch (error: DspStorageException) {
        DspSnapshot(state.value.saved, error.issue)
    }

    override suspend fun compareAndSet(expectedRevision: Long, settings: DspSettings, presets: List<DspPreset>): DspSaveResult {
        val replacement = try { DspStored(expectedRevision, settings, presets).immutableCopy() }
        catch (_: IllegalArgumentException) {
            return DspSaveResult.Failed(state.value, DspSettingsIssue.INVALID_SETTINGS)
        }
        return mutate { stored, observed ->
            val actual = observed.saved ?: throw DspStorageException(observed.issue ?: DspSettingsIssue.INVALID_SETTINGS)
            if (actual.revision != expectedRevision) throw Conflicting()
            if (actual.settings == replacement.settings && actual.presets == replacement.presets && observed.issue == null) stored
            else {
                if (actual.revision == Long.MAX_VALUE) throw DspStorageException(DspSettingsIssue.REVISION_EXHAUSTED)
                DspSettingsCodec.encode(replacement.copy(revision = actual.revision + 1L))
            }
        }
    }

    override suspend fun recoverDefaults(expectedRecoveryToken: String): DspSaveResult {
        if (!expectedRecoveryToken.matches(Regex("[0-9a-f]{64}"))) {
            return DspSaveResult.Failed(state.value, DspSettingsIssue.CONFLICT)
        }
        return mutate { _, observed ->
            if (observed.issue != DspSettingsIssue.INVALID_SETTINGS) {
                throw DspStorageException(observed.issue ?: DspSettingsIssue.CONFLICT)
            }
            if (observed.saved != null || observed.recoveryToken != expectedRecoveryToken) throw Conflicting()
            DspSettingsCodec.encode(DspStored())
        }
    }

    private suspend fun mutate(transform: (Preferences, DspSnapshot) -> Preferences): DspSaveResult = try {
        backupBarrier.withBackupMutation({ DspStorageException(DspSettingsIssue.BUSY) }) {
            mutations.withLock {
                var actual = state.value
                try {
                    val result = completedUpdate { stored ->
                        actual = DspSettingsCodec.decode(stored)
                        transform(stored, actual)
                    }
                    val saved = DspSettingsCodec.decode(result)
                    DspSaveResult.Saved(saved)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Conflicting) {
                    publish(actual)
                    DspSaveResult.Conflict(actual)
                }
                catch (error: DspStorageException) {
                    publish(actual)
                    DspSaveResult.Failed(actual, error.issue)
                }
                catch (_: IOException) {
                    val failed = DspSnapshot(actual.saved, DspSettingsIssue.STORAGE_UNAVAILABLE)
                    publish(failed)
                    DspSaveResult.Failed(failed, DspSettingsIssue.STORAGE_UNAVAILABLE)
                } catch (_: Exception) {
                    val failed = DspSnapshot(null, DspSettingsIssue.UNRECOVERABLE_SETTINGS)
                    publish(failed)
                    DspSaveResult.Failed(failed, DspSettingsIssue.UNRECOVERABLE_SETTINGS)
                } catch (_: OutOfMemoryError) {
                    val failed = DspSnapshot(null, DspSettingsIssue.SETTINGS_TOO_LARGE)
                    publish(failed)
                    DspSaveResult.Failed(failed, DspSettingsIssue.SETTINGS_TOO_LARGE)
                }
            }
        }
    } catch (error: DspStorageException) { DspSaveResult.Failed(state.value, error.issue) }

    private fun publish(value: DspSnapshot) {
        if (value.issue == null) backupBarrier.publishForBackup(this) { mutableState.value = value }
        else backupBarrier.publishFailureForBackup(this) { mutableState.value = value }
    }

    private suspend fun completedUpdate(transform: (Preferences) -> Preferences): Preferences {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val completed = withContext(NonCancellable) {
            runCatching {
                val actual = dataStore.updateData { stored ->
                    caller.ensureActive()
                    val replacement = transform(stored)
                    caller.ensureActive()
                    replacement
                }
                // 实际 actor 已成功提交；取消的是命令应答，不能把落盘误当回滚或晚于新 CAS 发布。
                publish(DspSettingsCodec.decode(actual))
                actual
            }
        }
        try { caller.ensureActive() }
        catch (cancelled: CancellationException) {
            completed.exceptionOrNull()?.let { error ->
                if (error !is CancellationException) cancelled.addSuppressed(error)
            }
            throw cancelled
        }
        return completed.getOrThrow()
    }

    private class Conflicting : Exception()
}
