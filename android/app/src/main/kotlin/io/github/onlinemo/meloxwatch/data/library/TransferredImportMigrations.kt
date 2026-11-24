package io.github.onlinemo.meloxwatch.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Content delta only. Root registers this alongside the accepted lyrics schema successor, then
 * independently reviews the combined migration. This object never selects or replaces a version.
 */
object TransferredImportMigrations {
    fun createTables(db: SupportSQLiteDatabase) { for (statement in statements) db.execSQL(statement) }
    fun createTables(connection: SQLiteConnection) {
        for (statement in statements) connection.prepare(statement).use { it.step() }
    }

    private val statements = listOf(
        """CREATE TABLE IF NOT EXISTS `transferred_import_operations` (
            `operationId` TEXT NOT NULL, `protocolSchema` INTEGER NOT NULL, `operationKind` INTEGER NOT NULL,
            `targetFingerprint` TEXT NOT NULL, `peerFingerprint` TEXT NOT NULL, `payloadHash` TEXT NOT NULL,
            `displayName` TEXT NOT NULL, `mediaType` TEXT NOT NULL, `declaredBytes` INTEGER,
            `declaredSha256` TEXT NOT NULL, `expectedRevision` INTEGER NOT NULL, `fileToken` TEXT NOT NULL,
            `phase` TEXT NOT NULL, `actualBytes` INTEGER NOT NULL, `sha256` TEXT, `metadata` TEXT, `issue` TEXT,
            `trackProviderId` TEXT, `trackId` TEXT, `ownedSourceId` TEXT, `ownedSpec` TEXT, `ownedGrant` TEXT,
            `ownedCandidate` TEXT, `committedRevision` INTEGER,
            PRIMARY KEY(`operationId`),
            FOREIGN KEY(`trackId`) REFERENCES `local_tracks`(`trackId`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )""".trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_transferred_import_operations_fileToken` ON `transferred_import_operations` (`fileToken`)",
        "CREATE INDEX IF NOT EXISTS `index_transferred_import_operations_phase_operationId` ON `transferred_import_operations` (`phase`, `operationId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_transferred_import_operations_ownedSourceId` ON `transferred_import_operations` (`ownedSourceId`)",
        "CREATE INDEX IF NOT EXISTS `index_transferred_import_operations_trackId` ON `transferred_import_operations` (`trackId`)",
    )
}
