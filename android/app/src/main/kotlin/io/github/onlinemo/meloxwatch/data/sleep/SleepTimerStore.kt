package io.github.onlinemo.meloxwatch.data.sleep

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerState
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SleepTimerStoreRecovery { INVALID_FIELDS, UNRECOGNIZED_SCHEMA, NEWER_SCHEMA, CORRUPT_FILE }
enum class SleepTimerStoreIssue { IO, RETIRED_OWNER, UNSUPPORTED_SCHEMA, REVISION_EXHAUSTED, INPUT_BOUND }

data class StoredSleepTimer internal constructor(
    val revision: Long = 0L,
    val state: SleepTimerState = SleepTimerState(),
    val recovery: SleepTimerStoreRecovery? = null,
    val canWrite: Boolean = true,
    internal val canonical: Boolean = false,
) {
    init { require(revision >= 0L) }
}

/** 只在进程内传递；服务重建取得新租约后，旧服务的收尾写入失去权限。 */
class SleepTimerStoreLease internal constructor(
    internal val ownerId: UUID,
    val stored: StoredSleepTimer,
)

sealed interface SleepTimerStoreOpenResult {
    data class Ready(val lease: SleepTimerStoreLease) : SleepTimerStoreOpenResult
    data class Failed(val issue: SleepTimerStoreIssue) : SleepTimerStoreOpenResult
}

sealed interface SleepTimerStoreWriteResult {
    data class Saved(val lease: SleepTimerStoreLease, val changed: Boolean) : SleepTimerStoreWriteResult
    data class Conflict(val current: StoredSleepTimer) : SleepTimerStoreWriteResult
    data class Failed(val issue: SleepTimerStoreIssue) : SleepTimerStoreWriteResult
}

/**
 * 仅保存一份定时状态，不计时、不暂停或创建播放器。
 *
 * 打开/写入/退役共用一个互斥边界：新服务读到此前已经完成的写入，再使旧租约失效。
 * 所有磁盘工作 main-safe；取消传播，未知写盘结果不伪称已保存或已回滚。
 * 等待曲目的各标识最多 128 个 UTF-16 单元；超限返回 INPUT_BOUND，不能截断播放权威标识。
 */
class SleepTimerStore internal constructor(private val dataStore: DataStore<Preferences>) {
    private val mutations = Mutex()
    private var activeOwner: UUID? = null

    suspend fun open(): SleepTimerStoreOpenResult = withContext(Dispatchers.IO) {
        mutations.withLock {
            activeOwner = null
            try {
                val stored = SleepTimerStoreCodec.decode(dataStore.data.first())
                currentCoroutineContext().ensureActive()
                val owner = UUID.randomUUID()
                activeOwner = owner
                SleepTimerStoreOpenResult.Ready(SleepTimerStoreLease(owner, stored))
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                SleepTimerStoreOpenResult.Failed(SleepTimerStoreIssue.IO)
            }
        }
    }

    suspend fun save(lease: SleepTimerStoreLease, next: SleepTimerState): SleepTimerStoreWriteResult =
        withContext(Dispatchers.IO) {
            mutations.withLock {
                if (activeOwner != lease.ownerId) {
                    return@withLock SleepTimerStoreWriteResult.Failed(SleepTimerStoreIssue.RETIRED_OWNER)
                }
                if (!SleepTimerStoreCodec.canEncode(next)) {
                    return@withLock SleepTimerStoreWriteResult.Failed(SleepTimerStoreIssue.INPUT_BOUND)
                }
                var changed = false
                try {
                    val committed = dataStore.updateData { raw ->
                        val current = SleepTimerStoreCodec.decode(raw)
                        if (!current.canWrite) throw RejectedWrite(SleepTimerStoreIssue.UNSUPPORTED_SCHEMA)
                        if (current.revision != lease.stored.revision) throw ConflictingWrite(current)
                        changed = current.state != next || !current.canonical
                        if (!changed) raw else {
                            if (current.revision == Long.MAX_VALUE) {
                                throw RejectedWrite(SleepTimerStoreIssue.REVISION_EXHAUSTED)
                            }
                            SleepTimerStoreCodec.encode(current.revision + 1L, next)
                        }
                    }
                    val stored = SleepTimerStoreCodec.decode(committed)
                    SleepTimerStoreWriteResult.Saved(SleepTimerStoreLease(lease.ownerId, stored), changed)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ConflictingWrite) {
                    SleepTimerStoreWriteResult.Conflict(error.current)
                } catch (error: RejectedWrite) {
                    SleepTimerStoreWriteResult.Failed(error.issue)
                } catch (_: IOException) {
                    SleepTimerStoreWriteResult.Failed(SleepTimerStoreIssue.IO)
                }
            }
        }

    suspend fun retire(lease: SleepTimerStoreLease) = withContext(Dispatchers.IO) {
        mutations.withLock {
            if (activeOwner == lease.ownerId) activeOwner = null
        }
    }

    private class ConflictingWrite(val current: StoredSleepTimer) : Exception()
    private class RejectedWrite(val issue: SleepTimerStoreIssue) : Exception()
}
