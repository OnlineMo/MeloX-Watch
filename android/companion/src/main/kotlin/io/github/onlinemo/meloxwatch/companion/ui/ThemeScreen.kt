package io.github.onlinemo.meloxwatch.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.onlinemo.meloxwatch.companion.R
import io.github.onlinemo.meloxwatch.companion.data.CompanionColor
import io.github.onlinemo.meloxwatch.companion.data.CompanionIconRole
import io.github.onlinemo.meloxwatch.companion.data.CompanionThemeBackground
import io.github.onlinemo.meloxwatch.companion.data.CompanionThemeDraft
import io.github.onlinemo.meloxwatch.companion.data.CompanionThemePreview

internal fun LazyListScope.themeItems(
    state: CompanionUiState, model: CompanionViewModel, selectIcon: (CompanionIconRole, String, String) -> Unit,
) {
    val saved = state.local?.theme ?: return
    item(key = "theme-editor-fields") {
        var name by remember(saved.id, saved.revision) { mutableStateOf(saved.name) }
        var author by remember(saved.id, saved.revision) { mutableStateOf(saved.author) }
        var licenseName by remember(saved.id, saved.revision) { mutableStateOf(saved.licenseName) }
        var licenseText by remember(saved.id, saved.revision) { mutableStateOf(saved.licenseText) }
        var colors by remember(saved.id, saved.revision) { mutableStateOf(saved.colors) }
        var background by remember(saved.id, saved.revision) { mutableStateOf(saved.background) }
        var blur by remember(saved.id, saved.revision) { mutableStateOf(saved.blurLevel.toString()) }
        var mask by remember(saved.id, saved.revision) { mutableStateOf(saved.darkMaskPercent.toString()) }
        MeloXCard {
            MeloXText(stringResource(R.string.theme_editor), heading = true)
            MeloXText(stringResource(R.string.theme_editor_local), secondary = true)
            MeloXField(stringResource(R.string.theme_name), name, { name = it }, enabled = state.available, maximum = 512)
            MeloXField(stringResource(R.string.theme_author), author, { author = it }, enabled = state.available, maximum = 512)
            MeloXField(stringResource(R.string.license_name), licenseName, { licenseName = it }, enabled = state.available, maximum = 512)
            MeloXField(stringResource(R.string.license_text), licenseText, { licenseText = it }, enabled = state.available, multiline = true, maximum = 16 * 1024)
            MeloXText(stringResource(R.string.theme_colors), heading = true)
            CompanionColor.entries.forEach { token ->
                MeloXField(colorName(token), colors.getValue(token), { value -> colors = colors + (token to value) },
                    enabled = state.available, keyboard = KeyboardType.Ascii, maximum = 7)
            }
            MeloXText(stringResource(R.string.color_format), secondary = true)
            MeloXText(stringResource(R.string.theme_background), heading = true)
            CompanionThemeBackground.entries.forEach { mode ->
                MeloXChoice(backgroundName(mode), mode == background, { background = mode }, enabled = state.available)
            }
            MeloXField(stringResource(R.string.blur_level), blur, { blur = it }, enabled = state.available, keyboard = KeyboardType.Number, maximum = 2)
            MeloXField(stringResource(R.string.dark_mask_percent), mask, { mask = it }, enabled = state.available, keyboard = KeyboardType.Number, maximum = 3)
            MeloXAction(stringResource(R.string.save_theme_draft), { model.saveTheme(name, author, licenseName, licenseText, colors, background, blur, mask) },
                enabled = state.available, prominent = true)
        }
    }
    item(key = "theme-phone-preview") {
        var round by remember { mutableStateOf(true) }
        val preview = state.themePreview?.takeIf { it.draft.id == saved.id && it.draft.revision == saved.revision }
        MeloXCard {
            MeloXText(stringResource(R.string.theme_saved_preview), heading = true)
            MeloXText(stringResource(R.string.theme_save_before_preview), secondary = true)
            MeloXToggle(stringResource(R.string.preview_round_screen), round, { round = it })
            MeloXAction(stringResource(R.string.preview_saved_theme), model::previewTheme, enabled = state.available)
            if (preview != null) {
                themePreviewView(preview, round)
                MeloXText(stringResource(R.string.theme_preview_static), secondary = true)
            }
            MeloXAction(stringResource(R.string.prepare_saved_theme_package), model::prepareTheme, enabled = state.available && state.local?.upload == null)
            MeloXText(stringResource(R.string.theme_watch_confirmation), secondary = true)
        }
    }
    CompanionIconRole.entries.forEach { role -> item(key = "theme-icon-" + role.name) {
            val icon = saved.icons.firstOrNull { it.role == role }
            var licenseName by remember(saved.id, role, icon?.fileId) { mutableStateOf(icon?.licenseName ?: saved.licenseName) }
            var licenseText by remember(saved.id, role, icon?.fileId) { mutableStateOf(icon?.licenseText ?: saved.licenseText) }
            var remove by remember(saved.id, role, icon?.fileId) { mutableStateOf(false) }
            val roleName = iconRoleName(role)
            MeloXCard {
                MeloXText(roleName, heading = true)
                if (icon == null) MeloXText(stringResource(R.string.builtin_icon), secondary = true)
                else {
                    MeloXText(stringResource(R.string.icon_prepared_bytes, icon.byteCount), secondary = true)
                    val preview = state.themePreview?.takeIf { it.draft.revision == saved.revision && it.draft.id == saved.id }
                    if (preview != null) themeIcon(preview, role, themeColor(preview.draft, CompanionColor.TEXT_PRIMARY))
                }
                MeloXField(stringResource(R.string.icon_license_name), licenseName, { licenseName = it }, enabled = state.available, maximum = 512)
                MeloXField(stringResource(R.string.icon_license_text), licenseText, { licenseText = it }, enabled = state.available, multiline = true, maximum = 16 * 1024)
                MeloXAction(stringResource(R.string.select_icon_image), { selectIcon(role, licenseName, licenseText) },
                enabled = state.available && licenseName.isNotBlank() && licenseText.isNotBlank())
                if (icon != null) MeloXAction(stringResource(R.string.remove_icon), { remove = true }, enabled = state.available)
            }
            if (remove) MeloXConfirmation(stringResource(R.string.remove_icon), stringResource(R.string.remove_icon_message, roleName), stringResource(R.string.remove_icon),
            onConfirm = {
                remove = false
                model.removeIcon(role)
            }, onDismiss = {
                remove = false
            })
    } }
    uploadItems(state, model)
}

@Composable
private fun themePreviewView(preview: CompanionThemePreview, round: Boolean) {
    val draft = preview.draft
    val background = themeColor(draft, CompanionColor.BACKGROUND)
    val raised = themeColor(draft, CompanionColor.SURFACE_RAISED)
    val accent = themeColor(draft, CompanionColor.ACCENT)
    val primary = themeColor(draft, CompanionColor.TEXT_PRIMARY)
    val secondary = themeColor(draft, CompanionColor.TEXT_SECONDARY)
    val brush = when (draft.background) {
        CompanionThemeBackground.BLACK -> Brush.linearGradient(listOf(Color.Black, Color.Black))
        CompanionThemeBackground.DEFAULT -> Brush.linearGradient(listOf(background, background))
        CompanionThemeBackground.COVER_BLUR -> Brush.linearGradient(listOf(accent.copy(alpha = .28f), background, raised))
        CompanionThemeBackground.FLUID -> Brush.linearGradient(listOf(accent.copy(alpha = .32f), raised, background))
    }
    val description = stringResource(R.string.theme_preview_description)
    BoxWithConstraints(Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)
        .clip(if (round) CircleShape else RoundedCornerShape(22.dp)).background(background).background(brush)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(maxWidth * if (round) .18f else .08f),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(48.dp)) {
                drawRoundRect(Brush.linearGradient(listOf(accent, raised)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                drawCircle(primary, radius = size.minDimension * .12f, center = Offset(size.width * .38f, size.height * .64f))
                drawLine(primary, Offset(size.width * .5f, size.height * .26f), Offset(size.width * .5f, size.height * .64f), size.width * .08f, StrokeCap.Round)
                drawLine(primary, Offset(size.width * .5f, size.height * .28f), Offset(size.width * .74f, size.height * .36f), size.width * .08f, StrokeCap.Round)
            }
            BasicText(stringResource(R.string.theme_preview_song), style = TextStyle(color = primary, fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText("MeloX Watch", style = TextStyle(color = secondary, fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Canvas(Modifier.fillMaxWidth().size(height = 4.dp, width = 120.dp)) {
                drawLine(secondary.copy(alpha = .5f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
                drawLine(accent, Offset(0f, size.height / 2), Offset(size.width * .42f, size.height / 2), size.height, StrokeCap.Round)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                themeIcon(preview, CompanionIconRole.PREVIOUS, primary)
                themeIcon(preview, CompanionIconRole.PLAY, primary)
                themeIcon(preview, CompanionIconRole.NEXT, primary)
            }
        }
    }
}

@Composable
private fun themeIcon(preview: CompanionThemePreview, role: CompanionIconRole, color: Color) {
    val image = preview.icons[role]
    val label = iconRoleName(role)
    if (image != null) {
        val bitmap = remember(image) { image.asImageBitmap() }
        Image(bitmap, contentDescription = label, modifier = Modifier.size(36.dp), contentScale = ContentScale.Fit)
    } else Canvas(Modifier.size(36.dp).semantics { contentDescription = label }) { drawThemeIcon(role, color) }
}

private fun DrawScope.drawThemeIcon(role: CompanionIconRole, color: Color) {
    val edge = size.minDimension
    fun point(x: Float, y: Float): Offset = Offset(edge * x, edge * y)
    fun triangle(reverse: Boolean, left: Float = .18f, right: Float = .82f) {
        val path = Path().apply {
            moveTo(edge * if (reverse) right else left, edge * .16f)
            lineTo(edge * if (reverse) left else right, edge * .5f)
            lineTo(edge * if (reverse) right else left, edge * .84f)
            close()
        }
        drawPath(path, color)
    }
    when (role) {
        CompanionIconRole.PLAY -> triangle(false)
        CompanionIconRole.PAUSE -> {
            drawRect(color, point(.2f, .16f), Size(edge * .2f, edge * .68f))
            drawRect(color, point(.6f, .16f), Size(edge * .2f, edge * .68f))
        }
        CompanionIconRole.PREVIOUS -> {
            triangle(true, .26f)
            drawLine(color, point(.16f, .16f), point(.16f, .84f), edge * .09f, StrokeCap.Round)
        }
        CompanionIconRole.NEXT -> {
            triangle(false, right = .74f)
            drawLine(color, point(.84f, .16f), point(.84f, .84f), edge * .09f, StrokeCap.Round)
        }
        CompanionIconRole.BACK -> {
            drawLine(color, point(.68f, .18f), point(.28f, .5f), edge * .09f, StrokeCap.Round)
            drawLine(color, point(.28f, .5f), point(.68f, .82f), edge * .09f, StrokeCap.Round)
        }
        CompanionIconRole.MENU, CompanionIconRole.LYRICS, CompanionIconRole.QUEUE -> {
            for (line in 0..2) {
                val y = .24f + line * .26f
                drawLine(color, point(.22f, y), point(if (line == 2 && role == CompanionIconRole.QUEUE) .6f else .8f, y), edge * .07f, StrokeCap.Round)
                if (role != CompanionIconRole.MENU) drawCircle(color, edge * .03f, point(.1f, y))
            }
        }
        CompanionIconRole.FAVORITE -> {
            val path = Path().apply {
                moveTo(edge * .5f, edge * .86f)
                cubicTo(edge * .08f, edge * .56f, edge * .1f, edge * .18f, edge * .3f, edge * .18f)
                cubicTo(edge * .4f, edge * .18f, edge * .48f, edge * .24f, edge * .5f, edge * .34f)
                cubicTo(edge * .52f, edge * .24f, edge * .6f, edge * .18f, edge * .7f, edge * .18f)
                cubicTo(edge * .9f, edge * .18f, edge * .92f, edge * .56f, edge * .5f, edge * .86f)
                close()
            }
            drawPath(path, color, style = Stroke(edge * .06f))
        }
    }
}

private fun themeColor(draft: CompanionThemeDraft, token: CompanionColor): Color = Color(0xff000000L or draft.colors.getValue(token).substring(1).toLong(16))

@Composable
private fun colorName(color: CompanionColor): String = stringResource(when (color) {
    CompanionColor.BACKGROUND -> R.string.color_background
    CompanionColor.SURFACE -> R.string.color_surface
    CompanionColor.SURFACE_RAISED -> R.string.color_surface_raised
    CompanionColor.SURFACE_PRESSED -> R.string.color_surface_pressed
    CompanionColor.TEXT_PRIMARY -> R.string.color_text_primary
    CompanionColor.TEXT_SECONDARY -> R.string.color_text_secondary
    CompanionColor.ACCENT -> R.string.color_accent
    CompanionColor.ERROR -> R.string.color_error
    CompanionColor.FOCUS -> R.string.color_focus
    CompanionColor.DISABLED_TEXT -> R.string.color_disabled_text
})

@Composable
private fun backgroundName(background: CompanionThemeBackground): String = stringResource(when (background) {
    CompanionThemeBackground.DEFAULT -> R.string.background_default
    CompanionThemeBackground.BLACK -> R.string.background_black
    CompanionThemeBackground.COVER_BLUR -> R.string.background_cover_blur
    CompanionThemeBackground.FLUID -> R.string.background_fluid
})

@Composable
private fun iconRoleName(role: CompanionIconRole): String = stringResource(when (role) {
    CompanionIconRole.PLAY -> R.string.icon_play
    CompanionIconRole.PAUSE -> R.string.icon_pause
    CompanionIconRole.PREVIOUS -> R.string.icon_previous
    CompanionIconRole.NEXT -> R.string.icon_next
    CompanionIconRole.MENU -> R.string.icon_menu
    CompanionIconRole.BACK -> R.string.icon_back
    CompanionIconRole.LYRICS -> R.string.icon_lyrics
    CompanionIconRole.FAVORITE -> R.string.icon_favorite
    CompanionIconRole.QUEUE -> R.string.icon_queue
})
