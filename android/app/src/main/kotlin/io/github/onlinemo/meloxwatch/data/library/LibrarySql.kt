package io.github.onlinemo.meloxwatch.data.library

import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.onlinemo.meloxwatch.model.TrackKey

/** All ordering happens in SQLite over versioned rows. No OFFSET or in-memory library sort. */
internal object LibrarySql {
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

    private fun cte(view: LibraryViewToken): Pair<String, MutableList<Any?>> {
        val revision = view.revision
        val args = mutableListOf<Any?>()
        if (view.query.relativePathKeyword != null && view.query.sourceId != null) args += view.query.sourceId
        val aliases = mutableListOf(valid("av", revision))
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
        view.query.albumKey?.let { filters += "t.albumKey = ?"; args += it }
        view.query.artistKey?.let { filters += "EXISTS (SELECT 1 FROM local_artist_values artist WHERE artist.versionId = t.versionId AND artist.artistKey = ?)"; args += it }
        view.query.addedSinceEpochMs?.let { filters += "t.firstAddedEpochMs >= ?"; args += it }
        if (view.query.search.isNotBlank()) {
            filters += "t.searchText LIKE ? ESCAPE '\\'"
            val escaped = LibrarySortKeys.normalize(view.query.search).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            args += "%$escaped%"
        }
        val (primary, nullRank, section) = sortColumns(view.sort.field)
        // Choose the minimum scoped access through this track's relations, then join its exact version payload.
        val sql = """
            WITH RECURSIVE ${if (view.query.relativePathKeyword != null) relativePaths(revision, view.query.sourceId != null) + "," else ""} alias_rows AS (
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
                FROM local_track_versions t LEFT JOIN alias_rows a ON a.aliasVersionId = (
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
        LibrarySortField.ARTIST -> Triple("artistKey", "CASE WHEN artistKey LIKE '4:%' THEN 1 ELSE 0 END", "artistSection")
        LibrarySortField.ALBUM -> Triple("albumSortKey", "CASE WHEN albumSortKey LIKE '4:%' THEN 1 ELSE 0 END", "albumSection")
        LibrarySortField.FILENAME_NATURAL -> Triple("filenameKey", "0", "filenameSection")
        LibrarySortField.DURATION -> Triple("printf('%019d', COALESCE(durationMs, 0))", "CASE WHEN durationMs IS NULL THEN 1 ELSE 0 END", "''")
        LibrarySortField.ADDED -> Triple("printf('%019d', firstAddedSequence)", "0", "''")
        LibrarySortField.ALBUM_TRACK -> Triple("printf('%010d:%010d', COALESCE(discNumber, 0), COALESCE(trackNumber, 0))", "CASE WHEN trackNumber IS NULL THEN 1 ELSE 0 END", "''")
        LibrarySortField.PLAY_COUNT -> Triple("printf('%019d', effectivePlayCount)", "0", "''")
        LibrarySortField.LISTENING_TIME -> Triple("printf('%019d', effectiveListeningMs)", "0", "''")
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

    fun count(view: LibraryViewToken): SimpleSQLiteQuery {
        val (sql, args) = cte(view)
        return SimpleSQLiteQuery("$sql SELECT COUNT(*) FROM ordered", args.toTypedArray())
    }
    fun availableCount(view: LibraryViewToken): SimpleSQLiteQuery {
        val (sql, args) = cte(view)
        return SimpleSQLiteQuery("$sql SELECT COUNT(*) FROM ordered WHERE availabilityRank = 0", args.toTypedArray())
    }
    fun playlistOverlap(view: LibraryViewToken, dataGenerationId: String): SimpleSQLiteQuery {
        val (sql, args) = cte(view)
        args += dataGenerationId
        return SimpleSQLiteQuery("""$sql SELECT COUNT(*) FROM ordered t WHERE EXISTS (
            SELECT 1 FROM playlist_members member WHERE member.generationId = ?
                AND member.providerId = t.providerId AND member.trackId = t.trackId)
        """.trimIndent(), args.toTypedArray())
    }
    fun rows(view: LibraryViewToken, cursor: LibraryCursor?, limit: Int, before: Boolean = false): SimpleSQLiteQuery {
        val (sql, args) = cte(view)
        val where = if (cursor == null) "" else "WHERE ${boundary(view, cursor, before, args)}"
        args += limit
        return SimpleSQLiteQuery("$sql SELECT * FROM ordered $where ORDER BY ${ordering(view, before)} LIMIT ?", args.toTypedArray())
    }
    fun row(view: LibraryViewToken, providerId: String, trackId: String): SimpleSQLiteQuery {
        val (sql, args) = cte(view)
        args += providerId; args += trackId
        return SimpleSQLiteQuery("$sql SELECT * FROM ordered WHERE providerId = ? AND trackId = ? LIMIT 1", args.toTypedArray())
    }
    fun rowsForKeys(view: LibraryViewToken, keys: List<TrackKey>): SimpleSQLiteQuery {
        require(keys.size <= LibraryLimits.PAGE_SIZE)
        val (sql, args) = cte(view)
        val predicate = keys.joinToString(" OR ") {
            args += it.providerId; args += it.trackId
            "(providerId = ? AND trackId = ?)"
        }.ifEmpty { "0" }
        args += keys.size + 1
        return SimpleSQLiteQuery("$sql SELECT * FROM ordered WHERE ($predicate) LIMIT ?", args.toTypedArray())
    }
    fun countBefore(view: LibraryViewToken, cursor: LibraryCursor): SimpleSQLiteQuery {
        val (sql, args) = cte(view)
        return SimpleSQLiteQuery("$sql SELECT COUNT(*) FROM ordered WHERE ${boundary(view, cursor, true, args)}", args.toTypedArray())
    }
    fun hasBeyond(view: LibraryViewToken, cursor: LibraryCursor, before: Boolean): SimpleSQLiteQuery {
        val (sql, args) = cte(view)
        return SimpleSQLiteQuery("$sql SELECT EXISTS (SELECT 1 FROM ordered WHERE ${boundary(view, cursor, before, args)} LIMIT 1)", args.toTypedArray())
    }
    fun groups(view: LibraryViewToken, kind: LibraryGroupKind, after: String?, limit: Int): SimpleSQLiteQuery {
        val (sql, args) = cte(view)
        val group = when (kind) {
            LibraryGroupKind.ALBUM -> "SELECT albumKey AS groupKey, MIN(album) AS title, MIN(albumArtist) AS subtitle, COUNT(*) AS trackCount FROM ordered GROUP BY albumKey"
            LibraryGroupKind.ARTIST -> "SELECT artists.artistKey AS groupKey, MIN(artists.displayName) AS title, NULL AS subtitle, COUNT(DISTINCT ordered.trackId) AS trackCount FROM ordered JOIN local_artist_values artists ON artists.versionId = ordered.versionId GROUP BY artists.artistKey"
        }
        val where = if (after == null) "" else "WHERE groupKey > ?".also { args += after }
        args += limit
        return SimpleSQLiteQuery("$sql SELECT * FROM ($group) $where ORDER BY groupKey LIMIT ?", args.toTypedArray())
    }
    fun sections(view: LibraryViewToken): SimpleSQLiteQuery {
        val (sql, args) = cte(view)
        return SimpleSQLiteQuery("$sql SELECT sectionKey, COUNT(*) AS trackCount FROM ordered WHERE sectionKey != '' GROUP BY sectionKey ORDER BY sectionKey", args.toTypedArray())
    }
    fun firstInSection(view: LibraryViewToken, section: String): SimpleSQLiteQuery {
        val (sql, args) = cte(view)
        args += section
        return SimpleSQLiteQuery("$sql SELECT * FROM ordered WHERE sectionKey = ? ORDER BY ${ordering(view)} LIMIT 1", args.toTypedArray())
    }
    fun folders(view: LibraryViewToken, parent: String?, after: String?, limit: Int, nodeId: String? = null): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = mutableListOf(valid("n", view.revision), "n.directory = 1", "sv.state != 'REMOVED'")
        view.query.sourceId?.let { where += "n.sourceId = ?"; args += it }
        if (nodeId != null) { where += "n.nodeId = ?"; args += nodeId }
        else if (parent == null) where += "n.parentNodeId IS NULL"
        else { where += "n.parentNodeId = ?"; args += parent }
        if (after != null) { where += "n.nodeId > ?"; args += after }
        if (!view.query.includeHidden) where += "NOT ${excluded("n.sourceId", "n.nodeId", view.revision)}"
        args += limit
        return SimpleSQLiteQuery("SELECT n.* FROM local_node_versions n JOIN local_source_versions sv ON sv.sourceId = n.sourceId AND ${valid("sv", view.revision)} WHERE ${where.joinToString(" AND ")} ORDER BY n.nodeId LIMIT ?", args.toTypedArray())
    }
    fun isExcluded(sourceId: String, nodeId: String?, parentNodeId: String?, revision: Long): SimpleSQLiteQuery {
        val start = nodeId ?: parentNodeId ?: return SimpleSQLiteQuery("SELECT 0")
        return SimpleSQLiteQuery("""
            WITH RECURSIVE ancestors(nodeId) AS (
                SELECT ? UNION SELECT n.parentNodeId FROM local_node_versions n JOIN ancestors a ON n.nodeId = a.nodeId
                WHERE n.parentNodeId IS NOT NULL AND ${valid("n", revision)}
            ) SELECT EXISTS (SELECT 1 FROM scan_rules r WHERE r.sourceId = ? AND ${valid("r", revision)}
                AND (r.nodeId = ? OR (r.subtree = 1 AND r.nodeId IN (SELECT nodeId FROM ancestors))))
        """.trimIndent(), arrayOf(start, sourceId, nodeId.orEmpty()))
    }

    fun relativePath(revision: Long, sourceId: String, nodeId: String): SimpleSQLiteQuery = SimpleSQLiteQuery(
        "WITH RECURSIVE ${relativePaths(revision, true)} SELECT relativePath FROM relative_paths WHERE sourceId = ? AND nodeId = ? LIMIT 1",
        arrayOf(sourceId, sourceId, nodeId),
    )

    fun relativePathMatches(revision: Long, sourceId: String, path: String, limit: Int): SimpleSQLiteQuery = SimpleSQLiteQuery(
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
    ): SimpleSQLiteQuery {
        require(components.size in 1..16 && limit in 1..LibraryLimits.PAGE_SIZE)
        val names = components.indices.joinToString(" ") { "WHEN $it THEN ?" }
        val args = mutableListOf<Any?>(rootNodeId, sourceId, sourceId)
        args.addAll(components)
        args += limit
        return SimpleSQLiteQuery("""
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
    fun absentAliases(sourceId: String, scanId: String, after: String?, limit: Int): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>(sourceId, scanId)
        val where = if (after == null) "" else "AND a.aliasId > ?".also { args += after }
        args += limit
        return SimpleSQLiteQuery("SELECT a.* FROM local_access_aliases a WHERE a.sourceId = ? AND NOT EXISTS (SELECT 1 FROM scan_seen seen WHERE seen.scanId = ? AND seen.aliasId = a.aliasId) $where ORDER BY a.aliasId LIMIT ?", args.toTypedArray())
    }
}
