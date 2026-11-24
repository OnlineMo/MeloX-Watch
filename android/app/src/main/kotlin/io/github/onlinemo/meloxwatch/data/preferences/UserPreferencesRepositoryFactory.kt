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
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob

/** 每个 Application 持有唯一仓库；页面和服务读取同一 owner 与恢复围栏。 */
object UserPreferencesRepositoryFactory {
    fun get(application: Application): UserPreferencesRepository {
        require(application is MeloXApplication)
        return application.preferences
    }

    internal fun create(application: MeloXApplication, ownerScope: CoroutineScope): UserPreferencesRepository {
        val ownerJob = checkNotNull(ownerScope.coroutineContext[Job])
        ownerJob.ensureActive()
        val applicationContext = application.applicationContext
        val actorScope = CoroutineScope(ownerScope.coroutineContext + Dispatchers.IO)
        // 取消后的串行fence等actor owner完成时结清；observer不能反过来阻止同一owner完成。
        val observerJob = SupervisorJob()
        ownerJob.invokeOnCompletion { observerJob.cancel() }
        val observerScope = CoroutineScope(actorScope.coroutineContext + observerJob)
        val store = createUserPreferencesDataStore(actorScope) {
            applicationContext.preferencesDataStoreFile("user_preferences")
        }
        return DataStoreUserPreferencesRepository(store, observerScope, application.backupMutationBarrier)
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
