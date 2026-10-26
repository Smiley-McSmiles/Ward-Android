package org.ward.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import org.ward.android.data.SettingsStore

/** Time formatting is shared by the chat list, message bubbles and message details. */
val LocalTimeFormat = compositionLocalOf { "system" }
val LocalTimeSeconds = compositionLocalOf { false }

private val accents = mapOf(
    "blue" to Color(0xFF3584E4), "teal" to Color(0xFF2190A4), "green" to Color(0xFF3A944A),
    "yellow" to Color(0xFFC88800), "orange" to Color(0xFFED5B00), "red" to Color(0xFFE62D42),
    "pink" to Color(0xFFD56199), "purple" to Color(0xFF9141AC), "slate" to Color(0xFF6F8396)
)

private fun tint(accent: Color, base: Color, alpha: Float): Color =
    accent.copy(alpha = alpha.coerceIn(0f, 1f)).compositeOver(base)

/**
 * Every Material 3 colour role is filled here. The previous theme only set a handful of roles,
 * so cards, sheets and sliders inherited the default purple-tinted baseline containers and showed
 * up as mismatched panels against the app background.
 */
@Composable
fun WardTheme(settings: SettingsStore, content: @Composable () -> Unit) {
    val settingsState by settings.state.collectAsState()
    val style = settingsState["appearance.style"] ?: "system"
    val dark = when (style) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val accent = accents[settingsState["appearance.accent"] ?: "blue"] ?: Color(0xFF3584E4)
    val onAccent = if (accent.luminance() > 0.52f) Color(0xFF15161A) else Color.White

    val colors = if (dark) {
        // Material 3's dark baseline, with the selected accent lightened for text and controls.
        val primary = tint(accent, Color.White, 0.45f)
        val onPrimary = Color(0xFF1C1B1F)
        val background = Color(0xFF221E28)
        val surface = Color(0xFF221E28)
        val surfaceContainer = Color(0xFF3C3742)
        val surfaceVariant = Color(0xFF514B5B)
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = tint(accent, surfaceContainer, 0.35f),
            onPrimaryContainer = Color(0xFFF3EDF7),
            inversePrimary = tint(accent, Color.Black, 0.18f),
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = tint(accent, surfaceContainer, 0.20f),
            onSecondaryContainer = Color(0xFFE6E1E5),
            tertiary = Color(0xFFEFB8C8),
            onTertiary = Color(0xFF492532),
            tertiaryContainer = tint(Color(0xFFEFB8C8), surfaceContainer, 0.22f),
            onTertiaryContainer = Color(0xFFFFD8E4),
            background = background,
            onBackground = Color(0xFFE6E1E5),
            surface = surface,
            onSurface = Color(0xFFE6E1E5),
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = Color(0xFFCAC4D0),
            surfaceTint = primary,
            inverseSurface = Color(0xFFE6E1E5),
            inverseOnSurface = Color(0xFF313033),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
            errorContainer = Color(0xFF8C1D18),
            onErrorContainer = Color(0xFFF9DEDC),
            outline = Color(0xFF9A93A3),
            outlineVariant = Color(0xFF55505D),
            scrim = Color.Black,
            surfaceBright = Color(0xFF4A4551),
            surfaceDim = Color(0xFF221E28),
            surfaceContainerLowest = Color(0xFF1A1720),
            surfaceContainerLow = Color(0xFF322E3A),
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = Color(0xFF4A4551),
            surfaceContainerHighest = Color(0xFF554F5D)
        )
    } else {
        val background = Color(0xFFF6F6FA)
        val surface = Color(0xFFF6F6FA)
        val surfaceVariant = Color(0xFFE3E2E9)
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = tint(accent, Color.White, 0.16f),
            onPrimaryContainer = Color(0xFF15161A),
            inversePrimary = tint(accent, Color.Black, 0.18f),
            secondary = tint(accent, Color.Black, 0.22f),
            onSecondary = Color.White,
            secondaryContainer = tint(accent, Color.White, 0.10f),
            onSecondaryContainer = Color(0xFF15161A),
            tertiary = Color(0xFF006A60),
            onTertiary = Color.White,
            tertiaryContainer = tint(Color(0xFF006A60), Color.White, 0.14f),
            onTertiaryContainer = Color(0xFF00201C),
            background = background,
            onBackground = Color(0xFF1A1B20),
            surface = surface,
            onSurface = Color(0xFF1A1B20),
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = Color(0xFF46464F),
            surfaceTint = accent,
            inverseSurface = Color(0xFF2F3036),
            inverseOnSurface = Color(0xFFF1F0F7),
            error = Color(0xFFBA1A1A),
            onError = Color.White,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            outline = Color(0xFF767680),
            outlineVariant = Color(0xFFC7C5D0),
            scrim = Color.Black,
            surfaceBright = Color(0xFFFFFFFF),
            surfaceDim = Color(0xFFD9D9E0),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF2F2F7),
            surfaceContainer = Color(0xFFEDEDF3),
            surfaceContainerHigh = Color(0xFFE7E7EE),
            surfaceContainerHighest = Color(0xFFE1E1E9)
        )
    }

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(26.dp),
        extraLarge = RoundedCornerShape(34.dp)
    )
    val typography = Typography(
        headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.1).sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
        bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
        bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.25.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.35.sp)
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    val baseDensity = LocalDensity.current
    val textScale = (settingsState["appearance.text_scale"]?.toFloatOrNull() ?: 100f).coerceIn(80f, 200f) / 100f
    CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * textScale),
        LocalAvatarShapeRounded provides (settingsState["appearance.avatar_shape"] == "rounded"),
        LocalTimeFormat provides (settingsState["appearance.time_format"] ?: "system"),
        LocalTimeSeconds provides (settingsState["appearance.time_seconds"] == "true")
    ) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
            // Screens draw on a raw background rather than a Surface. Without this, unstyled Text
            // falls back to Compose's default black content colour and disappears in dark mode.
            CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
