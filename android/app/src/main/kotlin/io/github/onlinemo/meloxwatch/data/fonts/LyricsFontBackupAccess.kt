package io.github.onlinemo.meloxwatch.data.fonts

import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontAsset
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontFiles
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontImportRequest
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSaveResult
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettingsStore
import io.github.onlinemo.meloxwatch.data.fonts.PreparedLyricsFont
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontFiles
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightCapability
import java.io.File
import io.github.onlinemo.meloxwatch.data.backup.BackupResourceWriter

/** The editor retains admission; a retained reference cannot mutate after its operation exits. */
internal class LyricsFontBackupAccess internal constructor(
    private val settings: LyricsFontSettingsStore,
    private val files: LyricsFontFiles,
    initial: LyricsFontSettings,
    private val active: () -> Boolean,
) {
    var confirmedSettings: LyricsFontSettings = initial
        private set
    suspend fun read(): LyricsFontSettings {
        requireActive()
        return settings.read().also { requireActive(); confirmedSettings = it }
    }
    suspend fun compareAndSet(revision: Long, replacement: LyricsFontSettings): LyricsFontSaveResult {
        requireActive()
        return settings.compareAndSet(revision, replacement).also {
            requireActive()
            when (it) {
                is LyricsFontSaveResult.Saved -> confirmedSettings = it.settings
                is LyricsFontSaveResult.Conflict -> confirmedSettings = it.settings
            }
        }
    }
    suspend fun importFont(request: LyricsFontImportRequest, retain: Set<String>): PreparedLyricsFont {
        requireActive()
        return files.importFont(request, retain).also { requireActive() }
    }
    suspend fun discard(asset: LyricsFontAsset, retain: Set<String>) { requireActive(); files.discard(asset, retain); requireActive() }
    suspend fun prune(retain: Set<String>) { requireActive(); files.prune(retain); requireActive() }
    suspend fun retainForBackup(assets: List<LyricsFontAsset>) {
        requireActive(); nativeFiles().retainForBackup(assets); requireActive()
    }
    suspend fun exportForBackup(asset: LyricsFontAsset, output: BackupResourceWriter) {
        requireActive(); nativeFiles().exportForBackup(asset, output); requireActive()
    }
    suspend fun validateForBackup(file: File, asset: LyricsFontAsset): FontWeightCapability {
        requireActive()
        return nativeFiles().validateForBackup(file, asset).also { requireActive() }
    }
    suspend fun releaseBackupRetention(retain: Set<String>) {
        requireActive(); nativeFiles().releaseBackupRetention(retain); requireActive()
    }
    private fun nativeFiles(): AndroidLyricsFontFiles = files as? AndroidLyricsFontFiles
        ?: throw BackupException(BackupIssue.UNSUPPORTED_RESOURCE)
    private fun requireActive() { if (!active()) throw BackupException(BackupIssue.BUSY) }
}

/** Application adapter retains the existing editor and native pair, without a data-to-UI dependency. */
internal interface BackupFontOwner {
    suspend fun <T> withSettings(action: suspend (LyricsFontBackupAccess) -> T): T
}
