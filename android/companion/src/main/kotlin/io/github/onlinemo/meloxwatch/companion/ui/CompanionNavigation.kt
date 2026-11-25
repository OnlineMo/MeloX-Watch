package io.github.onlinemo.meloxwatch.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/*
 * Structure adapted from MeloX-Android, commit 83632b17e1ee857bc428134528e0fd86c5fd4d01:
 * ui/library/LibraryScreen.kt (MeloXLibrarySegmentedPicker) and ui/MeloXApp.kt (RootTabButton).
 * GNU GPL v3; see LICENSE. Adapted on 2026-11-26 for six existing companion destinations.
 */
@Composable
internal fun MeloXPageTitle(title: String) {
    BasicText(
        title,
        Modifier.fillMaxWidth().semantics { heading() },
        style = CompanionTypography.page.copy(color = CompanionInk.primary, fontFamily = FontFamily.SansSerif),
    )
}

@Composable
internal fun MeloXBack(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val reverse = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (pressed) CompanionInk.pressed else CompanionInk.background, CompanionShape.control)
            .then(if (focused) Modifier.border(2.dp, CompanionInk.focus, CompanionShape.control) else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(22.dp).clearAndSetSemantics { }) {
            val near = if (reverse) .72f else .28f
            val far = if (reverse) .28f else .72f
            drawLine(CompanionInk.focus, Offset(size.width * far, size.height * .18f), Offset(size.width * near, size.height * .5f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(CompanionInk.focus, Offset(size.width * near, size.height * .5f), Offset(size.width * far, size.height * .82f), 2.dp.toPx(), StrokeCap.Round)
        }
        BasicText(label, style = CompanionTypography.body.copy(color = CompanionInk.primary, fontFamily = FontFamily.SansSerif))
    }
}

@Composable
internal fun MeloXNavigation(selected: CompanionScreen, onSelected: (CompanionScreen) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 六入口仅按可用矩形宽度分组；标签可增高并随整页滚动，不横藏或缩小热区。
        val columns = when {
            maxWidth >= 640.dp -> 6
            maxWidth >= 416.dp -> 3
            maxWidth >= 184.dp -> 2
            else -> 1
        }
        Column(
            Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (screens in CompanionScreen.entries.chunked(columns)) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (screen in screens) {
                        MeloXNavigationTab(
                            screenName(screen),
                            selected = screen == selected,
                            onClick = { onSelected(screen) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeloXNavigationTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val outline = when {
        focused -> CompanionInk.focus
        selected -> CompanionInk.accent
        else -> CompanionInk.border
    }
    Column(
        modifier.heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (pressed) CompanionInk.pressed else CompanionInk.raised, CompanionShape.navigation)
            .border(if (focused || selected) 2.dp else 1.dp, outline, CompanionShape.navigation)
            .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 固定符号盒保持选中前后几何一致；对勾和Tab语义共同表达选中，不依赖红色。
        Canvas(Modifier.size(16.dp).clearAndSetSemantics { }) {
            if (selected) {
                drawLine(CompanionInk.primary, Offset(size.width * .12f, size.height * .5f), Offset(size.width * .4f, size.height * .8f), 2.dp.toPx(), StrokeCap.Round)
                drawLine(CompanionInk.primary, Offset(size.width * .4f, size.height * .8f), Offset(size.width * .88f, size.height * .18f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        BasicText(
            label,
            style = CompanionTypography.secondary.copy(
                color = CompanionInk.primary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
            ),
        )
    }
}
