package io.github.onlinemo.meloxwatch.ui.watch

import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayAlignment
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPreferences
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsAlignment
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsDisplayOptions

@Composable
internal fun WatchSettingTitle(text: String) {
    BasicText(text, Modifier.fillMaxWidth(), style = TextStyle(
        color = LocalWatchThemeColors.current.accent,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    ))
}

/** Narrow content stacks the two actions so neither independent touch area shrinks below 48dp. */
@Composable
fun WatchSettingStepper(
    title: String,
    value: String,
    enabled: Boolean,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseLabel: String? = null,
    increaseLabel: String? = null,
) {
    val lower = decreaseLabel ?: stringResource(R.string.lyrics_setting_less)
    val higher = increaseLabel ?: stringResource(R.string.lyrics_setting_more)
    val gap = watchSpacing(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(watchSpacing(6.dp))) {
        WatchText(stringResource(R.string.lyrics_setting_value, title, value), primary = true)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 96.dp + gap) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    WatchStepperAction(title, value, lower, enabled && canDecrease, onDecrease, Modifier.weight(1f))
                    WatchStepperAction(title, value, higher, enabled && canIncrease, onIncrease, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    WatchStepperAction(title, value, lower, enabled && canDecrease, onDecrease)
                    WatchStepperAction(title, value, higher, enabled && canIncrease, onIncrease)
                }
            }
        }
    }
}

@Composable
private fun WatchStepperAction(
    title: String,
    value: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WatchContentRow(modifier.semantics { contentDescription = "$title, $value, $label" },
        enabled = enabled, onClick = onClick) {
        WatchText(label, Modifier.fillMaxWidth(), primary = true, enabled = enabled, textAlign = TextAlign.Center)
    }
}

/** Persistent display choices never supply a player clock or a screen-on lease. */
fun lyricsDisplayOptions(preferences: LyricsDisplayPreferences): LyricsDisplayOptions = LyricsDisplayOptions(
    textSizeSp = preferences.textSizeSp,
    blockSpacingDp = preferences.blockSpacingDp,
    alignment = when (preferences.alignment) {
        LyricsDisplayAlignment.START -> LyricsAlignment.START
        LyricsDisplayAlignment.CENTER -> LyricsAlignment.CENTER
    },
    anchorFraction = preferences.anchorFraction,
    horizontalMarginDp = preferences.horizontalMarginDp,
    showTranslation = preferences.showTranslation,
    emphasizeWords = preferences.emphasizeWords,
)
