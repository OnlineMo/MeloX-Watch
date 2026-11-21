package io.github.onlinemo.meloxwatch.data.themes

import android.util.JsonReader
import android.util.JsonToken
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.EnumMap
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** schema 1 只有显式白名单字段，不跳过未知代码、远端引用或重复 JSON 键。 */
internal object ThemeManifestReader {
    fun read(bytes: ByteArray, checkCancellation: () -> Unit = {}): ThemeManifest {
        if (bytes.isEmpty() || bytes.size > ThemeLimits.MANIFEST_BYTES) themeFail(ThemeIssue.MALFORMED_MANIFEST)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw ThemeException(ThemeIssue.MALFORMED_MANIFEST, error)
        }
        if (text.startsWith('\uFEFF')) themeFail(ThemeIssue.MALFORMED_MANIFEST)
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                val manifest = Reader(reader, checkCancellation).manifest()
                checkCancellation()
                if (reader.peek() != JsonToken.END_DOCUMENT) themeFail(ThemeIssue.MALFORMED_MANIFEST)
                validateReferences(manifest)
                validateThemePalette(manifest.palette)
                return manifest
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ThemeException) {
            throw error
        } catch (error: IOException) {
            throw ThemeException(ThemeIssue.MALFORMED_MANIFEST, error)
        } catch (error: IllegalStateException) {
            throw ThemeException(ThemeIssue.MALFORMED_MANIFEST, error)
        }
    }

    private fun validateReferences(manifest: ThemeManifest) {
        val paths = HashSet<String>()
        val ids = HashSet<String>()
        var bytes = 0L
        for (resource in manifest.resources) {
            if (!ids.add(resource.id)) themeFail(ThemeIssue.MALFORMED_MANIFEST)
            if (!paths.add(themeAssetPath(resource.path))) themeFail(ThemeIssue.DUPLICATE_PATH)
            if (resource.byteCount > ThemeLimits.UNPACKED_BYTES - bytes) themeFail(ThemeIssue.EXPANDED_TOO_LARGE)
            bytes += resource.byteCount
        }
        val backgroundImage = manifest.background.imageResourceId
        if ((manifest.background.mode == ThemeBackgroundMode.CUSTOM_IMAGE) != (backgroundImage != null)) {
            themeFail(ThemeIssue.MALFORMED_MANIFEST)
        }
        if (backgroundImage != null && manifest.resource(backgroundImage)?.kind != ThemeResourceKind.IMAGE) {
            themeFail(ThemeIssue.MISSING_RESOURCE)
        }
        for (id in manifest.icons.values) {
            val resource = manifest.resource(id) ?: themeFail(ThemeIssue.MISSING_RESOURCE)
            if (resource.kind != ThemeResourceKind.IMAGE || resource.mediaType != "image/png") {
                themeFail(ThemeIssue.INVALID_ICON)
            }
        }
        val fontIds = manifest.resources.filter { it.kind == ThemeResourceKind.FONT }.map { it.id }.toSet()
        if (fontIds.size > LyricsFontRole.entries.size || fontIds != manifest.fonts.values.toSet()) {
            themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
        }
    }

    private class Reader(private val input: JsonReader, private val checkCancellation: () -> Unit) {
        fun manifest(): ThemeManifest {
            var schema: Int? = null
            var id: String? = null
            var name: String? = null
            var author: String? = null
            var license: ThemeLicenseNotice? = null
            var palette: ThemePalette? = null
            var background: ThemeBackground? = null
            var resources: List<ThemeResource>? = null
            var icons: Map<ThemeIconRole, String> = emptyMap()
            var fonts: Map<LyricsFontRole, String> = emptyMap()
            fields(MANIFEST_FIELDS, MANIFEST_REQUIRED) { field ->
                when (field) {
                    "schemaVersion" -> schema = integer(0, Int.MAX_VALUE)
                    "id" -> id = identifier()
                    "displayName" -> name = string(ThemeLimits.NAME_CHARACTERS)
                    "author" -> author = string(ThemeLimits.NAME_CHARACTERS)
                    "license" -> license = license()
                    "colors" -> palette = colors()
                    "background" -> background = background()
                    "resources" -> resources = resources()
                    "icons" -> icons = icons()
                    "fonts" -> fonts = fonts()
                }
            }
            if (schema != 1) themeFail(ThemeIssue.UNSUPPORTED_SCHEMA)
            return ThemeManifest(
                required(id), required(name), required(author), required(license), required(palette),
                required(background), required(resources), icons, fonts,
            )
        }

        private fun resources(): List<ThemeResource> {
            token(JsonToken.BEGIN_ARRAY)
            input.beginArray()
            val result = ArrayList<ThemeResource>()
            while (input.hasNext()) {
                checkCancellation()
                if (result.size == ThemeLimits.RESOURCES) themeFail(ThemeIssue.TOO_MANY_RESOURCES)
                result += resource()
            }
            input.endArray()
            return result
        }

        private fun resource(): ThemeResource {
            var id: String? = null
            var path: String? = null
            var kind: ThemeResourceKind? = null
            var mediaType: String? = null
            var bytes: Long? = null
            var sha256: String? = null
            var notice: ThemeLicenseNotice? = null
            fields(RESOURCE_FIELDS, RESOURCE_FIELDS) { field ->
                when (field) {
                    "id" -> id = identifier()
                    "path" -> path = string(ThemeLimits.PATH_CHARACTERS)
                    "kind" -> kind = when (string(16)) {
                        "image" -> ThemeResourceKind.IMAGE
                        "font" -> ThemeResourceKind.FONT
                        else -> themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
                    }
                    "mediaType" -> mediaType = string(64)
                    "byteCount" -> bytes = longInteger(1, ThemeLimits.UNPACKED_BYTES)
                    "sha256" -> sha256 = string(64)
                    "license" -> notice = license()
                }
            }
            val resolvedKind = required(kind)
            val resolvedBytes = required(bytes)
            val resolvedPath = required(path)
            val resolvedType = required(mediaType)
            val resolvedDigest = required(sha256)
            themeAssetPath(resolvedPath)
            if (resolvedBytes > themeResourceLimit(resolvedKind)) {
                themeFail(if (resolvedKind == ThemeResourceKind.IMAGE) ThemeIssue.IMAGE_TOO_LARGE else ThemeIssue.INVALID_FONT)
            }
            if (!isThemeDigest(resolvedDigest)) themeFail(ThemeIssue.MALFORMED_MANIFEST)
            val extension = resolvedPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val allowed = when (resolvedType) {
                "image/png" -> resolvedKind == ThemeResourceKind.IMAGE && extension == "png"
                "image/jpeg" -> resolvedKind == ThemeResourceKind.IMAGE && extension in setOf("jpg", "jpeg")
                "image/webp" -> resolvedKind == ThemeResourceKind.IMAGE && extension == "webp"
                "font/ttf" -> resolvedKind == ThemeResourceKind.FONT && extension == "ttf"
                "font/otf" -> resolvedKind == ThemeResourceKind.FONT && extension == "otf"
                else -> false
            }
            if (!allowed) themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
            return ThemeResource(
                required(id), resolvedPath, resolvedKind, resolvedType, resolvedBytes, resolvedDigest, required(notice),
            )
        }

        private fun colors(): ThemePalette {
            val result = EnumMap<ColorToken, Int>(ColorToken::class.java)
            fields(COLOR_FIELDS, COLOR_FIELDS) { field ->
                val role = ColorToken.entries.first { it.jsonName == field }
                val value = string(7)
                if (!value.matches(Regex("#[0-9A-Fa-f]{6}"))) themeFail(ThemeIssue.UNREADABLE_PALETTE)
                result[role] = 0xff000000.toInt() or value.substring(1).toInt(16)
            }
            return ThemePalette(
                result.getValue(ColorToken.BACKGROUND), result.getValue(ColorToken.SURFACE),
                result.getValue(ColorToken.SURFACE_RAISED), result.getValue(ColorToken.SURFACE_PRESSED),
                result.getValue(ColorToken.TEXT_PRIMARY), result.getValue(ColorToken.TEXT_SECONDARY),
                result.getValue(ColorToken.ACCENT), result.getValue(ColorToken.ERROR),
                result.getValue(ColorToken.FOCUS), result.getValue(ColorToken.DISABLED_TEXT),
            )
        }

        private fun background(): ThemeBackground {
            var mode: ThemeBackgroundMode? = null
            var image: String? = null
            var blur = 0
            var mask = 50
            fields(BACKGROUND_FIELDS, setOf("mode")) { field ->
                when (field) {
                    "mode" -> mode = when (string(32)) {
                        "default" -> ThemeBackgroundMode.DEFAULT
                        "black" -> ThemeBackgroundMode.BLACK
                        "coverBlur" -> ThemeBackgroundMode.COVER_BLUR
                        "fluid" -> ThemeBackgroundMode.FLUID
                        "customImage" -> ThemeBackgroundMode.CUSTOM_IMAGE
                        else -> themeFail(ThemeIssue.UNSUPPORTED_FIELD)
                    }
                    "image" -> image = identifier()
                    "blurLevel" -> blur = integer(0, 20)
                    "darkMaskPercent" -> mask = integer(0, 100)
                }
            }
            return ThemeBackground(required(mode), image, blur, mask)
        }

        private fun icons(): Map<ThemeIconRole, String> {
            val result = EnumMap<ThemeIconRole, String>(ThemeIconRole::class.java)
            fields(ICON_FIELDS, emptySet()) { field ->
                val role = ThemeIconRole.entries.first { it.name.lowercase(Locale.ROOT) == field }
                result[role] = identifier()
            }
            return result
        }

        private fun fonts(): Map<LyricsFontRole, String> {
            val result = EnumMap<LyricsFontRole, String>(LyricsFontRole::class.java)
            fields(setOf("original", "translation"), emptySet()) { field ->
                val role = if (field == "original") LyricsFontRole.ORIGINAL else LyricsFontRole.TRANSLATION
                result[role] = identifier()
            }
            return result
        }

        private fun license(): ThemeLicenseNotice {
            var name: String? = null
            var text: String? = null
            fields(setOf("name", "text"), setOf("name", "text")) { field ->
                when (field) {
                    "name" -> name = string(ThemeLimits.NAME_CHARACTERS)
                    "text" -> text = string(ThemeLimits.LICENSE_CHARACTERS, multiline = true)
                }
            }
            return ThemeLicenseNotice(required(name), required(text))
        }

        private fun fields(allowed: Set<String>, required: Set<String>, read: (String) -> Unit) {
            token(JsonToken.BEGIN_OBJECT)
            input.beginObject()
            val seen = HashSet<String>()
            while (input.hasNext()) {
                checkCancellation()
                val key = input.nextName()
                if (key !in allowed) themeFail(ThemeIssue.UNSUPPORTED_FIELD)
                if (!seen.add(key)) themeFail(ThemeIssue.MALFORMED_MANIFEST)
                read(key)
            }
            input.endObject()
            if (!seen.containsAll(required)) themeFail(ThemeIssue.MALFORMED_MANIFEST)
        }

        private fun identifier(): String {
            val value = string(ThemeLimits.ID_CHARACTERS)
            if (!isThemeId(value)) themeFail(ThemeIssue.MALFORMED_MANIFEST)
            return value
        }

        private fun string(maximum: Int, multiline: Boolean = false): String {
            token(JsonToken.STRING)
            val value = input.nextString()
            if (value.isBlank() || value.length > maximum || !validText(value, multiline)) {
                themeFail(ThemeIssue.MALFORMED_MANIFEST)
            }
            return value
        }

        private fun integer(minimum: Int, maximum: Int): Int = longInteger(minimum.toLong(), maximum.toLong()).toInt()

        private fun longInteger(minimum: Long, maximum: Long): Long {
            token(JsonToken.NUMBER)
            val text = input.nextString()
            if (!text.matches(Regex("0|[1-9][0-9]*"))) themeFail(ThemeIssue.MALFORMED_MANIFEST)
            val value = text.toLongOrNull() ?: themeFail(ThemeIssue.MALFORMED_MANIFEST)
            if (value !in minimum..maximum) themeFail(ThemeIssue.MALFORMED_MANIFEST)
            return value
        }

        private fun token(token: JsonToken) {
            checkCancellation()
            if (input.peek() != token) themeFail(ThemeIssue.MALFORMED_MANIFEST)
        }
    }

    private fun validText(value: String, multiline: Boolean): Boolean {
        var offset = 0
        while (offset < value.length) {
            val character = value[offset]
            if (character.isISOControl() && !(multiline && character in setOf('\n', '\t'))) return false
            if (Character.isHighSurrogate(character)) {
                if (offset + 1 == value.length || !Character.isLowSurrogate(value[offset + 1])) return false
                offset++
            } else if (Character.isLowSurrogate(character)) return false
            offset++
        }
        return true
    }

    private fun <T> required(value: T?): T = value ?: themeFail(ThemeIssue.MALFORMED_MANIFEST)

    private enum class ColorToken(val jsonName: String) {
        BACKGROUND("background"), SURFACE("surface"), SURFACE_RAISED("surfaceRaised"),
        SURFACE_PRESSED("surfacePressed"), TEXT_PRIMARY("textPrimary"), TEXT_SECONDARY("textSecondary"),
        ACCENT("accent"), ERROR("error"), FOCUS("focus"), DISABLED_TEXT("disabledText"),
    }

    private val MANIFEST_REQUIRED = setOf(
        "schemaVersion", "id", "displayName", "author", "license", "colors", "background", "resources",
    )
    private val MANIFEST_FIELDS = MANIFEST_REQUIRED + setOf("icons", "fonts")
    private val RESOURCE_FIELDS = setOf("id", "path", "kind", "mediaType", "byteCount", "sha256", "license")
    private val COLOR_FIELDS = ColorToken.entries.map { it.jsonName }.toSet()
    private val BACKGROUND_FIELDS = setOf("mode", "image", "blurLevel", "darkMaskPercent")
    private val ICON_FIELDS = ThemeIconRole.entries.map { it.name.lowercase(Locale.ROOT) }.toSet()
}
