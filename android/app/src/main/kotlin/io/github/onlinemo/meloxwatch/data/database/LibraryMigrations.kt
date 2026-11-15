package io.github.onlinemo.meloxwatch.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The queue schema is untouched. Migration performs no provider access or library enrollment. */
object LibraryMigrations {
    val V1_TO_V2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in tables) db.execSQL(statement.trimIndent())
            for (statement in indices) db.execSQL(statement)
            db.execSQL("INSERT INTO library_meta (id, revision, rulesRevision, nextAddedSequence, nextScanSequence, shortAudioFilter, statisticsEpoch) VALUES (1, 0, 0, 1, 1, 0, 0)")
            db.execSQL("INSERT INTO listening_meta (id, historyEpoch, statisticsEpoch, rulesVersion, nextHistorySequence, historyFloorSequence, totalPlayCount, totalListeningMs) VALUES (1, 0, 0, 1, 1, 1, 0, 0)")
        }
    }

    private val tables = listOf(
        """CREATE TABLE IF NOT EXISTS `library_meta` (
            `id` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `rulesRevision` INTEGER NOT NULL,
            `nextAddedSequence` INTEGER NOT NULL, `nextScanSequence` INTEGER NOT NULL, `shortAudioFilter` INTEGER NOT NULL,
            `statisticsEpoch` INTEGER NOT NULL,
            PRIMARY KEY(`id`))""",
        """CREATE TABLE IF NOT EXISTS `local_sources` (
            `sourceId` TEXT NOT NULL, `spec` TEXT NOT NULL, `grantId` TEXT NOT NULL,
            `revision` INTEGER NOT NULL, `authEpoch` INTEGER NOT NULL, `state` TEXT NOT NULL,
            `activeScanId` TEXT, `latestScanId` TEXT, `absenceScanId` TEXT, `absenceScanSequence` INTEGER, `watermark` TEXT,
            PRIMARY KEY(`sourceId`))""",
        """CREATE TABLE IF NOT EXISTS `local_grants` (
            `grantId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `uri` TEXT NOT NULL, `observation` TEXT NOT NULL,
            `readCapability` TEXT NOT NULL, `acceptedPersistedFlags` INTEGER NOT NULL, `appOwned` INTEGER NOT NULL,
            PRIMARY KEY(`grantId`), FOREIGN KEY(`sourceId`) REFERENCES `local_sources`(`sourceId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `local_source_versions` (
            `sourceId` TEXT NOT NULL, `validFrom` INTEGER NOT NULL, `validTo` INTEGER, `state` TEXT NOT NULL, `spec` TEXT NOT NULL,
            `grantId` TEXT NOT NULL, `absenceScanId` TEXT, `absenceScanSequence` INTEGER,
            PRIMARY KEY(`sourceId`, `validFrom`),
            FOREIGN KEY(`sourceId`) REFERENCES `local_sources`(`sourceId`) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`grantId`) REFERENCES `local_grants`(`grantId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `local_locations` (
            `locationId` TEXT NOT NULL, `identityKey` TEXT NOT NULL, `contentRevision` INTEGER NOT NULL,
            `contentToken` TEXT, `verifiedContentToken` TEXT, `previousVerifiedContentToken` TEXT,
            `sizeBytes` INTEGER, `modifiedEpochMs` INTEGER, `appOwned` INTEGER NOT NULL,
            PRIMARY KEY(`locationId`))""",
        """CREATE TABLE IF NOT EXISTS `local_location_versions` (
            `versionId` TEXT NOT NULL, `locationId` TEXT NOT NULL, `contentRevision` INTEGER NOT NULL,
            `contentToken` TEXT, `verifiedContentToken` TEXT, `sizeBytes` INTEGER, `modifiedEpochMs` INTEGER,
            `validFrom` INTEGER NOT NULL, `validTo` INTEGER,
            PRIMARY KEY(`versionId`), FOREIGN KEY(`locationId`) REFERENCES `local_locations`(`locationId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `local_tracks` (
            `trackId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `firstAddedEpochMs` INTEGER NOT NULL, `firstAddedSequence` INTEGER NOT NULL,
            PRIMARY KEY(`trackId`))""",
        """CREATE TABLE IF NOT EXISTS `local_track_locations` (
            `relationId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `locationId` TEXT NOT NULL, `validFrom` INTEGER NOT NULL, `validTo` INTEGER,
            PRIMARY KEY(`relationId`),
            FOREIGN KEY(`trackId`) REFERENCES `local_tracks`(`trackId`) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`locationId`) REFERENCES `local_locations`(`locationId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `local_track_versions` (
            `versionId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `providerId` TEXT NOT NULL,
            `validFrom` INTEGER NOT NULL, `validTo` INTEGER, `member` INTEGER NOT NULL,
            `firstAddedEpochMs` INTEGER NOT NULL, `firstAddedSequence` INTEGER NOT NULL,
            `filename` TEXT NOT NULL, `displayTitle` TEXT NOT NULL, `title` TEXT, `artist` TEXT, `artists` TEXT NOT NULL,
            `album` TEXT, `albumArtist` TEXT, `durationMs` INTEGER, `discNumber` INTEGER, `trackNumber` INTEGER,
            `compilation` INTEGER NOT NULL, `mimeType` TEXT, `parserVersion` INTEGER NOT NULL, `metadataIssue` TEXT,
            `titleKey` TEXT NOT NULL, `artistKey` TEXT NOT NULL, `albumSortKey` TEXT NOT NULL, `filenameKey` TEXT NOT NULL,
            `albumKey` TEXT NOT NULL, `searchText` TEXT NOT NULL, `titleSection` TEXT NOT NULL, `artistSection` TEXT NOT NULL,
            `albumSection` TEXT NOT NULL, `filenameSection` TEXT NOT NULL, `playCount` INTEGER NOT NULL, `listeningMs` INTEGER NOT NULL,
            `statisticsEpoch` INTEGER NOT NULL,
            PRIMARY KEY(`versionId`), FOREIGN KEY(`trackId`) REFERENCES `local_tracks`(`trackId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `local_artist_values` (
            `versionId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `artistKey` TEXT NOT NULL, `displayName` TEXT,
            PRIMARY KEY(`versionId`, `ordinal`),
            FOREIGN KEY(`versionId`) REFERENCES `local_track_versions`(`versionId`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
        """CREATE TABLE IF NOT EXISTS `local_nodes` (
            `nodeId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `objectId` TEXT NOT NULL, `lastKnownName` TEXT,
            PRIMARY KEY(`nodeId`), FOREIGN KEY(`sourceId`) REFERENCES `local_sources`(`sourceId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `local_node_versions` (
            `versionId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `parentNodeId` TEXT,
            `name` TEXT, `uri` TEXT NOT NULL, `directory` INTEGER NOT NULL, `validFrom` INTEGER NOT NULL, `validTo` INTEGER,
            PRIMARY KEY(`versionId`), FOREIGN KEY(`nodeId`) REFERENCES `local_nodes`(`nodeId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `local_access_aliases` (
            `aliasId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `objectId` TEXT NOT NULL, `locationId` TEXT NOT NULL,
            PRIMARY KEY(`aliasId`),
            FOREIGN KEY(`sourceId`) REFERENCES `local_sources`(`sourceId`) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`locationId`) REFERENCES `local_locations`(`locationId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `local_alias_versions` (
            `versionId` TEXT NOT NULL, `aliasId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `locationId` TEXT NOT NULL,
            `nodeId` TEXT NOT NULL, `uri` TEXT NOT NULL, `filename` TEXT NOT NULL, `readIssue` TEXT,
            `lastSeenSequence` INTEGER NOT NULL, `contentRevision` INTEGER NOT NULL, `providerContentToken` TEXT, `sizeBytes` INTEGER,
            `validFrom` INTEGER NOT NULL, `validTo` INTEGER,
            PRIMARY KEY(`versionId`), FOREIGN KEY(`aliasId`) REFERENCES `local_access_aliases`(`aliasId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `scan_generations` (
            `scanId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `sourceRevision` INTEGER NOT NULL, `authEpoch` INTEGER NOT NULL,
            `rulesRevision` INTEGER NOT NULL, `mode` TEXT NOT NULL, `phase` TEXT NOT NULL, `sequence` INTEGER NOT NULL,
            `startedRevision` INTEGER NOT NULL, `seen` INTEGER NOT NULL, `processed` INTEGER NOT NULL,
            `added` INTEGER NOT NULL, `skipped` INTEGER NOT NULL, `failed` INTEGER NOT NULL,
            `enumerationComplete` INTEGER NOT NULL, `authorizationValid` INTEGER NOT NULL, `rulesUnchanged` INTEGER NOT NULL,
            `sourceUnchanged` INTEGER NOT NULL, `excludedSubtrees` INTEGER NOT NULL,
            `absenceAfterAliasId` TEXT, `absenceDone` INTEGER NOT NULL, `watermark` TEXT, `issue` TEXT,
            PRIMARY KEY(`scanId`))""",
        """CREATE TABLE IF NOT EXISTS `scan_seen` (
            `scanId` TEXT NOT NULL, `objectId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `aliasId` TEXT,
            `metadataProcessed` INTEGER NOT NULL, `metadataPayload` TEXT, `metadataAfterRelationId` TEXT,
            PRIMARY KEY(`scanId`, `objectId`))""",
        """CREATE TABLE IF NOT EXISTS `scan_frontier` (
            `scanId` TEXT NOT NULL, `objectId` TEXT NOT NULL, `candidate` TEXT NOT NULL, `state` TEXT NOT NULL,
            PRIMARY KEY(`scanId`, `objectId`))""",
        """CREATE TABLE IF NOT EXISTS `scan_absence_candidates` (
            `scanId` TEXT NOT NULL, `aliasId` TEXT NOT NULL, PRIMARY KEY(`scanId`, `aliasId`))""",
        """CREATE TABLE IF NOT EXISTS `scan_rules` (
            `versionId` TEXT NOT NULL, `ruleId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `subtree` INTEGER NOT NULL,
            `validFrom` INTEGER NOT NULL, `validTo` INTEGER, PRIMARY KEY(`versionId`))""",
        """CREATE TABLE IF NOT EXISTS `library_views` (
            `viewId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `totalCount` INTEGER NOT NULL,
            `fingerprint` TEXT NOT NULL, `owner` TEXT NOT NULL, `expiresElapsedMs` INTEGER NOT NULL, `sortRuleVersion` INTEGER NOT NULL,
            PRIMARY KEY(`viewId`))""",
        """CREATE TABLE IF NOT EXISTS `import_operations` (
            `operationId` TEXT NOT NULL, `sourceSpec` TEXT NOT NULL, `sourceCandidate` TEXT NOT NULL, `sourceIdentity` TEXT NOT NULL,
            `providerVersion` TEXT, `fileToken` TEXT NOT NULL, `phase` TEXT NOT NULL, `actualBytes` INTEGER NOT NULL,
            `sha256` TEXT, `metadata` TEXT, `issue` TEXT, `trackProviderId` TEXT, `trackId` TEXT,
            `ownedSourceId` TEXT, `ownedSpec` TEXT, `ownedGrant` TEXT, `ownedCandidate` TEXT,
            PRIMARY KEY(`operationId`), FOREIGN KEY(`trackId`) REFERENCES `local_tracks`(`trackId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `listening_meta` (
            `id` INTEGER NOT NULL, `historyEpoch` INTEGER NOT NULL, `statisticsEpoch` INTEGER NOT NULL,
            `rulesVersion` INTEGER NOT NULL, `nextHistorySequence` INTEGER NOT NULL, `historyFloorSequence` INTEGER NOT NULL,
            `totalPlayCount` INTEGER NOT NULL, `totalListeningMs` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        """CREATE TABLE IF NOT EXISTS `listening_rounds` (
            `roundId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `displayName` TEXT,
            `historyEpoch` INTEGER NOT NULL, `statisticsEpoch` INTEGER NOT NULL, `rulesVersion` INTEGER NOT NULL,
            `firstProgressEpochMs` INTEGER NOT NULL, `firstProgressElapsedMs` INTEGER NOT NULL, `firstProgressZoneId` TEXT NOT NULL,
            `sampledEpochMs` INTEGER NOT NULL, `sampledElapsedMs` INTEGER NOT NULL, `sampledZoneId` TEXT NOT NULL,
            `lastProgressEpochMs` INTEGER NOT NULL, `totalListeningMs` INTEGER NOT NULL, `knownDurationMs` INTEGER,
            `playCounted` INTEGER NOT NULL, `countedLocalDate` TEXT, `countedZoneId` TEXT, `historyId` INTEGER,
            PRIMARY KEY(`roundId`))""",
        """CREATE TABLE IF NOT EXISTS `listening_round_segments` (
            `roundId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `localDate` TEXT NOT NULL, `zoneId` TEXT NOT NULL,
            `listeningMs` INTEGER NOT NULL, PRIMARY KEY(`roundId`, `ordinal`),
            FOREIGN KEY(`roundId`) REFERENCES `listening_rounds`(`roundId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `listening_history` (
            `historyEpoch` INTEGER NOT NULL, `historyId` INTEGER NOT NULL, `roundId` TEXT NOT NULL,
            `providerId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `displayName` TEXT,
            `firstProgressEpochMs` INTEGER NOT NULL, `firstProgressZoneId` TEXT NOT NULL, `lastProgressEpochMs` INTEGER NOT NULL,
            `listeningMs` INTEGER NOT NULL, `knownDurationMs` INTEGER, PRIMARY KEY(`historyEpoch`, `historyId`),
            FOREIGN KEY(`roundId`) REFERENCES `listening_rounds`(`roundId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
        """CREATE TABLE IF NOT EXISTS `listening_track_totals` (
            `statisticsEpoch` INTEGER NOT NULL, `providerId` TEXT NOT NULL, `trackId` TEXT NOT NULL,
            `playCount` INTEGER NOT NULL, `listeningMs` INTEGER NOT NULL, `lastProgressEpochMs` INTEGER, `displayName` TEXT,
            PRIMARY KEY(`statisticsEpoch`, `providerId`, `trackId`))""",
        """CREATE TABLE IF NOT EXISTS `listening_day_totals` (
            `statisticsEpoch` INTEGER NOT NULL, `localDate` TEXT NOT NULL, `zoneId` TEXT NOT NULL,
            `playCount` INTEGER NOT NULL, `listeningMs` INTEGER NOT NULL, PRIMARY KEY(`statisticsEpoch`, `localDate`, `zoneId`))""",
    )

    private val indices = listOf(
        "CREATE INDEX IF NOT EXISTS `index_local_sources_activeScanId` ON `local_sources` (`activeScanId`)",
        "CREATE INDEX IF NOT EXISTS `index_local_sources_latestScanId` ON `local_sources` (`latestScanId`)",
        "CREATE INDEX IF NOT EXISTS `index_local_sources_absenceScanId` ON `local_sources` (`absenceScanId`)",
        "CREATE INDEX IF NOT EXISTS `index_local_grants_sourceId` ON `local_grants` (`sourceId`)",
        "CREATE INDEX IF NOT EXISTS `index_local_source_versions_grantId` ON `local_source_versions` (`grantId`)",
        "CREATE INDEX IF NOT EXISTS `index_local_source_versions_validTo` ON `local_source_versions` (`validTo`)",
        "CREATE INDEX IF NOT EXISTS `index_local_source_versions_absenceScanId` ON `local_source_versions` (`absenceScanId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_locations_identityKey` ON `local_locations` (`identityKey`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_location_versions_locationId_validFrom` ON `local_location_versions` (`locationId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_local_location_versions_validTo` ON `local_location_versions` (`validTo`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_tracks_firstAddedSequence` ON `local_tracks` (`firstAddedSequence`)",
        "CREATE INDEX IF NOT EXISTS `index_local_track_locations_trackId_validFrom` ON `local_track_locations` (`trackId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_local_track_locations_locationId_validFrom` ON `local_track_locations` (`locationId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_local_track_locations_locationId_validTo_relationId` ON `local_track_locations` (`locationId`, `validTo`, `relationId`)",
        "CREATE INDEX IF NOT EXISTS `index_local_track_locations_validTo` ON `local_track_locations` (`validTo`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_track_versions_trackId_validFrom` ON `local_track_versions` (`trackId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_local_track_versions_validTo` ON `local_track_versions` (`validTo`)",
        "CREATE INDEX IF NOT EXISTS `index_local_track_versions_titleKey_trackId` ON `local_track_versions` (`titleKey`, `trackId`)",
        "CREATE INDEX IF NOT EXISTS `index_local_track_versions_albumKey_trackId` ON `local_track_versions` (`albumKey`, `trackId`)",
        "CREATE INDEX IF NOT EXISTS `index_local_artist_values_artistKey_versionId` ON `local_artist_values` (`artistKey`, `versionId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_nodes_sourceId_objectId` ON `local_nodes` (`sourceId`, `objectId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_node_versions_nodeId_validFrom` ON `local_node_versions` (`nodeId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_local_node_versions_sourceId_parentNodeId` ON `local_node_versions` (`sourceId`, `parentNodeId`)",
        "CREATE INDEX IF NOT EXISTS `index_local_node_versions_validTo` ON `local_node_versions` (`validTo`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_access_aliases_sourceId_objectId` ON `local_access_aliases` (`sourceId`, `objectId`)",
        "CREATE INDEX IF NOT EXISTS `index_local_access_aliases_locationId` ON `local_access_aliases` (`locationId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_alias_versions_aliasId_validFrom` ON `local_alias_versions` (`aliasId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_local_alias_versions_sourceId_locationId` ON `local_alias_versions` (`sourceId`, `locationId`)",
        "CREATE INDEX IF NOT EXISTS `index_local_alias_versions_validTo` ON `local_alias_versions` (`validTo`)",
        "CREATE INDEX IF NOT EXISTS `index_scan_generations_sourceId` ON `scan_generations` (`sourceId`)",
        "CREATE INDEX IF NOT EXISTS `index_scan_seen_scanId_aliasId` ON `scan_seen` (`scanId`, `aliasId`)",
        "CREATE INDEX IF NOT EXISTS `index_scan_frontier_scanId_state_objectId` ON `scan_frontier` (`scanId`, `state`, `objectId`)",
        "CREATE INDEX IF NOT EXISTS `index_scan_absence_candidates_aliasId` ON `scan_absence_candidates` (`aliasId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_rules_ruleId_validFrom` ON `scan_rules` (`ruleId`, `validFrom`)",
        "CREATE INDEX IF NOT EXISTS `index_scan_rules_sourceId_nodeId` ON `scan_rules` (`sourceId`, `nodeId`)",
        "CREATE INDEX IF NOT EXISTS `index_scan_rules_validTo` ON `scan_rules` (`validTo`)",
        "CREATE INDEX IF NOT EXISTS `index_scan_rules_validTo_ruleId` ON `scan_rules` (`validTo`, `ruleId`)",
        "CREATE INDEX IF NOT EXISTS `index_library_views_revision` ON `library_views` (`revision`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_import_operations_fileToken` ON `import_operations` (`fileToken`)",
        "CREATE INDEX IF NOT EXISTS `index_import_operations_phase_operationId` ON `import_operations` (`phase`, `operationId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_import_operations_ownedSourceId` ON `import_operations` (`ownedSourceId`)",
        "CREATE INDEX IF NOT EXISTS `index_import_operations_trackId` ON `import_operations` (`trackId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_listening_history_roundId` ON `listening_history` (`roundId`)",
        "CREATE INDEX IF NOT EXISTS `index_listening_track_totals_epoch_time_key` ON `listening_track_totals` (`statisticsEpoch`, `listeningMs`, `providerId`, `trackId`)",
    )
}
