package io.github.onlinemo.meloxwatch.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import java.io.IOException
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
                    dataStore.data.collect { stored ->
                        publishObserved(PreferencesCodec.decode(stored))?.let { generation ->
                            observedGeneration = generation
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
                                _state.value = PreferencesState.Unavailable(lastKnown(), PreferencesFailure.IO)
                            }
                        }
                    }
                    if (retry != null) {
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

    override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult {
        if (expectedRevision < 0L) {
            return PreferencesWriteResult.Failed(lastKnown(), PreferencesFailure.INVALID_REVISION)
        }
        return mutations.withLock {
            var readAtWrite: DecodedPreferences? = null
            var changed = false
            try {
                val committed = dataStore.updateData { stored ->
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
                val known = readAtWrite
                if (known == null) publishUnavailable() else publishAuthoritative(known)
                PreferencesWriteResult.Failed(known?.preferences ?: lastKnown(), PreferencesFailure.IO)
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

    private fun publishObserved(decoded: DecodedPreferences): Any? = synchronized(publicationLock) {
        val previous = _state.value
        val known = previous.lastKnown()
        // collector 可晚于 updateData 的完成回调；活跃存储只有本仓库的递增 revision 写入。
        if (known == null || decoded.preferences.revision >= known.revision) {
            _state.value = decoded.asReady()
            readGeneration
        } else {
            null
        }
    }

    private fun publishAuthoritative(decoded: DecodedPreferences) {
        synchronized(publicationLock) {
            // 原子 transform 的真实当前值优先，恢复/未知 schema 也不能被旧 revision 护栏隐藏。
            readGeneration = Any()
            _state.value = decoded.asReady()
            // 明确操作已成功读取存储，解除旧读失败等待，不留下下次故障可消费的重试请求。
            pendingReadRetry?.complete(Unit)
        }
    }

    private fun DecodedPreferences.asReady() = PreferencesState.Ready(preferences, recovery, canWrite)

    private fun publishUnavailable() {
        synchronized(publicationLock) {
            val previous = _state.value
            _state.value = PreferencesState.Unavailable(previous.lastKnown(), PreferencesFailure.IO)
        }
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
