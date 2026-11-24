package io.github.onlinemo.meloxwatch.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase

/** V5 audio import rows remain byte-for-byte data; V6 adds separate display and operation tables. */
object DisplayMigrations {
    fun applyV5ToV6(db: SupportSQLiteDatabase) {
        db.query(incompleteCommittedReceipt).use { rows ->
            check(rows.moveToFirst() && rows.getLong(0) == 0L) { "Saved audio import receipt needs recovery" }
        }
        db.query(savedCommittedRows).use { rows ->
            while (rows.moveToNext()) validateSavedReceipt(
                text = { column -> if (rows.isNull(column)) null else rows.getString(column) },
                number = { column -> if (rows.isNull(column)) null else rows.getLong(column) },
            )
        }
        for (statement in statements) db.execSQL(statement)
    }
    fun applyV5ToV6(connection: SQLiteConnection) {
        connection.prepare(incompleteCommittedReceipt).use { rows ->
            check(rows.step() && rows.getLong(0) == 0L) { "Saved audio import receipt needs recovery" }
        }
        connection.prepare(savedCommittedRows).use { rows ->
            while (rows.step()) validateSavedReceipt(
                text = { column -> if (rows.isNull(column)) null else rows.getText(column) },
                number = { column -> if (rows.isNull(column)) null else rows.getLong(column) },
            )
        }
        for (statement in statements) connection.prepare(statement).use { it.step() }
    }

    // Decode each original saved completion before creating a replayable generic receipt. SQL
    // null checks alone cannot detect a damaged declaration, digest or serialized metadata.
    private fun validateSavedReceipt(text: (Int) -> String?, number: (Int) -> Long?) {
        val original = LibraryTransferredImportEntity(
            operationId = requireNotNull(text(0)), protocolSchema = requireNotNull(number(1)).toInt(),
            operationKind = requireNotNull(number(2)).toInt(), targetFingerprint = requireNotNull(text(3)),
            peerFingerprint = requireNotNull(text(4)), payloadHash = requireNotNull(text(5)),
            displayName = requireNotNull(text(6)), mediaType = requireNotNull(text(7)), declaredBytes = number(8),
            declaredSha256 = requireNotNull(text(9)), expectedRevision = requireNotNull(number(10)),
            fileToken = requireNotNull(text(11)), phase = requireNotNull(text(12)), actualBytes = requireNotNull(number(13)),
            sha256 = text(14), metadata = text(15), issue = text(16), trackProviderId = text(17), trackId = text(18),
            ownedSourceId = text(19), ownedSpec = text(20), ownedGrant = text(21), ownedCandidate = text(22),
            committedRevision = number(23),
        )
        checkNotNull(original.transferModel().receipt())
        val spec = LibraryCodec.source(requireNotNull(original.ownedSpec))
        LibraryCodec.grant(requireNotNull(original.ownedGrant))
        val candidate = LibraryCodec.candidate(requireNotNull(original.ownedCandidate))
        check(spec.sourceId == original.ownedSourceId && candidate.sourceId == spec.sourceId &&
            candidate.objectId == original.fileToken && candidate.uri == spec.uri &&
            candidate.sizeBytes == original.actualBytes && candidate.providerContentToken == "sha256:${original.sha256}") {
            "Saved audio import object needs recovery"
        }
    }

    private val savedCommittedRows = """
        SELECT operationId, protocolSchema, operationKind, targetFingerprint, peerFingerprint, payloadHash,
            displayName, mediaType, declaredBytes, declaredSha256, expectedRevision, fileToken, phase, actualBytes,
            sha256, metadata, issue, trackProviderId, trackId, ownedSourceId, ownedSpec, ownedGrant, ownedCandidate,
            committedRevision FROM transferred_import_operations WHERE phase = 'COMMITTED' ORDER BY operationId
    """.trimIndent()

    // Never turn a damaged old COMMITTED marker into an apparently successful new receipt.
    private val incompleteCommittedReceipt = """
        SELECT EXISTS (SELECT 1 FROM transferred_import_operations WHERE phase = 'COMMITTED' AND (
            protocolSchema NOT IN (1, 2) OR operationKind != 1 OR trackProviderId IS NULL OR trackProviderId != 'local'
            OR trackId IS NULL OR trackId = '' OR committedRevision IS NULL OR committedRevision <= 0 OR actualBytes <= 0
            OR length(targetFingerprint) != 64 OR targetFingerprint GLOB '*[^0-9a-f]*'
            OR length(peerFingerprint) != 64 OR peerFingerprint GLOB '*[^0-9a-f]*'
            OR length(payloadHash) != 64 OR payloadHash GLOB '*[^0-9a-f]*'))
    """.trimIndent()

    private val statements = listOf(
        """CREATE TABLE IF NOT EXISTS `display_groups` (
            `entityId` TEXT NOT NULL, `kind` TEXT NOT NULL, `groupKey` TEXT NOT NULL,
            `originalName` TEXT, `originalSubtitle` TEXT, `originalSortKey` TEXT NOT NULL,
            `originalSectionKey` TEXT NOT NULL, `originalSearchName` TEXT NOT NULL,
            `editedFrom` INTEGER, PRIMARY KEY(`entityId`)
        )""".trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_display_groups_kind_groupKey` ON `display_groups` (`kind`, `groupKey`)",
        """CREATE TABLE IF NOT EXISTS `display_names` (
            `versionId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `displayName` TEXT NOT NULL,
            `sortKey` TEXT NOT NULL, `sectionKey` TEXT NOT NULL, `searchName` TEXT NOT NULL,
            `validFrom` INTEGER NOT NULL, `validTo` INTEGER, PRIMARY KEY(`versionId`),
            FOREIGN KEY(`groupId`) REFERENCES `display_groups`(`entityId`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )""".trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_display_names_groupId_validFrom` ON `display_names` (`groupId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_display_names_validTo` ON `display_names` (`validTo`)",
        """CREATE TABLE IF NOT EXISTS `display_album_links` (
            `versionId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `groupId` TEXT NOT NULL,
            `validFrom` INTEGER NOT NULL, `validTo` INTEGER, PRIMARY KEY(`versionId`),
            FOREIGN KEY(`trackId`) REFERENCES `local_tracks`(`trackId`) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`groupId`) REFERENCES `display_groups`(`entityId`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )""".trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_display_album_links_trackId_validFrom` ON `display_album_links` (`trackId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_display_album_links_groupId_validFrom` ON `display_album_links` (`groupId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_display_album_links_validTo` ON `display_album_links` (`validTo`)",
        """CREATE TABLE IF NOT EXISTS `display_artist_links` (
            `versionId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `groupId` TEXT NOT NULL,
            `included` INTEGER NOT NULL, `validFrom` INTEGER NOT NULL, `validTo` INTEGER, PRIMARY KEY(`versionId`),
            FOREIGN KEY(`trackId`) REFERENCES `local_tracks`(`trackId`) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`groupId`) REFERENCES `display_groups`(`entityId`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )""".trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_display_artist_links_trackId_groupId_validFrom` ON `display_artist_links` (`trackId`, `groupId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_display_artist_links_groupId_validFrom` ON `display_artist_links` (`groupId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_display_artist_links_validTo` ON `display_artist_links` (`validTo`)",
        """CREATE TABLE IF NOT EXISTS `transfer_room_operations` (
            `operationId` TEXT NOT NULL, `protocolSchema` INTEGER NOT NULL, `operationKind` INTEGER NOT NULL,
            `targetFingerprint` TEXT NOT NULL, `peerFingerprint` TEXT NOT NULL, `payloadHash` TEXT NOT NULL,
            `resultKind` INTEGER, `appliedRevision` INTEGER, `resultId` TEXT, `actualBytes` INTEGER,
            PRIMARY KEY(`operationId`)
        )""".trimIndent(),
        """INSERT INTO `transfer_room_operations` (
            `operationId`, `protocolSchema`, `operationKind`, `targetFingerprint`, `peerFingerprint`, `payloadHash`,
            `resultKind`, `appliedRevision`, `resultId`, `actualBytes`)
            SELECT `operationId`, `protocolSchema`, `operationKind`, `targetFingerprint`, `peerFingerprint`, `payloadHash`,
                CASE WHEN `phase` = 'COMMITTED' THEN 1 ELSE NULL END,
                CASE WHEN `phase` = 'COMMITTED' THEN `committedRevision` ELSE NULL END,
                CASE WHEN `phase` = 'COMMITTED' THEN `trackId` ELSE NULL END,
                CASE WHEN `phase` = 'COMMITTED' THEN `actualBytes` ELSE NULL END
            FROM `transferred_import_operations`
        """.trimIndent(),
        """CREATE TABLE IF NOT EXISTS `transfer_display_previews` (
            `previewId` TEXT NOT NULL, `protocolSchema` INTEGER NOT NULL,
            `targetFingerprint` TEXT NOT NULL, `peerFingerprint` TEXT NOT NULL,
            `sessionId` TEXT NOT NULL, `grantId` TEXT NOT NULL, `grantEpoch` INTEGER NOT NULL,
            `processId` TEXT NOT NULL, `expiresElapsedMs` INTEGER NOT NULL,
            `kind` INTEGER NOT NULL, `entityId` TEXT NOT NULL,
            `libraryRevision` INTEGER NOT NULL, `rulesRevision` INTEGER NOT NULL,
            `currentName` TEXT NOT NULL, `proposedName` TEXT NOT NULL, `currentTrackCount` INTEGER NOT NULL,
            `patchKind` INTEGER NOT NULL, `patchName` TEXT, `patchTrackIds` TEXT NOT NULL,
            `payloadHash` TEXT NOT NULL, PRIMARY KEY(`previewId`)
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_transfer_display_previews_peerFingerprint_sessionId` ON `transfer_display_previews` (`peerFingerprint`, `sessionId`)",
        "CREATE INDEX IF NOT EXISTS `index_transfer_display_previews_processId_expiresElapsedMs` ON `transfer_display_previews` (`processId`, `expiresElapsedMs`)",
    )
}
