package io.github.onlinemo.meloxwatch.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.model.HelpPreferenceLimits
import io.github.onlinemo.meloxwatch.model.HelpPreferences
import io.github.onlinemo.meloxwatch.model.PrivacyNoticeChoice
import io.github.onlinemo.meloxwatch.model.LibraryLayout
import io.github.onlinemo.meloxwatch.model.LibraryListContext
import io.github.onlinemo.meloxwatch.model.LibrarySort
import io.github.onlinemo.meloxwatch.model.LibrarySortField
import io.github.onlinemo.meloxwatch.model.PlaybackBackendKind
import io.github.onlinemo.meloxwatch.model.PlaybackPreferenceLimits
import io.github.onlinemo.meloxwatch.model.PlaybackControlsPreferences
import io.github.onlinemo.meloxwatch.model.ProgressGestureMode
import io.github.onlinemo.meloxwatch.model.RotaryAction
import io.github.onlinemo.meloxwatch.model.RotaryOrientation
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.SongTapPolicy
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
    private val songTapKey = stringPreferencesKey("song_tap_policy")
    private val mediaButtonsKey = booleanPreferencesKey("media_buttons_enabled")
    private val audioFocusKey = booleanPreferencesKey("audio_focus_management")
    private val backendKey = stringPreferencesKey("playback_backend")
    private val speedKey = intPreferencesKey("playback_speed_tenths")
    private val gaplessKey = booleanPreferencesKey("gapless_playback")
    private val bluetoothLyricsKey = booleanPreferencesKey("bluetooth_lyrics_enabled")
    private val rotaryEnabledKey = booleanPreferencesKey("rotary_enabled")
    private val rotaryActionKey = stringPreferencesKey("rotary_action")
    private val rotaryOrientationKey = stringPreferencesKey("rotary_orientation")
    private val rotaryHapticsKey = booleanPreferencesKey("rotary_haptics")
    private val mainProgressKey = booleanPreferencesKey("show_main_progress")
    private val progressGestureKey = stringPreferencesKey("progress_gesture")
    private val sleepSummaryKey = booleanPreferencesKey("sleep_timer_summary")
    private val guideCompletedKey = intPreferencesKey("guide_completed_version")
    private val guideDismissedKey = intPreferencesKey("guide_dismissed_version")
    private val guideStepKey = intPreferencesKey("guide_step")
    private val showTipsKey = booleanPreferencesKey("help_show_tips")
    private val noticeVersionKey = intPreferencesKey("privacy_notice_version")
    private val noticeChoiceKey = stringPreferencesKey("privacy_notice_choice")
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
        songTapKey.name, mediaButtonsKey.name, audioFocusKey.name,
        backendKey.name, speedKey.name, gaplessKey.name, bluetoothLyricsKey.name,
        rotaryEnabledKey.name, rotaryActionKey.name, rotaryOrientationKey.name, rotaryHapticsKey.name,
        mainProgressKey.name, progressGestureKey.name, sleepSummaryKey.name,
        guideCompletedKey.name, guideDismissedKey.name, guideStepKey.name, showTipsKey.name,
        noticeVersionKey.name, noticeChoiceKey.name,
    ) + sortKeys.values.map { it.name } + sortDirectionKeys.values.map { it.name }
    private val allFields = PreferencesField.entries.toSet()

    fun decode(stored: Preferences): DecodedPreferences {
        // 旧字段可能与当前 key 同名但类型不同，不能直接用类型化 getter 强转。
        val raw = stored.asMap().entries.associate { entry -> entry.key.name to entry.value }
        if (raw.isEmpty()) return DecodedPreferences(UserPreferences())

        val schemaVersion = raw[schemaKey.name] as? Int
        val storedRevision = raw[revisionKey.name] as? Long
        val revision = storedRevision?.takeIf { value -> value >= 0L } ?: 0L
        val legacy = schemaVersion == 1 || schemaVersion == 2
        val legacyControls = legacy || schemaVersion == 3
        val legacyHelp = legacyControls || schemaVersion == 4
        val legacyBluetooth = legacyHelp || schemaVersion == 5
        if (!legacyBluetooth && schemaVersion != UserPreferences.CURRENT_SCHEMA_VERSION) {
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

        // Migration occurs only on an explicit write; reads preserve the original file and revision.
        val layout = if (schemaVersion == 1) LibraryLayout.LIST else {
            LibraryLayout.entries.firstOrNull { it.name == raw[libraryLayoutKey.name] }.also {
                if (it == null) invalidFields.add(PreferencesField.LIBRARY_LAYOUT)
            } ?: LibraryLayout.LIST
        }
        val artworkPath = if (schemaVersion == 1) ArtworkReadPath.SYSTEM_THUMBNAIL else {
            ArtworkReadPath.entries.firstOrNull { it.name == raw[artworkPathKey.name] }.also {
                if (it == null) invalidFields.add(PreferencesField.ARTWORK_READ_PATH)
            } ?: ArtworkReadPath.SYSTEM_THUMBNAIL
        }
        val defaults = defaultLibrarySorts()
        val sorts = LibraryListContext.entries.associateWith { context ->
            if (schemaVersion == 1) defaults.getValue(context) else {
                val field = LibrarySortField.entries.firstOrNull { it.name == raw[sortKeys.getValue(context).name] }
                val descending = raw[sortDirectionKeys.getValue(context).name] as? Boolean
                if (field == null || descending == null) invalidFields.add(PreferencesField.LIBRARY_SORTS)
                LibrarySort(field ?: defaults.getValue(context).field, descending ?: defaults.getValue(context).descending)
            }
        }

        val tapPolicy = if (legacy) SongTapPolicy.ASK else {
            SongTapPolicy.entries.firstOrNull { it.name == raw[songTapKey.name] }.also {
                if (it == null) invalidFields.add(PreferencesField.SONG_TAP_POLICY)
            } ?: SongTapPolicy.ASK
        }
        val backend = if (legacy) PlaybackBackendKind.MEDIA3 else {
            PlaybackBackendKind.entries.firstOrNull { it.name == raw[backendKey.name] }.also {
                if (it == null) invalidFields.add(PreferencesField.PLAYBACK_BACKEND)
            } ?: PlaybackBackendKind.MEDIA3
        }
        val speed = if (legacy) PlaybackPreferenceLimits.DEFAULT_SPEED_TENTHS else {
            (raw[speedKey.name] as? Int)?.takeIf {
                it in PlaybackPreferenceLimits.MIN_SPEED_TENTHS..PlaybackPreferenceLimits.MAX_SPEED_TENTHS
            }.also {
                if (it == null) invalidFields.add(PreferencesField.PLAYBACK_SPEED)
            } ?: PlaybackPreferenceLimits.DEFAULT_SPEED_TENTHS
        }
        fun playbackBoolean(key: Preferences.Key<Boolean>, field: PreferencesField): Boolean = if (legacy) true else {
            (raw[key.name] as? Boolean).also { if (it == null) invalidFields.add(field) } ?: true
        }
        val mediaButtons = playbackBoolean(mediaButtonsKey, PreferencesField.MEDIA_BUTTONS)
        val audioFocus = playbackBoolean(audioFocusKey, PreferencesField.AUDIO_FOCUS)
        val gapless = playbackBoolean(gaplessKey, PreferencesField.GAPLESS)
        // Old schemas have no external-output intent. Migration never grants or opens a receiver.
        val bluetoothLyrics = if (legacyBluetooth) false else {
            (raw[bluetoothLyricsKey.name] as? Boolean).also {
                if (it == null) invalidFields.add(PreferencesField.BLUETOOTH_LYRICS)
            } ?: false
        }

        fun controlsBoolean(key: Preferences.Key<Boolean>, field: PreferencesField, default: Boolean): Boolean =
            if (legacyControls) default else {
                (raw[key.name] as? Boolean).also { if (it == null) invalidFields.add(field) } ?: default
            }
        val rotaryAction = if (legacyControls) RotaryAction.MEDIA_VOLUME else {
            RotaryAction.entries.firstOrNull { it.name == raw[rotaryActionKey.name] }.also {
                if (it == null) invalidFields.add(PreferencesField.ROTARY_TARGET)
            } ?: RotaryAction.MEDIA_VOLUME
        }
        val rotaryOrientation = if (legacyControls) RotaryOrientation.NORMAL else {
            RotaryOrientation.entries.firstOrNull { it.name == raw[rotaryOrientationKey.name] }.also {
                if (it == null) invalidFields.add(PreferencesField.ROTARY_DIRECTION)
            } ?: RotaryOrientation.NORMAL
        }
        val progressGesture = if (legacyControls) ProgressGestureMode.DRAG_TO_COMMIT else {
            ProgressGestureMode.entries.firstOrNull { it.name == raw[progressGestureKey.name] }.also {
                if (it == null) invalidFields.add(PreferencesField.SEEK_GESTURE)
            } ?: ProgressGestureMode.DRAG_TO_COMMIT
        }
        val controls = PlaybackControlsPreferences(
            rotaryEnabled = controlsBoolean(rotaryEnabledKey, PreferencesField.ROTARY_CONTROL, false),
            rotaryAction = rotaryAction,
            rotaryOrientation = rotaryOrientation,
            rotaryHaptics = controlsBoolean(rotaryHapticsKey, PreferencesField.ROTARY_HAPTICS, false),
            showMainProgress = controlsBoolean(mainProgressKey, PreferencesField.MAIN_PROGRESS, true),
            progressGesture = progressGesture,
            sleepTimerSummary = controlsBoolean(sleepSummaryKey, PreferencesField.SLEEP_SUMMARY, false),
        )

        val help = if (legacyHelp) HelpPreferenceLimits.Legacy else {
            fun documentVersion(key: Preferences.Key<Int>): Int? =
                (raw[key.name] as? Int)?.takeIf { it in 0..HelpPreferenceLimits.MAX_DOCUMENT_VERSION }

            val completed = documentVersion(guideCompletedKey)
            val dismissed = documentVersion(guideDismissedKey)
            val step = (raw[guideStepKey.name] as? Int)?.takeIf { it in 0 until HelpPreferenceLimits.GUIDE_STEP_COUNT }
            val showTips = raw[showTipsKey.name] as? Boolean
            val noticeVersion = documentVersion(noticeVersionKey)
            val choice = PrivacyNoticeChoice.entries.firstOrNull { it.name == raw[noticeChoiceKey.name] }
            val noticeValid = noticeVersion != null && choice != null &&
                ((noticeVersion == 0) == (choice == PrivacyNoticeChoice.UNANSWERED))
            if (completed == null || dismissed == null || step == null || showTips == null || !noticeValid) {
                invalidFields.add(PreferencesField.HELP)
            }
            HelpPreferences(
                onboardingCompletedVersion = completed ?: 0,
                onboardingDismissedVersion = dismissed ?: 0,
                onboardingStep = step ?: 0,
                showTips = showTips ?: true,
                privacyNoticeVersion = if (noticeValid) checkNotNull(noticeVersion) else 0,
                privacyNoticeChoice = if (noticeValid) checkNotNull(choice) else PrivacyNoticeChoice.UNANSWERED,
            )
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
                songTapPolicy = tapPolicy,
                mediaButtonsEnabled = mediaButtons,
                audioFocusManagement = audioFocus,
                playbackBackend = backend,
                playbackSpeedTenths = speed,
                gaplessPlayback = gapless,
                controls = controls,
                help = help,
                bluetoothLyricsEnabled = bluetoothLyrics,
            ),
            recovery = recovery,
            isCanonical = !legacyBluetooth && recovery == null && raw.keys == canonicalNames,
        )
    }

    fun encode(preferences: UserPreferences): Preferences = mutablePreferencesOf(
        schemaKey to UserPreferences.CURRENT_SCHEMA_VERSION,
        revisionKey to preferences.revision,
        restorePositionKey to preferences.restorePosition,
        screenShapeKey to preferences.screenShape.name,
        libraryLayoutKey to preferences.libraryLayout.name,
        artworkPathKey to preferences.artworkReadPath.name,
        songTapKey to preferences.songTapPolicy.name,
        mediaButtonsKey to preferences.mediaButtonsEnabled,
        audioFocusKey to preferences.audioFocusManagement,
        backendKey to preferences.playbackBackend.name,
        speedKey to preferences.playbackSpeedTenths,
        gaplessKey to preferences.gaplessPlayback,
        bluetoothLyricsKey to preferences.bluetoothLyricsEnabled,
        rotaryEnabledKey to preferences.controls.rotaryEnabled,
        rotaryActionKey to preferences.controls.rotaryAction.name,
        rotaryOrientationKey to preferences.controls.rotaryOrientation.name,
        rotaryHapticsKey to preferences.controls.rotaryHaptics,
        mainProgressKey to preferences.controls.showMainProgress,
        progressGestureKey to preferences.controls.progressGesture.name,
        sleepSummaryKey to preferences.controls.sleepTimerSummary,
        guideCompletedKey to preferences.help.onboardingCompletedVersion,
        guideDismissedKey to preferences.help.onboardingDismissedVersion,
        guideStepKey to preferences.help.onboardingStep,
        showTipsKey to preferences.help.showTips,
        noticeVersionKey to preferences.help.privacyNoticeVersion,
        noticeChoiceKey to preferences.help.privacyNoticeChoice.name,
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
