package com.evaristosuarez.miscalorias.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Color.White,
    primaryContainer = AmberLight,
    onPrimaryContainer = AmberDark,
    secondary = ProteinAccent,
    onSecondary = Color.White,
    secondaryContainer = AppSurfaceSubtle,
    onSecondaryContainer = TextPrimary,
    tertiary = FiberAccent,
    onTertiary = Color.White,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = AppSurface,
    onSurface = TextPrimary,
    surfaceVariant = AppSurfaceSubtle,
    onSurfaceVariant = TextSecondary,
    outline = AppBorder,
    outlineVariant = AppSurfaceSubtle,
    error = ErrorRed,
    onError = Color.White,
    inverseSurface = TextPrimary,
    inverseOnSurface = AppBackground,
    scrim = Color.Black.copy(alpha = 0.4f),
)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = DarkBackground,
    primaryContainer = DarkAmberContainer,
    onPrimaryContainer = AmberLight,
    secondary = ProteinAccent,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceSecondary,
    onSecondaryContainer = DarkOnSurface,
    tertiary = FiberAccent,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceSecondary,
    onSurfaceVariant = DarkSecondaryText,
    outline = DarkBorder,
    outlineVariant = DarkSurfaceSecondary,
    error = Color(0xFFE06D6D),
    onError = DarkBackground,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = DarkBackground,
    scrim = Color.Black.copy(alpha = 0.6f),
)

@Composable
fun MisCaloriasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = colors.background.toArgb()
                window.navigationBarColor = colors.surface.toArgb()
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = MisCaloriasTypography,
        shapes = MisCaloriasShapes,
        content = content,
    )
}
