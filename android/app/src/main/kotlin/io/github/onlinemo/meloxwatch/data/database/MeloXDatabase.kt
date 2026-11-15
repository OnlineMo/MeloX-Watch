package io.github.onlinemo.meloxwatch.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.onlinemo.meloxwatch.data.queue.QueueDao
import io.github.onlinemo.meloxwatch.data.queue.QueueEntryEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueGenerationEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueHeadEntity

/** v1 只有真实消费的队列表；后续曲库表沿用这个数据库身份并提供显式迁移。 */
@Database(
    entities = [QueueGenerationEntity::class, QueueEntryEntity::class, QueueHeadEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MeloXDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao

    companion object {
        const val FILE_NAME = "melox.db"

        fun open(context: Context): MeloXDatabase = Room.databaseBuilder(
            context.applicationContext,
            MeloXDatabase::class.java,
            FILE_NAME,
        ).build()
    }
}
