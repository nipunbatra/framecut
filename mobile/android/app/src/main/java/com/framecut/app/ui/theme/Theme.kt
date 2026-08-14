package com.framecut.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Ink = Color(0xFF1B2A4A)
private val Ember = Color(0xFFFF7A45)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4F7),
    onPrimaryContainer = Color(0xFF0B1428),
    secondary = Color(0xFF4C5B7A),
    tertiary = Ember,
    onTertiary = Color(0xFF3A1400),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFE2E4EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF757780),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C6EE),
    onPrimary = Color(0xFF1B2A4A),
    primaryContainer = Color(0xFF2C3D60),
    onPrimaryContainer = Color(0xFFDCE4F7),
    secondary = Color(0xFFBCC5DC),
    tertiary = Ember,
    onTertiary = Color(0xFF3A1400),
    background = Color(0xFF12131A),
    surface = Color(0xFF12131A),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
)

@Composable
fun FrameCutTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Material You where the platform offers it, hand-tuned palette otherwise.
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            @Suppress("DEPRECATION")
            window.statusBarColor = colors.background.toArgb()
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}
