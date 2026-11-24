package io.github.onlinemo.meloxwatch.data.sleep

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application 内唯一存储；启动标识只进 noBackup，不随配置备份导出。 */
object SleepTimerStoreFactory {
    @Volatile private var instance: SleepTimerStore? = null

    fun get(application: Application): SleepTimerStore = instance ?: synchronized(this) {
        instance ?: run {
            val context = application.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            SleepTimerStore(createSleepTimerDataStore(scope) {
                File(context.noBackupFilesDir, "sleep_timer.preferences_pb")
            }).also { instance = it }
        }
    }
}

internal fun createSleepTimerDataStore(scope: CoroutineScope, produceFile: () -> File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { SleepTimerStoreCodec.corruptionDefaults() },
        scope = scope,
        produceFile = produceFile,
    )
