package io.github.onlinemo.meloxwatch.data.themes

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CoroutineScope

/** Application 注入唯一存储 scope；同一进程不会为同一文件创建第二个 DataStore。 */
class AppearancePreferencesStoreFactory {
    private var store: AppearancePreferencesStore? = null

    @Synchronized
    fun get(context: Context, scope: CoroutineScope): AppearancePreferencesStore {
        store?.let { return it }
        val application = context.applicationContext
        val created = DataStoreAppearancePreferencesStore(PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = {
                val declared = application.noBackupFilesDir
                if (Files.isSymbolicLink(declared.toPath())) throw IOException("Appearance storage root is a link")
                val root = declared.canonicalFile
                if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("Appearance storage root unavailable")
                val file = File(root, "appearance-v1.preferences_pb")
                if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != root ||
                    Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Appearance settings must stay in private storage")
                }
                file
            },
        ))
        store = created
        return created
    }
}
