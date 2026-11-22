package io.github.onlinemo.meloxwatch.data.database

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * Compatibility transaction cleanup may fail after SQLite has already rolled back a failed write.
 * Keep the block failure for typed classification, and retain cleanup failure as suppressed evidence.
 */
internal suspend fun <T> RoomDatabase.withPreservedTransaction(block: suspend () -> T): T {
    var blockFailure: Throwable? = null
    try {
        return withTransaction {
            try {
                block()
            } catch (error: Throwable) {
                blockFailure = error
                throw error
            }
        }
    } catch (error: Throwable) {
        val original = blockFailure ?: throw error
        if (original !== error) original.addSuppressed(error)
        throw original
    }
}
