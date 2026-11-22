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
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION)
        require(revision >= 0L)
        require(librarySorts.keys == LibraryListContext.entries.toSet())
    }

    fun asDraft(): UserPreferencesDraft = UserPreferencesDraft(
        restorePosition, screenShape, libraryLayout, artworkReadPath, librarySorts.toMap(),
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

/** 页面预览不修改已保存值；只有带预期 revision 的明确保存才提交。 */
data class UserPreferencesDraft(
    val restorePosition: Boolean = true,
    val screenShape: ScreenShape = ScreenShape.AUTO,
    val libraryLayout: LibraryLayout = LibraryLayout.LIST,
    val artworkReadPath: ArtworkReadPath = ArtworkReadPath.SYSTEM_THUMBNAIL,
    val librarySorts: Map<LibraryListContext, LibrarySort> = defaultLibrarySorts(),
) {
    init {
        require(librarySorts.keys == LibraryListContext.entries.toSet())
    }
}
