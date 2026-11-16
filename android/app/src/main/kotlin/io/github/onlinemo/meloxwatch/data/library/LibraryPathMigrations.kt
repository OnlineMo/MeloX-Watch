package io.github.onlinemo.meloxwatch.data.library

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.Normalizer

/** V3 adds query projections; the original source specification and display name remain intact. */
internal object LibraryPathMigrations {
    fun applyV2ToV3(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE local_source_versions ADD COLUMN rootObjectId TEXT")
        database.execSQL("ALTER TABLE local_node_versions ADD COLUMN pathName TEXT")
        backfillRoots(database)
        backfillNames(database)
    }

    private data class SourceRow(val sourceId: String, val validFrom: Long, val spec: String)

    private fun backfillRoots(database: SupportSQLiteDatabase) {
        var afterSource: String? = null
        var afterRevision = 0L
        while (true) {
            val rows = database.query(SimpleSQLiteQuery(
                "SELECT sourceId, validFrom, spec FROM local_source_versions " +
                    "WHERE (? IS NULL OR sourceId > ? OR (sourceId = ? AND validFrom > ?)) " +
                    "ORDER BY sourceId, validFrom LIMIT 100",
                arrayOf<Any?>(afterSource, afterSource, afterSource, afterRevision),
            )).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(SourceRow(cursor.getString(0), cursor.getLong(1), cursor.getString(2)))
                }
            }
            if (rows.isEmpty()) return
            rows.forEach { row ->
                // Only the recorded provider root is authoritative. URI spelling and orphan nodes are not roots.
                database.execSQL("UPDATE local_source_versions SET rootObjectId = ? WHERE sourceId = ? AND validFrom = ?",
                    arrayOf<Any?>(LibraryCodec.source(row.spec).rootObjectId, row.sourceId, row.validFrom))
            }
            afterSource = rows.last().sourceId
            afterRevision = rows.last().validFrom
        }
    }

    private fun backfillNames(database: SupportSQLiteDatabase) {
        var after: String? = null
        while (true) {
            val rows = database.query(SimpleSQLiteQuery(
                "SELECT versionId, name FROM local_node_versions WHERE (? IS NULL OR versionId > ?) ORDER BY versionId LIMIT 100",
                arrayOf(after, after),
            )).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to
                        if (cursor.isNull(1)) null else Normalizer.normalize(cursor.getString(1), Normalizer.Form.NFC))
                }
            }
            if (rows.isEmpty()) return
            rows.forEach { (versionId, normalized) ->
                database.execSQL("UPDATE local_node_versions SET pathName = ? WHERE versionId = ?", arrayOf(normalized, versionId))
            }
            after = rows.last().first
        }
    }
}
