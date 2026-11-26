package io.github.onlinemo.meloxwatch.companion.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Portions adapted from MeloX-Android, commit 83632b17e1ee857bc428134528e0fd86c5fd4d01:
 * ui/theme/MeloXTheme.kt and ui/glass/MeloXGlassTokens.kt.
 * GNU GPL v3, as provided in this repository's LICENSE.
 * Adapted on 2026-11-26; static Foundation surfaces and system fonts replace glass/font runtimes.
 */
internal object CompanionInk {
    val background = Color(0xFF0B0B0D)
    val surface = Color(0xFF151518)
    val raised = Color(0xFF252529)
    val primary = Color(0xFFF5F5F7)
    val secondary = Color(0xFFB8B8C0)
    val accent = Color(0xFFFF3B30)
    val focus = Color(0xFF0A84FF)
    // 中性按压态和较亮边界保留文字4.5:1、控件3:1；错误色沿用可读的浅红。
    val pressed = Color(0xFF353539)
    val border = Color(0xFF8C8C96)
    val error = Color(0xFFFFB4AB)
}

internal object CompanionShape {
    val control = RoundedCornerShape(14.dp)
    val navigation = RoundedCornerShape(16.dp)
    val field = RoundedCornerShape(20.dp)
    val card = RoundedCornerShape(22.dp)
}

internal object CompanionTypography {
    val page = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp)
    val title = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp)
    val action = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp)
    val secondary = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
}
