package io.github.onlinemo.meloxwatch.data.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException as PlatformSQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.sqlite.SQLiteException

/**
 * Bundled SQLite 2.6.2 JNI emits an integer prefix even when the Android actual is SQLException.
 * Preserve the original evidence while restoring the framework types used by storage contracts.
 */
internal fun SQLiteException.toPlatformSQLiteException(): PlatformSQLiteException {
    if (this is PlatformSQLiteException) return this
    val typed = platformType()
    // Map only direct SQL cleanup failures; their original cause retains the complete failure graph.
    suppressed.forEach { failure ->
        typed.addSuppressed(if (failure is SQLiteException && failure !is PlatformSQLiteException) {
            failure.platformType()
        } else failure)
    }
    return typed
}

private fun SQLiteException.platformType(): PlatformSQLiteException {
    val code = bundledSQLiteErrorCode(message)
    val result = when (code?.and(0xff)) {
        13 -> SQLiteFullException(message)
        19 -> SQLiteConstraintException(message)
        else -> PlatformSQLiteException(message)
    }
    result.initCause(this)
    return result
}

/** Parse the pinned JNI prefix, never SQLite's error prose or a RAISE message supplied by SQL. */
internal fun bundledSQLiteErrorCode(message: String?): Int? {
    val prefix = "Error code: "
    if (message == null || !message.startsWith(prefix)) return null
    val separator = message.indexOf(", message: ", prefix.length)
    val digits = message.substring(prefix.length, if (separator < 0) message.length else separator)
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    return digits.toIntOrNull()
}
