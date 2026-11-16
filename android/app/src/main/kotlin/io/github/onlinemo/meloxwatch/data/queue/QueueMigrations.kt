package io.github.onlinemo.meloxwatch.data.queue

import androidx.sqlite.db.SupportSQLiteDatabase

/** 由全局 2→3 migration 组合；不改旧实例、随机序、位置或 operation receipt。 */
object QueueMigrations {
    fun applyV2ToV3(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE queue_generations ADD COLUMN operationKind TEXT NOT NULL DEFAULT 'REPLACE'")
        database.execSQL("ALTER TABLE queue_generations ADD COLUMN operationFingerprint TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE queue_generations ADD COLUMN expectedCurrentEntryId TEXT DEFAULT NULL")
        database.execSQL("ALTER TABLE queue_generations ADD COLUMN orderPolicy TEXT NOT NULL DEFAULT 'PRESERVE_SUPPLIED'")
    }
}
