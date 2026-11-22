package io.github.onlinemo.meloxwatch.data.themes

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
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

/** DataStore 是显示偏好的磁盘权威；读取失败只等待用户重试，没有隐藏轮询。 */
class DataStoreAppearancePreferencesStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val backupBarrier: BackupMutationBarrier? = null,
) : AppearancePreferencesStore {
    private val mutations = Mutex()
    private val mutableState = MutableStateFlow(AppearanceSnapshot(null))
    override val state = mutableState.asStateFlow()

    override suspend fun read(): AppearanceSnapshot = try {
        backupBarrier.withBackupMutation({ BackupException(BackupIssue.BUSY) }) { readActual() }
    } catch (error: BackupException) {
        if (error.issue != BackupIssue.BUSY) throw error
        AppearanceSnapshot(state.value.settings, AppearanceIssue.BUSY)
    }

    private suspend fun readActual(): AppearanceSnapshot = mutations.withLock {
        try {
            // DataStore 1.1.7 may cache a proposal before atomicMove fails. An identity
            // update reads the real file under its lock without writing or trusting that cache.
            AppearancePreferencesCodec.decode(completedUpdate { it }).also(::publish)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            AppearanceSnapshot(mutableState.value.settings, AppearanceIssue.STORAGE_UNAVAILABLE)
                .also(::publish)
        } catch (_: Exception) {
            AppearanceSnapshot(null, AppearanceIssue.UNRECOVERABLE_SETTINGS).also(::publish)
        } catch (_: OutOfMemoryError) {
            AppearanceSnapshot(null, AppearanceIssue.SETTINGS_TOO_LARGE).also(::publish)
        }
    }

    override suspend fun compareAndSet(expectedRevision: Long, replacement: AppearanceOptions): AppearanceSaveResult = try {
        backupBarrier.withBackupMutation({ BackupException(BackupIssue.BUSY) }) { compareAndSetActual(expectedRevision, replacement) }
    } catch (error: BackupException) {
        if (error.issue != BackupIssue.BUSY) throw error
        AppearanceSaveResult.Failed(state.value, AppearanceIssue.BUSY)
    }

    private suspend fun compareAndSetActual(expectedRevision: Long, replacement: AppearanceOptions): AppearanceSaveResult =
        mutations.withLock {
            if (expectedRevision < 0L) return@withLock AppearanceSaveResult.Failed(state.value, AppearanceIssue.CONFLICT)
            var actual = state.value
            try {
                val saved = completedUpdate { stored ->
                    actual = AppearancePreferencesCodec.decode(stored)
                    val settings = actual.settings ?: throw Rejected(actual.issue ?: AppearanceIssue.INVALID_SETTINGS)
                    if (settings.revision != expectedRevision) throw Conflicting()
                    if (settings.options == replacement && actual.issue == null) return@completedUpdate stored
                    if (settings.revision == Long.MAX_VALUE) throw Rejected(AppearanceIssue.REVISION_EXHAUSTED)
                    AppearancePreferencesCodec.encode(AppearanceSettings(settings.revision + 1L, replacement))
                }
                val snapshot = AppearancePreferencesCodec.decode(saved)
                publish(snapshot)
                AppearanceSaveResult.Saved(snapshot)
            } catch (cancelled: CancellationException) {
                // 下一次 read 回读真实文件；重建和迟到完成都不能重放此写入。
                throw cancelled
            } catch (_: Conflicting) {
                publish(actual)
                AppearanceSaveResult.Conflict(actual)
            } catch (error: Rejected) {
                publish(actual)
                AppearanceSaveResult.Failed(actual, error.issue)
            } catch (_: IOException) {
                val snapshot = AppearanceSnapshot(actual.settings, AppearanceIssue.STORAGE_UNAVAILABLE)
                publish(snapshot)
                AppearanceSaveResult.Failed(snapshot, AppearanceIssue.STORAGE_UNAVAILABLE)
            } catch (_: Exception) {
                val snapshot = AppearanceSnapshot(null, AppearanceIssue.UNRECOVERABLE_SETTINGS)
                publish(snapshot)
                AppearanceSaveResult.Failed(snapshot, AppearanceIssue.UNRECOVERABLE_SETTINGS)
            } catch (_: OutOfMemoryError) {
                val snapshot = AppearanceSnapshot(null, AppearanceIssue.SETTINGS_TOO_LARGE)
                publish(snapshot)
                AppearanceSaveResult.Failed(snapshot, AppearanceIssue.SETTINGS_TOO_LARGE)
            }
        }

    override suspend fun recoverDefaults(expectedRecoveryToken: String): AppearanceSaveResult = try {
        backupBarrier.withBackupMutation({ BackupException(BackupIssue.BUSY) }) { recoverDefaultsActual(expectedRecoveryToken) }
    } catch (error: BackupException) {
        if (error.issue != BackupIssue.BUSY) throw error
        AppearanceSaveResult.Failed(state.value, AppearanceIssue.BUSY)
    }

    private suspend fun recoverDefaultsActual(expectedRecoveryToken: String): AppearanceSaveResult = mutations.withLock {
        if (expectedRecoveryToken.length != 64 || !expectedRecoveryToken.matches(Regex("[0-9a-f]{64}"))) {
            return@withLock AppearanceSaveResult.Failed(state.value, AppearanceIssue.CONFLICT)
        }
        var actual = state.value
        try {
            val saved = completedUpdate { stored ->
                actual = AppearancePreferencesCodec.decode(stored)
                val issue = actual.issue
                if (issue != null && issue != AppearanceIssue.INVALID_SETTINGS) throw Rejected(issue)
                if (actual.settings != null || actual.issue != AppearanceIssue.INVALID_SETTINGS ||
                    actual.recoveryToken != expectedRecoveryToken) throw Conflicting()
                AppearancePreferencesCodec.encode(AppearanceSettings())
            }
            val snapshot = AppearancePreferencesCodec.decode(saved)
            publish(snapshot)
            AppearanceSaveResult.Saved(snapshot)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Conflicting) {
            publish(actual)
            AppearanceSaveResult.Conflict(actual)
        } catch (error: Rejected) {
            publish(actual)
            AppearanceSaveResult.Failed(actual, error.issue)
        } catch (_: IOException) {
            val snapshot = AppearanceSnapshot(actual.settings, AppearanceIssue.STORAGE_UNAVAILABLE)
            publish(snapshot)
            AppearanceSaveResult.Failed(snapshot, AppearanceIssue.STORAGE_UNAVAILABLE)
        } catch (_: Exception) {
            val snapshot = AppearanceSnapshot(null, AppearanceIssue.UNRECOVERABLE_SETTINGS)
            publish(snapshot)
            AppearanceSaveResult.Failed(snapshot, AppearanceIssue.UNRECOVERABLE_SETTINGS)
        } catch (_: OutOfMemoryError) {
            val snapshot = AppearanceSnapshot(null, AppearanceIssue.SETTINGS_TOO_LARGE)
            publish(snapshot)
            AppearanceSaveResult.Failed(snapshot, AppearanceIssue.SETTINGS_TOO_LARGE)
        }
    }

    private suspend fun completedUpdate(transform: (Preferences) -> Preferences): Preferences {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        // The Application DataStore actor can outlive a cancelled acknowledgement. Keep
        // the existing mutex admitted until the real attempt exits, then propagate cancellation.
        val completed = withContext(NonCancellable) {
            runCatching {
                dataStore.updateData { stored ->
                    caller.ensureActive()
                    val transformed = transform(stored)
                    caller.ensureActive()
                    transformed
                }
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

    private fun publish(snapshot: AppearanceSnapshot) {
        val publication = { mutableState.value = snapshot }
        if (snapshot.issue == null) backupBarrier.publishForBackup(this, publication)
        else backupBarrier.publishFailureForBackup(this, publication)
    }

    private class Conflicting : Exception()
    private class Rejected(val issue: AppearanceIssue) : Exception()
}
