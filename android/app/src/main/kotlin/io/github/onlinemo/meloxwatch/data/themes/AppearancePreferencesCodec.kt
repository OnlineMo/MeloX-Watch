package io.github.onlinemo.meloxwatch.data.themes

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal object AppearancePreferencesCodec {
    private val schema = intPreferencesKey("schema")
    private val revision = longPreferencesKey("revision")
    private val scale = intPreferencesKey("scale_percent")
    private val horizontal = intPreferencesKey("horizontal_margin_dp")
    private val vertical = intPreferencesKey("vertical_margin_dp")
    private val compactTitle = booleanPreferencesKey("compact_playback_title")
    private val keepBright = booleanPreferencesKey("lyrics_keep_bright")
    private val language = stringPreferencesKey("language")

    fun decode(stored: Preferences): AppearanceSnapshot {
        val values = try { boundedValues(stored.asMap()) }
        catch (error: InputRejected) { return AppearanceSnapshot(null, error.issue) }
        if (values.isEmpty()) return AppearanceSnapshot(AppearanceSettings())
        val version = values[schema.name] as? Int
        if (version != 1) return if (version != null && version > 1) AppearanceSnapshot(null, AppearanceIssue.NEWER_SCHEMA)
            else AppearanceSnapshot(null, AppearanceIssue.INVALID_SETTINGS, recoveryToken(values))
        val savedRevision = values[revision.name] as? Long
        if (savedRevision == null || savedRevision < 0L) return AppearanceSnapshot(null, AppearanceIssue.INVALID_SETTINGS, recoveryToken(values))
        val scalePercent = values[scale.name] as? Int
        val horizontalDp = values[horizontal.name] as? Int
        val verticalDp = values[vertical.name] as? Int
        val compact = values[compactTitle.name] as? Boolean
        val bright = values[keepBright.name] as? Boolean
        val selectedLanguage = AppLanguage.entries.firstOrNull { it.name == values[language.name] }
        val valid = scalePercent != null && scalePercent in 85..120 && scalePercent % 5 == 0 &&
            horizontalDp != null && horizontalDp in 0..24 && verticalDp != null && verticalDp in 0..24 &&
            compact != null && bright != null && selectedLanguage != null
        // 无效字段回安全显示值；保留可信修订号，明确保存才能修复此偏好文件。
        val options = AppearanceOptions(
            scalePercent = scalePercent?.takeIf { it in 85..120 && it % 5 == 0 } ?: 100,
            horizontalMarginDp = horizontalDp?.takeIf { it in 0..24 } ?: 0,
            verticalMarginDp = verticalDp?.takeIf { it in 0..24 } ?: 0,
            compactPlaybackTitle = compact ?: false,
            lyricsKeepBright = bright ?: false,
            language = selectedLanguage ?: AppLanguage.SYSTEM,
        )
        return AppearanceSnapshot(AppearanceSettings(savedRevision, options),
            if (valid) null else AppearanceIssue.INVALID_SETTINGS)
    }

    fun encode(settings: AppearanceSettings): Preferences = mutablePreferencesOf(
        schema to 1,
        revision to settings.revision,
        scale to settings.options.scalePercent,
        horizontal to settings.options.horizontalMarginDp,
        vertical to settings.options.verticalMarginDp,
        compactTitle to settings.options.compactPlaybackTitle,
        keepBright to settings.options.lyricsKeepBright,
        language to settings.options.language.name,
    )

    private fun boundedValues(stored: Map<Preferences.Key<*>, Any>): Map<String, Any> {
        if (stored.size > AppearanceCodecLimits.KEYS) throw InputRejected(AppearanceIssue.SETTINGS_TOO_LARGE)
        val result = LinkedHashMap<String, Any>(stored.size)
        var units = 0
        fun add(count: Int) {
            if (count < 0 || count > AppearanceCodecLimits.RECORD_UNITS - units) {
                throw InputRejected(AppearanceIssue.SETTINGS_TOO_LARGE)
            }
            units += count
        }
        fun string(value: String, limit: Int = AppearanceCodecLimits.STRING_CHARACTERS) {
            if (value.length > limit) throw InputRejected(AppearanceIssue.SETTINGS_TOO_LARGE)
            add(value.length)
        }
        for ((key, value) in stored) {
            string(key.name, AppearanceCodecLimits.KEY_CHARACTERS)
            if (result.put(key.name, value) != null) throw InputRejected(AppearanceIssue.UNRECOVERABLE_SETTINGS)
            when (value) {
                is Boolean -> add(1)
                is Int, is Float -> add(4)
                is Long, is Double -> add(8)
                is String -> string(value)
                is ByteArray -> {
                    if (value.size > AppearanceCodecLimits.BINARY_BYTES) throw InputRejected(AppearanceIssue.SETTINGS_TOO_LARGE)
                    add(value.size)
                }
                is Set<*> -> {
                    if (value.size > AppearanceCodecLimits.SET_ITEMS) throw InputRejected(AppearanceIssue.SETTINGS_TOO_LARGE)
                    for (item in value) {
                        if (item !is String) throw InputRejected(AppearanceIssue.UNRECOVERABLE_SETTINGS)
                        string(item)
                    }
                }
                else -> throw InputRejected(AppearanceIssue.UNRECOVERABLE_SETTINGS)
            }
        }
        return result
    }

    /** Typed, length-framed UTF-16 code units distinguish NUL, surrogates and collection elements. */
    private fun recoveryToken(values: Map<String, Any>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(DiscardOutput, digest)).use { output ->
            output.writeInt(1)
            output.writeInt(values.size)
            for ((key, value) in values.entries.sortedBy { it.key }) {
                output.framedString(key)
                when (value) {
                    is Boolean -> { output.writeByte(1); output.writeBoolean(value) }
                    is Int -> { output.writeByte(2); output.writeInt(value) }
                    is Long -> { output.writeByte(3); output.writeLong(value) }
                    is Float -> { output.writeByte(4); output.writeInt(value.toRawBits()) }
                    is Double -> { output.writeByte(5); output.writeLong(value.toRawBits()) }
                    is String -> { output.writeByte(6); output.framedString(value) }
                    is Set<*> -> {
                        output.writeByte(7)
                        output.writeInt(value.size)
                        value.map { it as String }.sorted().forEach { output.framedString(it) }
                    }
                    is ByteArray -> { output.writeByte(8); output.writeInt(value.size); output.write(value) }
                    else -> throw InputRejected(AppearanceIssue.UNRECOVERABLE_SETTINGS)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun DataOutputStream.framedString(value: String) {
        writeInt(value.length)
        for (character in value) writeChar(character.code)
    }

    private object DiscardOutput : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }

    private class InputRejected(val issue: AppearanceIssue) : Exception(issue.name)
}

/** Bounds apply before hashing or sorting; over-limit records cannot obtain a repair token. */
internal object AppearanceCodecLimits {
    const val KEYS = 32
    const val KEY_CHARACTERS = 128
    const val STRING_CHARACTERS = 4096
    const val SET_ITEMS = 64
    const val BINARY_BYTES = 4096
    const val RECORD_UNITS = 8192
}
