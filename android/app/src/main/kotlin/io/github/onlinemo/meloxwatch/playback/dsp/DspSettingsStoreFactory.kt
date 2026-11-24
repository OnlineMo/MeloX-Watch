package io.github.onlinemo.meloxwatch.playback.dsp

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CoroutineScope

/** Application 持有唯一实例和 scope，设置与命名预设仅写入本地私有存储。 */
class DspSettingsStoreFactory {
    private var store: DspSettingsStore? = null
    private var boundBarrier: BackupMutationBarrier? = null

    @Synchronized
    fun get(context: Context, scope: CoroutineScope, barrier: BackupMutationBarrier): DspSettingsStore {
        val existing = store
        if (existing != null) {
            check(boundBarrier === barrier)
            return existing
        }
        val application = context.applicationContext
        val created = DataStoreDspSettingsStore(PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = {
                val declared = application.noBackupFilesDir
                if (Files.isSymbolicLink(declared.toPath())) throw IOException("DSP storage root is a link")
                val root = declared.canonicalFile
                if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("DSP storage unavailable")
                val file = File(root, "dsp-v1.preferences_pb")
                if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != root ||
                    Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("DSP preferences must remain in private storage")
                }
                file
            },
        ), barrier)
        boundBarrier = barrier
        store = created
        return created
    }
}
