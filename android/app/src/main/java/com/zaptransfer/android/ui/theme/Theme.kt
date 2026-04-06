package com.zaptransfer.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// ─── Dark colour scheme ───────────────────────────────────────────────────────
// Default for Beam — the app targets a dark-mode-first aesthetic that works well
// for file-transfer contexts (long sessions, often at night or in low-light).
private val BeamDarkColorScheme = darkColorScheme(
    primary = IndigoPrimary,
    onPrimary = OnPrimary,
    primaryContainer = IndigoPrimaryDark,
    onPrimaryContainer = OnPrimary,

    secondary = VioletSecondary,
    onSecondary = OnPrimary,
    secondaryContainer = VioletSecondaryContainer,

    background = DarkBackground,
    onBackground = OnSurfaceDark,

    surface = DarkSurface,
    onSurface = OnSurfaceDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceTint = DarkSurfaceTint,

    outline = OutlineDark,

    error = StatusError,
    onError = OnPrimary,
)

// ─── Light colour scheme ─────────────────────────────────────────────────────
// Provided for users with system light mode preference.
private val BeamLightColorScheme = lightColorScheme(
    primary = IndigoPrimaryDark,
    onPrimary = OnPrimary,
    primaryContainer = IndigoPrimaryContainer,
    onPrimaryContainer = IndigoPrimaryContainerOnLight,

    secondary = VioletSecondary,
    onSecondary = OnPrimary,
    secondaryContainer = VioletSecondaryContainer,

    background = LightBackground,
    onBackground = OnSurfaceLight,

    surface = LightSurface,
    onSurface = OnSurfaceLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = OnSurfaceVariantLight,

    outline = OutlineLight,

    error = StatusError,
    onError = OnPrimary,
)

/**
 * Top-level theme composable wrapping the entire Beam application.
 *
 * Usage: call this in [MainActivity.setContent] as the outermost composable.
 *
 * @param darkTheme       Whether to apply the dark colour scheme. Defaults to the
 *                        system setting. Can be forced to true/false in previews.
 * @param dynamicColor    Whether to use Android 12+ dynamic colours (Material You).
 *                        Defaults to false — we want consistent brand colours across
 *                        all devices rather than wallpaper-derived palettes.
 * @param content         The Compose content tree to theme.
 */
@Composable
fun BeamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Dynamic colour (Android 12+) — opt-in via caller, off by default
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BeamDarkColorScheme
        else -> BeamLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BeamTypography,
        content = content,
    )
}
