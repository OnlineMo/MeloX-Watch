package io.github.onlinemo.meloxwatch.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.data.backup.publishForBackup
import io.github.onlinemo.meloxwatch.data.backup.publishFailureForBackup
import io.github.onlinemo.meloxwatch.data.backup.publishIfCurrentForBackup
import io.github.onlinemo.meloxwatch.data.backup.readBackupProjection
import io.github.onlinemo.meloxwatch.data.backup.withBackupMutation
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 单实例 DataStore 是磁盘权威；所有写入在其原子 transform 中核验 revision。
 * 活跃存储的原文件不能被外部替换；未来恢复入口须经仓库提交并保持 revision 单调递增。
 */
class DataStoreUserPreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
    private val backupBarrier: BackupMutationBarrier? = null,
) : UserPreferencesRepository {
    private val _state = MutableStateFlow<PreferencesState>(PreferencesState.Loading)
    override val state = _state.asStateFlow()
    private val mutations = Mutex()
    private val publicationLock = Any()
    private var readGeneration = Any()
    private var pendingReadRetry: CompletableDeferred<Unit>? = null

    init {
        scope.launch {
            while (isActive) {
                var observedGeneration = synchronized(publicationLock) { readGeneration }
                try {
                    backupBarrier.readBackupProjection { Unit }
                    dataStore.data.collect {
                        // Cache emissions are notifications; the locked identity read confirms bytes.
                        val generation = synchronized(publicationLock) { readGeneration }
                        val decoded = backupBarrier.readBackupProjection {
                            mutations.withLock { PreferencesCodec.decode(completedUpdate { it }) }
                        }
                        publishObserved(decoded, generation)?.let { currentGeneration ->
                            observedGeneration = currentGeneration
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: IOException) {
                    val retry = synchronized(publicationLock) {
                        // 晚到的旧读错误不能盖过明确操作已核验的当前值。
                        if (observedGeneration !== readGeneration) {
                            null
                        } else {
                            CompletableDeferred<Unit>().also { signal ->
                                pendingReadRetry = signal
                            }
                        }
                    }
                    if (retry != null) {
                        backupBarrier.publishFailureForBackup(this) {
                            synchronized(publicationLock) {
                                if (observedGeneration === readGeneration && pendingReadRetry === retry) {
                                    _state.value = PreferencesState.Unavailable(lastKnown(), PreferencesFailure.IO)
                                }
                            }
                        }
                        try {
                            retry.await()
                        } finally {
                            synchronized(publicationLock) {
                                if (pendingReadRetry === retry) pendingReadRetry = null
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult = try {
        backupBarrier.withBackupMutation({ BackupException(BackupIssue.BUSY) }) { saveActual(expectedRevision, draft) }
    } catch (error: BackupException) {
        if (error.issue != BackupIssue.BUSY) throw error
        PreferencesWriteResult.Failed(lastKnown(), PreferencesFailure.BUSY)
    }

    /** A restore never uses a cached notification as the old generation's durable configuration. */
    internal suspend fun readForBackup(): UserPreferences = backupBarrier.withBackupMutation({ BackupException(BackupIssue.BUSY) }) {
        mutations.withLock {
            val actual = PreferencesCodec.decode(completedUpdate { it })
            if (!actual.canWrite || actual.recovery != null) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            publishAuthoritative(actual)
            actual.preferences
        }
    }

    private suspend fun saveActual(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult {
        if (expectedRevision < 0L) {
            return PreferencesWriteResult.Failed(lastKnown(), PreferencesFailure.INVALID_REVISION)
        }
        return mutations.withLock {
            var readAtWrite: DecodedPreferences? = null
            var changed = false
            try {
                val committed = completedUpdate { stored ->
                    val decoded = PreferencesCodec.decode(stored)
                    readAtWrite = decoded
                    if (!decoded.canWrite) throw RejectedWrite(PreferencesFailure.UNSUPPORTED_SCHEMA)
                    val current = decoded.preferences
                    if (current.revision != expectedRevision) throw ConflictingWrite(current)
                    changed = current.asDraft() != draft || !decoded.isCanonical
                    if (!changed) {
                        stored
                    } else {
                        if (current.revision == Long.MAX_VALUE) {
                            throw RejectedWrite(PreferencesFailure.REVISION_EXHAUSTED)
                        }
                        PreferencesCodec.encode(
                            current.copy(
                                revision = current.revision + 1L,
                                restorePosition = draft.restorePosition,
                                screenShape = draft.screenShape,
                                libraryLayout = draft.libraryLayout,
                                artworkReadPath = draft.artworkReadPath,
                                librarySorts = draft.librarySorts.toMap(),
                                songTapPolicy = draft.songTapPolicy,
                                mediaButtonsEnabled = draft.mediaButtonsEnabled,
                                audioFocusManagement = draft.audioFocusManagement,
                                playbackBackend = draft.playbackBackend,
                                playbackSpeedTenths = draft.playbackSpeedTenths,
                                gaplessPlayback = draft.gaplessPlayback,
                                controls = draft.controls,
                                help = draft.help,
                                bluetoothLyricsEnabled = draft.bluetoothLyricsEnabled,
                            ),
                        )
                    }
                }
                val decoded = PreferencesCodec.decode(committed)
                publishAuthoritative(decoded)
                if (changed) {
                    PreferencesWriteResult.Saved(decoded.preferences)
                } else {
                    PreferencesWriteResult.Unchanged(decoded.preferences)
                }
            } catch (error: CancellationException) {
                // 已确认且已经落盘的提交由观察流呈现；取消不虚构磁盘回滚。
                throw error
            } catch (error: ConflictingWrite) {
                readAtWrite?.let(::publishAuthoritative)
                PreferencesWriteResult.Conflict(error.current)
            } catch (error: RejectedWrite) {
                readAtWrite?.let(::publishAuthoritative)
                PreferencesWriteResult.Failed(readAtWrite?.preferences ?: lastKnown(), error.failure)
            } catch (_: IOException) {
                // A failed ack may follow a real commit. The true file read precedes any retry/UI state.
                val actual = try { PreferencesCodec.decode(completedUpdate { it }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) { null }
                if (actual == null) publishUnavailable() else publishAuthoritative(actual)
                PreferencesWriteResult.Failed(actual?.preferences ?: readAtWrite?.preferences ?: lastKnown(), PreferencesFailure.IO)
            }
        }
    }

    override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult {
        if (confirmation == ResetConfirmation.CANCELLED) return PreferencesWriteResult.Cancelled(lastKnown())
        return save(expectedRevision, UserPreferencesDraft())
    }

    override fun retryRead() {
        synchronized(publicationLock) { pendingReadRetry?.complete(Unit) }
    }

    private fun publishObserved(decoded: DecodedPreferences, expectedGeneration: Any): Any? {
        var generation: Any? = null
        backupBarrier.publishIfCurrentForBackup(this, { synchronized(publicationLock) { expectedGeneration === readGeneration } }) {
            synchronized(publicationLock) {
                val known = _state.value.lastKnown()
                if (expectedGeneration === readGeneration && (known == null || decoded.preferences.revision >= known.revision)) {
                    _state.value = decoded.asReady()
                    generation = readGeneration
                }
            }
        }
        return generation
    }

    private fun publishAuthoritative(decoded: DecodedPreferences) {
        // Retire old reads at the durable result, before its visible value is deferred.
        val generation = synchronized(publicationLock) {
            Any().also { readGeneration = it; pendingReadRetry?.complete(Unit) }
        }
        backupBarrier.publishIfCurrentForBackup(this, { synchronized(publicationLock) { generation === readGeneration } }) {
            synchronized(publicationLock) {
                if (generation === readGeneration) _state.value = decoded.asReady()
            }
        }
    }

    private fun DecodedPreferences.asReady() = PreferencesState.Ready(preferences, recovery, canWrite)

    private fun publishUnavailable() {
        backupBarrier.publishFailureForBackup(this) {
            synchronized(publicationLock) {
                _state.value = PreferencesState.Unavailable(_state.value.lastKnown(), PreferencesFailure.IO)
            }
        }
    }

    private suspend fun completedUpdate(transform: (Preferences) -> Preferences): Preferences {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        // Retain the mutex/admission until the Application DataStore actor has truly exited.
        val attempt = withContext(NonCancellable) {
            runCatching {
                dataStore.updateData { stored ->
                    caller.ensureActive()
                    transform(stored).also { caller.ensureActive() }
                }
            }
        }
        try { caller.ensureActive() }
        catch (cancelled: CancellationException) {
            attempt.exceptionOrNull()?.takeUnless { it is CancellationException }?.let { cancelled.addSuppressed(it) }
            throw cancelled
        }
        return attempt.getOrThrow()
    }

    private fun lastKnown(): UserPreferences? = _state.value.lastKnown()

    private fun PreferencesState.lastKnown(): UserPreferences? = when (this) {
        is PreferencesState.Ready -> preferences
        is PreferencesState.Unavailable -> lastKnown
        PreferencesState.Loading -> null
    }

    private class ConflictingWrite(val current: UserPreferences) : Exception()
    private class RejectedWrite(val failure: PreferencesFailure) : Exception()
}
