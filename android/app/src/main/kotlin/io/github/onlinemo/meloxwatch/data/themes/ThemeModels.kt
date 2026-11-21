package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightCapability
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.imports.ImportLimits
import java.util.Collections
import java.util.EnumMap
import java.util.Locale
import java.util.UUID
import kotlin.math.pow

/** LP-12/14 的输入边界；已安装资源不是可以自动淘汰的图片缓存。 */
object ThemeLimits {
    const val PACKAGE_BYTES = 16L * 1024 * 1024
    const val UNPACKED_BYTES = 32L * 1024 * 1024
    const val RESOURCES = 64
    const val DIRECTORY_DEPTH = 4
    const val EXPANSION_RATIO = 20
    const val MANIFEST_BYTES = 64 * 1024
    const val LICENSE_CHARACTERS = 16 * 1024
    const val NAME_CHARACTERS = 512
    const val ID_CHARACTERS = 64
    const val PATH_CHARACTERS = 512
    const val IO_BYTES = 64 * 1024
    const val ZIP_ENTRIES = 1 + RESOURCES + RESOURCES * DIRECTORY_DEPTH
    const val ICON_EDGE = 256
    const val VALIDATION_IMAGE_EDGE = 128
    const val INSTALLED_THEMES = 8
    const val CATALOG_BYTES = 64 * 1024
    // 一份完整旧资源、一份候选及其输入包，另留有界的目录事务元数据。
    const val STORED_BYTES = 2 * UNPACKED_BYTES + PACKAGE_BYTES + 1024 * 1024
    const val OWNED_NODES = (INSTALLED_THEMES + 2) * (RESOURCES * (DIRECTORY_DEPTH + 1) + 4) + 32
    const val OWNED_DEPTH = DIRECTORY_DEPTH + 6
    const val FREE_RESERVE_BYTES = ImportLimits.FREE_RESERVE_BYTES
}

enum class ThemeResourceKind { IMAGE, FONT }
enum class ThemeBackgroundMode { DEFAULT, BLACK, COVER_BLUR, FLUID, CUSTOM_IMAGE }
enum class ThemeIconRole { PLAY, PAUSE, PREVIOUS, NEXT, MENU, BACK, LYRICS, FAVORITE, QUEUE }
enum class BuiltinTheme { DARK, LIGHT }

data class ThemeLicenseNotice(val name: String, val text: String)

/** 图片只能作为装饰背景；正文、错误和必要控制必须使用经过对比度校验的不透明表面。 */
data class ThemePalette(
    val background: Int,
    val surface: Int,
    val surfaceRaised: Int,
    val surfacePressed: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val error: Int,
    val focus: Int,
    val disabledText: Int,
)

data class ThemeBackground(
    val mode: ThemeBackgroundMode,
    val imageResourceId: String? = null,
    val blurLevel: Int = 0,
    val darkMaskPercent: Int = 50,
)

data class ThemeResource(
    val id: String,
    val path: String,
    val kind: ThemeResourceKind,
    val mediaType: String,
    val byteCount: Long,
    val sha256: String,
    val license: ThemeLicenseNotice,
)

/** 仅包含静态数据；集合复制后发布，原始 JSON、外部 URI 和字体字节不进入页面状态。 */
class ThemeManifest internal constructor(
    val id: String,
    val displayName: String,
    val author: String,
    val license: ThemeLicenseNotice,
    val palette: ThemePalette,
    val background: ThemeBackground,
    resources: List<ThemeResource>,
    icons: Map<ThemeIconRole, String>,
    fonts: Map<LyricsFontRole, String>,
) {
    val schemaVersion: Int = 1
    val resources: List<ThemeResource> = Collections.unmodifiableList(resources.toList())
    val icons: Map<ThemeIconRole, String> = immutableEnumMap(ThemeIconRole::class.java, icons)
    val fonts: Map<LyricsFontRole, String> = immutableEnumMap(LyricsFontRole::class.java, fonts)

    fun resource(id: String): ThemeResource? = this.resources.firstOrNull { it.id == id }
}

data class ThemeRef(val installationId: String, val themeId: String, val manifestSha256: String) {
    init {
        require(isThemeOperationId(installationId))
        require(isThemeId(themeId))
        require(isThemeDigest(manifestSha256))
    }
}

sealed interface ThemeChoice {
    data class Builtin(val theme: BuiltinTheme) : ThemeChoice
    data class Installed(val ref: ThemeRef) : ThemeChoice
}

data class InstalledThemeSummary(
    val ref: ThemeRef,
    val displayName: String,
    val packageSha256: String,
    val resourceBytes: Long,
)

/** revision/selected 为 null 表示尚未读到可信持久状态，不能把安全默认当作保存成功。 */
class ThemeSnapshot internal constructor(
    val revision: Long?,
    val selected: ThemeChoice?,
    val previous: ThemeChoice?,
    val effective: ThemeChoice,
    installed: List<InstalledThemeSummary>,
    val issue: ThemeIssue? = null,
    val effectiveManifest: ThemeManifest = ThemeBuiltins.manifest(BuiltinTheme.DARK),
) {
    val installed: List<InstalledThemeSummary> = Collections.unmodifiableList(installed.toList())
}

/** 预览不是选择；token 只指向本所有者的一份候选，失效后不可应用到另一个包。 */
class ThemePreview internal constructor(
    val token: String,
    val manifest: ThemeManifest,
    val expectedRevision: Long,
    val packageSha256: String?,
    val installedChoice: ThemeChoice?,
    sameIdInstallations: List<ThemeRef>,
    fontCapabilities: Map<LyricsFontRole, FontWeightCapability> = emptyMap(),
) {
    val sameIdInstallations: List<ThemeRef> = Collections.unmodifiableList(sameIdInstallations.toList())
    val fontCapabilities: Map<LyricsFontRole, FontWeightCapability> = Collections.unmodifiableMap(
        EnumMap<LyricsFontRole, FontWeightCapability>(LyricsFontRole::class.java).also { it.putAll(fontCapabilities) },
    )
}

data class ThemeImportRequest(val operationId: String, val uri: String)

/** fontLicenseAcknowledgmentVersion 沿用 LP-07 的同一许可提示，不建立另一份字体同意状态。 */
data class ThemeApplyRequest(
    val operationId: String,
    val previewToken: String,
    val expectedRevision: Long,
    val replaces: ThemeRef? = null,
    val fontLicenseAcknowledgmentVersion: Int? = null,
)

data class ThemeApplyResult(val snapshot: ThemeSnapshot, val installedRef: ThemeRef?, val cleanupRequired: Boolean)
data class ThemeRemovalResult(val snapshot: ThemeSnapshot, val filesDeleted: Boolean, val cleanupRequired: Boolean)

/**
 * 同一字体所有者提供主题目录以外的真实字节量；null 表示未取得计量，不推定为零。
 * Root 集成须让计量、导入和保留集合使用同一物理 worker/修改所有者。
 */
fun interface ThemeFontStorageBudget {
    fun otherOwnedBytes(): Long?
}

enum class ThemeIssue {
    BUSY,
    INVALID_INPUT,
    PERMISSION_DENIED,
    SOURCE_UNAVAILABLE,
    INPUT_CLOSE_FAILED,
    PACKAGE_TOO_LARGE,
    EXPANDED_TOO_LARGE,
    EXPANSION_RATIO,
    TOO_MANY_RESOURCES,
    UNSUPPORTED_ARCHIVE,
    INVALID_ARCHIVE,
    INVALID_PATH,
    DUPLICATE_PATH,
    LINK_OR_SPECIAL_FILE,
    MALFORMED_MANIFEST,
    UNSUPPORTED_SCHEMA,
    UNSUPPORTED_FIELD,
    MISSING_RESOURCE,
    UNDECLARED_RESOURCE,
    INTEGRITY_FAILED,
    UNSUPPORTED_RESOURCE,
    INVALID_IMAGE,
    IMAGE_TOO_LARGE,
    INVALID_ICON,
    INVALID_FONT,
    FONT_BUDGET_UNAVAILABLE,
    FONT_QUOTA_EXCEEDED,
    FONT_LICENSE_ACKNOWLEDGMENT_REQUIRED,
    UNREADABLE_PALETTE,
    LOW_MEMORY,
    STORAGE_FULL,
    STORAGE_QUOTA_EXCEEDED,
    STORAGE_UNAVAILABLE,
    CORRUPT_STORAGE,
    SAVE_FAILED,
    RECOVERY_REQUIRED,
    CLEANUP_REQUIRED,
    PREVIEW_NOT_FOUND,
    THEME_NOT_FOUND,
    THEME_ID_CONFLICT,
    REVISION_CONFLICT,
    OPERATION_CONFLICT,
    IN_USE,
}

class ThemeException(val issue: ThemeIssue, cause: Throwable? = null) : Exception(issue.name, cause)

internal fun themeFail(issue: ThemeIssue): Nothing = throw ThemeException(issue)
internal fun isThemeDigest(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))
internal fun isThemeId(value: String): Boolean = value.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")) &&
    !value.startsWith("builtin.")

internal fun isThemeOperationId(value: String): Boolean = try {
    value.length == 36 && UUID.fromString(value).toString() == value
} catch (_: IllegalArgumentException) {
    false
}

internal fun themeResourceLimit(kind: ThemeResourceKind): Long = when (kind) {
    ThemeResourceKind.IMAGE -> ArtworkLimits.INPUT_BYTES.toLong()
    ThemeResourceKind.FONT -> LyricsFontLimits.FILE_BYTES.toLong()
}

internal fun validateThemePalette(palette: ThemePalette) {
    val surfaces = listOf(palette.background, palette.surface, palette.surfaceRaised, palette.surfacePressed)
    val text = listOf(palette.textPrimary, palette.textSecondary, palette.error, palette.disabledText)
    val controls = listOf(palette.accent, palette.focus)
    if ((surfaces + text + controls).any { it ushr 24 != 255 }) themeFail(ThemeIssue.UNREADABLE_PALETTE)
    for (surface in surfaces) {
        if (text.any { themeContrast(it, surface) < 4.5 } || controls.any { themeContrast(it, surface) < 3.0 }) {
            themeFail(ThemeIssue.UNREADABLE_PALETTE)
        }
    }
}

internal fun themeContrast(first: Int, second: Int): Double {
    val left = themeLuminance(first)
    val right = themeLuminance(second)
    return (maxOf(left, right) + 0.05) / (minOf(left, right) + 0.05)
}

private fun themeLuminance(color: Int): Double {
    fun channel(shift: Int): Double {
        val value = (color ushr shift and 255) / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
}

private fun <T : Enum<T>> immutableEnumMap(type: Class<T>, source: Map<T, String>): Map<T, String> {
    val copy = EnumMap<T, String>(type)
    copy.putAll(source)
    return Collections.unmodifiableMap(copy)
}

internal object ThemeBuiltins {
    val defaultChoice: ThemeChoice = ThemeChoice.Builtin(BuiltinTheme.DARK)

    fun manifest(theme: BuiltinTheme): ThemeManifest {
        val palette = when (theme) {
            BuiltinTheme.DARK -> ThemePalette(
                0xff000000.toInt(), 0xff1a1a1a.toInt(), 0xff262626.toInt(), 0xff3a3a3a.toInt(),
                0xffffffff.toInt(), 0xffc7c7cc.toInt(), 0xffff375f.toInt(), 0xffffb4ab.toInt(),
                0xff8a8a8a.toInt(), 0xffadadad.toInt(),
            )
            BuiltinTheme.LIGHT -> ThemePalette(
                0xffffffff.toInt(), 0xfff2f2f7.toInt(), 0xffe5e5ea.toInt(), 0xffd9d9e0.toInt(),
                0xff000000.toInt(), 0xff3c3c43.toInt(), 0xffb00020.toInt(), 0xff9b001a.toInt(),
                0xff606066.toInt(), 0xff4a4a50.toInt(),
            )
        }
        validateThemePalette(palette)
        return ThemeManifest(
            "builtin." + theme.name.lowercase(Locale.ROOT), "MeloX Watch " + theme.name.lowercase(Locale.ROOT), "MeloX Watch",
            ThemeLicenseNotice("Built-in", "Original color tokens; see the application license information."),
            palette, ThemeBackground(ThemeBackgroundMode.DEFAULT), emptyList(), emptyMap(), emptyMap(),
        )
    }
}
