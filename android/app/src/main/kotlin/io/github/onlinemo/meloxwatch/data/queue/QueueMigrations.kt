package io.github.onlinemo.meloxwatch.data.queue

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/** 由全局 2→3 migration 组合；不改旧实例、随机序、位置或 operation receipt。 */
object QueueMigrations {
    fun applyV2ToV3(database: SupportSQLiteDatabase) {
        applyV2ToV3 { database.execSQL(it) }
    }

    fun applyV2ToV3(connection: SQLiteConnection) {
        applyV2ToV3 { connection.execSQL(it) }
    }

    private fun applyV2ToV3(execute: (String) -> Unit) {
        execute("ALTER TABLE queue_generations ADD COLUMN operationKind TEXT NOT NULL DEFAULT 'REPLACE'")
        execute("ALTER TABLE queue_generations ADD COLUMN operationFingerprint TEXT NOT NULL DEFAULT ''")
        execute("ALTER TABLE queue_generations ADD COLUMN expectedCurrentEntryId TEXT DEFAULT NULL")
        execute("ALTER TABLE queue_generations ADD COLUMN orderPolicy TEXT NOT NULL DEFAULT 'PRESERVE_SUPPLIED'")
    }
}
