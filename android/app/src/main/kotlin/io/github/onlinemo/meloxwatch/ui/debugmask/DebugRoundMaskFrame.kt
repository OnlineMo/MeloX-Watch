package io.github.onlinemo.meloxwatch.ui.debugmask

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import io.github.onlinemo.meloxwatch.BuildConfig

internal const val DEBUG_ROUND_MASK_ACTION = "io.github.onlinemo.meloxwatch.debug.ROUND_MASK"
internal const val DEBUG_ROUND_MASK_ENABLED = "io.github.onlinemo.meloxwatch.debug.round_mask_enabled"

private const val DEBUG_ROUND_MASK_PREFERENCES = "melox_debug_round_mask"
private const val DEBUG_ROUND_MASK_SAVED_ENABLED = "enabled"

/** Missing or malformed settings leave the existing crop unchanged. */
@Suppress("DEPRECATION")
internal fun debugRoundMaskRequested(intent: Intent?): Boolean? {
    if (!BuildConfig.DEBUG) return null
    val source = intent ?: return null
    if (source.action != DEBUG_ROUND_MASK_ACTION) return null
    return try { source.extras?.get(DEBUG_ROUND_MASK_ENABLED) as? Boolean }
    catch (_: RuntimeException) { null }
}

internal fun readDebugRoundMask(context: Context): Boolean {
    if (!BuildConfig.DEBUG) return false
    return try {
        context.getSharedPreferences(DEBUG_ROUND_MASK_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(DEBUG_ROUND_MASK_SAVED_ENABLED, false)
    } catch (_: RuntimeException) { false }
}

/** Schedule this private Debug value without blocking an Activity callback on a disk commit. */
internal fun saveDebugRoundMask(context: Context, enabled: Boolean): Boolean {
    if (!BuildConfig.DEBUG) return false
    return try {
        context.getSharedPreferences(DEBUG_ROUND_MASK_PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(DEBUG_ROUND_MASK_SAVED_ENABLED, enabled).apply()
        true
    } catch (_: RuntimeException) { false }
}

/** Keep the Debug composition stable while toggling the crop; Release uses the original content. */
@Composable
internal fun DebugRoundMaskFrame(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!BuildConfig.DEBUG) {
        content()
        return
    }
    val window = LocalWindowInfo.current.containerSize
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
        .drawWithCache {
            val radius = minOf(window.width, window.height) / 2f
            val center = Offset(window.width / 2f, window.height / 2f) - origin
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                if (radius > 0f) addOval(Rect(center.x - radius, center.y - radius,
                    center.x + radius, center.y + radius))
            }
            onDrawWithContent {
                drawContent()
                if (enabled && radius > 0f) clipRect { drawPath(outside, Color.Black) }
            }
        }) {
        content()
    }
}
