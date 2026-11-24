package io.github.onlinemo.meloxwatch.data.playlists

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/** The unaccepted v3 migration adds relationships without enrolling files or changing the active queue. */
object PlaylistMigrations {
    fun applyV2ToV3(database: SupportSQLiteDatabase) {
        applyV2ToV3 { database.execSQL(it) }
    }

    fun applyV2ToV3(connection: SQLiteConnection) {
        applyV2ToV3 { connection.execSQL(it) }
    }

    private fun applyV2ToV3(execute: (String) -> Unit) {
        for (statement in tables) execute(statement.trimIndent())
        for (statement in indices) execute(statement)
    }

    private val tables = listOf(
        """CREATE TABLE `playlist_meta` (
            `id` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `nextPlaylistSequence` INTEGER NOT NULL,
            PRIMARY KEY(`id`))""",
        """CREATE TABLE `playlist_generations` (
            `generationId` TEXT NOT NULL, `playlistId` TEXT NOT NULL, `dataGenerationId` TEXT NOT NULL,
            `operationId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `state` TEXT NOT NULL,
            `revision` INTEGER NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL, `kind` TEXT NOT NULL,
            `createdEpochMs` INTEGER NOT NULL, `updatedEpochMs` INTEGER NOT NULL, `memberCount` INTEGER NOT NULL,
            `validFrom` INTEGER, `validTo` INTEGER, `processId` TEXT NOT NULL,
            `bindingSourceId` TEXT, `bindingNodeId` TEXT, `bindingSubtree` INTEGER,
            PRIMARY KEY(`generationId`))""",
        """CREATE TABLE `playlist_heads` (
            `playlistId` TEXT NOT NULL, `activeGenerationId` TEXT, `revision` INTEGER NOT NULL,
            `createdSequence` INTEGER NOT NULL, `deleted` INTEGER NOT NULL,
            PRIMARY KEY(`playlistId`),
            FOREIGN KEY(`activeGenerationId`) REFERENCES `playlist_generations`(`generationId`)
                ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE `playlist_members` (
            `generationId` TEXT NOT NULL, `memberId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL,
            `referenceKey` TEXT NOT NULL, `providerId` TEXT, `trackId` TEXT, `placeholderId` TEXT, `issue` TEXT,
            `titleHint` TEXT, `artistHint` TEXT, `albumHint` TEXT, `relativePathHint` TEXT,
            `descriptionHint` TEXT, `durationMs` INTEGER,
            PRIMARY KEY(`generationId`, `memberId`),
            FOREIGN KEY(`generationId`) REFERENCES `playlist_generations`(`generationId`)
                ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE `playlist_views` (
            `viewId` TEXT NOT NULL, `kind` TEXT NOT NULL, `generationId` TEXT, `revision` INTEGER NOT NULL,
            `totalCount` INTEGER NOT NULL, `owner` TEXT NOT NULL, `expiresElapsedMs` INTEGER NOT NULL,
            `processId` TEXT NOT NULL, PRIMARY KEY(`viewId`),
            FOREIGN KEY(`generationId`) REFERENCES `playlist_generations`(`generationId`)
                ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE `playlist_receipts` (
            `operationId` TEXT NOT NULL, `playlistId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL,
            `appliedRevision` INTEGER NOT NULL, `result` TEXT NOT NULL, `addedCount` INTEGER NOT NULL,
            `removedCount` INTEGER NOT NULL, `duplicateCount` INTEGER NOT NULL, `sameNameCount` INTEGER NOT NULL,
            PRIMARY KEY(`operationId`))""",
    )

    private val indices = listOf(
        "CREATE INDEX `index_playlist_heads_activeGenerationId` ON `playlist_heads` (`activeGenerationId`)",
        "CREATE UNIQUE INDEX `index_playlist_heads_createdSequence` ON `playlist_heads` (`createdSequence`)",
        "CREATE INDEX `index_playlist_generations_playlistId_validFrom` ON `playlist_generations` (`playlistId`, `validFrom`)",
        "CREATE INDEX `index_playlist_generations_state` ON `playlist_generations` (`state`)",
        "CREATE INDEX `index_playlist_generations_dataGenerationId` ON `playlist_generations` (`dataGenerationId`)",
        "CREATE INDEX `index_playlist_generations_operationId` ON `playlist_generations` (`operationId`)",
        "CREATE INDEX `index_playlist_generations_nameKey` ON `playlist_generations` (`nameKey`)",
        "CREATE UNIQUE INDEX `index_playlist_members_generationId_ordinal` ON `playlist_members` (`generationId`, `ordinal`)",
        "CREATE UNIQUE INDEX `index_playlist_members_generationId_referenceKey` ON `playlist_members` (`generationId`, `referenceKey`)",
        "CREATE INDEX `index_playlist_views_generationId` ON `playlist_views` (`generationId`)",
        "CREATE INDEX `index_playlist_views_kind_revision` ON `playlist_views` (`kind`, `revision`)",
        "CREATE INDEX `index_playlist_views_owner` ON `playlist_views` (`owner`)",
    )
}
