package io.github.onlinemo.meloxwatch.data.backup

import androidx.room.PooledConnection
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.library.LibraryCodec
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataAssociationRow
import io.github.onlinemo.meloxwatch.data.metadata.MetadataProvenance
import io.github.onlinemo.meloxwatch.data.metadata.MetadataPurpose
import io.github.onlinemo.meloxwatch.data.metadata.decode
import io.github.onlinemo.meloxwatch.data.metadata.validMetadataAssetId
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.security.MessageDigest
import java.nio.ByteBuffer

/** Named current logical projections, never SELECT * or a copy of grant/operation tables. */
internal class RoomBackupCapture(private val database: MeloXDatabase,
    private val ownedLyricsUri: (String) -> String? = { null }) {
    suspend fun sourceDatabaseVersion(): Int = database.useReaderConnection { reader ->
        reader.usePrepared("PRAGMA user_version") { statement ->
            if (!statement.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            statement.getLong(0).takeIf { it in 6L..BackupLimits.DATABASE_VERSION.toLong() }?.toInt()
                ?: throw BackupException(BackupIssue.UNSUPPORTED_VERSION)
        }
    }
    suspend fun captureMain(stage: SqliteBackupStage, includeResourceReferences: Boolean = true) {
        database.useReaderConnection { reader -> reader.deferredTransaction {
            capture(reader, stage.header.scopes, includeResourceReferences, true, stage::checkActive, stage::record)
        } }
    }

    /** The prospective digest is recorded durably before the caller exits its actual writer COMMIT. */
    suspend fun captureInsideWriter(stage: SqliteBackupStage) {
        database.useWriterConnection { writer ->
            if (!writer.inTransaction()) throw BackupException(BackupIssue.INVALID_INPUT)
            capture(writer, stage.header.scopes, false, true, stage::checkActive, stage::record)
        }
    }

    /** Recovery compares the actual committed state without materializing an unbounded library. */
    suspend fun mainDigest(checkCancelled: () -> Unit, insideWriter: Boolean = false): String {
        suspend fun digest(reader: PooledConnection): String {
            val hash = MessageDigest.getInstance("SHA-256")
            fun add(bytes: ByteArray) {
                hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                hash.update(bytes)
            }
            capture(reader, MAIN_SCOPES, false, false, checkCancelled) { row ->
                add(BackupJsonCodec.record(row, checkCancelled))
            }
            // Epochs and revisions distinguish a logical no-op from an uncertain native COMMIT.
            for (sql in DIGEST_MARKERS) reader.usePrepared(sql) { statement ->
                if (!statement.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                add(sql.toByteArray(Charsets.UTF_8))
                for (column in 0 until statement.getColumnCount() / 2) {
                    val type = statement.getText(column * 2 + 1)
                    val value = when (type) {
                        "null" -> BackupCell.Null
                        "integer" -> BackupCell.Integer(statement.getLong(column * 2))
                        "text" -> BackupCell.Text(statement.getText(column * 2))
                        else -> throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                    }
                    add(BackupJsonCodec.encodeValues(mapOf("value" to value), checkCancelled))
                }
                if (statement.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            }
            var afterLyrics: String? = null
            while (true) {
                val last = reader.usePrepared("SELECT trackId,revision,typeof(revision),substr(primarySourceId,1,129)," +
                    "substr(primaryUri,1,8193) FROM lyrics_track_preferences WHERE (? IS NULL OR trackId>?) ORDER BY trackId LIMIT 100") { statement ->
                    statement.bindNullableText(1, afterLyrics); statement.bindNullableText(2, afterLyrics)
                    var last: String? = null
                    while (statement.step()) {
                        checkCancelled()
                        if (statement.getText(2) != "integer" || statement.getLong(1) < 0L) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                        last = statement.getText(0)
                        add(BackupJsonCodec.encodeValues(mapOf("track" to BackupCell.Text(checkNotNull(last)),
                            "lyricsRevision" to BackupCell.Integer(statement.getLong(1))), checkCancelled))
                        val sourceId = if (statement.isNull(3)) null else statement.getText(3)
                        if (metadataSourceAsset(sourceId) != null) add(BackupJsonCodec.encodeValues(mapOf(
                            "track" to BackupCell.Text(checkNotNull(last)), "metadataPrimarySource" to backupText(sourceId),
                            "metadataPrimaryUri" to backupText(if (statement.isNull(4)) null else statement.getText(4))), checkCancelled))
                    }
                    last
                }
                if (last == null) break
                afterLyrics = last
            }
            captureMetadataIdentities(reader, checkCancelled, ::add)
            return backupHex(hash.digest())
        }
        return if (insideWriter) database.useWriterConnection {
            if (!it.inTransaction()) throw BackupException(BackupIssue.INVALID_INPUT)
            digest(it)
        } else database.useReaderConnection { reader -> reader.deferredTransaction { digest(reader) } }
    }

    /** Native asset UUIDs affect COMMIT identity but never become portable archive fields. */
    private suspend fun captureMetadataIdentities(reader: PooledConnection, checkCancelled: () -> Unit,
        add: (ByteArray) -> Unit) {
        val version = reader.usePrepared("PRAGMA user_version") {
            if (!it.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            it.getLong(0)
        }
        if (version < 9L) return
        for (purpose in MetadataPurpose.entries) {
            var after: String? = null
            while (true) {
                val last = reader.usePrepared("SELECT substr(trackId,1,129),revision,typeof(revision),substr(assetId,1,37),typeof(assetId) " +
                    "FROM local_metadata_associations WHERE purpose=? AND (? IS NULL OR trackId>?) ORDER BY trackId COLLATE BINARY LIMIT 100") {
                    it.bindText(1, purpose.name); it.bindNullableText(2, after); it.bindNullableText(3, after)
                    var last: String? = null
                    while (it.step()) {
                        checkCancelled()
                        if (it.getText(2) != "integer" || it.getLong(1) < 0L || it.getText(4) !in setOf("text", "null")) {
                            throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                        }
                        last = it.getText(0)
                        add(BackupJsonCodec.encodeValues(mapOf("track" to BackupCell.Text(checkNotNull(last)),
                            "purpose" to BackupCell.Text(purpose.name), "metadataRevision" to BackupCell.Integer(it.getLong(1)),
                            "metadataAssetId" to backupText(if (it.isNull(3)) null else it.getText(3))), checkCancelled))
                    }
                    last
                }
                if (last == null) break
                after = last
            }
        }
    }

    private suspend fun capture(reader: PooledConnection, scopes: Set<BackupScope>, includeResourceReferences: Boolean, includePrimaryLinks: Boolean,
        checkCancelled: () -> Unit, accept: (BackupRecord) -> Unit) {
        val schema = reader.usePrepared("PRAGMA user_version") { statement ->
            if (!statement.step()) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
            statement.getLong(0)
        }
        if (schema !in 6L..BackupLimits.DATABASE_VERSION.toLong()) throw BackupException(BackupIssue.UNSUPPORTED_VERSION)
        if (BackupScope.LYRICS in scopes) reader.usePrepared(
            "SELECT COUNT(*) FROM lyrics_track_preferences WHERE typeof(schemaVersion)!='integer' OR schemaVersion!=1",
        ) { statement ->
            if (!statement.step() || statement.getLong(0) != 0L) throw BackupException(BackupIssue.UNSUPPORTED_VERSION)
        }
        if (schema >= 9L && (BackupScope.LYRICS in scopes || BackupScope.LIBRARY in scopes)) reader.usePrepared(
            "SELECT COUNT(*) FROM local_metadata_associations WHERE typeof(trackId)!='text' OR typeof(purpose)!='text' " +
                "OR purpose NOT IN ('LYRICS','ARTWORK') OR typeof(schemaVersion)!='integer' OR schemaVersion!=1",
        ) {
            if (!it.step() || it.getLong(0) != 0L) throw BackupException(BackupIssue.UNSUPPORTED_VERSION)
        }
        for (projection in projections(includeResourceReferences, schema >= 7L, schema >= 8L, schema >= 9L, includePrimaryLinks)) {
            if (projection.kind.scope !in scopes) continue
            var after: String? = null
            while (true) {
                val page = reader.usePrepared(projection.sql) { statement ->
                    statement.bindNullableText(1, after); statement.bindNullableText(2, after)
                    val result = ArrayList<Pair<String, BackupRecord>>()
                    var bytes = 0
                    while (statement.step()) {
                        checkCancelled()
                        val key = cell(statement, 0, Type.TEXT).let { (it as? BackupCell.Text)?.value }
                            ?: throw BackupException(BackupIssue.INVALID_INPUT)
                        val raw = projection.columns.mapIndexed { index, column -> column.name to cell(statement, 2 + index * 2, column.type) }.toMap()
                        val record = projection.transform(key, raw)
                        val encoded = BackupJsonCodec.record(record, checkCancelled)
                        if (result.isNotEmpty() && encoded.size > BackupLimits.CHUNK_BYTES - bytes) break
                        result += key to record
                        bytes += encoded.size
                    }
                    result
                }
                if (page.isEmpty()) break
                for ((_, record) in page) accept(record)
                after = page.last().first
            }
        }
    }

    private enum class Type { TEXT, INTEGER, FLAG }
    private data class Column(val name: String, val expression: String, val type: Type)
    private class Projection(val kind: BackupRecordKind, key: String, from: String, where: String,
        val columns: List<Column>, val transform: (String, Map<String, BackupCell>) -> BackupRecord = { id, values -> BackupRecord(kind, id, values) }) {
        val sql: String = "SELECT $key,typeof($key)," + columns.joinToString(",") { "${it.expression},typeof(${it.expression})" } +
            " FROM $from WHERE ($where) AND (? IS NULL OR ($key) COLLATE BINARY>?) ORDER BY ($key) COLLATE BINARY LIMIT 100"
    }
    private fun t(name: String, expression: String = name) = Column(name, expression, Type.TEXT)
    private fun n(name: String, expression: String = name) = Column(name, expression, Type.INTEGER)
    private fun f(name: String, expression: String = name) = Column(name, expression, Type.FLAG)
    private fun simple(kind: BackupRecordKind, key: String, from: String, where: String = "1", vararg columns: Column) =
        Projection(kind, key, from, where, columns.toList())

    private fun projections(includeResources: Boolean, includeCodecParameters: Boolean, includeCue: Boolean,
        includeMetadata: Boolean, includePrimaryLinks: Boolean): List<Projection> = buildList {
        add(simple(BackupRecordKind.LIBRARY_META, "'library'", "library_meta m", "m.id=1", f("shortAudioFilter", "m.shortAudioFilter")))
        add(Projection(BackupRecordKind.TRACK, "v.trackId", "local_track_versions v", "v.validTo IS NULL", listOf(
            t("providerId", "v.providerId"), f("member", "v.member"), n("firstAddedEpochMs", "v.firstAddedEpochMs"),
            n("firstAddedSequence", "v.firstAddedSequence"), t("filename", "v.filename"), t("title", "v.title"), t("artist", "v.artist"),
            t("artists", "v.artists"), t("album", "v.album"), t("albumArtist", "v.albumArtist"), n("durationMs", "v.durationMs"),
            n("discNumber", "v.discNumber"), n("trackNumber", "v.trackNumber"), f("compilation", "v.compilation"), t("mimeType", "v.mimeType"),
            n("parserVersion", "v.parserVersion"), t("metadataIssue", "v.metadataIssue"),
            t("codecParameters", if (includeCodecParameters) "v.codecParameters" else "NULL"),
            t("cueReference", if (includeCue) "v.cueReference" else "NULL"))) { key, raw ->
            BackupRecord(BackupRecordKind.TRACK, key, raw.toMutableMap().apply {
                put("cueReference", portableCueCell(raw.optionalTextValue("cueReference")))
            })
        })
        add(simple(BackupRecordKind.TRACK_LOCATION, "r.relationId", "local_track_locations r", "r.validTo IS NULL", t("trackId", "r.trackId"), t("locationId", "r.locationId")))
        val ownedToken = "COALESCE((SELECT i.fileToken FROM import_operations i WHERE i.ownedSourceId=s.sourceId AND i.phase='COMMITTED'),(SELECT i.fileToken FROM transferred_import_operations i WHERE i.ownedSourceId=s.sourceId AND i.phase='COMMITTED'))"
        add(Projection(BackupRecordKind.SOURCE_HINT, "s.sourceId", "local_sources s", "1", listOf(t("spec", "s.spec"), t("state", "s.state"), t("ownedTokenHint", ownedToken))) { key, raw ->
            val spec = LibraryCodec.source(raw.textValue("spec"))
            if (spec.sourceId != key) throw BackupException(BackupIssue.INVALID_INPUT)
            BackupRecord(BackupRecordKind.SOURCE_HINT, key, mapOf(
                "kind" to BackupCell.Text(spec.kind.name), "uriHint" to backupText(BackupLogicalSchema.portableUri(spec.uri)),
                "mediaVolume" to BackupCell.Text(spec.mediaVolume), "authorityHint" to backupText(spec.authority),
                "rootObjectIdHint" to backupText(BackupLogicalSchema.portableHint(spec.rootObjectId)), "state" to checkNotNull(raw["state"]),
                "ownedTokenHint" to if (spec.kind == LocalSourceKind.APP_OWNED) checkNotNull(raw["ownedTokenHint"]) else BackupCell.Null,
            ))
        })
        val fullHash = "COALESCE(CASE WHEN l.verifiedContentToken LIKE 'sha256:%' AND length(l.verifiedContentToken)=71 THEN substr(l.verifiedContentToken,8) END," +
            "(SELECT i.sha256 FROM import_operations i JOIN local_track_locations r ON r.trackId=i.trackId AND r.validTo IS NULL WHERE r.locationId=l.locationId AND i.phase='COMMITTED' LIMIT 1)," +
            "(SELECT i.sha256 FROM transferred_import_operations i JOIN local_track_locations r ON r.trackId=i.trackId AND r.validTo IS NULL WHERE r.locationId=l.locationId AND i.phase='COMMITTED' LIMIT 1))"
        add(Projection(BackupRecordKind.LOCATION_HINT, "l.locationId", "local_locations l",
            "EXISTS(SELECT 1 FROM local_track_locations r WHERE r.locationId=l.locationId AND r.validTo IS NULL) OR EXISTS(SELECT 1 FROM local_alias_versions a WHERE a.locationId=l.locationId AND a.validTo IS NULL)",
            listOf(n("sizeBytes", "l.sizeBytes"), n("modifiedEpochMs", "l.modifiedEpochMs"), t("contentSha256", fullHash),
                t("providerContentTokenHint", "COALESCE((SELECT a.providerContentToken FROM local_alias_versions a WHERE a.locationId=l.locationId AND a.validTo IS NULL AND a.providerContentToken IS NOT NULL ORDER BY a.aliasId LIMIT 1),l.contentToken)"))) { key, raw ->
            BackupRecord(BackupRecordKind.LOCATION_HINT, key, raw.toMutableMap().apply {
                put("providerContentTokenHint", backupText(BackupLogicalSchema.portableHint(raw.optionalTextValue("providerContentTokenHint"))))
            })
        })
        add(Projection(BackupRecordKind.NODE_HINT, "v.nodeId", "local_node_versions v JOIN local_nodes n ON n.nodeId=v.nodeId", "v.validTo IS NULL",
            listOf(t("sourceId", "v.sourceId"), t("objectIdHint", "n.objectId"), t("parentNodeId", "v.parentNodeId"), t("name", "v.name"),
                t("uriHint", "v.uri"), f("directory", "v.directory"), t("pathName", "v.pathName"))) { key, raw ->
            BackupRecord(BackupRecordKind.NODE_HINT, key, raw.toMutableMap().apply {
                put("uriHint", backupText(BackupLogicalSchema.portableUri(raw.optionalTextValue("uriHint"))))
                put("objectIdHint", backupText(BackupLogicalSchema.portableHint(raw.optionalTextValue("objectIdHint"))))
                put("pathName", backupText(BackupLogicalSchema.portableRelative(raw.optionalTextValue("pathName"))))
            })
        })
        add(Projection(BackupRecordKind.ALIAS_HINT, "v.aliasId", "local_alias_versions v JOIN local_access_aliases a ON a.aliasId=v.aliasId", "v.validTo IS NULL",
            listOf(t("sourceId", "v.sourceId"), t("objectIdHint", "a.objectId"), t("locationId", "v.locationId"), t("nodeId", "v.nodeId"), t("uriHint", "v.uri"), t("filename", "v.filename"))) { key, raw ->
            BackupRecord(BackupRecordKind.ALIAS_HINT, key, raw.toMutableMap().apply {
                put("uriHint", backupText(BackupLogicalSchema.portableUri(raw.optionalTextValue("uriHint"))))
                put("objectIdHint", backupText(BackupLogicalSchema.portableHint(raw.optionalTextValue("objectIdHint"))))
            })
        })
        for (table in listOf("import_operations", "transferred_import_operations")) add(simple(BackupRecordKind.IMPORT_HINT,
            "i.ownedSourceId", "$table i", "i.phase='COMMITTED' AND i.ownedSourceId IS NOT NULL",
            t("sourceId", "i.ownedSourceId"), t("trackId", "i.trackId"), t("sha256", "i.sha256"), n("bytes", "i.actualBytes"), t("ownedTokenHint", "i.fileToken")))
        add(simple(BackupRecordKind.RULE, "r.ruleId", "scan_rules r", "r.validTo IS NULL", t("sourceId", "r.sourceId"), t("nodeId", "r.nodeId"), f("subtree", "r.subtree")))
        add(simple(BackupRecordKind.DISPLAY_GROUP, "g.entityId", "display_groups g", "1", t("kind", "g.kind"), t("groupKey", "g.groupKey"), t("originalName", "g.originalName"), t("originalSubtitle", "g.originalSubtitle")))
        add(simple(BackupRecordKind.DISPLAY_NAME, "n.groupId", "display_names n", "n.validTo IS NULL", t("displayName", "n.displayName")))
        add(simple(BackupRecordKind.DISPLAY_ALBUM_LINK, "l.trackId", "display_album_links l", "l.validTo IS NULL", t("groupId", "l.groupId")))
        add(Projection(BackupRecordKind.DISPLAY_ARTIST_LINK, "length(l.trackId)||':'||l.trackId||l.groupId", "display_artist_links l", "l.validTo IS NULL",
            listOf(t("trackId", "l.trackId"), t("groupId", "l.groupId"), f("included", "l.included"))) { _, raw ->
            BackupRecord(BackupRecordKind.DISPLAY_ARTIST_LINK, backupStableId(raw.textValue("trackId"), raw.textValue("groupId")), raw)
        })
        for ((headKind, memberKind, favorites) in listOf(Triple(BackupRecordKind.FAVORITES_HEAD, BackupRecordKind.FAVORITES_MEMBER, true),
            Triple(BackupRecordKind.PLAYLIST_HEAD, BackupRecordKind.PLAYLIST_MEMBER, false))) {
            val condition = "h.deleted=0 AND g.state='PUBLISHED' AND h.playlistId" + if (favorites) "='${PlaylistLimits.FAVORITES_ID}'" else "!='${PlaylistLimits.FAVORITES_ID}'"
            add(simple(headKind, "h.playlistId", "playlist_heads h JOIN playlist_generations g ON g.generationId=h.activeGenerationId", condition,
                t("name", "g.name"), t("kind", "g.kind"), n("createdEpochMs", "g.createdEpochMs"), n("updatedEpochMs", "g.updatedEpochMs"),
                n("memberCount", "g.memberCount"), t("bindingSourceId", "g.bindingSourceId"), t("bindingNodeId", "g.bindingNodeId"), f("bindingSubtree", "g.bindingSubtree")))
            add(Projection(memberKind, "length(h.playlistId)||':'||h.playlistId||m.memberId",
                "playlist_heads h JOIN playlist_generations g ON g.generationId=h.activeGenerationId JOIN playlist_members m ON m.generationId=g.dataGenerationId", condition,
                listOf(t("playlistId", "h.playlistId"), t("memberId", "m.memberId"), n("ordinal", "m.ordinal"), t("providerId", "m.providerId"), t("trackId", "m.trackId"),
                    t("placeholderId", "m.placeholderId"), t("issue", "m.issue"), t("titleHint", "m.titleHint"), t("artistHint", "m.artistHint"), t("albumHint", "m.albumHint"),
                    t("relativePathHint", "m.relativePathHint"), t("descriptionHint", "m.descriptionHint"), n("durationMs", "m.durationMs"),
                    t("cueReference", if (includeCue) "m.cueReference" else "NULL"))) { _, raw ->
                BackupRecord(memberKind, backupStableId(raw.textValue("playlistId"), raw.textValue("memberId")), raw.toMutableMap().apply {
                    put("relativePathHint", backupText(BackupLogicalSchema.portableRelative(raw.optionalTextValue("relativePathHint"))))
                    put("cueReference", portableCueCell(raw.optionalTextValue("cueReference")))
                })
            })
        }
        add(simple(BackupRecordKind.QUEUE_META, "'queue'", "queue_head h LEFT JOIN queue_generations g ON g.generationId=h.activeGenerationId", "h.id=1",
            n("totalCount", "COALESCE(g.totalCount,0)"), f("shuffle", "COALESCE(g.shuffle,0)"), t("repeatMode", "COALESCE(g.repeatMode,'OFF')"),
            n("cycleLimit", "g.cycleLimit"), t("currentEntryId", "h.currentEntryId"), n("positionMs", "h.positionMs"), n("completedCycles", "h.completedCycles")))
        add(Projection(BackupRecordKind.QUEUE_ENTRY, "e.queueEntryId", "queue_head h JOIN queue_entries e ON e.generationId=h.activeGenerationId", "h.id=1",
            listOf(t("providerId", "e.providerId"), t("trackId", "e.trackId"), n("originalOrder", "e.originalOrder"), n("playbackOrder", "e.playbackOrder"),
                t("displayName", "e.displayName"), n("durationMs", "e.durationMs"), t("locationIdHint", "e.locationId"), t("uriHint", "e.locationUri"),
                t("cueReference", if (includeCue) "e.cueReference" else "NULL"))) { key, raw ->
            BackupRecord(BackupRecordKind.QUEUE_ENTRY, key, raw.toMutableMap().apply {
                put("cueReference", portableCueCell(raw.optionalTextValue("cueReference")))
                put("uriHint", backupText(BackupLogicalSchema.portableUri(raw.optionalTextValue("uriHint"))))
            })
        })
        add(simple(BackupRecordKind.HISTORY_META, "'history'", "listening_meta m", "m.id=1", n("totalPlayCount", "m.totalPlayCount"), n("totalListeningMs", "m.totalListeningMs")))
        add(Projection(BackupRecordKind.HISTORY_ENTRY, "printf('%020d:',h.historyId)||h.roundId", "listening_history h JOIN listening_meta m ON m.historyEpoch=h.historyEpoch", "m.id=1 AND h.historyId>=m.historyFloorSequence",
            listOf(n("ordinal", "h.historyId"), t("providerId", "h.providerId"), t("trackId", "h.trackId"), t("displayName", "h.displayName"),
                n("firstProgressEpochMs", "h.firstProgressEpochMs"), t("firstProgressZoneId", "h.firstProgressZoneId"), n("lastProgressEpochMs", "h.lastProgressEpochMs"),
                n("listeningMs", "h.listeningMs"), n("knownDurationMs", "h.knownDurationMs"))) { key, raw -> BackupRecord(BackupRecordKind.HISTORY_ENTRY, backupStableId(key), raw) })
        add(Projection(BackupRecordKind.TRACK_TOTAL, "length(t.providerId)||':'||t.providerId||t.trackId", "listening_track_totals t JOIN listening_meta m ON m.statisticsEpoch=t.statisticsEpoch", "m.id=1",
            listOf(t("providerId", "t.providerId"), t("trackId", "t.trackId"), n("playCount", "t.playCount"), n("listeningMs", "t.listeningMs"),
                n("lastProgressEpochMs", "t.lastProgressEpochMs"), t("displayName", "t.displayName"))) { _, raw ->
            BackupRecord(BackupRecordKind.TRACK_TOTAL, backupStableId(raw.textValue("providerId"), raw.textValue("trackId")), raw)
        })
        add(Projection(BackupRecordKind.DAY_TOTAL, "length(d.localDate)||':'||d.localDate||d.zoneId", "listening_day_totals d JOIN listening_meta m ON m.statisticsEpoch=d.statisticsEpoch", "m.id=1",
            listOf(t("localDate", "d.localDate"), t("zoneId", "d.zoneId"), n("playCount", "d.playCount"), n("listeningMs", "d.listeningMs"))) { _, raw ->
            BackupRecord(BackupRecordKind.DAY_TOTAL, backupStableId(raw.textValue("localDate"), raw.textValue("zoneId")), raw)
        })
        val lyricColumns = mutableListOf(n("userDelayMs", "p.userDelayMs"))
        for (prefix in listOf("primary", "translation")) {
            lyricColumns += listOf(t(prefix + "DocumentIdHint", "p.${prefix}DocumentId"), t(prefix + "SourceIdHint", "p.${prefix}SourceId"),
                t(prefix + "UriHint", "p.${prefix}Uri"), t(prefix + "Encoding", "p.${prefix}Encoding"), t(prefix + "Sha256", "p.${prefix}ConfirmedContentSha256"))
        }
        lyricColumns += t("metadataAssetOwner", if (includeMetadata) "substr(m.assetId,1,37)" else "NULL")
        val lyricFrom = "lyrics_track_preferences p" + if (includeMetadata)
            " LEFT JOIN local_metadata_associations m ON m.trackId=p.trackId AND m.purpose='LYRICS'" else ""
        add(Projection(BackupRecordKind.LYRICS_TRACK, "p.trackId", lyricFrom, "p.schemaVersion=1", lyricColumns) { key, raw ->
            BackupRecord(BackupRecordKind.LYRICS_TRACK, key, raw.toMutableMap().apply {
                remove("metadataAssetOwner")
                for (prefix in listOf("primary", "translation")) {
                    val uri = BackupLogicalSchema.portableUri(raw.optionalTextValue(prefix + "UriHint"))
                    val document = BackupLogicalSchema.portableHint(raw.optionalTextValue(prefix + "DocumentIdHint"))
                    val source = BackupLogicalSchema.portableHint(raw.optionalTextValue(prefix + "SourceIdHint"))
                    val hash = raw.optionalTextValue(prefix + "Sha256")
                    val encoding = raw.optionalTextValue(prefix + "Encoding")
                    if (hash == null || encoding == null) {
                        for (field in listOf("DocumentIdHint", "SourceIdHint", "UriHint", "Encoding", "Sha256", "ResourceId")) {
                            put(prefix + field, BackupCell.Null)
                        }
                        continue
                    }
                    // Content survives a nonportable document ID; hints can never restore a grant.
                    val assetOwner = raw.optionalTextValue("metadataAssetOwner")?.takeIf(::validMetadataAssetId)
                    val metadataOwned = metadataSourceAsset(raw.optionalTextValue(prefix + "SourceIdHint")) != null ||
                        includePrimaryLinks && assetOwner?.let { ownedLyricsUri(it) }
                            ?.let { it == raw.optionalTextValue(prefix + "UriHint") } == true
                    put(prefix + "DocumentIdHint", backupText(document?.takeUnless { metadataOwned }))
                    put(prefix + "SourceIdHint", backupText(source?.takeUnless { metadataOwned }))
                    put(prefix + "UriHint", backupText(uri?.takeUnless { metadataOwned }))
                    put(prefix + "ResourceId", if (includeResources) BackupCell.Text(BackupLogicalSchema.resourceId(BackupResourceKind.LYRICS, hash)) else BackupCell.Null)
                }
            })
        })
        if (includeMetadata) for (purpose in MetadataPurpose.entries) {
            val kind = BackupMetadataAssociations.kind(purpose)
            val primary = if (purpose == MetadataPurpose.LYRICS) listOf(
                t("linkedPrimaryUri", "substr(p.primaryUri,1,8193)"), t("linkedPrimarySource", "substr(p.primarySourceId,1,129)"),
                t("linkedPrimarySha256", "substr(p.primaryConfirmedContentSha256,1,65)"), t("linkedPrimaryEncoding", "substr(p.primaryEncoding,1,33)")) else emptyList()
            add(Projection(kind, "substr(m.trackId,1,129)",
                "local_metadata_associations m LEFT JOIN local_track_versions v ON v.trackId=m.trackId AND v.validTo IS NULL" +
                    if (purpose == MetadataPurpose.LYRICS) " LEFT JOIN lyrics_track_preferences p ON p.trackId=m.trackId AND p.schemaVersion=1" else "",
                "m.purpose='${purpose.name}'", listOf(n("schemaVersion", "m.schemaVersion"), n("revision", "m.revision"),
                    t("assetId", "substr(m.assetId,1,37)"), t("sha256", "substr(m.sha256,1,65)"), n("bytes", "m.byteCount"),
                    t("provenance", "substr(m.provenance,1,${MetadataProvenance.PROVENANCE_BYTES + 1})"),
                    t("cueReference", "substr(v.cueReference,1,${CueReferenceCodec.MAX_BYTES + 1})")) + primary) { key, raw ->
                val native = try {
                    LocalMetadataAssociationRow(true, raw.integerValue("schemaVersion"), raw.integerValue("revision"),
                        raw.optionalTextValue("assetId"), raw.optionalTextValue("sha256"), raw.optionalIntegerValue("bytes"),
                        raw.optionalTextValue("provenance")).decode(TrackKey(TrackKey.LOCAL_PROVIDER, key), purpose)
                } catch (failure: Exception) { throw BackupException(BackupIssue.INVALID_INPUT, failure) }
                val linked = includePrimaryLinks && purpose == MetadataPurpose.LYRICS && native.assetId?.let { assetId ->
                    val expected = ownedLyricsUri(assetId)
                    expected != null && raw.optionalTextValue("linkedPrimaryUri") == expected &&
                        raw.optionalTextValue("linkedPrimarySource") == "metadata-v1:$assetId" &&
                        raw.optionalTextValue("linkedPrimarySha256") == native.sha256 && raw.optionalTextValue("linkedPrimaryEncoding") == "UTF8"
                } == true
                BackupMetadataAssociations.record(native, portableCueCell(raw.optionalTextValue("cueReference")), includeResources, linked)
            })
        }
    }

    private fun metadataSourceAsset(sourceId: String?): String? = sourceId?.takeIf { it.startsWith("metadata-v1:") }
        ?.removePrefix("metadata-v1:")?.takeIf(::validMetadataAssetId)

    private fun cell(statement: SQLiteStatement, index: Int, type: Type): BackupCell {
        return when (statement.getText(index + 1)) {
            "null" -> BackupCell.Null
            "text" -> if (type == Type.TEXT) BackupCell.Text(statement.getText(index)) else invalidType()
            "integer" -> when (type) {
                Type.INTEGER -> BackupCell.Integer(statement.getLong(index))
                Type.FLAG -> statement.getLong(index).let { if (it !in 0L..1L) invalidType() else BackupCell.Flag(it == 1L) }
                Type.TEXT -> invalidType()
            }
            else -> invalidType()
        }
    }
    private fun invalidType(): Nothing = throw BackupException(BackupIssue.INVALID_INPUT)
    companion object {
        val MAIN_SCOPES: Set<BackupScope> = setOf(BackupScope.LIBRARY, BackupScope.FAVORITES, BackupScope.PLAYLISTS,
            BackupScope.QUEUE, BackupScope.HISTORY, BackupScope.LYRICS)
        private val DIGEST_MARKERS = listOf(
            "SELECT revision,typeof(revision),rulesRevision,typeof(rulesRevision),nextAddedSequence,typeof(nextAddedSequence),nextScanSequence,typeof(nextScanSequence),statisticsEpoch,typeof(statisticsEpoch) FROM library_meta WHERE id=1",
            "SELECT revision,typeof(revision),nextPlaylistSequence,typeof(nextPlaylistSequence) FROM playlist_meta WHERE id=1",
            "SELECT historyEpoch,typeof(historyEpoch),statisticsEpoch,typeof(statisticsEpoch),rulesVersion,typeof(rulesVersion),nextHistorySequence,typeof(nextHistorySequence),historyFloorSequence,typeof(historyFloorSequence) FROM listening_meta WHERE id=1",
            "SELECT activeGenerationId,typeof(activeGenerationId),queueRevision,typeof(queueRevision),checkpointRevision,typeof(checkpointRevision) FROM queue_head WHERE id=1",
        )
    }
}

internal fun SqliteBackupStage.logicalDigest(scopes: Set<BackupScope>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (kind in BackupRecordKind.entries) {
        if (kind.scope !in scopes) continue
        digest.update(ByteBuffer.allocate(4).putInt(kind.ordinal).array())
        var after: String? = null
        while (true) {
            val page = pageNow(kind, after)
            if (page.isEmpty()) break
            for (record in page) {
                checkActive()
                val encoded = BackupJsonCodec.record(record, ::checkActive)
                digest.update(ByteBuffer.allocate(4).putInt(encoded.size).array()); digest.update(encoded)
            }
            after = page.last().key
        }
    }
    return backupHex(digest.digest())
}
private fun Map<String, BackupCell>.textValue(name: String): String = (this[name] as? BackupCell.Text)?.value ?: throw BackupException(BackupIssue.INVALID_INPUT)
private fun Map<String, BackupCell>.optionalTextValue(name: String): String? = when (val value = this[name]) {
    BackupCell.Null -> null
    is BackupCell.Text -> value.value
    else -> throw BackupException(BackupIssue.INVALID_INPUT)
}
private fun Map<String, BackupCell>.integerValue(name: String): Long = (this[name] as? BackupCell.Integer)?.value
    ?: throw BackupException(BackupIssue.INVALID_INPUT)
private fun Map<String, BackupCell>.optionalIntegerValue(name: String): Long? = when (val value = this[name]) {
    BackupCell.Null -> null
    is BackupCell.Integer -> value.value
    else -> throw BackupException(BackupIssue.INVALID_INPUT)
}
