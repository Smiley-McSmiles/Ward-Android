package org.ward.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import org.ward.android.data.SettingsStore

private val accents = mapOf(
    "blue" to Color(0xFF3584E4), "teal" to Color(0xFF2190A4), "green" to Color(0xFF3A944A),
    "yellow" to Color(0xFFC88800), "orange" to Color(0xFFED5B00), "red" to Color(0xFFE62D42),
    "pink" to Color(0xFFD56199), "purple" to Color(0xFF9141AC), "slate" to Color(0xFF6F8396)
)

@Composable
fun WardTheme(settings: SettingsStore, content: @Composable () -> Unit) {
    val settingsState by settings.state.collectAsState()
    val style = settingsState["appearance.style"] ?: "system"
    val dark = when (style) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val accent = accents[settingsState["appearance.accent"] ?: "blue"] ?: Color(0xFF3584E4)
    val surface = if (dark) Color(0xFF1E1E1E) else Color(0xFFFDFDFD)
    val background = if (dark) Color(0xFF171717) else Color(0xFFF6F5F4)
    val tintAlpha = if (dark) 0.34f else 0.18f
    val primaryContainer = accent.copy(alpha = tintAlpha).compositeOver(surface)
    val secondaryContainer = accent.copy(alpha = tintAlpha * 0.80f).compositeOver(surface)
    val tertiaryContainer = accent.copy(alpha = tintAlpha * 1.25f).compositeOver(surface)
    val onContainer = if (dark) Color(0xFFF4F1F7) else Color(0xFF1C1B1F)
    val onAccent = if (accent.luminance() > 0.55f) Color(0xFF1C1B1F) else Color.White
    val colors = if (dark) darkColorScheme(
        primary = accent, onPrimary = onAccent,
        primaryContainer = primaryContainer, onPrimaryContainer = onContainer,
        secondary = accent, onSecondary = onAccent,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onContainer,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onContainer,
        surface = surface, background = background, surfaceVariant = Color(0xFF2C2C2C)
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent,
        primaryContainer = primaryContainer, onPrimaryContainer = onContainer,
        secondary = accent, onSecondary = onAccent,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onContainer,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onContainer,
        surface = surface, background = background, surfaceVariant = Color(0xFFEEEDEB)
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            window.statusBarColor = colors.surface.toArgb()
            window.navigationBarColor = colors.surface.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
