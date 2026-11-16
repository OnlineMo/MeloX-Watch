package io.github.onlinemo.meloxwatch.data.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteStatement
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals

/** Historical expectations come from pinned cloud-exported JSON, never current entities or migration SQL. */
internal class FrozenRoomSchemaTestFixture private constructor(private val schema: JSONObject) {
    private val entities = schema.getJSONArray("entities")
    val tableNames: Set<String> = (0 until entities.length()).mapTo(mutableSetOf()) {
        entities.getJSONObject(it).getString("tableName")
    }

    fun create(database: SQLiteDatabase) {
        for (position in 0 until entities.length()) {
            val entity = entities.getJSONObject(position)
            val table = entity.getString("tableName")
            database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            // Room omits empty index arrays; a present value must retain strict array validation.
            val indices = if (entity.has("indices")) entity.getJSONArray("indices") else null
            if (indices != null) {
                for (ordinal in 0 until indices.length()) {
                    database.execSQL(indices.getJSONObject(ordinal).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (position in 0 until setup.length()) database.execSQL(setup.getString(position))
    }

    fun assertStructure(query: (String) -> Cursor) {
        // A separate native SQLite oracle interprets the historical DDL. Comparing catalog SQL
        // also covers constraints (for example deferred FKs) which PRAGMA foreign_key_list omits.
        SQLiteDatabase.create(null).use { expected ->
            create(expected)
            assertEquals(snapshotStructure { expected.rawQuery(it, null) }, snapshotStructure(query))
        }
    }

    fun snapshotRows(query: (String) -> Cursor): Map<String, FrozenTableRows> = buildMap {
        for (position in 0 until entities.length()) {
            val entity = entities.getJSONObject(position)
            val table = entity.getString("tableName")
            val keys = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
            val keyNames = (0 until keys.length()).map(keys::getString)
            val order = keyNames.joinToString(", ", transform = ::identifier)
            put(table, FrozenTableRows(keyNames, readRows(query("SELECT * FROM ${identifier(table)} ORDER BY $order"))))
        }
    }

    fun assertRowsPreserved(before: Map<String, FrozenTableRows>, query: (String) -> Cursor) {
        assertEquals(tableNames, before.keys)
        for ((table, saved) in before) {
            val columns = saved.values.columns.joinToString(", ", transform = ::identifier)
            val order = saved.keys.joinToString(", ", transform = ::identifier)
            assertEquals("Every historical cell in $table must survive", saved.values,
                readRows(query("SELECT $columns FROM ${identifier(table)} ORDER BY $order")))
        }
    }

    suspend fun assertStructure(database: TestDatabaseSql) {
        SQLiteDatabase.create(null).use { expected ->
            create(expected)
            assertEquals(snapshotStructure { expected.rawQuery(it, null) }, snapshotStructure(database))
        }
    }

    suspend fun snapshotRows(database: TestDatabaseSql): Map<String, FrozenTableRows> = buildMap {
        for (position in 0 until entities.length()) {
            val entity = entities.getJSONObject(position)
            val table = entity.getString("tableName")
            val keys = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
            val keyNames = (0 until keys.length()).map(keys::getString)
            val order = keyNames.joinToString(", ", transform = ::identifier)
            put(table, FrozenTableRows(keyNames, readRows(database,
                "SELECT * FROM ${identifier(table)} ORDER BY $order")))
        }
    }

    suspend fun assertRowsPreserved(before: Map<String, FrozenTableRows>, database: TestDatabaseSql) {
        assertEquals(tableNames, before.keys)
        for ((table, saved) in before) {
            val columns = saved.values.columns.joinToString(", ", transform = ::identifier)
            val order = saved.keys.joinToString(", ", transform = ::identifier)
            assertEquals("Every historical cell in $table must survive", saved.values,
                readRows(database, "SELECT $columns FROM ${identifier(table)} ORDER BY $order"))
        }
    }

    private suspend fun snapshotStructure(database: TestDatabaseSql): SchemaStructure {
        val objects = database.query("""
            SELECT type, name, tbl_name, sql FROM sqlite_master
            WHERE name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')
            ORDER BY type, name
        """.trimIndent()) { statement ->
            buildList {
                while (statement.step()) add(DatabaseObject(
                    statement.getText(0), statement.getText(1), statement.getText(2),
                    if (statement.isNull(3)) null else canonicalSql(statement.getText(3)),
                ))
            }
        }
        val tables = objects.filter { it.type == "table" }.associate { table ->
            // Close the catalog statement before querying each index; pooled statements cannot escape.
            val headers = database.query("PRAGMA index_list(${identifier(table.name)})") { statement ->
                fun column(name: String): Int = (0 until statement.getColumnCount())
                    .first { statement.getColumnName(it) == name }
                val nameColumn = column("name")
                val uniqueColumn = column("unique")
                val originColumn = column("origin")
                val partialColumn = column("partial")
                buildList {
                    while (statement.step()) add(IndexHeader(
                        statement.getText(nameColumn), statement.getLong(uniqueColumn).toInt(),
                        statement.getText(originColumn), statement.getLong(partialColumn).toInt(),
                    ))
                }
            }
            val indexes = headers.map { header ->
                IndexStructure(header.name, header.unique, header.origin, header.partial,
                    readRows(database, "PRAGMA index_xinfo(${identifier(header.name)})"))
            }.sortedBy { it.name }
            table.name to TableStructure(
                readRows(database, "PRAGMA table_info(${identifier(table.name)})"),
                readRows(database, "PRAGMA foreign_key_list(${identifier(table.name)})"), indexes,
            )
        }
        return SchemaStructure(objects, tables)
    }

    private suspend fun readRows(database: TestDatabaseSql, sql: String): FrozenCursorRows = database.query(sql) { statement ->
        FrozenCursorRows((0 until statement.getColumnCount()).map(statement::getColumnName), buildList {
            while (statement.step()) add((0 until statement.getColumnCount()).map { index ->
                val type = statement.cursorStorageType(index)
                val value = when (type) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> statement.getBlob(index).joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
                    else -> statement.getText(index)
                }
                FrozenCell(type, value)
            })
        })
    }

    private fun snapshotStructure(query: (String) -> Cursor): SchemaStructure {
        val objects = query("""
            SELECT type, name, tbl_name, sql FROM sqlite_master
            WHERE name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')
            ORDER BY type, name
        """.trimIndent()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(DatabaseObject(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    if (cursor.isNull(3)) null else canonicalSql(cursor.getString(3)),
                ))
            }
        }
        val tables = objects.filter { it.type == "table" }.associate { table ->
            val indexes = query("PRAGMA index_list(${identifier(table.name)})").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        add(IndexStructure(
                            name, cursor.getInt(cursor.getColumnIndexOrThrow("unique")),
                            cursor.getString(cursor.getColumnIndexOrThrow("origin")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("partial")),
                            readRows(query("PRAGMA index_xinfo(${identifier(name)})")),
                        ))
                    }
                }.sortedBy { it.name }
            }
            table.name to TableStructure(
                readRows(query("PRAGMA table_info(${identifier(table.name)})")),
                readRows(query("PRAGMA foreign_key_list(${identifier(table.name)})")), indexes,
            )
        }
        return SchemaStructure(objects, tables)
    }

    private fun canonicalSql(sql: String): String = buildString {
        var quotedBy: Char? = null
        var position = 0
        while (position < sql.length) {
            val character = sql[position]
            if (quotedBy != null) {
                append(character)
                if (character == quotedBy) {
                    if (position + 1 < sql.length && sql[position + 1] == character) {
                        append(sql[++position])
                    } else quotedBy = null
                }
            } else if (!character.isWhitespace()) {
                append(character)
                if (character == '\'' || character == '"' || character == '`') quotedBy = character
            }
            position++
        }
    }.removeSuffix(";")

    private fun readRows(cursor: Cursor): FrozenCursorRows = cursor.use { rows ->
        FrozenCursorRows(rows.columnNames.toList(), buildList {
            while (rows.moveToNext()) add((0 until rows.columnCount).map { index ->
                val type = rows.getType(index)
                val value = when (type) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> rows.getBlob(index).joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
                    else -> rows.getString(index)
                }
                FrozenCell(type, value)
            })
        })
    }

    private fun identifier(name: String): String = "`" + name.replace("`", "``") + "`"

    private data class DatabaseObject(val type: String, val name: String, val table: String, val sql: String?)
    private data class SchemaStructure(val objects: List<DatabaseObject>, val tables: Map<String, TableStructure>)
    private data class TableStructure(val columns: FrozenCursorRows, val foreignKeys: FrozenCursorRows, val indexes: List<IndexStructure>)
    private data class IndexStructure(val name: String, val unique: Int, val origin: String, val partial: Int, val columns: FrozenCursorRows)
    private data class IndexHeader(val name: String, val unique: Int, val origin: String, val partial: Int)

    companion object {
        fun load(resource: String, version: Int, sha256: String, identity: String): FrozenRoomSchemaTestFixture {
            val bytes = FrozenRoomSchemaTestFixture::class.java.getResourceAsStream(resource)
                ?.use { it.readBytes() } ?: throw AssertionError("The frozen v$version schema resource is required")
            val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            assertEquals(sha256, actualHash)
            val schema = JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("database")
            assertEquals(version, schema.getInt("version"))
            assertEquals(identity, schema.getString("identityHash"))
            return FrozenRoomSchemaTestFixture(schema)
        }
    }
}

internal data class FrozenTableRows(val keys: List<String>, val values: FrozenCursorRows)
internal data class FrozenCursorRows(val columns: List<String>, val rows: List<List<FrozenCell>>)
internal data class FrozenCell(val storageType: Int, val value: String?)

/** SQLite's NULL is 5, while the historical Android Cursor oracle records NULL as 0. */
internal fun SQLiteStatement.cursorStorageType(index: Int): Int = when (getColumnType(index)) {
    SQLITE_DATA_INTEGER -> Cursor.FIELD_TYPE_INTEGER
    SQLITE_DATA_FLOAT -> Cursor.FIELD_TYPE_FLOAT
    SQLITE_DATA_TEXT -> Cursor.FIELD_TYPE_STRING
    SQLITE_DATA_BLOB -> Cursor.FIELD_TYPE_BLOB
    SQLITE_DATA_NULL -> Cursor.FIELD_TYPE_NULL
    else -> throw AssertionError("Unknown native SQLite column storage type")
}
