package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Static watch surfaces, system fonts and original geometric icons; no remote assets or animation. */
object WatchPalette {
    val Background = Color.Black
    val Row = Color(0xff1a1a1a)
    val Raised = Color(0xff262626)
    val Pressed = Color(0xff3a3a3a)
    val Text = Color.White
    val Secondary = Color(0xffc7c7cc)
    val Disabled = Color(0xffadadad)
    val Accent = Color(0xffff375f)
    val Focus = Color(0xff8a8a8a)
    val Outline = Color.White.copy(alpha = 0.14f)
}

@Composable
fun WatchText(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    error: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
) {
    BasicText(text, modifier, style = TextStyle(
        color = when {
            !enabled -> WatchPalette.Disabled
            error -> Color(0xffffb4ab)
            primary -> WatchPalette.Text
            else -> WatchPalette.Secondary
        },
        fontSize = if (primary) 16.sp else 14.sp,
        lineHeight = if (primary) 22.sp else 20.sp,
        fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
        textAlign = textAlign,
    ))
}

@Composable
fun WatchActionRow(
    label: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    selected: Boolean? = null,
    onClick: () -> Unit,
) {
    WatchContentRow(enabled = enabled, primary = primary, selected = selected, onClick = onClick) {
        WatchText(label, Modifier.fillMaxWidth(), primary = true, enabled = enabled)
    }
}

/** The visible row owns its whole touch area; adjacent actions never share an enlarged hit target. */
@Composable
fun WatchContentRow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    selected: Boolean? = null,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val selection = if (selected == null) Modifier else Modifier.semantics { this.selected = selected }
    Column(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (pressed) WatchPalette.Pressed else if (primary) WatchPalette.Raised else WatchPalette.Row)
            .border(if (focused || selected == true) 2.dp else 1.dp,
                if (focused) WatchPalette.Focus else if (selected == true) WatchPalette.Accent else WatchPalette.Outline, shape).then(selection)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun WatchCheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    WatchToggleRow(label, checked, enabled, Role.Checkbox, onChange)
}

@Composable
fun WatchSwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    WatchToggleRow(label, checked, enabled, Role.Switch, onChange)
}

@Composable
private fun WatchToggleRow(label: String, checked: Boolean, enabled: Boolean, role: Role, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (pressed) WatchPalette.Pressed else WatchPalette.Row)
            .border(if (focused) 2.dp else 1.dp, if (focused) WatchPalette.Focus else WatchPalette.Outline, shape)
            .toggleable(value = checked, interactionSource = interaction, indication = null,
                enabled = enabled, role = role, onValueChange = onChange)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(if (role == Role.Switch) Modifier.size(32.dp, 24.dp) else Modifier.size(24.dp)) {
            val color = if (enabled) WatchPalette.Text else WatchPalette.Disabled
            if (role == Role.Switch) {
                drawRoundRect(color, Offset(0f, size.height / 6f), Size(size.width, size.height * 2f / 3f),
                    CornerRadius(size.height / 3f),
                    style = if (checked) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2.dp.toPx()))
                drawCircle(if (checked) WatchPalette.Background else color, size.height * 0.22f,
                    Offset(size.width * if (checked) 0.75f else 0.25f, size.height / 2f))
            } else {
                val inset = 2.dp.toPx()
                drawRoundRect(color, Offset(inset, inset), Size(size.width - inset * 2f, size.height - inset * 2f),
                    CornerRadius(4.dp.toPx()), style = if (checked) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2.dp.toPx()))
                if (checked) {
                    val tick = Path().apply {
                        moveTo(size.width * 0.27f, size.height * 0.52f)
                        lineTo(size.width * 0.43f, size.height * 0.68f)
                        lineTo(size.width * 0.75f, size.height * 0.34f)
                    }
                    drawPath(tick, WatchPalette.Background, style = Stroke(2.dp.toPx()))
                }
            }
        }
        WatchText(label, Modifier.weight(1f), primary = true, enabled = enabled)
    }
}

@Composable
fun WatchChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (pressed) WatchPalette.Pressed else WatchPalette.Row)
            .border(if (focused) 2.dp else 1.dp, if (focused) WatchPalette.Focus else WatchPalette.Outline, shape)
            .selectable(selected = selected, interactionSource = interaction, indication = null,
                enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val color = if (enabled) WatchPalette.Text else WatchPalette.Disabled
            drawCircle(color, radius = size.minDimension * 0.4f, style = Stroke(2.dp.toPx()))
            if (selected) drawCircle(color, radius = size.minDimension * 0.22f)
        }
        WatchText(label, Modifier.weight(1f), primary = true, enabled = enabled)
    }
}

enum class WatchGlyph { MENU, BACK, PLAY, PAUSE, PREVIOUS, NEXT, QUEUE }

@Composable
fun WatchCircleButton(
    label: String,
    glyph: WatchGlyph,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    emphasized: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier.size(maxOf(48.dp, size)).clip(CircleShape)
            .background(if (pressed) WatchPalette.Pressed else if (emphasized) WatchPalette.Raised else WatchPalette.Row)
            .border(if (focused) 2.dp else 1.dp, if (focused) WatchPalette.Focus else WatchPalette.Outline, CircleShape)
            .semantics { contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        WatchSymbol(glyph, if (enabled) WatchPalette.Text else WatchPalette.Disabled,
            Modifier.size(if (emphasized) 28.dp else 22.dp))
    }
}

@Composable
private fun WatchSymbol(glyph: WatchGlyph, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val stroke = 2.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color,
            Offset(width * x1, height * y1), Offset(width * x2, height * y2), strokeWidth = stroke)
        fun triangle(left: Float, right: Float) {
            val path = Path().apply {
                moveTo(width * left, height * 0.16f)
                lineTo(width * right, height * 0.5f)
                lineTo(width * left, height * 0.84f)
                close()
            }
            drawPath(path, color)
        }
        when (glyph) {
            WatchGlyph.MENU, WatchGlyph.QUEUE -> for (y in listOf(0.25f, 0.5f, 0.75f)) line(0.14f, y, 0.86f, y)
            WatchGlyph.BACK -> { line(0.65f, 0.15f, 0.3f, 0.5f); line(0.3f, 0.5f, 0.65f, 0.85f) }
            WatchGlyph.PLAY -> triangle(0.27f, 0.85f)
            WatchGlyph.PAUSE -> {
                drawRect(color, Offset(width * 0.23f, height * 0.17f), Size(width * 0.18f, height * 0.66f))
                drawRect(color, Offset(width * 0.59f, height * 0.17f), Size(width * 0.18f, height * 0.66f))
            }
            WatchGlyph.PREVIOUS -> { line(0.16f, 0.16f, 0.16f, 0.84f); triangle(0.79f, 0.28f) }
            WatchGlyph.NEXT -> { triangle(0.21f, 0.72f); line(0.84f, 0.16f, 0.84f, 0.84f) }
        }
    }
}
