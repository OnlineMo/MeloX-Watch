package io.github.onlinemo.meloxwatch.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.model.LibraryLayout
import io.github.onlinemo.meloxwatch.model.LibraryListContext
import io.github.onlinemo.meloxwatch.model.LibrarySort
import io.github.onlinemo.meloxwatch.model.LibrarySortField
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.defaultLibrarySorts

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
    private val libraryLayoutKey = stringPreferencesKey("library_layout")
    private val artworkPathKey = stringPreferencesKey("artwork_read_path")
    private val sortKeys = LibraryListContext.entries.associateWith { context ->
        stringPreferencesKey("library_sort_" + context.name.lowercase(java.util.Locale.ROOT))
    }
    private val sortDirectionKeys = LibraryListContext.entries.associateWith { context ->
        booleanPreferencesKey("library_descending_" + context.name.lowercase(java.util.Locale.ROOT))
    }
    private val corruptionKey = booleanPreferencesKey("recovered_corrupt_file")
    private val canonicalNames = setOf(
        schemaKey.name, revisionKey.name, restorePositionKey.name, screenShapeKey.name,
        libraryLayoutKey.name, artworkPathKey.name,
    ) + sortKeys.values.map { it.name } + sortDirectionKeys.values.map { it.name }
    private val allFields = PreferencesField.entries.toSet()

    fun decode(stored: Preferences): DecodedPreferences {
        // 旧字段可能与当前 key 同名但类型不同，不能直接用类型化 getter 强转。
        val raw = stored.asMap().entries.associate { entry -> entry.key.name to entry.value }
        if (raw.isEmpty()) return DecodedPreferences(UserPreferences())

        val schemaVersion = raw[schemaKey.name] as? Int
        val storedRevision = raw[revisionKey.name] as? Long
        val revision = storedRevision?.takeIf { value -> value >= 0L } ?: 0L
        val legacy = schemaVersion == 1
        if (!legacy && schemaVersion != UserPreferences.CURRENT_SCHEMA_VERSION) {
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

        // v1 already has valid playback/window settings. New fields gain defaults without resetting them.
        val layout = if (legacy) LibraryLayout.LIST else {
            LibraryLayout.entries.firstOrNull { it.name == raw[libraryLayoutKey.name] }.also {
                if (it == null) invalidFields.add(PreferencesField.LIBRARY_LAYOUT)
            } ?: LibraryLayout.LIST
        }
        val artworkPath = if (legacy) ArtworkReadPath.SYSTEM_THUMBNAIL else {
            ArtworkReadPath.entries.firstOrNull { it.name == raw[artworkPathKey.name] }.also {
                if (it == null) invalidFields.add(PreferencesField.ARTWORK_READ_PATH)
            } ?: ArtworkReadPath.SYSTEM_THUMBNAIL
        }
        val defaults = defaultLibrarySorts()
        val sorts = LibraryListContext.entries.associateWith { context ->
            if (legacy) defaults.getValue(context) else {
                val field = LibrarySortField.entries.firstOrNull { it.name == raw[sortKeys.getValue(context).name] }
                val descending = raw[sortDirectionKeys.getValue(context).name] as? Boolean
                if (field == null || descending == null) invalidFields.add(PreferencesField.LIBRARY_SORTS)
                LibrarySort(field ?: defaults.getValue(context).field, descending ?: defaults.getValue(context).descending)
            }
        }

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
                libraryLayout = layout,
                artworkReadPath = artworkPath,
                librarySorts = sorts,
            ),
            recovery = recovery,
            isCanonical = !legacy && recovery == null && raw.keys == canonicalNames,
        )
    }

    fun encode(preferences: UserPreferences): Preferences = mutablePreferencesOf(
        schemaKey to UserPreferences.CURRENT_SCHEMA_VERSION,
        revisionKey to preferences.revision,
        restorePositionKey to preferences.restorePosition,
        screenShapeKey to preferences.screenShape.name,
        libraryLayoutKey to preferences.libraryLayout.name,
        artworkPathKey to preferences.artworkReadPath.name,
    ).apply {
        LibraryListContext.entries.forEach { context ->
            val sort = preferences.librarySorts.getValue(context)
            this[sortKeys.getValue(context)] = sort.field.name
            this[sortDirectionKeys.getValue(context)] = sort.descending
        }
    }

    fun corruptionDefaults(): Preferences = encode(UserPreferences()).toMutablePreferences().apply {
        // 提示随安全默认一起落盘，重开应用也能解释原设置为何丢失。
        this[corruptionKey] = true
    }
}
