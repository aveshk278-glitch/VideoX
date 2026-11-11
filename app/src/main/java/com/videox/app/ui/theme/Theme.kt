package com.videox.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Premium shape system (v2.11): rounded corners between 14dp and 20dp.
private val VideoXShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private val DarkColorScheme = darkColorScheme(
    primary = VideoXPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A1E5C),
    onPrimaryContainer = Color(0xFFD9CCFF),
    secondary = VideoXPurpleSoft,
    onSecondary = Color(0xFF221A3D),
    secondaryContainer = Color(0xFF2E2A45),
    onSecondaryContainer = VideoXTextPrimary,
    tertiary = VideoXInfo,
    background = VideoXBackground,
    onBackground = VideoXTextPrimary,
    surface = VideoXBackgroundSecondary,
    onSurface = VideoXTextPrimary,
    surfaceVariant = VideoXCard,
    onSurfaceVariant = VideoXTextSecondary,
    surfaceContainer = VideoXCard,
    surfaceContainerHigh = VideoXCardElevated,
    surfaceContainerLow = VideoXBackgroundSecondary,
    outline = VideoXBorderLight,
    outlineVariant = VideoXBorder,
    error = VideoXError,
    errorContainer = VideoXErrorContainer,
    onErrorContainer = Color(0xFFFFD7D7),
    surfaceTint = VideoXPurple
)

private val LightColorScheme = lightColorScheme(
    primary = VideoXPurpleDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF2A1E5C),
    secondary = VideoXPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FB),
    onSecondaryContainer = VideoXLightTextPrimary,
    tertiary = Color(0xFF2F7DE1),
    background = VideoXLightBackground,
    onBackground = VideoXLightTextPrimary,
    surface = VideoXLightCard,
    onSurface = VideoXLightTextPrimary,
    surfaceVariant = VideoXLightBackgroundSecondary,
    onSurfaceVariant = VideoXLightTextSecondary,
    surfaceContainer = VideoXLightCard,
    surfaceContainerHigh = Color(0xFFF9F8FD),
    surfaceContainerLow = VideoXLightBackgroundSecondary,
    outline = VideoXLightBorderLight,
    outlineVariant = VideoXLightBorder,
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFF5F1414),
    surfaceTint = VideoXPurple
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
        shapes = VideoXShapes,
        content = content
    )
}
