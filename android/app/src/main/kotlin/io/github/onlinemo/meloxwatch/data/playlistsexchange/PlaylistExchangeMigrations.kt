package io.github.onlinemo.meloxwatch.data.playlistsexchange

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/** This extends the still-unaccepted v3, not a fabricated deployed v4. */
object PlaylistExchangeMigrations {
    fun applyV2ToV3(database: SupportSQLiteDatabase) {
        applyV2ToV3 { database.execSQL(it) }
    }

    fun applyV2ToV3(connection: SQLiteConnection) {
        applyV2ToV3 { connection.execSQL(it) }
    }

    private fun applyV2ToV3(execute: (String) -> Unit) {
        execute("""CREATE TABLE IF NOT EXISTS playlist_exchange_previews (
            previewId TEXT NOT NULL, processId TEXT NOT NULL, state TEXT NOT NULL,
            catalogRevision INTEGER NOT NULL, rulesRevision INTEGER NOT NULL, expiresElapsedMs INTEGER NOT NULL,
            memberCount INTEGER NOT NULL, nameHint TEXT, byteCount INTEGER NOT NULL, sha256 TEXT,
            PRIMARY KEY(previewId))""")
        execute("CREATE INDEX IF NOT EXISTS index_playlist_exchange_previews_processId_state ON playlist_exchange_previews(processId, state)")
        execute("""CREATE TABLE IF NOT EXISTS playlist_exchange_rows (
            previewId TEXT NOT NULL, ordinal INTEGER NOT NULL, memberId TEXT NOT NULL, trackId TEXT,
            placeholderId TEXT, referenceIssue TEXT, title TEXT, artist TEXT, album TEXT, relativePath TEXT,
            description TEXT, durationMs INTEGER, importIssue TEXT NOT NULL, availability TEXT NOT NULL,
            PRIMARY KEY(previewId, ordinal), FOREIGN KEY(previewId) REFERENCES playlist_exchange_previews(previewId)
            ON UPDATE NO ACTION ON DELETE CASCADE)""")
        execute("CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_exchange_rows_previewId_memberId ON playlist_exchange_rows(previewId, memberId)")
        execute("CREATE INDEX IF NOT EXISTS index_playlist_exchange_rows_previewId_trackId ON playlist_exchange_rows(previewId, trackId)")
        execute("""CREATE TABLE IF NOT EXISTS playlist_export_receipts (
            operationId TEXT NOT NULL, intentFingerprint TEXT NOT NULL, memberCount INTEGER NOT NULL,
            byteCount INTEGER NOT NULL, sha256 TEXT NOT NULL, destinationLabel TEXT, completedEpochMs INTEGER NOT NULL,
            PRIMARY KEY(operationId))""")
    }
}
