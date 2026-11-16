package io.github.onlinemo.meloxwatch.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.Normalizer

/** V3 adds query projections; the original source specification and display name remain intact. */
internal object LibraryPathMigrations {
    fun applyV2ToV3(database: SupportSQLiteDatabase) {
        applyV2ToV3(
            execute = { sql, arguments -> database.execSQL(sql, arguments) },
            sources = { afterSource, afterRevision ->
                database.query(SimpleSQLiteQuery(sourcePageSql,
                    arrayOf<Any?>(afterSource, afterSource, afterSource, afterRevision))).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(SourceRow(cursor.getString(0), cursor.getLong(1), cursor.getString(2)))
                    }
                }
            },
            names = { after ->
                database.query(SimpleSQLiteQuery(namePageSql, arrayOf(after, after))).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0) to
                            if (cursor.isNull(1)) null else Normalizer.normalize(cursor.getString(1), Normalizer.Form.NFC))
                    }
                }
            },
        )
    }

    fun applyV2ToV3(connection: SQLiteConnection) {
        applyV2ToV3(
            execute = { sql, arguments ->
                connection.prepare(sql).use { statement ->
                    statement.bind(arguments)
                    statement.step()
                }
            },
            sources = { afterSource, afterRevision ->
                connection.prepare(sourcePageSql).use { statement ->
                    statement.bind(arrayOf<Any?>(afterSource, afterSource, afterSource, afterRevision))
                    buildList {
                        while (statement.step()) add(SourceRow(statement.getText(0), statement.getLong(1), statement.getText(2)))
                    }
                }
            },
            names = { after ->
                connection.prepare(namePageSql).use { statement ->
                    statement.bind(arrayOf(after, after))
                    buildList {
                        while (statement.step()) add(statement.getText(0) to
                            if (statement.isNull(1)) null else Normalizer.normalize(statement.getText(1), Normalizer.Form.NFC))
                    }
                }
            },
        )
    }

    private fun applyV2ToV3(
        execute: (String, Array<out Any?>) -> Unit,
        sources: (String?, Long) -> List<SourceRow>,
        names: (String?) -> List<Pair<String, String?>>,
    ) {
        execute("ALTER TABLE local_source_versions ADD COLUMN rootObjectId TEXT", emptyArray())
        execute("ALTER TABLE local_node_versions ADD COLUMN pathName TEXT", emptyArray())
        backfillRoots(execute, sources)
        backfillNames(execute, names)
    }

    private data class SourceRow(val sourceId: String, val validFrom: Long, val spec: String)

    private fun backfillRoots(
        execute: (String, Array<out Any?>) -> Unit,
        sources: (String?, Long) -> List<SourceRow>,
    ) {
        var afterSource: String? = null
        var afterRevision = 0L
        while (true) {
            val rows = sources(afterSource, afterRevision)
            if (rows.isEmpty()) return
            rows.forEach { row ->
                // Only the recorded provider root is authoritative. URI spelling and orphan nodes are not roots.
                execute("UPDATE local_source_versions SET rootObjectId = ? WHERE sourceId = ? AND validFrom = ?",
                    arrayOf<Any?>(LibraryCodec.source(row.spec).rootObjectId, row.sourceId, row.validFrom))
            }
            afterSource = rows.last().sourceId
            afterRevision = rows.last().validFrom
        }
    }

    private fun backfillNames(
        execute: (String, Array<out Any?>) -> Unit,
        names: (String?) -> List<Pair<String, String?>>,
    ) {
        var after: String? = null
        while (true) {
            val rows = names(after)
            if (rows.isEmpty()) return
            rows.forEach { (versionId, normalized) ->
                execute("UPDATE local_node_versions SET pathName = ? WHERE versionId = ?", arrayOf(normalized, versionId))
            }
            after = rows.last().first
        }
    }

    private fun SQLiteStatement.bind(arguments: Array<out Any?>) {
        arguments.forEachIndexed { index, argument ->
            when (argument) {
                null -> bindNull(index + 1)
                is String -> bindText(index + 1, argument)
                is Long -> bindLong(index + 1, argument)
                else -> error("Unsupported path migration argument")
            }
        }
    }

    private const val sourcePageSql = "SELECT sourceId, validFrom, spec FROM local_source_versions " +
        "WHERE (? IS NULL OR sourceId > ? OR (sourceId = ? AND validFrom > ?)) " +
        "ORDER BY sourceId, validFrom LIMIT 100"
    private const val namePageSql =
        "SELECT versionId, name FROM local_node_versions WHERE (? IS NULL OR versionId > ?) ORDER BY versionId LIMIT 100"
}
