package io.github.onlinemo.meloxwatch.data.metadata

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase

internal object LocalMetadataMigrations {
    private val statements = listOf(
        "CREATE TABLE IF NOT EXISTS local_metadata_associations (trackId TEXT NOT NULL, purpose TEXT NOT NULL, " +
            "schemaVersion INTEGER NOT NULL, revision INTEGER NOT NULL, assetId TEXT, sha256 TEXT, byteCount INTEGER, " +
            "provenance TEXT, PRIMARY KEY(trackId, purpose), FOREIGN KEY(trackId) REFERENCES local_tracks(trackId) " +
            "ON UPDATE NO ACTION ON DELETE RESTRICT)",
        "CREATE INDEX IF NOT EXISTS index_local_metadata_associations_assetId_purpose " +
            "ON local_metadata_associations(assetId, purpose)",
        "CREATE INDEX IF NOT EXISTS index_local_metadata_associations_trackId ON local_metadata_associations(trackId)",
    )

    fun applyV8ToV9(database: SupportSQLiteDatabase) { statements.forEach(database::execSQL) }

    fun applyV8ToV9(connection: SQLiteConnection) {
        for (statement in statements) connection.prepare(statement).use { it.step() }
    }
}
