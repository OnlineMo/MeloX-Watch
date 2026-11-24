package io.github.onlinemo.meloxwatch.data.fonts

import android.graphics.Typeface
import io.github.onlinemo.meloxwatch.data.imports.ImportLimits
import java.util.UUID

/** LP-07/14 representation bounds; smaller test limits must not widen the production contract. */
object LyricsFontLimits {
    const val FILE_BYTES = 8 * 1024 * 1024
    const val OWNED_BYTES = 32L * 1024 * 1024
    const val IO_BYTES = 64 * 1024
    const val TABLES = 128
    const val AXES = 32
    const val CMAP_RECORDS = 64
    const val CMAP_SEGMENTS = 8192
    const val CMAP_GROUPS = 65_536
    const val DIRECTORY_ENTRIES = 64
    const val DISPLAY_NAME_CHARACTERS = 512
    const val TEXT_CHARACTERS = 1024 * 1024
    const val LICENSE_ACKNOWLEDGMENT_VERSION = 1
    const val FREE_RESERVE_BYTES = ImportLimits.FREE_RESERVE_BYTES
}

enum class LyricsFontRole { ORIGINAL, TRANSLATION }

/** No external URI survives import; this record names a verified application-owned copy. */
data class LyricsFontAsset(
    val id: String,
    val sha256: String,
    val byteCount: Long,
    val displayName: String,
    val licenseAcknowledgmentVersion: Int,
) {
    init {
        require(isLyricsFontAssetId(id))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(byteCount in 1..LyricsFontLimits.FILE_BYTES.toLong())
        require(validFontDisplayName(displayName))
        require(licenseAcknowledgmentVersion == LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION)
    }
}

data class LyricsFontSelection(
    val asset: LyricsFontAsset? = null,
    val requestedWeight: Float? = null,
) {
    init { require(requestedWeight == null || requestedWeight.isFinite()) }
}

data class LyricsFontSettings(
    val revision: Long = 0L,
    val original: LyricsFontSelection = LyricsFontSelection(),
    val translation: LyricsFontSelection = LyricsFontSelection(),
) {
    init { require(revision >= 0L) }
    fun selection(role: LyricsFontRole): LyricsFontSelection = when (role) {
        LyricsFontRole.ORIGINAL -> original
        LyricsFontRole.TRANSLATION -> translation
    }

    fun withSelection(role: LyricsFontRole, selection: LyricsFontSelection): LyricsFontSettings = when (role) {
        LyricsFontRole.ORIGINAL -> copy(original = selection)
        LyricsFontRole.TRANSLATION -> copy(translation = selection)
    }

    fun assetIds(): Set<String> = setOfNotNull(original.asset?.id, translation.asset?.id)
}

sealed interface LyricsFontSaveResult {
    data class Saved(val settings: LyricsFontSettings) : LyricsFontSaveResult
    data class Conflict(val settings: LyricsFontSettings) : LyricsFontSaveResult
}

/**
 * The root preference adapter owns durable atomic CAS and increments revision exactly once.
 * A failed write may have reached storage: callers must read the actual record before retrying.
 * Every font mutation, including restore/companion integration, uses the single editor owner.
 */
interface LyricsFontSettingsStore {
    suspend fun read(): LyricsFontSettings
    suspend fun compareAndSet(expectedRevision: Long, replacement: LyricsFontSettings): LyricsFontSaveResult
}

data class LyricsFontImportRequest(
    val uri: String,
    val displayName: String,
    val licenseAcknowledgmentVersion: Int,
)

enum class LyricsFontIssue {
    BUSY,
    INVALID_INPUT,
    LICENSE_ACKNOWLEDGMENT_REQUIRED,
    PERMISSION_DENIED,
    SOURCE_UNAVAILABLE,
    TOO_LARGE,
    QUOTA_EXCEEDED,
    FONT_BUDGET_UNAVAILABLE,
    STORAGE_FULL,
    UNSUPPORTED_FORMAT,
    INVALID_FONT,
    INTEGRITY_FAILED,
    NATIVE_DECODE_FAILED,
    MISSING_GLYPHS,
    LOW_MEMORY,
    SETTINGS_UNAVAILABLE,
    SAVE_FAILED,
    SETTINGS_CONFLICT,
}

class LyricsFontException(val issue: LyricsFontIssue, cause: Throwable? = null) : Exception(issue.name, cause)

data class FontWeightAxis(val minimum: Float, val default: Float, val maximum: Float) {
    init {
        require(minimum.isFinite() && default.isFinite() && maximum.isFinite())
        require(minimum <= default && default <= maximum)
    }

    fun clamp(requested: Float?): Float = (requested ?: default).coerceIn(minimum, maximum)
}

enum class FontWeightSupport { AVAILABLE, NO_AXIS, PLATFORM_UNAVAILABLE, NO_OBSERVED_CHANGE }

/** Fixed-size raster evidence, not a mirror of the API's requested variation settings. */
data class FontWeightObservation(
    val differingPixels: Int,
    val minimumInkPixels: Int,
    val maximumInkPixels: Int,
    val probeCodePoints: Int,
) {
    val changed: Boolean get() = differingPixels > 0 && minimumInkPixels > 0 && maximumInkPixels > 0
}

data class FontWeightCapability(
    val support: FontWeightSupport,
    val axis: FontWeightAxis?,
    val observation: FontWeightObservation? = null,
)

/** Configured axis value is separate from native style metadata and observed endpoint glyphs. */
data class LoadedLyricsFont(
    val typeface: Typeface? = null,
    val asset: LyricsFontAsset? = null,
    val requestedWeight: Float? = null,
    val clampedWeight: Float? = null,
    val configuredAxisWeight: Float? = null,
    val nativeStyleWeight: Int? = null,
    val capability: FontWeightCapability = FontWeightCapability(FontWeightSupport.NO_AXIS, null),
    val issue: LyricsFontIssue? = null,
    val missingCodePoints: Int = 0,
    val firstMissingCodePoint: Int? = null,
) {
    val usesSystemFont: Boolean get() = typeface == null
}

data class PreparedLyricsFont(
    val asset: LyricsFontAsset,
    val info: SfntFontInfo,
    val capability: FontWeightCapability,
)

interface LyricsFontFiles {
    /** retain contains both saved roles and the sole editor draft/retry asset. */
    suspend fun importFont(request: LyricsFontImportRequest, retain: Set<String>): PreparedLyricsFont
    suspend fun loadFont(selection: LyricsFontSelection, text: String): LoadedLyricsFont
    suspend fun discard(asset: LyricsFontAsset, retain: Set<String>)
    suspend fun prune(retain: Set<String>)
}

internal fun fontFail(issue: LyricsFontIssue): Nothing = throw LyricsFontException(issue)

internal fun isLyricsFontAssetId(value: String): Boolean = value.length == 36 && try {
    UUID.fromString(value).toString() == value
} catch (_: IllegalArgumentException) { false }

internal fun validFontDisplayName(value: String): Boolean = value.isNotBlank() &&
    value.length <= LyricsFontLimits.DISPLAY_NAME_CHARACTERS && value.none(Char::isISOControl)
