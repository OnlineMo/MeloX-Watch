package io.github.onlinemo.meloxwatch.model

enum class ScreenShape {
    AUTO,
    ROUND,
    RECTANGULAR,
}

/** 经校验的偏好快照；恢复位置只影响下一次恢复，绝不代表自动播放意图。 */
data class UserPreferences(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revision: Long = 0L,
    val restorePosition: Boolean = true,
    val screenShape: ScreenShape = ScreenShape.AUTO,
    val libraryLayout: LibraryLayout = LibraryLayout.LIST,
    val artworkReadPath: ArtworkReadPath = ArtworkReadPath.SYSTEM_THUMBNAIL,
    val librarySorts: Map<LibraryListContext, LibrarySort> = defaultLibrarySorts(),
    val songTapPolicy: SongTapPolicy = SongTapPolicy.ASK,
    val mediaButtonsEnabled: Boolean = true,
    val audioFocusManagement: Boolean = true,
    val playbackBackend: PlaybackBackendKind = PlaybackBackendKind.MEDIA3,
    val playbackSpeedTenths: Int = PlaybackPreferenceLimits.DEFAULT_SPEED_TENTHS,
    val gaplessPlayback: Boolean = true,
    val controls: PlaybackControlsPreferences = PlaybackControlsPreferences(),
    val help: HelpPreferences = HelpPreferences(),
    val bluetoothLyricsEnabled: Boolean = false,
    val metadataLookupEnabled: Boolean = false,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION)
        require(revision >= 0L)
        require(librarySorts.keys == LibraryListContext.entries.toSet())
        require(playbackSpeedTenths in PlaybackPreferenceLimits.MIN_SPEED_TENTHS..PlaybackPreferenceLimits.MAX_SPEED_TENTHS)
    }

    fun asDraft(): UserPreferencesDraft = UserPreferencesDraft(
        restorePosition = restorePosition,
        screenShape = screenShape,
        libraryLayout = libraryLayout,
        artworkReadPath = artworkReadPath,
        librarySorts = librarySorts.toMap(),
        songTapPolicy = songTapPolicy,
        mediaButtonsEnabled = mediaButtonsEnabled,
        audioFocusManagement = audioFocusManagement,
        playbackBackend = playbackBackend,
        playbackSpeedTenths = playbackSpeedTenths,
        gaplessPlayback = gaplessPlayback,
        controls = controls,
        help = help,
        bluetoothLyricsEnabled = bluetoothLyricsEnabled,
        metadataLookupEnabled = metadataLookupEnabled,
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 7
    }
}

/** 页面预览不修改已保存值；只有带预期 revision 的明确保存才提交。 */
data class UserPreferencesDraft(
    val restorePosition: Boolean = true,
    val screenShape: ScreenShape = ScreenShape.AUTO,
    val libraryLayout: LibraryLayout = LibraryLayout.LIST,
    val artworkReadPath: ArtworkReadPath = ArtworkReadPath.SYSTEM_THUMBNAIL,
    val librarySorts: Map<LibraryListContext, LibrarySort> = defaultLibrarySorts(),
    val songTapPolicy: SongTapPolicy = SongTapPolicy.ASK,
    val mediaButtonsEnabled: Boolean = true,
    val audioFocusManagement: Boolean = true,
    val playbackBackend: PlaybackBackendKind = PlaybackBackendKind.MEDIA3,
    val playbackSpeedTenths: Int = PlaybackPreferenceLimits.DEFAULT_SPEED_TENTHS,
    val gaplessPlayback: Boolean = true,
    val controls: PlaybackControlsPreferences = PlaybackControlsPreferences(),
    val help: HelpPreferences = HelpPreferences(),
    val bluetoothLyricsEnabled: Boolean = false,
    val metadataLookupEnabled: Boolean = false,
) {
    init {
        require(librarySorts.keys == LibraryListContext.entries.toSet())
        require(playbackSpeedTenths in PlaybackPreferenceLimits.MIN_SPEED_TENTHS..PlaybackPreferenceLimits.MAX_SPEED_TENTHS)
    }
}
