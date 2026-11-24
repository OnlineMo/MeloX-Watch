package io.github.onlinemo.meloxwatch.data.lyrics.store

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey

internal data class DecodedLyricsDisplay(
    val preferences: LyricsDisplayPreferences?,
    val persisted: Boolean,
    val issue: LyricsDisplayIssue? = null,
)

/** A malformed or unknown envelope stays read-only; decoding never invents a saved default. */
internal object LyricsDisplayCodec {
    private val schema = intPreferencesKey("schema_version")
    private val revision = longPreferencesKey("revision")
    private val textSize = floatPreferencesKey("text_size_sp")
    private val blockSpacing = floatPreferencesKey("block_spacing_dp")
    private val alignment = stringPreferencesKey("alignment")
    private val anchor = floatPreferencesKey("anchor_fraction")
    private val margin = floatPreferencesKey("horizontal_margin_dp")
    private val translation = booleanPreferencesKey("show_translation")
    private val words = booleanPreferencesKey("emphasize_words")
    private val keepScreen = booleanPreferencesKey("keep_screen_on")
    private val canonicalNames = setOf(schema.name, revision.name, textSize.name, blockSpacing.name,
        alignment.name, anchor.name, margin.name, translation.name, words.name, keepScreen.name)

    fun decode(stored: Preferences): DecodedLyricsDisplay {
        val entries = stored.asMap()
        if (entries.isEmpty()) return DecodedLyricsDisplay(LyricsDisplayPreferences(), persisted = false)
        if (entries.size > canonicalNames.size) return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        // Typed getters would cast a corrupt/old value before its type was checked.
        val raw = entries.entries.associate { it.key.name to it.value }
        val storedSchema = raw[schema.name] as? Int ?: return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        if (storedSchema != LyricsDisplayLimits.SCHEMA_VERSION) return rejected(LyricsDisplayIssue.UNSUPPORTED_SCHEMA)
        if (raw.keys != canonicalNames) return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        val storedRevision = raw[revision.name] as? Long ?: return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        val storedText = raw[textSize.name] as? Float ?: return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        val storedBlock = raw[blockSpacing.name] as? Float ?: return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        val storedAlignment = (raw[alignment.name] as? String)?.takeIf { it.length <= 6 }?.let { name ->
            LyricsDisplayAlignment.entries.firstOrNull { it.name == name }
        } ?: return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        val storedAnchor = raw[anchor.name] as? Float ?: return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        val storedMargin = raw[margin.name] as? Float ?: return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        val storedTranslation = raw[translation.name] as? Boolean ?: return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        val storedWords = raw[words.name] as? Boolean ?: return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        val storedKeepScreen = raw[keepScreen.name] as? Boolean ?: return rejected(LyricsDisplayIssue.INVALID_FIELDS)
        return try {
            DecodedLyricsDisplay(LyricsDisplayPreferences(storedRevision, storedText, storedBlock, storedAlignment,
                storedAnchor, storedMargin, storedTranslation, storedWords, storedKeepScreen), persisted = true)
        } catch (_: IllegalArgumentException) { rejected(LyricsDisplayIssue.INVALID_FIELDS) }
    }

    fun encode(preferences: LyricsDisplayPreferences): Preferences = mutablePreferencesOf(
        schema to LyricsDisplayLimits.SCHEMA_VERSION,
        revision to preferences.revision,
        textSize to preferences.textSizeSp,
        blockSpacing to preferences.blockSpacingDp,
        alignment to preferences.alignment.name,
        anchor to preferences.anchorFraction,
        margin to preferences.horizontalMarginDp,
        translation to preferences.showTranslation,
        words to preferences.emphasizeWords,
        keepScreen to preferences.legacyKeepScreenOn,
    )

    private fun rejected(issue: LyricsDisplayIssue) = DecodedLyricsDisplay(null, persisted = true, issue = issue)
}
