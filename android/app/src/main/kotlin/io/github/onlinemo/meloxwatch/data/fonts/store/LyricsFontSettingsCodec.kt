package io.github.onlinemo.meloxwatch.data.fonts.store

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontAsset
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings

/** Only this two-role envelope is writable; unknown fields/schema preserve the existing file. */
internal object LyricsFontSettingsCodec {
    private const val SCHEMA_VERSION = 1
    private val schema = intPreferencesKey("schema_version")
    private val revision = longPreferencesKey("revision")
    private val original = RoleKeys("original")
    private val translation = RoleKeys("translation")
    private val knownNames = setOf(schema.name, revision.name) + original.names + translation.names

    fun decode(stored: Preferences): LyricsFontSettings {
        val entries = stored.asMap()
        if (entries.isEmpty()) return LyricsFontSettings()
        if (entries.size > knownNames.size) fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_FIELDS)
        // Inspect raw value types before Preferences' typed getters can cast damaged data.
        val raw = entries.entries.associate { it.key.name to it.value }
        val version = raw[schema.name] as? Int ?: fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_FIELDS)
        if (version != SCHEMA_VERSION) fontSettingsFail(LyricsFontSettingsStorageIssue.UNSUPPORTED_SCHEMA)
        if (raw.keys.any { it !in knownNames }) fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_FIELDS)
        val storedRevision = raw[revision.name] as? Long ?: fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_FIELDS)
        if (storedRevision < 0L) fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_FIELDS)
        return LyricsFontSettings(storedRevision, original.decode(raw), translation.decode(raw))
    }

    fun encode(settings: LyricsFontSettings): Preferences = mutablePreferencesOf(
        schema to SCHEMA_VERSION,
        revision to settings.revision,
    ).apply {
        original.encode(this, settings.original)
        translation.encode(this, settings.translation)
    }

    private class RoleKeys(prefix: String) {
        private val id = stringPreferencesKey("${prefix}_asset_id")
        private val sha256 = stringPreferencesKey("${prefix}_asset_sha256")
        private val bytes = longPreferencesKey("${prefix}_asset_byte_count")
        private val displayName = stringPreferencesKey("${prefix}_asset_display_name")
        private val acknowledgment = intPreferencesKey("${prefix}_asset_license_acknowledgment_version")
        private val weight = floatPreferencesKey("${prefix}_requested_weight")
        private val assetNames = setOf(id.name, sha256.name, bytes.name, displayName.name, acknowledgment.name)
        val names = assetNames + weight.name

        fun decode(raw: Map<String, Any>): LyricsFontSelection {
            val assetFieldCount = assetNames.count(raw::containsKey)
            if (assetFieldCount != 0 && assetFieldCount != assetNames.size) {
                fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_FIELDS)
            }
            val requested = if (raw.containsKey(weight.name)) {
                (raw[weight.name] as? Float)?.takeIf(Float::isFinite)
                    ?: fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_FIELDS)
            } else null
            val asset = if (assetFieldCount == 0) null else {
                val count = raw[bytes.name] as? Long ?: fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_FIELDS)
                val license = raw[acknowledgment.name] as? Int ?: fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_FIELDS)
                try {
                    LyricsFontAsset(
                        boundedString(raw, id.name, 36),
                        boundedString(raw, sha256.name, 64),
                        count,
                        boundedString(raw, displayName.name, LyricsFontLimits.DISPLAY_NAME_CHARACTERS),
                        license,
                    )
                } catch (_: IllegalArgumentException) { fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_FIELDS) }
            }
            // Requests are persisted verbatim; only a revalidated font's actual wght axis may clamp them.
            return LyricsFontSelection(asset, requested)
        }

        fun encode(target: MutablePreferences, selection: LyricsFontSelection) {
            selection.asset?.let { asset ->
                target[id] = asset.id
                target[sha256] = asset.sha256
                target[bytes] = asset.byteCount
                target[displayName] = asset.displayName
                target[acknowledgment] = asset.licenseAcknowledgmentVersion
            }
            selection.requestedWeight?.let { target[weight] = it }
        }

        private fun boundedString(raw: Map<String, Any>, name: String, maximum: Int): String =
            (raw[name] as? String)?.takeIf { it.length <= maximum }
                ?: fontSettingsFail(LyricsFontSettingsStorageIssue.INVALID_FIELDS)
    }
}
