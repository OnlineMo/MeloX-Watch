package io.github.onlinemo.meloxwatch.data.lyrics.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/** Adds records only; existing audio, grants, library revisions and queue objects are untouched. */
object LyricsMigrations {
    fun applyV3ToV4(db: SupportSQLiteDatabase) {
        applyV3ToV4 { db.execSQL(it) }
    }

    fun applyV3ToV4(connection: SQLiteConnection) {
        applyV3ToV4 { connection.execSQL(it) }
    }

    private fun applyV3ToV4(execute: (String) -> Unit) {
        execute("""
            CREATE TABLE IF NOT EXISTS `lyrics_track_preferences` (
                `trackId` TEXT NOT NULL,
                `schemaVersion` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                `primaryDocumentId` TEXT,
                `primarySourceId` TEXT,
                `primaryUri` TEXT,
                `primaryReadFlags` INTEGER,
                `primaryEncoding` TEXT,
                `primaryConfirmedContentSha256` TEXT,
                `translationDocumentId` TEXT,
                `translationSourceId` TEXT,
                `translationUri` TEXT,
                `translationReadFlags` INTEGER,
                `translationEncoding` TEXT,
                `translationConfirmedContentSha256` TEXT,
                `userDelayMs` INTEGER NOT NULL,
                PRIMARY KEY(`trackId`),
                FOREIGN KEY(`trackId`) REFERENCES `local_tracks`(`trackId`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
    }
}
