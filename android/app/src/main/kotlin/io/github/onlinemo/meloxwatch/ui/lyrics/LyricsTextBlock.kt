package io.github.onlinemo.meloxwatch.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTimedLine
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsWordPosition
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors

/**
 * 只消费调用层已经校验、解码并保有的两个 face；组件不打开文件、不下载字体、不缓存轴组合。
 * 可变字体的真实 wght 设置须在该 face 中配置；FontSynthesis.None 避免伪造未支持的字重。
 */
data class LyricsFonts(
    val originalFamily: FontFamily = FontFamily.Default,
    val translationFamily: FontFamily = FontFamily.Default,
    val originalWeight: FontWeight = FontWeight.SemiBold,
    val translationWeight: FontWeight = FontWeight.Normal,
)

internal data class LyricsWordRange(val start: Int, val endExclusive: Int)

internal fun lyricsWordRange(line: LyricsTimedLine, word: LyricsWordPosition?): LyricsWordRange? {
    if (word == null || word.lineId != line.id || line.wordTimings.getOrNull(word.wordIndex) != word.timing) return null
    val start = word.timing.startChar
    val end = word.timing.endChar
    val text = line.text
    if (start < 0 || end > text.length || start >= end) return null
    if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) return null
    if (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) return null
    return LyricsWordRange(start, end)
}

/** 静态行块只排版已解析文本和真正当前字；不产生时间、动画、seek 或文件读取。 */
@Composable
internal fun LyricsTextBlock(
    text: String,
    translation: String?,
    active: Boolean,
    wordRange: LyricsWordRange?,
    options: LyricsDisplayOptions,
    fonts: LyricsFonts,
    currentLabel: String,
    actionLabel: String?,
    onPreview: (() -> Unit)?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWatchThemeColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed = if (visible) {
        val value by interaction.collectIsPressedAsState()
        value
    } else false
    val focused = if (visible) {
        val value by interaction.collectIsFocusedAsState()
        value
    } else false
    val shape = RoundedCornerShape(8.dp)
    val alignment = if (options.alignment == LyricsAlignment.CENTER) TextAlign.Center else TextAlign.Start
    val color = if (active) colors.text else colors.secondary
    val original = remember(text, wordRange, pressed, colors) {
        buildAnnotatedString {
            append(text)
            if (wordRange != null) addStyle(
                SpanStyle(color = if (pressed) colors.text else colors.accent,
                    textDecoration = TextDecoration.Underline),
                wordRange.start,
                wordRange.endExclusive,
            )
        }
    }
    val intent = if (onPreview == null) Modifier else Modifier.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = visible,
        role = Role.Button,
        onClickLabel = actionLabel,
        onClick = onPreview,
    )
    Column(
        modifier.fillMaxWidth().heightIn(min = if (actionLabel != null) 48.dp else 0.dp)
            .clip(shape)
            .background(if (pressed) colors.pressed else colors.surface)
            .border(if (focused) 2.dp else 0.dp, if (focused) colors.focus else Color.Transparent, shape)
            .semantics(mergeDescendants = true) {
                if (actionLabel != null && onPreview == null) disabled()
                if (active) {
                    selected = true
                    stateDescription = currentLabel
                }
            }
            .then(intent)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(
            original,
            Modifier.fillMaxWidth(),
            style = TextStyle(
                color = color,
                fontFamily = fonts.originalFamily,
                fontWeight = fonts.originalWeight,
                fontSynthesis = FontSynthesis.None,
                fontSize = options.textSizeSp.sp,
                lineHeight = (options.textSizeSp * 1.4f).sp,
                textAlign = alignment,
            ),
        )
        if (translation != null) BasicText(
            translation,
            Modifier.fillMaxWidth(),
            style = TextStyle(
                color = colors.secondary,
                fontFamily = fonts.translationFamily,
                fontWeight = fonts.translationWeight,
                fontSynthesis = FontSynthesis.None,
                fontSize = (options.textSizeSp * 0.8f).coerceAtLeast(14f).sp,
                lineHeight = (options.textSizeSp * 1.12f).coerceAtLeast(20f).sp,
                textAlign = alignment,
            ),
        )
        if (actionLabel != null) Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) {
                if (active) Canvas(Modifier.size(10.dp)) {
                    val marker = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, size.height / 2f)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(marker, colors.accent)
                }
            }
            BasicText(actionLabel, Modifier.weight(1f), style = TextStyle(
                color = colors.secondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = alignment,
            ))
        }
    }
}
