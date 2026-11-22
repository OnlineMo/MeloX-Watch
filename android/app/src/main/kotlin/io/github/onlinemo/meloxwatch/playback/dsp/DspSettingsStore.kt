package io.github.onlinemo.meloxwatch.playback.dsp

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

object BuiltinDspPresets {
    val values: List<DspPreset> = listOf(
        DspPreset("builtin_flat", "Flat", List(10) { 0 }),
        DspPreset("builtin_bass", "Bass", listOf(60, 50, 35, 15, 0, 0, 0, 0, 0, 0)),
        DspPreset("builtin_voice", "Voice", listOf(-40, -30, -15, 0, 20, 35, 35, 20, 0, -10)),
    )
}

/** 命名预设只保存 EQ 曲线，启停和其它音效各自保存。 */
data class DspStored(
    val revision: Long = 0L,
    val settings: DspSettings = DspSettings(),
    val presets: List<DspPreset> = emptyList(),
) {
    init {
        require(revision >= 0L && presets.size <= DspLimits.MAX_PRESETS)
        require(presets.none { it.id.startsWith("builtin_") })
        require(presets.map { it.id }.toSet().size == presets.size)
        val names = presets.map { normalizedPresetName(it.name) }
        require(names.toSet().size == names.size)
        val selected = settings.selectedPresetId
        if (selected != null) {
            val preset = (BuiltinDspPresets.values + presets).singleOrNull { it.id == selected }
            require(preset != null && preset.bandGainTenthsDb == settings.bandGainTenthsDb)
        }
    }

    fun immutableCopy(): DspStored = copy(settings = settings.immutableCopy(),
        presets = presets.map { it.copy(bandGainTenthsDb = it.bandGainTenthsDb.toList()) })
}

internal fun normalizedPresetName(name: String): String =
    Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

enum class DspSettingsIssue {
    BUSY, CONFLICT, INVALID_SETTINGS, NEWER_SCHEMA, SETTINGS_TOO_LARGE,
    STORAGE_UNAVAILABLE, UNRECOVERABLE_SETTINGS, REVISION_EXHAUSTED,
}

data class DspSnapshot(
    val saved: DspStored?,
    val issue: DspSettingsIssue? = null,
    val recoveryToken: String? = null,
)

sealed interface DspSaveResult {
    data class Saved(val snapshot: DspSnapshot) : DspSaveResult
    data class Conflict(val actual: DspSnapshot) : DspSaveResult
    data class Failed(val actual: DspSnapshot, val issue: DspSettingsIssue) : DspSaveResult
}

interface DspSettingsStore {
    val state: StateFlow<DspSnapshot>
    suspend fun read(): DspSnapshot
    suspend fun compareAndSet(expectedRevision: Long, settings: DspSettings, presets: List<DspPreset>): DspSaveResult
    suspend fun recoverDefaults(expectedRecoveryToken: String): DspSaveResult
}

internal class DspStorageException(val issue: DspSettingsIssue) : Exception(issue.name)
