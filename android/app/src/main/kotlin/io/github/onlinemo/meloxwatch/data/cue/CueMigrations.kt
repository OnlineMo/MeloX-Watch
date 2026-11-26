package io.github.onlinemo.meloxwatch.data.cue

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase

/** 可空引用列保持 V7 的整曲、队列、编解码及关系行完整。 */
internal object CueMigrations {
    fun applyV7ToV8(database: SupportSQLiteDatabase) { statements.forEach(database::execSQL) }
    fun applyV7ToV8(connection: SQLiteConnection) {
        for (sql in statements) connection.prepare(sql).use { it.step() }
    }

    private val statements = listOf(
        "ALTER TABLE local_track_versions ADD COLUMN cueReference TEXT DEFAULT NULL",
        "ALTER TABLE queue_entries ADD COLUMN cueReference TEXT DEFAULT NULL",
        "ALTER TABLE playlist_members ADD COLUMN cueReference TEXT DEFAULT NULL",
        "ALTER TABLE playlist_exchange_rows ADD COLUMN cueReference TEXT DEFAULT NULL",
        "CREATE INDEX index_playlist_members_providerId_trackId_generationId_memberId ON playlist_members(providerId, trackId, generationId, memberId)",
        "CREATE INDEX index_queue_entries_generationId_providerId_trackId_queueEntryId ON queue_entries(generationId, providerId, trackId, queueEntryId)",
        """CREATE TABLE local_cue_documents (
            cueId TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, objectId TEXT NOT NULL,
            aliasId TEXT NOT NULL, locationId TEXT NOT NULL, lastGroupRevision INTEGER NOT NULL DEFAULT 0,
            lastIssue TEXT DEFAULT NULL, lastEncoding TEXT DEFAULT NULL, reassociationPending INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY(sourceId) REFERENCES local_sources(sourceId) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(aliasId) REFERENCES local_access_aliases(aliasId) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(locationId) REFERENCES local_locations(locationId) ON UPDATE NO ACTION ON DELETE RESTRICT
        )""".trimIndent(),
        "CREATE UNIQUE INDEX index_local_cue_documents_sourceId_objectId ON local_cue_documents(sourceId, objectId)",
        "CREATE UNIQUE INDEX index_local_cue_documents_aliasId ON local_cue_documents(aliasId)",
        "CREATE INDEX index_local_cue_documents_locationId ON local_cue_documents(locationId)",
        """CREATE TABLE local_cue_group_versions (
            versionId TEXT NOT NULL PRIMARY KEY, cueId TEXT NOT NULL, groupRevision INTEGER NOT NULL,
            signature TEXT NOT NULL, rawContentToken TEXT NOT NULL, segmentCount INTEGER NOT NULL,
            sourceRevision INTEGER NOT NULL, authEpoch INTEGER NOT NULL, grantId TEXT NOT NULL,
            documentContentRevision INTEGER NOT NULL, encoding TEXT NOT NULL, boundaryVersion INTEGER NOT NULL,
            validFrom INTEGER NOT NULL, validTo INTEGER,
            FOREIGN KEY(cueId) REFERENCES local_cue_documents(cueId) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(grantId) REFERENCES local_grants(grantId) ON UPDATE NO ACTION ON DELETE RESTRICT
        )""".trimIndent(),
        "CREATE UNIQUE INDEX index_local_cue_group_versions_cueId_groupRevision ON local_cue_group_versions(cueId, groupRevision)",
        "CREATE UNIQUE INDEX index_local_cue_group_versions_cueId_validFrom ON local_cue_group_versions(cueId, validFrom)",
        "CREATE INDEX index_local_cue_group_versions_grantId ON local_cue_group_versions(grantId)",
        "CREATE INDEX index_local_cue_group_versions_validTo ON local_cue_group_versions(validTo)",
        """CREATE TABLE local_cue_native_segments (
            groupVersionId TEXT NOT NULL, trackNumber INTEGER NOT NULL, trackId TEXT NOT NULL,
            cueReference TEXT NOT NULL, metadata TEXT NOT NULL, filename TEXT NOT NULL, displayTitle TEXT NOT NULL,
            PRIMARY KEY(groupVersionId, trackNumber),
            FOREIGN KEY(groupVersionId) REFERENCES local_cue_group_versions(versionId) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent(),
        "CREATE INDEX index_local_cue_native_segments_trackId_groupVersionId ON local_cue_native_segments(trackId, groupVersionId)",
        """CREATE TABLE local_cue_aliases (
            aliasId TEXT NOT NULL PRIMARY KEY, logicalSourceId TEXT NOT NULL, logicalCueId TEXT NOT NULL,
            lastGroupRevision INTEGER NOT NULL DEFAULT 0, reassociationPending INTEGER NOT NULL DEFAULT 0
        )""".trimIndent(),
        "CREATE UNIQUE INDEX index_local_cue_aliases_logicalSourceId_logicalCueId ON local_cue_aliases(logicalSourceId, logicalCueId)",
        """CREATE TABLE local_cue_alias_versions (
            versionId TEXT NOT NULL PRIMARY KEY, aliasId TEXT NOT NULL, logicalGroupRevision INTEGER NOT NULL,
            nativeGroupVersionId TEXT NOT NULL, nativeCueId TEXT NOT NULL, nativeGroupRevision INTEGER NOT NULL,
            memberCount INTEGER NOT NULL, nativeMemberCount INTEGER NOT NULL, signature TEXT NOT NULL,
            intentSignature TEXT NOT NULL, operationId TEXT NOT NULL, repairedQueueEntries INTEGER NOT NULL,
            validFrom INTEGER NOT NULL, validTo INTEGER,
            FOREIGN KEY(aliasId) REFERENCES local_cue_aliases(aliasId) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(nativeGroupVersionId) REFERENCES local_cue_group_versions(versionId) ON UPDATE NO ACTION ON DELETE RESTRICT
        )""".trimIndent(),
        "CREATE UNIQUE INDEX index_local_cue_alias_versions_aliasId_logicalGroupRevision ON local_cue_alias_versions(aliasId, logicalGroupRevision)",
        "CREATE UNIQUE INDEX index_local_cue_alias_versions_operationId ON local_cue_alias_versions(operationId)",
        "CREATE INDEX index_local_cue_alias_versions_nativeGroupVersionId ON local_cue_alias_versions(nativeGroupVersionId)",
        "CREATE INDEX index_local_cue_alias_versions_validTo ON local_cue_alias_versions(validTo)",
        """CREATE TABLE local_cue_alias_segments (
            aliasVersionId TEXT NOT NULL, trackNumber INTEGER NOT NULL, logicalTrackId TEXT NOT NULL,
            cueReference TEXT NOT NULL, member INTEGER NOT NULL,
            PRIMARY KEY(aliasVersionId, trackNumber),
            FOREIGN KEY(aliasVersionId) REFERENCES local_cue_alias_versions(versionId) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(logicalTrackId) REFERENCES local_tracks(trackId) ON UPDATE NO ACTION ON DELETE RESTRICT
        )""".trimIndent(),
        "CREATE INDEX index_local_cue_alias_segments_logicalTrackId_aliasVersionId ON local_cue_alias_segments(logicalTrackId, aliasVersionId)",
    )
}
