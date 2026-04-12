package com.zaptransfer.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.zaptransfer.android.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Beam design system tokens — v1 (dark-only).
 *
 * Single source of truth for the Beam redesign token layer on Android.
 * Mirrors [extension/popup/tokens.css] value-for-value. The two files MUST
 * stay in lockstep; do not edit one without updating the other, and do not
 * add values that are not in the design direction document at
 * `docs/design/2026-04-11-design-direction-v1.md`.
 *
 * This file is intentionally inert in sub-phase 1a: nothing in the current
 * UI consumes these objects yet. The existing `Color.kt`, `Type.kt`, and
 * `Theme.kt` continue to drive every screen. Sub-phase 1c will migrate
 * composables from the `MaterialTheme` tokens to `BeamPalette` /
 * `BeamTextStyle` / `BeamSpace` / `BeamCorner` / `BeamMotion`, at which
 * point the old files become deletable.
 *
 * Per design decision D5, Android uses the system sans font stack
 * (`FontFamily.Default`) rather than bundling Inter Variable, with tabular
 * figures enabled via `fontFeatureSettings = "tnum"`. Chrome bundles Inter
 * separately.
 */

// ─── Palette ───────────────────────────────────────────────────────────────

/**
 * Beam color palette. Dark mode only in v1. Depth is expressed by stepping
 * `bg0 -> bg1 -> bg2` plus hairline borders — never shadows.
 */
object BeamPalette {
    // Canvas and surfaces.
    val bg0: Color = Color(0xFF0A0B0D)
    val bg1: Color = Color(0xFF111316)
    val bg2: Color = Color(0xFF181A1F)

    // Borders and focus.
    val borderSubtle: Color = Color(0xFF1F232A)
    val borderStrong: Color = Color(0xFF2A2F38)

    // Text — four steps.
    val textHi: Color = Color(0xFFF2F4F7)
    val textMid: Color = Color(0xFF9BA3AE)
    val textLo: Color = Color(0xFF6B7280)
    val textDisabled: Color = Color(0xFF3F4451)

    // Accent — Signal Cyan. Carries focus ring, selection, progress fill, primary button.
    val accent: Color = Color(0xFF5BE4E4)
    val accentHover: Color = Color(0xFF7BEDED)

    /**
     * Selection-fill at 16% accent alpha. Named `accent12` by convention
     * but tuned from 12% to 16% after the contrast audit: 12% produced
     * only 1.24:1 contrast on bg/0 — below the 1.3 perceptual threshold.
     * 16% yields 1.36:1 on bg/0 and 1.41:1 on bg/1 — passes. The
     * primary selection cue remains a 2 dp accent left border; this fill
     * is supplementary.
     */
    val accent12: Color = accent.copy(alpha = 0.16f)

    // Semantic — all muted, never bright.
    val success: Color = Color(0xFF5FB88C)
    val success12: Color = success.copy(alpha = 0.12f)
    val warning: Color = Color(0xFFD4A55F)
    val danger: Color = Color(0xFFD46F6F)
    val danger12: Color = danger.copy(alpha = 0.12f)

    // Presence (status dots).
    val online: Color = success
    val offline: Color = Color(0xFF6B7280)

    // Focus ring. Named separately from `accent` so a later theme can tune
    // one without the other.
    val focusRing: Color = accent
}

// ─── Typography ───────────────────────────────────────────────────────────

/**
 * Named Beam text styles. System sans with tabular figures. The scale is
 * hand-tuned at 11 / 12 / 13 / 14 / 16 / 22 sp — no modular ratio. Three
 * weights only: 400 / 500 / 600.
 *
 * Usage: components pass one of these values to a Compose `Text`
 * composable's `style` parameter. Do not define new styles outside this
 * scale.
 */
object BeamTextStyle {

    /**
     * Inter Variable — bundled at res/font/inter_variable.ttf (~860 KB).
     * Matches the Chrome extension's @font-face Inter exactly for full
     * cross-platform visual parity. The variable font supports weights
     * 100–900 on a single axis.
     */
    private val interFamily: FontFamily = FontFamily(
        Font(R.font.inter_variable, FontWeight.Normal),   // 400
        Font(R.font.inter_variable, FontWeight.Medium),    // 500
        Font(R.font.inter_variable, FontWeight.SemiBold),  // 600
    )
    private val systemMono: FontFamily = FontFamily.Monospace

    /** Tabular figures + Inter character variant 11 for 1/l/I disambiguation. */
    private const val NUMERIC_FEATURES = "tnum, cv11"

    // xs — 11 sp — shortcut chips, finest metadata.
    val xsRegular: TextStyle = TextStyle(
        fontFamily = interFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = NUMERIC_FEATURES,
    )
    val xsMono: TextStyle = TextStyle(
        fontFamily = systemMono,
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp, // approx +0.02em on an 11 sp glyph
        fontFeatureSettings = NUMERIC_FEATURES,
    )

    // sm — 12 sp — section headers, secondary rows.
    val smRegular: TextStyle = TextStyle(
        fontFamily = interFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = NUMERIC_FEATURES,
    )
    val smMedium: TextStyle = TextStyle(
        fontFamily = interFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = NUMERIC_FEATURES,
    )
    val smMono: TextStyle = TextStyle(
        fontFamily = systemMono,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = NUMERIC_FEATURES,
    )

    // base — 13 sp — default body, device names, row content.
    val baseRegular: TextStyle = TextStyle(
        fontFamily = interFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = NUMERIC_FEATURES,
    )
    val baseMedium: TextStyle = TextStyle(
        fontFamily = interFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = NUMERIC_FEATURES,
    )
    val baseSemibold: TextStyle = TextStyle(
        fontFamily = interFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = NUMERIC_FEATURES,
    )

    // md — 14 sp — section titles, emphasized row leads. Tight tracking.
    val mdMedium: TextStyle = TextStyle(
        fontFamily = interFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.14).sp, // approx -0.01em
        fontFeatureSettings = NUMERIC_FEATURES,
    )
    val mdSemibold: TextStyle = TextStyle(
        fontFamily = interFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.14).sp,
        fontFeatureSettings = NUMERIC_FEATURES,
    )

    // lg — 16 sp — surface titles.
    val lgSemibold: TextStyle = TextStyle(
        fontFamily = interFamily,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.16).sp,
        fontFeatureSettings = NUMERIC_FEATURES,
    )

    // xl — 22 sp — hero / pairing display type (SAS, PIN, hero alias).
    val xlSemibold: TextStyle = TextStyle(
        fontFamily = interFamily,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.22).sp,
        fontFeatureSettings = NUMERIC_FEATURES,
    )
    val xlMono: TextStyle = TextStyle(
        fontFamily = systemMono,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = NUMERIC_FEATURES,
    )
}

// ─── Spacing ──────────────────────────────────────────────────────────────

/**
 * 4 dp base grid. `s1` … `s8` = 4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 dp.
 * Row horizontal padding is `s4` (16 dp) on Android; vertical padding is
 * `s3` (12 dp). Chrome uses `s3` / `s2` — see `tokens.css`.
 */
object BeamSpace {
    val s0 = 0.dp
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s7 = 32.dp
    val s8 = 40.dp
}

// ─── Corner radius ────────────────────────────────────────────────────────

/**
 * Sharp, not pillowy. Nothing above 8 dp except the pill.
 *
 * Use `CornerSize(BeamCorner.md)` or `RoundedCornerShape(BeamCorner.md)`
 * when composing shapes for buttons, inputs, and row selection fills.
 */
object BeamCorner {
    val sm = 4.dp
    val md = 6.dp
    val lg = 8.dp
    val pill = 999.dp
}

// ─── Row metrics ──────────────────────────────────────────────────────────

/**
 * Canonical row dimensions. Android row height is 64 dp (touch target
 * friendly). Chrome uses 36 px — see `tokens.css`.
 */
object BeamRow {
    val height = 64.dp
    val paddingHorizontal = BeamSpace.s4    // 16 dp
    val paddingVertical = BeamSpace.s3      // 12 dp

    /** Leading slot size for status dot or ring-progress. */
    val dotSize = 8.dp
    val ringSize = 28.dp
}

// ─── Motion ───────────────────────────────────────────────────────────────

/**
 * Three durations, two easings. No bounces, no oscillation. No hover
 * motion on Android (there is no hover); on Chrome, hover fills snap
 * with the `fast` duration but do not translate or scale.
 */
object BeamMotion {
    /** 120 ms — focus ring, hover fill, selection commit, ring fade-in. */
    const val durFastMs: Int = 120

    /** 180 ms — row state change, sheet slide, popup / surface-replacement slide. */
    const val durBaseMs: Int = 180

    /** 260 ms — pairing step transitions, empty-to-populated staggers. */
    const val durSlowMs: Int = 260

    /** Enters, state-in, appearances. `cubic-bezier(0.2, 0, 0, 1)`. */
    val easeOut: CubicBezierEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Exits, dismissals. `cubic-bezier(0.4, 0, 1, 1)`. */
    val easeIn: CubicBezierEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** Pre-built tween specs for common Compose animations. */
    val tweenFast = tween<Float>(durationMillis = durFastMs, easing = easeOut)
    val tweenBase = tween<Float>(durationMillis = durBaseMs, easing = easeOut)
    val tweenSlow = tween<Float>(durationMillis = durSlowMs, easing = easeOut)
}
