package io.github.onlinemo.meloxwatch.data.library

import androidx.room.RoomRawQuery
import io.github.onlinemo.meloxwatch.model.TrackKey

/** All ordering happens in SQLite over versioned rows. No OFFSET or in-memory library sort. */
internal object LibrarySql {
    fun raw(sql: String, arguments: Array<out Any?> = emptyArray()): RoomRawQuery {
        // Query generation may continue after this call; bindings must remain those of this query.
        val values = arguments.copyOf()
        return RoomRawQuery(sql) { statement ->
            values.forEachIndexed { index, value ->
                val parameter = index + 1
                when (value) {
                    null -> statement.bindNull(parameter)
                    is String -> statement.bindText(parameter, value)
                    is ByteArray -> statement.bindBlob(parameter, value)
                    is Boolean -> statement.bindLong(parameter, if (value) 1L else 0L)
                    is Byte -> statement.bindLong(parameter, value.toLong())
                    is Short -> statement.bindLong(parameter, value.toLong())
                    is Int -> statement.bindLong(parameter, value.toLong())
                    is Long -> statement.bindLong(parameter, value)
                    is Float -> statement.bindDouble(parameter, value.toDouble())
                    is Double -> statement.bindDouble(parameter, value)
                    else -> throw IllegalArgumentException("Unsupported library SQL argument type")
                }
            }
        }
    }

    private fun valid(alias: String, revision: Long) = "$alias.validFrom <= $revision AND ($alias.validTo IS NULL OR $alias.validTo >= $revision)"

    private fun pathSegment(alias: String) = "$alias.pathName != '' AND $alias.pathName NOT IN ('.', '..') " +
        "AND instr($alias.pathName, '/') = 0 AND instr($alias.pathName, char(92)) = 0 AND instr($alias.pathName, char(0)) = 0"

    /** Only indexed enumeration edges below an authorized root contribute to a relative path. */
    private fun relativePaths(revision: Long, sourceFiltered: Boolean = false): String = """
        relative_paths(sourceId, nodeId, relativePath, directory) AS (
            SELECT n.sourceId, n.nodeId, CASE WHEN n.directory = 1 THEN '' ELSE n.pathName END, n.directory
            FROM local_node_versions n
            JOIN local_nodes identity ON identity.nodeId = n.nodeId AND identity.sourceId = n.sourceId
            JOIN local_source_versions sv ON sv.sourceId = n.sourceId AND ${valid("sv", revision)}
            JOIN local_grants g ON g.grantId = sv.grantId
            WHERE n.parentNodeId IS NULL AND ${valid("n", revision)}
                AND identity.objectId = sv.rootObjectId ${if (sourceFiltered) "AND n.sourceId = ?" else ""}
                AND sv.state = 'ACTIVE' AND g.readCapability = 'AVAILABLE'
                AND (n.directory = 1 OR (${pathSegment("n")}))
            UNION ALL
            SELECT n.sourceId, n.nodeId,
                CASE WHEN p.relativePath = '' THEN n.pathName ELSE p.relativePath || '/' || n.pathName END, n.directory
            FROM local_node_versions n JOIN relative_paths p
                ON p.sourceId = n.sourceId AND p.nodeId = n.parentNodeId AND p.directory = 1
            WHERE ${valid("n", revision)} AND ${pathSegment("n")}
        )
    """.trimIndent()

    private fun excluded(source: String, node: String, revision: Long): String = """
        EXISTS (SELECT 1 FROM scan_rules rule WHERE rule.sourceId = $source
            AND ${valid("rule", revision)} AND (rule.nodeId = $node OR (rule.subtree = 1 AND rule.nodeId IN (
                WITH RECURSIVE ancestors(nodeId) AS (
                    SELECT n.parentNodeId FROM local_node_versions n WHERE n.nodeId = $node AND ${valid("n", revision)}
                    UNION
                    SELECT n.parentNodeId FROM local_node_versions n JOIN ancestors a ON n.nodeId = a.nodeId
                        WHERE n.sourceId = $source AND n.parentNodeId IS NOT NULL AND ${valid("n", revision)}
                ) SELECT nodeId FROM ancestors
            ))))
    """.trimIndent()

    /** 全库筛选与已选窗口共用同一艺人覆盖、缺省和名称规则，避免显示语义分叉。 */
    private fun artistCtes(revision: Long, tracks: String): String {
        require(tracks == "scanned_tracks" || tracks == "page_artist_tracks")
        return """artist_overrides AS (
            SELECT link.trackId, groups.groupKey, groups.originalName, groups.originalSortKey,
                groups.originalSectionKey, groups.originalSearchName, link.included
            FROM display_artist_links link JOIN display_groups groups ON groups.entityId = link.groupId
            WHERE groups.kind = 'ARTIST' AND ${valid("link", revision)}
        ), raw_artists AS (
            SELECT t.versionId, t.trackId, artists.artistKey, artists.displayName,
                artists.artistKey AS originalSortKey, NULL AS originalSectionKey,
                '' AS originalSearchName, artists.ordinal
            FROM $tracks t JOIN local_artist_values artists ON artists.versionId = t.versionId
            WHERE artists.artistKey != '4:' AND NOT EXISTS (
                SELECT 1 FROM artist_overrides changed WHERE changed.trackId = t.trackId
                    AND changed.groupKey = artists.artistKey AND changed.included = 0)
            UNION ALL
            SELECT t.versionId, t.trackId, changed.groupKey, changed.originalName, changed.originalSortKey,
                changed.originalSectionKey, changed.originalSearchName, 128
            FROM $tracks t JOIN artist_overrides changed ON changed.trackId = t.trackId
            WHERE changed.included = 1 AND changed.groupKey != '4:' AND NOT EXISTS (
                SELECT 1 FROM local_artist_values original WHERE original.versionId = t.versionId
                    AND original.artistKey = changed.groupKey)
        ), nonempty_artists AS (
            SELECT raw.* FROM raw_artists raw
            UNION ALL
            SELECT t.versionId, t.trackId, '4:', NULL, '4:', 'UNKNOWN', '', 0
            FROM $tracks t WHERE NOT EXISTS (SELECT 1 FROM raw_artists raw WHERE raw.trackId = t.trackId)
        ), display_artist_rows AS MATERIALIZED (
            SELECT raw.versionId, raw.trackId, raw.artistKey, raw.ordinal,
                COALESCE(names.displayName, raw.displayName) AS displayName,
                COALESCE(names.sortKey, raw.originalSortKey) AS sortKey,
                COALESCE(names.sectionKey, groups.originalSectionKey, raw.originalSectionKey,
                    CASE substr(raw.artistKey, 1, 1) WHEN '1' THEN 'DIGITS' WHEN '2' THEN 'HAN'
                        WHEN '3' THEN 'OTHER' WHEN '4' THEN 'UNKNOWN'
                        ELSE upper(char((instr('0123456789abcdef', substr(raw.artistKey, 7, 1)) - 1) * 16
                            + instr('0123456789abcdef', substr(raw.artistKey, 8, 1)) - 1)) END) AS sectionKey,
                COALESCE(names.searchName, groups.originalSearchName, raw.originalSearchName) AS searchName
            FROM nonempty_artists raw
            LEFT JOIN display_groups groups ON groups.kind = 'ARTIST' AND groups.groupKey = raw.artistKey
            LEFT JOIN display_names names ON names.groupId = groups.entityId AND ${valid("names", revision)}
        )
        """.trimIndent()
    }

    /** 艺人参与筛选或排序时，必须在选窗口前解析其显示值。 */
    private fun artistsBeforeWindow(view: LibraryViewToken): Boolean =
        view.sort.field == LibrarySortField.ARTIST || view.query.artistKey != null || view.query.search.isNotBlank()

    /** 只在选窗不依赖艺人时延后显示投影，扫描元数据保持原值。 */
    private fun displayCtes(revision: Long, includeArtists: Boolean = true, selectedTracks: String? = null): String {
        val trackSelection = selectedTracks?.let { " AND ($it)" }.orEmpty()
        val artistColumns = if (includeArtists) """,
                primaryArtist.displayName AS displayArtist, primaryArtist.sortKey AS displayArtistSortKey,
                primaryArtist.sectionKey AS displayArtistSection""" else ""
        val artistJoin = if (includeArtists) """
            LEFT JOIN display_artist_rows primaryArtist ON primaryArtist.trackId = t.trackId
                AND primaryArtist.artistKey = (SELECT artistKey FROM display_artist_rows candidate
                    WHERE candidate.trackId = t.trackId ORDER BY ordinal, artistKey LIMIT 1)""" else ""
        return """
        scanned_tracks AS (
            SELECT * FROM local_track_versions t WHERE ${valid("t", revision)} AND t.member = 1$trackSelection
        ), ${if (includeArtists) artistCtes(revision, "scanned_tracks") + ", " else ""}display_tracks AS (
            SELECT t.*, COALESCE(assigned.groupKey, t.albumKey) AS displayAlbumKey,
                COALESCE(albumName.displayName, CASE WHEN albumLink.versionId IS NULL THEN t.album
                    ELSE albumGroup.originalName END) AS displayAlbum,
                CASE WHEN albumLink.versionId IS NULL THEN t.albumArtist ELSE albumGroup.originalSubtitle END AS displayAlbumSubtitle,
                COALESCE(albumName.sortKey, CASE WHEN albumLink.versionId IS NULL THEN t.albumSortKey
                    ELSE albumGroup.originalSortKey END, '4:') AS displayAlbumSortKey,
                COALESCE(albumName.sectionKey, CASE WHEN albumLink.versionId IS NULL THEN t.albumSection
                    ELSE albumGroup.originalSectionKey END, 'UNKNOWN') AS displayAlbumSection,
                COALESCE(albumName.searchName, albumGroup.originalSearchName, '') AS displayAlbumSearchName$artistColumns
            FROM scanned_tracks t
            LEFT JOIN display_album_links albumLink ON albumLink.trackId = t.trackId AND ${valid("albumLink", revision)}
            LEFT JOIN display_groups assigned ON assigned.entityId = albumLink.groupId AND assigned.kind = 'ALBUM'
            LEFT JOIN display_groups albumGroup ON albumGroup.kind = 'ALBUM'
                AND albumGroup.groupKey = COALESCE(assigned.groupKey, t.albumKey)
            LEFT JOIN display_names albumName ON albumName.groupId = albumGroup.entityId AND ${valid("albumName", revision)}$artistJoin
        )
        """.trimIndent()
    }

    private fun cte(view: LibraryViewToken, includeArtists: Boolean = true,
        selectedKeys: List<TrackKey>? = null, trackCursor: LibraryCursor? = null,
        beforeCursor: Boolean = false, trackCursorLimit: Int = LibraryLimits.PAGE_SIZE): Pair<String, MutableList<Any?>> {
        require(selectedKeys == null || trackCursor == null)
        val revision = view.revision
        val args = mutableListOf<Any?>()
        if (view.query.relativePathKeyword != null && view.query.sourceId != null) args += view.query.sourceId
        val cursorWindowSql = trackCursor?.let {
            checkNotNull(trackCursorWindow(view, it, beforeCursor, trackCursorLimit, args))
        }
        val selectedTracks = when {
            selectedKeys != null -> {
                val predicate = selectedKeys.joinToString(" OR ") { key ->
                    args += key.providerId
                    args += key.trackId
                    "(t.providerId = ? AND t.trackId = ?)"
                }
                if (selectedKeys.isEmpty()) "0" else {
                    // 单列键域可使用 trackId 索引；成对谓词核验来源，全部有效版本仍参与投影。
                    val trackIds = selectedKeys.joinToString(",") { key ->
                        args += key.trackId
                        "?"
                    }
                    "($predicate) AND t.trackId IN ($trackIds)"
                }
            }
            trackCursor != null -> "t.trackId IN (SELECT trackId FROM track_cursor_window)"
            else -> null
        }
        val aliases = mutableListOf(valid("av", revision))
        if (selectedKeys != null || trackCursor != null) {
            // 候选标识保留全部有效位置；授权、排除和最优别名仍在原关系中决定。
            aliases += """av.locationId IN (SELECT relation.locationId FROM local_track_locations relation
                WHERE ${valid("relation", revision)}
                    AND relation.trackId IN (SELECT trackId FROM scanned_tracks))""".trimIndent()
        }
        view.query.sourceId?.let { aliases += "av.sourceId = ?"; args += it }
        view.query.folderNodeId?.let {
            aliases += if (view.query.folderSubtree) """
                av.nodeId IN (WITH RECURSIVE descendants(nodeId, directory) AS (
                    SELECT n.nodeId, n.directory FROM local_node_versions n
                    WHERE n.nodeId = ? AND n.sourceId = av.sourceId AND n.directory = 1 AND ${valid("n", revision)}
                    UNION ALL SELECT n.nodeId, n.directory FROM local_node_versions n
                        JOIN descendants d ON n.parentNodeId = d.nodeId AND d.directory = 1
                    WHERE n.sourceId = av.sourceId AND ${valid("n", revision)}
                ) SELECT nodeId FROM descendants WHERE directory = 0)
            """.trimIndent() else "EXISTS (SELECT 1 FROM local_node_versions folder WHERE folder.nodeId = av.nodeId AND folder.sourceId = av.sourceId AND folder.parentNodeId = ? AND ${valid("folder", revision)})"
            args += it
        }
        view.query.relativePathKeyword?.let {
            // instr is literal and case sensitive: %, _ and backslash cannot become SQL wildcards.
            aliases += "EXISTS (SELECT 1 FROM relative_paths p WHERE p.sourceId = av.sourceId AND p.nodeId = av.nodeId AND instr(p.relativePath, ?) > 0)"
            args += it
        }
        if (!view.query.includeHidden) aliases += "NOT ${excluded("av.sourceId", "av.nodeId", revision)}"
        val filters = mutableListOf(valid("t", revision), "t.member = 1")
        if (!view.query.includeHidden || view.query.sourceId != null || view.query.folderNodeId != null || view.query.relativePathKeyword != null) filters += "a.aliasVersionId IS NOT NULL"
        if (view.query.readableOnly) filters += "a.availabilityRank = 0"
        if (!view.query.includeHidden) filters += "COALESCE(a.availabilityRank, 3) != 5"
        if (!view.query.includeHidden && view.shortAudioFilter) filters += "(t.durationMs IS NULL OR t.durationMs >= ${LibraryLimits.SHORT_AUDIO_MS})"
        view.query.albumKey?.let { filters += "t.displayAlbumKey = ?"; args += it }
        view.query.artistKey?.let { filters += "EXISTS (SELECT 1 FROM display_artist_rows artist WHERE artist.versionId = t.versionId AND artist.artistKey = ?)"; args += it }
        view.query.addedSinceEpochMs?.let { filters += "t.firstAddedEpochMs >= ?"; args += it }
        if (view.query.search.isNotBlank()) {
            filters += "(t.searchText LIKE ? ESCAPE '\\' OR t.displayAlbumSearchName LIKE ? ESCAPE '\\' " +
                "OR EXISTS (SELECT 1 FROM display_artist_rows searched WHERE searched.trackId = t.trackId AND searched.searchName LIKE ? ESCAPE '\\'))"
            val escaped = LibrarySortKeys.normalize(view.query.search).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            repeat(3) { args += "%$escaped%" }
        }
        val (primary, nullRank, section) = sortColumns(view.sort.field)
        // Choose the minimum scoped access through this track's relations, then join its exact version payload.
        // The outer payload join and correlated selector share one materialized scoped-access
        // relation; permission and exclusion resolution must not be rebuilt for each track.
        val sql = """
            WITH RECURSIVE ${if (view.query.relativePathKeyword != null) relativePaths(revision, view.query.sourceId != null) + "," else ""}
            ${cursorWindowSql?.let { "$it,\n" }.orEmpty()}${displayCtes(revision, includeArtists, selectedTracks)}, alias_rows AS MATERIALIZED (
                SELECT av.versionId AS aliasVersionId, av.aliasId, av.sourceId, av.locationId, av.nodeId,
                    lv.versionId AS locationVersionId, sv.validFrom AS sourceVersionFrom,
                    CASE
                        WHEN sv.state = 'REMOVED' THEN 5
                        WHEN sv.state = 'NEEDS_AUTHORIZATION' OR av.readIssue IN ('PERMISSION_DENIED', 'PERMISSION_REVOKED') THEN 2
                        WHEN sv.state = 'UNAVAILABLE' OR av.readIssue IN ('SOURCE_UNAVAILABLE', 'SOURCE_CHANGED') THEN 3
                        WHEN av.readIssue = 'FILE_MISSING' OR (sv.absenceScanId IS NOT NULL
                            AND av.lastSeenSequence < sv.absenceScanSequence
                            AND EXISTS (SELECT 1 FROM scan_absence_candidates absent WHERE absent.scanId = sv.absenceScanId AND absent.aliasId = av.aliasId)) THEN 4
                        WHEN g.readCapability = 'AVAILABLE' THEN 0 ELSE 1 END AS availabilityRank
                FROM local_alias_versions av
                JOIN local_access_aliases aa ON aa.aliasId = av.aliasId
                JOIN local_location_versions lv ON lv.locationId = av.locationId AND ${valid("lv", revision)}
                JOIN local_source_versions sv ON sv.sourceId = av.sourceId AND ${valid("sv", revision)}
                JOIN local_grants g ON g.grantId = sv.grantId
                WHERE ${aliases.joinToString(" AND ")}
            ), base AS (
                SELECT t.*, COALESCE(a.availabilityRank, 3) AS availabilityRank,
                    CASE WHEN t.statisticsEpoch = ${view.statisticsEpoch} THEN t.playCount ELSE 0 END AS effectivePlayCount,
                    CASE WHEN t.statisticsEpoch = ${view.statisticsEpoch} THEN t.listeningMs ELSE 0 END AS effectiveListeningMs,
                    a.aliasId AS selectedAliasId, a.sourceId AS selectedSourceId, a.locationId AS selectedLocationId,
                    a.nodeId AS selectedNodeId, payloadAlias.uri AS selectedUri, payloadLocation.contentRevision AS selectedContentRevision,
                    payloadGrant.uri AS selectedGrantUri, payloadGrant.observation AS selectedGrantObservation,
                    payloadSource.spec AS selectedSpec, payloadAccess.objectId AS selectedObjectId,
                    payloadLocation.contentToken AS selectedProviderContentToken, payloadLocation.verifiedContentToken AS selectedVerifiedContentToken,
                    payloadLocation.sizeBytes AS selectedSizeBytes
                FROM display_tracks t LEFT JOIN alias_rows a ON a.aliasVersionId = (
                    SELECT ar.aliasVersionId FROM local_track_locations relation
                    JOIN alias_rows ar ON ar.locationId = relation.locationId
                    WHERE relation.trackId = t.trackId AND ${valid("relation", revision)}
                    ORDER BY ar.availabilityRank, ar.locationId, ar.aliasId LIMIT 1
                )
                LEFT JOIN local_alias_versions payloadAlias ON payloadAlias.versionId = a.aliasVersionId
                LEFT JOIN local_access_aliases payloadAccess ON payloadAccess.aliasId = a.aliasId
                LEFT JOIN local_location_versions payloadLocation ON payloadLocation.versionId = a.locationVersionId
                LEFT JOIN local_source_versions payloadSource ON payloadSource.sourceId = a.sourceId AND payloadSource.validFrom = a.sourceVersionFrom
                LEFT JOIN local_grants payloadGrant ON payloadGrant.grantId = payloadSource.grantId
                WHERE ${filters.joinToString(" AND ")}
            ), ordered AS (
                SELECT base.*, CASE WHEN availabilityRank = 0 THEN 0 ELSE 1 END AS missingRank,
                    $nullRank AS nullRank, $primary AS sortKey, $section AS sectionKey FROM base
            )
        """.trimIndent()
        return sql to args
    }

    private fun sortColumns(field: LibrarySortField): Triple<String, String, String> = when (field) {
        LibrarySortField.TITLE -> Triple("titleKey", "0", "titleSection")
        LibrarySortField.ARTIST -> Triple("displayArtistSortKey", "CASE WHEN displayArtistSortKey LIKE '4:%' THEN 1 ELSE 0 END", "displayArtistSection")
        LibrarySortField.ALBUM -> Triple("displayAlbumSortKey", "CASE WHEN displayAlbumSortKey LIKE '4:%' THEN 1 ELSE 0 END", "displayAlbumSection")
        LibrarySortField.FILENAME_NATURAL -> Triple("filenameKey", "0", "filenameSection")
        LibrarySortField.DURATION -> Triple("printf('%019d', COALESCE(durationMs, 0))", "CASE WHEN durationMs IS NULL THEN 1 ELSE 0 END", "''")
        LibrarySortField.ADDED -> Triple("printf('%019d', firstAddedSequence)", "0", "''")
        LibrarySortField.ALBUM_TRACK -> Triple("printf('%010d:%010d', COALESCE(discNumber, 0), COALESCE(trackNumber, 0))", "CASE WHEN trackNumber IS NULL THEN 1 ELSE 0 END", "''")
        LibrarySortField.PLAY_COUNT -> Triple("printf('%019d', effectivePlayCount)", "0", "''")
        LibrarySortField.LISTENING_TIME -> Triple("printf('%019d', effectiveListeningMs)", "0", "''")
    }

    /** 显示覆盖不会改变这些排序列；艺人和专辑排序继续使用完整查询。 */
    private fun trackCursorColumns(view: LibraryViewToken): Pair<String, String>? = when (view.sort.field) {
        LibrarySortField.TITLE -> "boundaryTrack.titleKey" to "0"
        LibrarySortField.FILENAME_NATURAL -> "boundaryTrack.filenameKey" to "0"
        LibrarySortField.DURATION -> "printf('%019d', COALESCE(boundaryTrack.durationMs, 0))" to
            "CASE WHEN boundaryTrack.durationMs IS NULL THEN 1 ELSE 0 END"
        LibrarySortField.ADDED -> "printf('%019d', boundaryTrack.firstAddedSequence)" to "0"
        LibrarySortField.ALBUM_TRACK -> "printf('%010d:%010d', COALESCE(boundaryTrack.discNumber, 0), " +
            "COALESCE(boundaryTrack.trackNumber, 0))" to
            "CASE WHEN boundaryTrack.trackNumber IS NULL THEN 1 ELSE 0 END"
        LibrarySortField.PLAY_COUNT -> "printf('%019d', CASE WHEN boundaryTrack.statisticsEpoch = ${view.statisticsEpoch} " +
            "THEN boundaryTrack.playCount ELSE 0 END)" to "0"
        LibrarySortField.LISTENING_TIME -> "printf('%019d', CASE WHEN boundaryTrack.statisticsEpoch = ${view.statisticsEpoch} " +
            "THEN boundaryTrack.listeningMs ELSE 0 END)" to "0"
        LibrarySortField.ARTIST, LibrarySortField.ALBUM -> null
    }

    /**
     * 七种支持排序的原始元组与 ordered 的 nullRank/sortKey/providerId/trackId 逐项相同。
     * 先取原 limit 的版本前窗，再按 trackId 保留全部有效版本供艺人、位置和搜索关系解析。
     */
    private fun trackCursorWindow(view: LibraryViewToken, cursor: LibraryCursor, before: Boolean,
        limit: Int, args: MutableList<Any?>): String? {
        require(limit in 1..LibraryLimits.PAGE_SIZE)
        val (sortKey, nullRank) = trackCursorColumns(view) ?: return null
        val fixed = if (before) "<" else ">"
        val primary = if (view.sort.descending xor before) "<" else ">"
        val fixedOrder = if (before) "DESC" else "ASC"
        val primaryOrder = if (view.sort.descending xor before) "DESC" else "ASC"
        args.addAll(listOf(cursor.nullRank, cursor.nullRank, cursor.sortKey, cursor.sortKey,
            cursor.providerId, cursor.providerId, cursor.trackId))
        args += limit
        return """track_cursor_window AS MATERIALIZED (
            SELECT boundaryTrack.trackId, boundaryTrack.providerId, $nullRank AS nullRank, $sortKey AS sortKey
            FROM local_track_versions boundaryTrack
            WHERE ${valid("boundaryTrack", view.revision)} AND boundaryTrack.member = 1 AND
                ($nullRank $fixed ? OR ($nullRank = ? AND
                    ($sortKey $primary ? OR ($sortKey = ? AND
                        (boundaryTrack.providerId $fixed ? OR
                            (boundaryTrack.providerId = ? AND boundaryTrack.trackId $fixed ?))))))
            ORDER BY nullRank $fixedOrder, sortKey $primaryOrder, providerId $fixedOrder, trackId $fixedOrder
            LIMIT ?
        )""".trimIndent()
    }

    /**
     * 所有有效版本仍参与投影，但返回行不能越过 raw 窗的最远元组。
     * 完整四分量比较包含同元组的全部版本，避免同标识远端版本填满假前缀。
     */
    private fun inTrackCursorWindow(view: LibraryViewToken, before: Boolean): String {
        val fixed = if (before) ">" else "<"
        val primary = if (view.sort.descending xor before) ">" else "<"
        val inclusive = if (before) ">=" else "<="
        return """EXISTS (SELECT 1 FROM track_cursor_window edge WHERE
            (ordered.nullRank $fixed edge.nullRank OR (ordered.nullRank = edge.nullRank AND
                (ordered.sortKey $primary edge.sortKey OR (ordered.sortKey = edge.sortKey AND
                    (ordered.providerId $fixed edge.providerId OR
                        (ordered.providerId = edge.providerId AND ordered.trackId $inclusive edge.trackId)))))))""".trimIndent()
    }

    private fun ordering(view: LibraryViewToken, reverse: Boolean = false): String {
        val fixed = if (reverse) "DESC" else "ASC"
        val primary = if (view.sort.descending xor reverse) "DESC" else "ASC"
        return "missingRank $fixed, nullRank $fixed, sortKey $primary, providerId $fixed, trackId $fixed"
    }

    private fun boundary(view: LibraryViewToken, cursor: LibraryCursor, before: Boolean, args: MutableList<Any?>): String {
        val fixed = if (before) "<" else ">"
        val primary = if (view.sort.descending xor before) "<" else ">"
        args.addAll(listOf(cursor.missingRank, cursor.missingRank, cursor.nullRank, cursor.nullRank,
            cursor.sortKey, cursor.sortKey, cursor.providerId, cursor.providerId, cursor.trackId))
        return """(missingRank $fixed ? OR (missingRank = ? AND (nullRank $fixed ? OR (nullRank = ? AND
            (sortKey $primary ? OR (sortKey = ? AND (providerId $fixed ? OR (providerId = ? AND trackId $fixed ?))))))))"""
    }

    fun count(view: LibraryViewToken): RoomRawQuery {
        val (sql, args) = cte(view)
        return raw("$sql SELECT COUNT(*) FROM ordered", args.toTypedArray())
    }
    fun availableCount(view: LibraryViewToken): RoomRawQuery {
        val (sql, args) = cte(view)
        return raw("$sql SELECT COUNT(*) FROM ordered WHERE availabilityRank = 0", args.toTypedArray())
    }
    fun playlistOverlap(view: LibraryViewToken, dataGenerationId: String): RoomRawQuery {
        val (sql, args) = cte(view)
        args += dataGenerationId
        return raw("""$sql SELECT COUNT(*) FROM ordered t WHERE EXISTS (
            SELECT 1 FROM playlist_members member WHERE member.generationId = ?
                AND member.providerId = t.providerId AND member.trackId = t.trackId)
        """.trimIndent(), args.toTypedArray())
    }

    fun rows(view: LibraryViewToken, cursor: LibraryCursor?, limit: Int, before: Boolean = false): RoomRawQuery {
        val full = artistsBeforeWindow(view)
        val (sql, args) = cte(view, includeArtists = full)
        val where = if (cursor == null) "" else "WHERE ${boundary(view, cursor, before, args)}"
        if (!full) return projectedWindow(view, sql, args, where, "ORDER BY ${ordering(view, before)}", limit)
        args += limit
        return raw("$sql SELECT * FROM ordered $where ORDER BY ${ordering(view, before)} LIMIT ?", args.toTypedArray())
    }
    /** 结果是原查询的子集；跨可用性分区的完整前缀由仓库核验。 */
    fun rowsForTrackCursor(view: LibraryViewToken, cursor: LibraryCursor, limit: Int,
        before: Boolean = false): RoomRawQuery? {
        require(limit in 1..LibraryLimits.PAGE_SIZE)
        if (trackCursorColumns(view) == null) return null
        val full = artistsBeforeWindow(view)
        val (sql, args) = cte(view, includeArtists = full, trackCursor = cursor,
            beforeCursor = before, trackCursorLimit = limit)
        val where = "WHERE ${boundary(view, cursor, before, args)} AND ${inTrackCursorWindow(view, before)}"
        if (!full) return projectedWindow(view, sql, args, where, "ORDER BY ${ordering(view, before)}", limit)
        args += limit
        return raw("$sql SELECT * FROM ordered $where ORDER BY ${ordering(view, before)} LIMIT ?", args.toTypedArray())
    }

    fun row(view: LibraryViewToken, providerId: String, trackId: String): RoomRawQuery {
        val full = artistsBeforeWindow(view)
        val selectedKeys = if (providerId.isNotBlank() && trackId.isNotBlank()) {
            listOf(TrackKey(providerId, trackId))
        } else null
        val (sql, args) = cte(view, includeArtists = full, selectedKeys = selectedKeys)
        args += providerId; args += trackId
        if (!full) return projectedWindow(view, sql, args, "WHERE providerId = ? AND trackId = ?", "", 1)
        return raw("$sql SELECT * FROM ordered WHERE providerId = ? AND trackId = ? LIMIT 1", args.toTypedArray())
    }
    fun rowsForKeys(view: LibraryViewToken, keys: List<TrackKey>): RoomRawQuery {
        require(keys.size <= LibraryLimits.PAGE_SIZE)
        val full = artistsBeforeWindow(view)
        val (sql, args) = cte(view, includeArtists = full, selectedKeys = keys)
        val predicate = keys.joinToString(" OR ") {
            args += it.providerId; args += it.trackId
            "(providerId = ? AND trackId = ?)"
        }.ifEmpty { "0" }
        if (!full) return projectedWindow(view, sql, args, "WHERE ($predicate)", "", keys.size + 1)
        args += keys.size + 1
        return raw("$sql SELECT * FROM ordered WHERE ($predicate) LIMIT ?", args.toTypedArray())
    }

    /**
     * 权限、版本、别名、过滤和排序先确定窗口，显示艺人随后投影。
     * 主艺人关系按 trackId 关联，窗口中的每个标识保留全部有效版本。
     */
    private fun projectedWindow(view: LibraryViewToken, sql: String, args: MutableList<Any?>,
        where: String, order: String, limit: Int): RoomRawQuery {
        check(!artistsBeforeWindow(view))
        args += limit
        args += limit
        return raw("""$sql, page_candidates AS MATERIALIZED (
            SELECT * FROM ordered $where $order LIMIT ?
        ), page_artist_tracks AS (
            SELECT t.versionId, t.trackId FROM scanned_tracks t
            WHERE t.trackId IN (SELECT trackId FROM page_candidates)
        ), ${artistCtes(view.revision, "page_artist_tracks")}
        SELECT page.*, primaryArtist.displayName AS displayArtist, primaryArtist.sortKey AS displayArtistSortKey,
            primaryArtist.sectionKey AS displayArtistSection
        FROM page_candidates page
        LEFT JOIN display_artist_rows primaryArtist ON primaryArtist.trackId = page.trackId
            AND primaryArtist.artistKey = (SELECT artistKey FROM display_artist_rows candidate
                WHERE candidate.trackId = page.trackId ORDER BY ordinal, artistKey LIMIT 1)
        $order LIMIT ?
        """.trimIndent(), args.toTypedArray())
    }
    fun countBefore(view: LibraryViewToken, cursor: LibraryCursor): RoomRawQuery {
        val (sql, args) = cte(view)
        return raw("$sql SELECT COUNT(*) FROM ordered WHERE ${boundary(view, cursor, true, args)}", args.toTypedArray())
    }
    fun hasBeyond(view: LibraryViewToken, cursor: LibraryCursor, before: Boolean): RoomRawQuery {
        // 存在性仍由真实别名、权限和游标边界证明；未参与选择的艺人关系可省去。
        val (sql, args) = cte(view, includeArtists = artistsBeforeWindow(view))
        return raw("$sql SELECT EXISTS (SELECT 1 FROM ordered WHERE ${boundary(view, cursor, before, args)} LIMIT 1)", args.toTypedArray())
    }
    /** 候选存在即可证明原结果存在；候选为空不能代替原存在性查询。 */
    fun hasBeyondForTrackCursor(view: LibraryViewToken, cursor: LibraryCursor, before: Boolean): RoomRawQuery? {
        if (trackCursorColumns(view) == null) return null
        val (sql, args) = cte(view, includeArtists = artistsBeforeWindow(view),
            trackCursor = cursor, beforeCursor = before)
        return raw("$sql SELECT EXISTS (SELECT 1 FROM ordered WHERE ${boundary(view, cursor, before, args)} " +
            "AND ${inTrackCursorWindow(view, before)} LIMIT 1)", args.toTypedArray())
    }

    fun groups(view: LibraryViewToken, kind: LibraryGroupKind, after: String?, limit: Int): RoomRawQuery {
        val (sql, args) = cte(view)
        val group = groupSelection(view, kind, args)
        val where = if (after == null) "" else "WHERE groupKey > ?".also { args += after }
        args += limit
        return raw("$sql SELECT * FROM ($group) $where ORDER BY groupKey LIMIT ?", args.toTypedArray())
    }

    fun groupCount(view: LibraryViewToken, kind: LibraryGroupKind): RoomRawQuery {
        val (sql, args) = cte(view)
        val group = groupSelection(view, kind, args)
        return raw("$sql SELECT COUNT(*) FROM ($group)", args.toTypedArray())
    }

    fun group(view: LibraryViewToken, kind: LibraryGroupKind, groupKey: String): RoomRawQuery {
        val (sql, args) = cte(view)
        val group = groupSelection(view, kind, args)
        args += groupKey
        return raw("$sql SELECT * FROM ($group) WHERE groupKey = ? LIMIT 2", args.toTypedArray())
    }

    private fun groupSelection(view: LibraryViewToken, kind: LibraryGroupKind, args: MutableList<Any?>): String {
        val populated = when (kind) {
            LibraryGroupKind.ALBUM -> "SELECT displayAlbumKey AS groupKey, MIN(displayAlbum) AS title, MIN(displayAlbumSubtitle) AS subtitle, COUNT(*) AS trackCount FROM ordered GROUP BY displayAlbumKey"
            LibraryGroupKind.ARTIST -> "SELECT artists.artistKey AS groupKey, MIN(artists.displayName) AS title, NULL AS subtitle, COUNT(DISTINCT ordered.trackId) AS trackCount FROM ordered JOIN display_artist_rows artists ON artists.versionId = ordered.versionId GROUP BY artists.artistKey"
        }
        if (view.query.sourceId != null || view.query.folderNodeId != null || view.query.relativePathKeyword != null ||
            view.query.albumKey != null || view.query.artistKey != null || view.query.addedSinceEpochMs != null ||
            view.query.readableOnly) return populated
        val source = if (kind == LibraryGroupKind.ALBUM) "display_album_links" else "display_artist_links"
        val search = if (view.query.search.isBlank()) "" else {
            args += "%" + LibrarySortKeys.normalize(view.query.search).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            "AND COALESCE(names.searchName, groups.originalSearchName) LIKE ? ESCAPE '\\'"
        }
        // An explicitly edited empty group stays manageable; merely reading a group never creates it.
        return """$populated UNION ALL
            SELECT groups.groupKey, COALESCE(names.displayName, groups.originalName), groups.originalSubtitle, 0
            FROM display_groups groups LEFT JOIN display_names names ON names.groupId = groups.entityId AND ${valid("names", view.revision)}
            WHERE groups.kind = '${kind.name}' AND ((groups.editedFrom IS NOT NULL AND groups.editedFrom <= ${view.revision})
                OR names.versionId IS NOT NULL OR EXISTS (
                SELECT 1 FROM $source changed WHERE changed.groupId = groups.entityId AND ${valid("changed", view.revision)}))
                AND NOT EXISTS (SELECT 1 FROM ($populated) populated WHERE populated.groupKey = groups.groupKey)
                $search
        """.trimIndent()
    }
    fun sections(view: LibraryViewToken): RoomRawQuery {
        val (sql, args) = cte(view)
        return raw("$sql SELECT sectionKey, COUNT(*) AS trackCount FROM ordered WHERE sectionKey != '' GROUP BY sectionKey ORDER BY sectionKey", args.toTypedArray())
    }
    fun firstInSection(view: LibraryViewToken, section: String): RoomRawQuery {
        val (sql, args) = cte(view)
        args += section
        return raw("$sql SELECT * FROM ordered WHERE sectionKey = ? ORDER BY ${ordering(view)} LIMIT 1", args.toTypedArray())
    }
    fun folders(view: LibraryViewToken, parent: String?, after: String?, limit: Int, nodeId: String? = null): RoomRawQuery {
        val args = mutableListOf<Any?>()
        val where = mutableListOf(valid("n", view.revision), "n.directory = 1", "sv.state != 'REMOVED'")
        view.query.sourceId?.let { where += "n.sourceId = ?"; args += it }
        if (nodeId != null) { where += "n.nodeId = ?"; args += nodeId }
        else if (parent == null) where += "n.parentNodeId IS NULL"
        else { where += "n.parentNodeId = ?"; args += parent }
        if (after != null) { where += "n.nodeId > ?"; args += after }
        if (!view.query.includeHidden) where += "NOT ${excluded("n.sourceId", "n.nodeId", view.revision)}"
        args += limit
        return raw("SELECT n.* FROM local_node_versions n JOIN local_source_versions sv ON sv.sourceId = n.sourceId AND ${valid("sv", view.revision)} WHERE ${where.joinToString(" AND ")} ORDER BY n.nodeId LIMIT ?", args.toTypedArray())
    }
    fun isExcluded(sourceId: String, nodeId: String?, parentNodeId: String?, revision: Long): RoomRawQuery {
        val start = nodeId ?: parentNodeId ?: return raw("SELECT 0")
        return raw("""
            WITH RECURSIVE ancestors(nodeId) AS (
                SELECT ? UNION SELECT n.parentNodeId FROM local_node_versions n JOIN ancestors a ON n.nodeId = a.nodeId
                WHERE n.parentNodeId IS NOT NULL AND ${valid("n", revision)}
            ) SELECT EXISTS (SELECT 1 FROM scan_rules r WHERE r.sourceId = ? AND ${valid("r", revision)}
                AND (r.nodeId = ? OR (r.subtree = 1 AND r.nodeId IN (SELECT nodeId FROM ancestors))))
        """.trimIndent(), arrayOf(start, sourceId, nodeId.orEmpty()))
    }

    fun relativePath(revision: Long, sourceId: String, nodeId: String): RoomRawQuery = raw(
        "WITH RECURSIVE ${relativePaths(revision, true)} SELECT relativePath FROM relative_paths WHERE sourceId = ? AND nodeId = ? LIMIT 1",
        arrayOf(sourceId, sourceId, nodeId),
    )

    fun relativePathMatches(revision: Long, sourceId: String, path: String, limit: Int): RoomRawQuery = raw(
        """WITH RECURSIVE ${relativePaths(revision, true)}
            SELECT DISTINCT t.providerId, t.trackId FROM relative_paths p
            JOIN local_alias_versions av ON av.sourceId = p.sourceId AND av.nodeId = p.nodeId AND ${valid("av", revision)}
            JOIN local_track_locations relation ON relation.locationId = av.locationId AND ${valid("relation", revision)}
            JOIN local_track_versions t ON t.trackId = relation.trackId AND ${valid("t", revision)}
            JOIN local_source_versions sv ON sv.sourceId = av.sourceId AND ${valid("sv", revision)}
            WHERE p.relativePath = ? AND p.directory = 0 AND t.member = 1
                AND (av.readIssue IS NULL OR av.readIssue = 'METADATA_UNREADABLE')
                AND NOT ${excluded("av.sourceId", "av.nodeId", revision)}
                AND NOT (sv.absenceScanId IS NOT NULL AND av.lastSeenSequence < sv.absenceScanSequence
                    AND EXISTS (SELECT 1 FROM scan_absence_candidates absent WHERE absent.scanId = sv.absenceScanId AND absent.aliasId = av.aliasId))
            ORDER BY t.providerId, t.trackId LIMIT ?
        """.trimIndent(), arrayOf<Any?>(sourceId, path, limit),
    )
    /** Walk only the chosen indexed root and the already validated literal components, at most 16 edges. */
    fun scopedRelativePathMatches(
        revision: Long, sourceId: String, rootNodeId: String, components: List<String>, limit: Int,
    ): RoomRawQuery {
        require(components.size in 1..16 && limit in 1..LibraryLimits.PAGE_SIZE)
        val names = components.indices.joinToString(" ") { "WHEN $it THEN ?" }
        val args = mutableListOf<Any?>(rootNodeId, sourceId, sourceId)
        args.addAll(components)
        args += limit
        return raw("""
            WITH RECURSIVE path_matches(nodeId, depth, directory) AS (
                SELECT n.nodeId, 0, n.directory FROM local_node_versions n
                    WHERE n.nodeId = ? AND n.sourceId = ? AND n.directory = 1 AND ${valid("n", revision)}
                UNION ALL
                SELECT n.nodeId, p.depth + 1, n.directory FROM local_node_versions n JOIN path_matches p
                    ON n.parentNodeId = p.nodeId AND p.directory = 1
                    WHERE n.sourceId = ? AND ${valid("n", revision)} AND p.depth < ${components.size}
                        AND n.pathName = CASE p.depth $names END
            )
            SELECT DISTINCT t.providerId, t.trackId FROM path_matches p
                JOIN local_alias_versions av ON av.nodeId = p.nodeId AND ${valid("av", revision)}
                JOIN local_track_locations relation ON relation.locationId = av.locationId AND ${valid("relation", revision)}
                JOIN local_track_versions t ON t.trackId = relation.trackId AND ${valid("t", revision)}
                WHERE p.depth = ${components.size} AND p.directory = 0 AND t.member = 1
                ORDER BY t.providerId, t.trackId LIMIT ?
        """.trimIndent(), args.toTypedArray())
    }
    fun absentAliases(sourceId: String, scanId: String, after: String?, limit: Int): RoomRawQuery {
        val args = mutableListOf<Any?>(sourceId, scanId)
        val where = if (after == null) "" else "AND a.aliasId > ?".also { args += after }
        args += limit
        return raw("SELECT a.* FROM local_access_aliases a WHERE a.sourceId = ? AND NOT EXISTS (SELECT 1 FROM scan_seen seen WHERE seen.scanId = ? AND seen.aliasId = a.aliasId) $where ORDER BY a.aliasId LIMIT ?", args.toTypedArray())
    }
}
