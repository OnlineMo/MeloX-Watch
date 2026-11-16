package io.github.onlinemo.meloxwatch.data.playlistsexchange

import androidx.sqlite.db.SupportSQLiteDatabase

/** This extends the still-unaccepted v3, not a fabricated deployed v4. */
object PlaylistExchangeMigrations {
    fun applyV2ToV3(database: SupportSQLiteDatabase) {
        database.execSQL("""CREATE TABLE IF NOT EXISTS playlist_exchange_previews (
            previewId TEXT NOT NULL, processId TEXT NOT NULL, state TEXT NOT NULL,
            catalogRevision INTEGER NOT NULL, rulesRevision INTEGER NOT NULL, expiresElapsedMs INTEGER NOT NULL,
            memberCount INTEGER NOT NULL, nameHint TEXT, byteCount INTEGER NOT NULL, sha256 TEXT,
            PRIMARY KEY(previewId))""")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_exchange_previews_processId_state ON playlist_exchange_previews(processId, state)")
        database.execSQL("""CREATE TABLE IF NOT EXISTS playlist_exchange_rows (
            previewId TEXT NOT NULL, ordinal INTEGER NOT NULL, memberId TEXT NOT NULL, trackId TEXT,
            placeholderId TEXT, referenceIssue TEXT, title TEXT, artist TEXT, album TEXT, relativePath TEXT,
            description TEXT, durationMs INTEGER, importIssue TEXT NOT NULL, availability TEXT NOT NULL,
            PRIMARY KEY(previewId, ordinal), FOREIGN KEY(previewId) REFERENCES playlist_exchange_previews(previewId)
            ON UPDATE NO ACTION ON DELETE CASCADE)""")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_exchange_rows_previewId_memberId ON playlist_exchange_rows(previewId, memberId)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_exchange_rows_previewId_trackId ON playlist_exchange_rows(previewId, trackId)")
        database.execSQL("""CREATE TABLE IF NOT EXISTS playlist_export_receipts (
            operationId TEXT NOT NULL, intentFingerprint TEXT NOT NULL, memberCount INTEGER NOT NULL,
            byteCount INTEGER NOT NULL, sha256 TEXT NOT NULL, destinationLabel TEXT, completedEpochMs INTEGER NOT NULL,
            PRIMARY KEY(operationId))""")
    }
}
