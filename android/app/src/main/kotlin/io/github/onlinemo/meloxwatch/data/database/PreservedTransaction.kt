package io.github.onlinemo.meloxwatch.data.database

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException
import io.github.onlinemo.meloxwatch.data.backup.withBackupMutation

/**
 * Finish the writer lease before returning a result, including rollback of a failed native COMMIT.
 * Keep a block failure when transaction cleanup also fails, with cleanup retained as evidence.
 */
internal suspend fun <T> RoomDatabase.withPreservedTransaction(block: suspend () -> T): T =
    (this as? MeloXDatabase)?.backupMutationBarrier.withBackupMutation {
        withRoomCallerCancellation {
        var blockFailure: Throwable? = null
        try {
            useWriterConnection { writer ->
                writer.immediateTransaction {
                    try {
                        block()
                    } catch (error: Throwable) {
                        blockFailure = error
                        throw error
                    }
                }
            }
        } catch (error: Throwable) {
            val original = blockFailure ?: error
            if (original !== error) {
                // A previously typed block failure keeps identity, so type newly attached SQL cleanup too.
                val cleanup = if (error is SQLiteException) error.toPlatformSQLiteException() else error
                original.addSuppressed(cleanup)
            }
            throw if (original is SQLiteException) original.toPlatformSQLiteException() else original
        }
        }
    }

/** Reuses the coroutine's confined writer; Room's legacy helper cannot inspect a native driver. */
internal suspend fun RoomDatabase.hasCurrentWriterTransaction(): Boolean =
    withRoomCallerCancellation { useWriterConnection { it.inTransaction() } }
