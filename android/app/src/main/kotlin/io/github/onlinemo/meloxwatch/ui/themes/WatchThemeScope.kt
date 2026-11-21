package io.github.onlinemo.meloxwatch.ui.themes

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.onlinemo.meloxwatch.data.themes.AppLanguage
import io.github.onlinemo.meloxwatch.data.themes.AppearanceOptions
import io.github.onlinemo.meloxwatch.data.themes.BuiltinTheme
import io.github.onlinemo.meloxwatch.data.themes.ThemeBuiltins
import io.github.onlinemo.meloxwatch.data.themes.ThemePalette
import io.github.onlinemo.meloxwatch.data.themes.validateThemePalette
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsFonts
import java.util.Locale

data class WatchThemeColors(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val pressed: Color,
    val text: Color,
    val secondary: Color,
    val disabled: Color,
    val accent: Color,
    val focus: Color,
    val outline: Color,
    val error: Color,
)

fun watchThemeColors(palette: ThemePalette): WatchThemeColors {
    validateThemePalette(palette)
    return WatchThemeColors(Color(palette.background), Color(palette.surface), Color(palette.surfaceRaised),
        Color(palette.surfacePressed), Color(palette.textPrimary), Color(palette.textSecondary),
        Color(palette.disabledText), Color(palette.accent), Color(palette.focus),
        Color(palette.textPrimary).copy(alpha = 0.14f), Color(palette.error))
}

val LocalWatchThemeColors = staticCompositionLocalOf { watchThemeColors(ThemeBuiltins.manifest(BuiltinTheme.DARK).palette) }
val LocalWatchAppearance = staticCompositionLocalOf { AppearanceOptions() }
val LocalWatchThemeFonts = staticCompositionLocalOf { LyricsFonts() }

/** Colors/fonts are already decoded values. Language recomposes resources without recreating owners. */
@Composable
fun WatchThemeScope(
    palette: ThemePalette,
    options: AppearanceOptions,
    fonts: LyricsFonts = LyricsFonts(),
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val systemConfiguration = LocalConfiguration.current
    val configured = remember(baseContext, systemConfiguration, options.language) {
        if (options.language == AppLanguage.SYSTEM) baseContext else {
            val locale = if (options.language == AppLanguage.SIMPLIFIED_CHINESE) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
            baseContext.createConfigurationContext(Configuration(systemConfiguration).apply {
                setLocales(LocaleList(locale))
                setLayoutDirection(locale)
            })
        }
    }
    val colors = remember(palette) { watchThemeColors(palette) }
    CompositionLocalProvider(
        LocalContext provides configured,
        LocalConfiguration provides configured.resources.configuration,
        LocalWatchThemeColors provides colors,
        LocalWatchAppearance provides options,
        LocalWatchThemeFonts provides fonts,
        content = content,
    )
}
