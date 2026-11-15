package io.github.onlinemo.meloxwatch.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.UserPreferences

internal data class DecodedPreferences(
    val preferences: UserPreferences,
    val recovery: PreferencesRecovery? = null,
    val canWrite: Boolean = true,
    val isCanonical: Boolean = false,
)

internal object PreferencesCodec {
    private val schemaKey = intPreferencesKey("schema_version")
    private val revisionKey = longPreferencesKey("revision")
    private val restorePositionKey = booleanPreferencesKey("restore_position")
    private val screenShapeKey = stringPreferencesKey("screen_shape")
    private val corruptionKey = booleanPreferencesKey("recovered_corrupt_file")
    private val canonicalNames = setOf(schemaKey.name, revisionKey.name, restorePositionKey.name, screenShapeKey.name)
    private val allFields = PreferencesField.entries.toSet()

    fun decode(stored: Preferences): DecodedPreferences {
        // 旧字段可能与当前 key 同名但类型不同，不能直接用类型化 getter 强转。
        val raw = stored.asMap().entries.associate { entry -> entry.key.name to entry.value }
        if (raw.isEmpty()) return DecodedPreferences(UserPreferences())

        val schemaVersion = raw[schemaKey.name] as? Int
        val storedRevision = raw[revisionKey.name] as? Long
        val revision = storedRevision?.takeIf { value -> value >= 0L } ?: 0L
        if (schemaVersion != UserPreferences.CURRENT_SCHEMA_VERSION) {
            val isNewer = schemaVersion != null && schemaVersion > UserPreferences.CURRENT_SCHEMA_VERSION
            val reason = if (isNewer) {
                PreferencesRecoveryReason.NEWER_SCHEMA
            } else {
                PreferencesRecoveryReason.UNRECOGNIZED_SCHEMA
            }
            return DecodedPreferences(
                preferences = UserPreferences(revision = revision),
                recovery = PreferencesRecovery(reason, allFields, schemaVersion),
                canWrite = !isNewer,
            )
        }

        val invalidFields = mutableSetOf<PreferencesField>()
        if (storedRevision == null || storedRevision < 0L) invalidFields.add(PreferencesField.REVISION)

        val restorePosition = raw[restorePositionKey.name] as? Boolean
        if (restorePosition == null) invalidFields.add(PreferencesField.RESTORE_POSITION)

        val storedShape = raw[screenShapeKey.name] as? String
        val shape = ScreenShape.entries.firstOrNull { candidate -> candidate.name == storedShape }
        if (shape == null) invalidFields.add(PreferencesField.SCREEN_SHAPE)

        val recovery = when {
            raw[corruptionKey.name] == true -> PreferencesRecovery(PreferencesRecoveryReason.CORRUPT_FILE, allFields)
            invalidFields.isNotEmpty() -> PreferencesRecovery(
                PreferencesRecoveryReason.INVALID_FIELDS,
                invalidFields.toSet(),
                schemaVersion,
            )
            else -> null
        }
        return DecodedPreferences(
            preferences = UserPreferences(
                revision = revision,
                restorePosition = restorePosition ?: true,
                screenShape = shape ?: ScreenShape.AUTO,
            ),
            recovery = recovery,
            isCanonical = recovery == null && raw.keys == canonicalNames,
        )
    }

    fun encode(preferences: UserPreferences): Preferences = mutablePreferencesOf(
        schemaKey to UserPreferences.CURRENT_SCHEMA_VERSION,
        revisionKey to preferences.revision,
        restorePositionKey to preferences.restorePosition,
        screenShapeKey to preferences.screenShape.name,
    )

    fun corruptionDefaults(): Preferences = encode(UserPreferences()).toMutablePreferences().apply {
        // 提示随安全默认一起落盘，重开应用也能解释原设置为何丢失。
        this[corruptionKey] = true
    }
}
