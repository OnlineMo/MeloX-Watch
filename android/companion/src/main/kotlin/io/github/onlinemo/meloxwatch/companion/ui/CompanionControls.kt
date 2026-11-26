package io.github.onlinemo.meloxwatch.companion.ui

import androidx.annotation.PluralsRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading as semanticHeading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.onlinemo.meloxwatch.companion.R
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue

@Composable
internal fun MeloXText(
    text: String,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    heading: Boolean = false,
    mono: Boolean = false,
) {
    val typography = when {
        heading -> CompanionTypography.title
        secondary -> CompanionTypography.secondary
        else -> CompanionTypography.body
    }
    BasicText(
        text,
        modifier.then(if (heading) Modifier.semantics { semanticHeading() } else Modifier),
        style = typography.copy(
            color = if (secondary) CompanionInk.secondary else CompanionInk.primary,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
        ),
    )
}

@Composable
internal fun MeloXCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(CompanionInk.surface, CompanionShape.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun MeloXAction(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, prominent: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val outline = when {
        focused -> CompanionInk.focus
        prominent && enabled -> CompanionInk.accent
        else -> CompanionInk.border
    }
    // 按压只改变中性表面；亮红保留给符号和边界，文字对比度不随强调色下降。
    Box(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (pressed && enabled) CompanionInk.pressed else CompanionInk.raised, CompanionShape.control)
            .border(if (focused) 2.dp else 1.dp, outline, CompanionShape.control)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        BasicText(
            label,
            style = CompanionTypography.action.copy(
                color = if (enabled) CompanionInk.primary else CompanionInk.secondary,
                fontFamily = FontFamily.SansSerif,
            ),
        )
    }
}

@Composable
internal fun MeloXField(
    label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, multiline: Boolean = false, maximum: Int = 4096,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MeloXText(label, secondary = true)
        BasicTextField(
            value,
            { next -> if (next.length <= maximum) onValueChange(next) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label }
                .border(if (focused) 2.dp else 1.dp, if (focused) CompanionInk.focus else CompanionInk.border, CompanionShape.field)
                .background(CompanionInk.background, CompanionShape.field)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            enabled = enabled,
            singleLine = !multiline,
            textStyle = TextStyle(color = CompanionInk.primary, fontSize = 18.sp, lineHeight = 26.sp, fontFamily = FontFamily.SansSerif),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(CompanionInk.focus),
        )
    }
}

@Composable
internal fun MeloXToggle(label: String, selected: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (pressed && enabled) CompanionInk.pressed else CompanionInk.surface, CompanionShape.control)
            .then(if (focused) Modifier.border(2.dp, CompanionInk.focus, CompanionShape.control) else Modifier)
            .toggleable(selected, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(22.dp)) {
            drawRoundRect(CompanionInk.border, style = Stroke(width = 2.dp.toPx()))
            if (selected) {
                drawLine(CompanionInk.primary, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .44f, size.height * .75f), 2.dp.toPx(), StrokeCap.Round)
                drawLine(CompanionInk.primary, Offset(size.width * .44f, size.height * .75f), Offset(size.width * .83f, size.height * .24f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        MeloXText(label, secondary = !enabled)
    }
}

@Composable
internal fun MeloXChoice(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (pressed && enabled) CompanionInk.pressed else CompanionInk.surface, CompanionShape.control)
            .then(if (focused) Modifier.border(2.dp, CompanionInk.focus, CompanionShape.control) else Modifier)
            .selectable(selected, interactionSource = interaction, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(22.dp)) {
            drawCircle(CompanionInk.border, style = Stroke(width = 2.dp.toPx()))
            if (selected) drawCircle(CompanionInk.primary, radius = size.minDimension * .22f)
        }
        MeloXText(label, secondary = !enabled)
    }
}

@Composable
internal fun MeloXDivider() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(CompanionInk.border))
}

@Composable
internal fun MeloXConfirmation(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maximumHeight = with(LocalDensity.current) { windowHeight.toDp() * .85f }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maximumHeight)
                .background(CompanionInk.surface, CompanionShape.card)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MeloXText(title, heading = true)
            MeloXText(message)
            MeloXAction(confirm, onConfirm, prominent = true)
            MeloXAction(stringResource(R.string.cancel), onDismiss)
        }
    }
}

@Composable
internal fun companionPlural(@PluralsRes id: Int, count: Long, vararg formatArgs: Any): String {
    // Chinese and English need only these quantity classes; formatting keeps the full Long.
    val quantity = when (count) {
        0L -> 0
        1L -> 1
        else -> 2
    }
    val arguments = if (formatArgs.isEmpty()) arrayOf<Any>(count) else formatArgs
    return pluralStringResource(id, quantity, *arguments)
}

@Composable
internal fun transferIssueText(issue: TransferIssue): String = stringResource(when (issue) {
    TransferIssue.BUSY -> R.string.error_busy
    TransferIssue.CLOSED, TransferIssue.EXPIRED -> R.string.error_expired
    TransferIssue.UNAUTHENTICATED, TransferIssue.NOT_APPROVED, TransferIssue.PERMISSION_DENIED -> R.string.error_permission
    TransferIssue.WRONG_TARGET -> R.string.error_target
    TransferIssue.INVALID_INPUT, TransferIssue.INVALID_OFFSET -> R.string.error_input
    TransferIssue.INCOMPATIBLE_SCHEMA -> R.string.error_schema
    TransferIssue.PAIRING_FAILED, TransferIssue.PAIRING_LOCKED -> R.string.error_pairing
    TransferIssue.RATE_LIMITED -> R.string.error_rate
    TransferIssue.CONFLICT -> R.string.error_conflict
    TransferIssue.NOT_FOUND -> R.string.error_missing
    TransferIssue.QUOTA_EXCEEDED -> R.string.error_quota
    TransferIssue.STORAGE_FULL -> R.string.error_space
    TransferIssue.STORAGE_UNAVAILABLE -> R.string.error_storage
    TransferIssue.INTEGRITY_FAILED -> R.string.error_integrity
    TransferIssue.UNSUPPORTED_TYPE, TransferIssue.NOT_AUDIO -> R.string.error_type
    TransferIssue.INTERRUPTED -> R.string.error_cancelled
    TransferIssue.RECOVERY_REQUIRED -> R.string.error_recovery
    TransferIssue.IDENTITY_UNAVAILABLE -> R.string.error_identity
    TransferIssue.TRANSPORT_FAILED -> R.string.error_network
})
