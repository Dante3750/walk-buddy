package com.walkbuddy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Calm greens with a warm amber accent: friendly, readable outdoors in daylight and at night.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F0D2),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDAE),
    onSecondaryContainer = Color(0xFF2C1700),
    tertiary = Color(0xFF3B6470),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBFE9F7),
    onTertiaryContainer = Color(0xFF001F28),
    background = Color(0xFFF6FBF7),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFF6FBF7),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD4B5),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF0A5238),
    onPrimaryContainer = Color(0xFFB8F0D2),
    secondary = Color(0xFFFFB950),
    onSecondary = Color(0xFF482900),
    secondaryContainer = Color(0xFF6A3C00),
    onSecondaryContainer = Color(0xFFFFDDAE),
    tertiary = Color(0xFFA4CDDB),
    onTertiary = Color(0xFF053541),
    tertiaryContainer = Color(0xFF214C58),
    onTertiaryContainer = Color(0xFFBFE9F7),
    background = Color(0xFF0F1714),
    onBackground = Color(0xFFDEE4DE),
    surface = Color(0xFF0F1714),
    onSurface = Color(0xFFDEE4DE),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFBFC9C1),
    outline = Color(0xFF8A938C),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun WalkBuddyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
