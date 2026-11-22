package io.github.onlinemo.meloxwatch.data.preferences

import android.app.Application
import io.github.onlinemo.meloxwatch.MeloXApplication
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 绑定 Application 生命周期，页面或服务重连不得再创建同文件的 DataStore。 */
object UserPreferencesRepositoryFactory {
    @Volatile
    private var instance: UserPreferencesRepository? = null

    fun get(application: Application): UserPreferencesRepository = instance ?: synchronized(this) {
        instance ?: run {
            val applicationContext = application.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = createUserPreferencesDataStore(scope) {
                applicationContext.preferencesDataStoreFile("user_preferences")
            }
            DataStoreUserPreferencesRepository(store, scope, (application as? MeloXApplication)?.backupMutationBarrier).also { repository -> instance = repository }
        }
    }
}

internal fun createUserPreferencesDataStore(
    scope: CoroutineScope,
    produceFile: () -> File,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { PreferencesCodec.corruptionDefaults() },
    scope = scope,
    produceFile = produceFile,
)
