package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.preferences.DataStoreUserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.lyrics.store.DataStoreLyricsDisplayStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayStore
import io.github.onlinemo.meloxwatch.data.themes.AppearancePreferencesStore
import io.github.onlinemo.meloxwatch.data.themes.LocalBackgroundImages
import io.github.onlinemo.meloxwatch.data.themes.LocalThemeRepository
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettingsStore
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontBackupAccess
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The storage revision belongs to the live native owner, never to the portable preference. */
internal class BackupConfigVersion(val revision: Long, val record: BackupRecord) {
    init {
        require(revision >= 0L)
        BackupPreferencesCodec.validate(record)
    }
    override fun toString(): String = "BackupConfigVersion"
}

/** Uses the application's existing seven native owners; it creates no DataStore or file actor. */
internal class BackupNativeConfigBridge(
    private val barrier: BackupMutationBarrier,
    preferences: UserPreferencesRepository,
    lyricsDisplay: LyricsDisplayStore,
    private val appearance: AppearancePreferencesStore,
    private val background: LocalBackgroundImages,
    private val themes: LocalThemeRepository,
    private val dsp: DspSettingsStore,
) {
    private val preferences = preferences as? DataStoreUserPreferencesRepository
        ?: throw BackupException(BackupIssue.UNSUPPORTED_VERSION)
    private val lyricsDisplay = lyricsDisplay as? DataStoreLyricsDisplayStore
        ?: throw BackupException(BackupIssue.UNSUPPORTED_VERSION)

    suspend fun readAll(fonts: LyricsFontBackupAccess): Map<String, BackupConfigVersion> {
        barrier.requireExclusiveOwner()
        val result = LinkedHashMap<String, BackupConfigVersion>()
        for (key in BackupPreferencesCodec.KEYS) result[key] = read(key, fonts)
        return result
    }

    suspend fun read(key: String, fonts: LyricsFontBackupAccess): BackupConfigVersion {
        barrier.requireExclusiveOwner()
        return when (key) {
            "player" -> preferences.readForBackup().let { BackupConfigVersion(it.revision, BackupPreferencesCodec.player(it)) }
            "appearance" -> appearance.read().let {
                val actual = it.settings
                if (actual == null || it.issue != null) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                BackupConfigVersion(actual.revision, BackupPreferencesCodec.appearance(actual.options))
            }
            "lyrics-display" -> lyricsDisplay.readForBackup().let { BackupConfigVersion(it.revision, BackupPreferencesCodec.display(it)) }
            "lyrics-fonts" -> fonts.read().let { BackupConfigVersion(it.revision, BackupPreferencesCodec.fonts(it)) }
            "background" -> background.readForBackup().let { BackupConfigVersion(it.revision, BackupPreferencesCodec.background(it.options)) }
            "theme" -> themes.readCatalogForBackup().let { BackupConfigVersion(it.revision, BackupPreferencesCodec.theme(it.selected, it.previous)) }
            "dsp" -> dsp.read().let {
                val actual = it.saved
                if (actual == null || it.issue != null) throw BackupException(BackupIssue.RECOVERY_REQUIRED)
                BackupConfigVersion(actual.revision, BackupPreferencesCodec.dsp(actual))
            }
            else -> throw BackupException(BackupIssue.INVALID_INPUT)
        }
    }

    /** A lost native acknowledgement is resolved by its true identity read before any retry. */
    suspend fun compareAndSet(before: BackupConfigVersion, target: BackupRecord, fonts: LyricsFontBackupAccess): BackupConfigVersion {
        barrier.requireExclusiveOwner()
        val canonical = canonicalConfigPreference(target)
        if (before.record.key != canonical.key) throw BackupException(BackupIssue.INVALID_INPUT)
        val playerBefore = if (canonical.key == "player") preferences.readForBackup() else null
        val actualBefore = playerBefore?.let {
            BackupConfigVersion(it.revision, BackupPreferencesCodec.player(it))
        } ?: read(canonical.key, fonts)
        if (actualBefore.revision != before.revision || !actualBefore.record.sameContent(before.record)) {
            throw BackupException(BackupIssue.STALE_PREVIEW)
        }
        // Portable player rows omit activation; equal rows still need a real native disable.
        if (actualBefore.record.sameContent(canonical) && playerBefore?.bluetoothLyricsEnabled != true) return actualBefore
        if (before.revision == Long.MAX_VALUE) throw BackupException(BackupIssue.CONFLICT)
        val attempt = try {
            when (canonical.key) {
                "player" -> preferences.save(before.revision, BackupPreferencesCodec.player(canonical).asDraft())
                "appearance" -> appearance.compareAndSet(before.revision, BackupPreferencesCodec.appearance(canonical))
                "lyrics-display" -> lyricsDisplay.save(before.revision, BackupPreferencesCodec.display(canonical).copy(revision = before.revision))
                "lyrics-fonts" -> fonts.compareAndSet(before.revision, BackupPreferencesCodec.fonts(canonical).copy(revision = before.revision))
                "background" -> background.compareAndSetForBackup(before.revision, BackupPreferencesCodec.background(canonical))
                "theme" -> themes.setChoicesForBackup(before.revision, BackupPreferencesCodec.choice(canonical, "selected"),
                    BackupPreferencesCodec.choice(canonical, "previous"), UUID.randomUUID().toString())
                "dsp" -> BackupPreferencesCodec.dsp(canonical).let { dsp.compareAndSet(before.revision, it.settings, it.presets) }
                else -> throw BackupException(BackupIssue.INVALID_INPUT)
            }
            null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { failure }
        currentCoroutineContext().ensureActive()
        var playerDisabled = canonical.key != "player"
        val observed = try {
            if (canonical.key == "player") {
                val player = preferences.readForBackup()
                playerDisabled = !player.bluetoothLyricsEnabled
                BackupConfigVersion(player.revision, BackupPreferencesCodec.player(player))
            } else read(canonical.key, fonts)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (attempt != null && attempt !== failure) failure.addSuppressed(attempt)
            throw BackupException(BackupIssue.RECOVERY_REQUIRED, failure)
        }
        if (observed.revision == before.revision + 1L && observed.record.sameContent(canonical) && playerDisabled) return observed
        if (observed.revision == before.revision && observed.record.sameContent(before.record)) {
            throw BackupException(BackupIssue.IO, attempt)
        }
        throw BackupException(BackupIssue.RECOVERY_REQUIRED, attempt)
    }
}

internal fun BackupRecord.sameContent(other: BackupRecord): Boolean = kind == other.kind && key == other.key && values == other.values

/** Resource transport hints are resolved separately; they cannot become native configuration. */
internal fun canonicalConfigPreference(record: BackupRecord): BackupRecord {
    BackupPreferencesCodec.validate(record)
    return when (record.key) {
        "player" -> BackupPreferencesCodec.player(BackupPreferencesCodec.player(record))
        "appearance" -> BackupPreferencesCodec.appearance(BackupPreferencesCodec.appearance(record))
        "lyrics-display" -> BackupPreferencesCodec.display(BackupPreferencesCodec.display(record))
        "lyrics-fonts" -> BackupPreferencesCodec.fonts(BackupPreferencesCodec.fonts(record))
        "background" -> BackupPreferencesCodec.background(BackupPreferencesCodec.background(record))
        "theme" -> BackupPreferencesCodec.theme(BackupPreferencesCodec.choice(record, "selected"), BackupPreferencesCodec.choice(record, "previous"))
        "dsp" -> BackupPreferencesCodec.dsp(BackupPreferencesCodec.dsp(record))
        else -> throw BackupException(BackupIssue.INVALID_INPUT)
    }
}
