package io.github.onlinemo.meloxwatch.data.fonts.store

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSaveResult
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettingsStore
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.data.backup.withBackupMutation
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One admitted operation, no observer/cache/retry loop; the actual file owns both roles' revision. */
class DataStoreLyricsFontSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val backupBarrier: BackupMutationBarrier? = null,
) : LyricsFontSettingsStore {
    private val occupied = AtomicBoolean(false)

    override suspend fun read(): LyricsFontSettings = operation {
        // DataStore 1.1.7 can cache a proposal before atomicMove fails. Identity updateData
        // reads the file under its lock and returns that record without writing or refreshing cache.
        val stored = completedUpdate { it }
        LyricsFontSettingsCodec.decode(stored)
    }

    override suspend fun compareAndSet(
        expectedRevision: Long,
        replacement: LyricsFontSettings,
    ): LyricsFontSaveResult {
        if (expectedRevision < 0L || replacement.revision != expectedRevision) {
            fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_REVISION)
        }
        return operation {
            try {
                val committed = completedUpdate { stored ->
                    val current = LyricsFontSettingsCodec.decode(stored)
                    if (current.revision != expectedRevision) throw ConflictingWrite(current)
                    if (current.revision == Long.MAX_VALUE) fontSettingsFail(LyricsFontSettingsStorageIssue.REVISION_EXHAUSTED)
                    // The frozen Saved API increments exactly once, including an equal replacement.
                    LyricsFontSettingsCodec.encode(replacement.copy(revision = current.revision + 1L))
                }
                LyricsFontSaveResult.Saved(LyricsFontSettingsCodec.decode(committed))
            } catch (conflict: ConflictingWrite) {
                LyricsFontSaveResult.Conflict(conflict.current)
            }
        }
    }

    private suspend fun completedUpdate(transform: (Preferences) -> Preferences): Preferences {
        val caller = currentCoroutineContext()
        // DataStore's Application writer outlives a cancelled ack await. Reads and CAS retain
        // admission until the real attempt exits, then propagate the original caller cancellation.
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

    private suspend fun <T> operation(action: suspend () -> T): T = backupBarrier.withBackupMutation(
        { LyricsFontSettingsStorageException(LyricsFontSettingsStorageIssue.BUSY) },
    ) { operationActual(action) }

    private suspend fun <T> operationActual(action: suspend () -> T): T {
        if (!occupied.compareAndSet(false, true)) fontSettingsFail(LyricsFontSettingsStorageIssue.BUSY)
        try {
            currentCoroutineContext().ensureActive()
            return action()
        } catch (cancelled: CancellationException) {
            // A completed disk commit may survive cancellation. The editor retains its asset until a true read.
            throw cancelled
        } catch (error: LyricsFontSettingsStorageException) { throw error }
        catch (error: CorruptionException) { throw LyricsFontSettingsStorageException(LyricsFontSettingsStorageIssue.CORRUPT_FILE, error) }
        catch (error: IOException) { throw LyricsFontSettingsStorageException(LyricsFontSettingsStorageIssue.IO, error) }
        catch (error: IllegalStateException) { throw LyricsFontSettingsStorageException(LyricsFontSettingsStorageIssue.IO, error) }
        catch (error: SecurityException) { throw LyricsFontSettingsStorageException(LyricsFontSettingsStorageIssue.IO, error) }
        catch (error: OutOfMemoryError) { throw LyricsFontSettingsStorageException(LyricsFontSettingsStorageIssue.LOW_MEMORY, error) }
        finally { occupied.set(false) }
    }

    private class ConflictingWrite(val current: LyricsFontSettings) : Exception()
}
