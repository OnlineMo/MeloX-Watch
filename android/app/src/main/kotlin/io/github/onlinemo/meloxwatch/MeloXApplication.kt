package io.github.onlinemo.meloxwatch

import android.app.Application
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepositoryFactory
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore

/** UI 和服务共享存储身份；播放内核只在唯一服务中建立。 */
class MeloXApplication : Application() {
    private val database by lazy { MeloXDatabase.open(this) }
    val queues: QueueStore by lazy { RoomQueueStore(database) }
    val preferences: UserPreferencesRepository
        get() = UserPreferencesRepositoryFactory.get(this)
}
