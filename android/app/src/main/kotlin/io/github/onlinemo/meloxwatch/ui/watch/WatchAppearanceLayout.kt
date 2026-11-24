package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchAppearance

/** Only application layout changes. System density/fontScale and minimum hit areas stay authoritative. */
@Composable
fun watchSpacing(value: Dp): Dp = value * LocalWatchAppearance.current.scale

@Composable
fun watchRowMinimum(): Dp = maxOf(48.dp, watchSpacing(56.dp))

fun protectsPhysicalRoundBounds(preference: ScreenShape, actualRound: Boolean): Boolean =
    actualRound || preference == ScreenShape.ROUND

/** Remove simple suffix groups from the hero only; nested, malformed or all-suffix titles stay intact. */
fun compactPlaybackTitle(original: String, enabled: Boolean): String {
    if (!enabled || original.length > 4096) return original
    val pairs = mapOf(')' to '(', '）' to '（', ']' to '[', '】' to '【')
    var pending: Char? = null
    for (character in original) {
        if (character in pairs.values) {
            if (pending != null) return original
            pending = character
        } else if (character in pairs.keys) {
            if (pending != pairs[character]) return original
            pending = null
        }
    }
    if (pending != null) return original
    var end = original.trimEnd().length
    var removed = false
    while (end > 0) {
        val close = original[end - 1]
        val open = pairs[close] ?: break
        var cursor = end - 2
        while (cursor >= 0 && original[cursor] != open) {
            val value = original[cursor]
            if (value in pairs.keys || value in pairs.values) return original
            cursor--
        }
        if (cursor < 0) return original
        end = cursor
        while (end > 0 && original[end - 1].isWhitespace()) end--
        removed = true
    }
    return if (removed && end > 0) original.substring(0, end) else original
}
