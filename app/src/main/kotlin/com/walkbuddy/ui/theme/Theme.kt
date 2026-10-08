package com.walkbuddy.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/*
 * "Dusk": the colours of an evening walk. Saffron to coral to rose on the ring, a lagoon teal for your buddy,
 * plum-tinted neutrals in light mode and true black in dark mode (kind to OLED screens and to night-time eyes).
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFC93F67),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E1),
    onPrimaryContainer = Color(0xFF3F0019),
    secondary = Color(0xFF0E7C7B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBDEFEA),
    onSecondaryContainer = Color(0xFF00201F),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFFBF6F9),
    onBackground = Color(0xFF2A1B2E),
    surface = Color(0xFFFBF6F9),
    onSurface = Color(0xFF2A1B2E),
    surfaceVariant = Color(0xFFEADFE6),
    onSurfaceVariant = Color(0xFF4F4254),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6EEF3),
    surfaceContainer = Color(0xFFF1E7EE),
    surfaceContainerHigh = Color(0xFFEBDFE8),
    surfaceContainerHighest = Color(0xFFE4D6E1),
    outline = Color(0xFF827086),
    outlineVariant = Color(0xFFD3C3D0),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C4),
    onPrimary = Color(0xFF650030),
    primaryContainer = Color(0xFF8E1F48),
    onPrimaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFF63D8D3),
    onSecondary = Color(0xFF003735),
    secondaryContainer = Color(0xFF00504E),
    onSecondaryContainer = Color(0xFFBDEFEA),
    tertiary = Color(0xFFFFC46B),
    onTertiary = Color(0xFF472A00),
    tertiaryContainer = Color(0xFF664000),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF3E8F0),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF3E8F0),
    surfaceVariant = Color(0xFF2B2230),
    onSurfaceVariant = Color(0xFFD3C3D0),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0C080E),
    surfaceContainer = Color(0xFF140E17),
    surfaceContainerHigh = Color(0xFF1D1520),
    surfaceContainerHighest = Color(0xFF271D2B),
    outline = Color(0xFF9C8BA0),
    outlineVariant = Color(0xFF42354A),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Colours that Material 3 has no slot for: the ring gradient, the buddy colour and the flame. */
@Immutable
data class WbColors(
    val ringStart: Color,
    val ringMid: Color,
    val ringEnd: Color,
    val ringTrack: Color,
    val glow: Color,
    val buddy: Color,
    val onBuddy: Color,
    val flameOuter: Color,
    val flameInner: Color,
    val confetti: List<Color>,
)

private val LightWb = WbColors(
    ringStart = Color(0xFFFFB347), ringMid = Color(0xFFFF6B5E), ringEnd = Color(0xFFD63384),
    ringTrack = Color(0x1F2A1B2E), glow = Color(0xFFFF8A6B), buddy = Color(0xFF0E7C7B), onBuddy = Color.White,
    flameOuter = Color(0xFFFF6B3D), flameInner = Color(0xFFFFC94D),
    confetti = listOf(Color(0xFFFFB347), Color(0xFFFF6B5E), Color(0xFFD63384), Color(0xFF0E9E9A), Color(0xFF7B5EA7)),
)

private val DarkWb = WbColors(
    ringStart = Color(0xFFFFC46B), ringMid = Color(0xFFFF7A6E), ringEnd = Color(0xFFFF5C9E),
    ringTrack = Color(0x26F3E8F0), glow = Color(0xFFFF7A6E), buddy = Color(0xFF4FD6CF), onBuddy = Color(0xFF00201F),
    flameOuter = Color(0xFFFF7A45), flameInner = Color(0xFFFFD166),
    confetti = listOf(Color(0xFFFFC46B), Color(0xFFFF7A6E), Color(0xFFFF5C9E), Color(0xFF4FD6CF), Color(0xFFB79CFF)),
)

@Immutable
data class WbMotion(val reduceMotion: Boolean, val haptics: Boolean)

val LocalWbColors = compositionLocalOf { LightWb }
val LocalWbMotion = compositionLocalOf { WbMotion(reduceMotion = false, haptics = true) }

object WbTheme {
    val colors: WbColors @Composable @ReadOnlyComposable get() = LocalWbColors.current
    val motion: WbMotion @Composable @ReadOnlyComposable get() = LocalWbMotion.current
}

/** Rounded "pebble" shapes: generous on cards and sheets, tighter on chips. */
private val WbShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

@Composable
fun systemReducesMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

@Composable
fun WalkBuddyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    reduceMotion: Boolean = false,
    haptics: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(ctx).copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black)
            else dynamicLightColorScheme(ctx)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val wb = if (darkTheme) DarkWb else LightWb
    val motion = WbMotion(reduceMotion = reduceMotion || systemReducesMotion(), haptics = haptics)
    CompositionLocalProvider(LocalWbColors provides wb, LocalWbMotion provides motion) {
        MaterialTheme(colorScheme = scheme, typography = WbTypography, shapes = WbShapes, content = content)
    }
}
