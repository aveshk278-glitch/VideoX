package com.videox.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = VideoXPurple,
    secondary = VideoXBlue,
    tertiary = Pink80,
    background = VideoXBackground,
    surface = VideoXSurface,
    surfaceVariant = VideoXSurfaceVariant,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = VideoXOnBackground,
    onSurface = VideoXOnSurface
)

private val LightColorScheme = lightColorScheme(
    primary = VideoXPurpleDark,
    secondary = VideoXBlueDark,
    tertiary = Pink40,
    background = Color(0xFFF6F5FB),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F)
)

@Composable
fun VideoXTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    // darkTheme is the user's setting (default dark). It directly selects the scheme.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
