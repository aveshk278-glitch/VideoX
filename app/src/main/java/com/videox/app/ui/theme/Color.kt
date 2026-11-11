package com.videox.app.ui.theme

import androidx.compose.ui.graphics.Color

// VideoX premium identity (v2.11 redesign). Exact palette from the
// redesign brief — purple used strategically for actions and active
// states, everything else restrained dark neutrals.

// Dark palette (the default VideoX look).
val VideoXBackground = Color(0xFF101019)
val VideoXBackgroundSecondary = Color(0xFF171722)
val VideoXCard = Color(0xFF1D1D2B)
val VideoXCardElevated = Color(0xFF232233)
val VideoXPurple = Color(0xFF8050FF)
val VideoXPurpleDeep = Color(0xFF6A3DF0)
val VideoXPurpleSoft = Color(0xFFA78BFA)
val VideoXTextPrimary = Color(0xFFF5F4FA)
val VideoXTextSecondary = Color(0xFFA6A4B5)
val VideoXBorder = Color(0xFF2A2A3A)
val VideoXBorderLight = Color(0xFF34344A)

// Light palette (when the user turns the dark theme off). Same purple
// identity, light neutrals, darker text for contrast.
val VideoXLightBackground = Color(0xFFF6F5FB)
val VideoXLightBackgroundSecondary = Color(0xFFEDECF5)
val VideoXLightCard = Color(0xFFFFFFFF)
val VideoXLightCardElevated = Color(0xFFFFFFFF)
val VideoXLightTextPrimary = Color(0xFF1C1B22)
val VideoXLightTextSecondary = Color(0xFF6B6A78)
val VideoXLightBorder = Color(0xFFE2E0EE)
val VideoXLightBorderLight = Color(0xFFD5D3E6)

// Semantic colours, readable on both themes.
val VideoXSuccess = Color(0xFF34C98E)
val VideoXSuccessContainer = Color(0xFF14382C)
val VideoXWarning = Color(0xFFF5B544)
val VideoXWarningContainer = Color(0xFF3A2C12)
val VideoXError = Color(0xFFFF6B6B)
val VideoXErrorContainer = Color(0xFF3D1D22)
val VideoXInfo = Color(0xFF7AB8FF)

// Legacy aliases kept so older references keep compiling.
val VideoXPurpleDark = VideoXPurpleDeep
val VideoXBlue = VideoXPurpleSoft
val VideoXBlueDark = VideoXPurpleDeep
val VideoXSurface = VideoXBackgroundSecondary
val VideoXSurfaceVariant = VideoXCard
val VideoXOnBackground = VideoXTextPrimary
val VideoXOnSurface = VideoXTextPrimary
val Purple80 = VideoXPurpleSoft
val PurpleGrey80 = VideoXTextSecondary
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = VideoXPurpleDeep
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)
