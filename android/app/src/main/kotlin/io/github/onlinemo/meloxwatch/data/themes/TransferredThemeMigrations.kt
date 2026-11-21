package io.github.onlinemo.meloxwatch.data.themes

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/** 由 V5→V6 迁移注册；两条路径都不改写已有音乐或收据。 */
object TransferredThemeMigrations {
    fun createTables(database: SupportSQLiteDatabase) { statements.forEach(database::execSQL) }
    fun createTables(connection: SQLiteConnection) { statements.forEach(connection::execSQL) }

    private val statements = listOf(
        """
        CREATE TABLE IF NOT EXISTS transferred_theme_candidates (
            operationId TEXT NOT NULL PRIMARY KEY,
            protocolSchema INTEGER NOT NULL,
            targetFingerprint TEXT NOT NULL,
            peerFingerprint TEXT NOT NULL,
            payloadHash TEXT NOT NULL,
            packageSha256 TEXT NOT NULL,
            packageBytes INTEGER NOT NULL,
            manifestSha256 TEXT NOT NULL,
            themeId TEXT NOT NULL,
            displayName TEXT NOT NULL,
            receivedThemeRevision INTEGER NOT NULL,
            state TEXT NOT NULL,
            localApplyOperationId TEXT,
            localPreviewToken TEXT,
            localExpectedRevision INTEGER,
            localReplacementId TEXT,
            localReplacementThemeId TEXT,
            localReplacementManifestSha256 TEXT,
            fontLicenseAcknowledgmentVersion INTEGER,
            appliedInstallationId TEXT,
            appliedRevision INTEGER
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_transferred_theme_candidates_state_operationId ON transferred_theme_candidates(state, operationId)",
    )
}
