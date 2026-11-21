package io.github.onlinemo.meloxwatch.data.lyrics.store

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** One Application-lifetime observer/DataStore owns this file across all page/service reconnects. */
object LyricsDisplayStoreFactory {
    @Volatile private var instance: LyricsDisplayStore? = null

    fun get(application: Application): LyricsDisplayStore = instance ?: synchronized(this) {
        instance ?: run {
            val context = application.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataStore = createLyricsDisplayDataStore(scope) { context.preferencesDataStoreFile("lyrics_display") }
            DataStoreLyricsDisplayStore(dataStore, scope).also { instance = it }
        }
    }
}

internal fun createLyricsDisplayDataStore(scope: CoroutineScope, produceFile: () -> File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile)
