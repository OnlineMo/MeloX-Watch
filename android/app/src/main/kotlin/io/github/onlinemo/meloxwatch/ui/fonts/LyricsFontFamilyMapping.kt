package io.github.onlinemo.meloxwatch.ui.fonts

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.onlinemo.meloxwatch.data.fonts.LoadedLyricsFont
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsFonts

/** Cache this pair with the editor state in the caller; composition does not read or decode files. */
fun lyricsFontFamilies(original: LoadedLyricsFont, translation: LoadedLyricsFont): LyricsFonts = LyricsFonts(
    originalFamily = original.typeface?.let { FontFamily(it) } ?: FontFamily.Default,
    translationFamily = translation.typeface?.let { FontFamily(it) } ?: FontFamily.Default,
    originalWeight = original.displayWeight(FontWeight.SemiBold),
    translationWeight = translation.displayWeight(FontWeight.Normal),
)

private fun LoadedLyricsFont.displayWeight(systemDefault: FontWeight): FontWeight = when {
    typeface != null && nativeStyleWeight != null -> FontWeight(nativeStyleWeight.coerceIn(1, 1000))
    asset != null || requestedWeight != null -> FontWeight.Normal
    else -> systemDefault
}
