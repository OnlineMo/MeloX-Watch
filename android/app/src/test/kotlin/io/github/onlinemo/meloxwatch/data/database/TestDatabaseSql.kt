package io.github.onlinemo.meloxwatch.data.database

import androidx.room.PooledConnection
import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement

/** Test SQL stays inside the public pooled-connection scope; no connection or cursor is cached. */
internal class TestDatabaseSql(private val connection: PooledConnection) {
    suspend fun execute(sql: String, vararg arguments: Any?) {
        query(sql, *arguments) { it.step() }
    }

    suspend fun <T> query(sql: String, vararg arguments: Any?, block: (SQLiteStatement) -> T): T = try {
        connection.usePrepared(sql) { statement ->
            arguments.forEachIndexed { index, argument -> statement.bindArgument(index + 1, argument) }
            block(statement)
        }
    } catch (failure: SQLiteException) {
        throw failure.toPlatformSQLiteException()
    }

    suspend fun scalarLong(sql: String, vararg arguments: Any?): Long = query(sql, *arguments) {
        check(it.step())
        it.getLong(0)
    }

    suspend fun scalarText(sql: String, vararg arguments: Any?): String? = query(sql, *arguments) {
        check(it.step())
        if (it.isNull(0)) null else it.getText(0)
    }
}

internal suspend fun <T> RoomDatabase.withTestWriterSql(block: suspend TestDatabaseSql.() -> T): T =
    useWriterConnection { TestDatabaseSql(it).block() }

internal suspend fun <T> RoomDatabase.withTestReaderSql(block: suspend TestDatabaseSql.() -> T): T =
    useReaderConnection { TestDatabaseSql(it).block() }

internal suspend fun <T> RoomDatabase.withTestWriterTransaction(block: suspend TestDatabaseSql.() -> T): T =
    withPreservedTransaction { withTestWriterSql(block) }

private fun SQLiteStatement.bindArgument(index: Int, argument: Any?) {
    when (argument) {
        null -> bindNull(index)
        is String -> bindText(index, argument)
        is ByteArray -> bindBlob(index, argument)
        is Boolean -> bindLong(index, if (argument) 1L else 0L)
        is Byte -> bindLong(index, argument.toLong())
        is Short -> bindLong(index, argument.toLong())
        is Int -> bindLong(index, argument.toLong())
        is Long -> bindLong(index, argument)
        is Float -> bindDouble(index, argument.toDouble())
        is Double -> bindDouble(index, argument)
        else -> throw IllegalArgumentException("Unsupported test SQL argument type")
    }
}
