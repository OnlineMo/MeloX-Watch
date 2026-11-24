package io.github.onlinemo.meloxwatch.data.fonts.store

import android.app.Application
import io.github.onlinemo.meloxwatch.MeloXApplication
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Reconnects share one Application-lifetime file owner; obtaining it performs no font/provider read. */
object LyricsFontSettingsStoreFactory {
    @Volatile private var instance: LyricsFontSettingsStore? = null

    fun get(application: Application): LyricsFontSettingsStore = instance ?: synchronized(this) {
        instance ?: run {
            val context = application.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataStore = createLyricsFontSettingsDataStore(scope) {
                context.preferencesDataStoreFile("lyrics_font_settings")
            }
            DataStoreLyricsFontSettingsStore(dataStore, (application as? MeloXApplication)?.backupMutationBarrier).also { instance = it }
        }
    }
}

/** No corruption handler: an unreadable envelope must not erase saved assets or become saved defaults. */
internal fun createLyricsFontSettingsDataStore(scope: CoroutineScope, produceFile: () -> File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile)
