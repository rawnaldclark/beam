package com.zaptransfer.android.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Brand / Primary ──────────────────────────────────────────────────────────
// Indigo 500 (#6366F1) — primary brand colour for buttons, FABs, and active states.
val IndigoPrimary = Color(0xFF6366F1)

// Indigo 400 — lighter variant for dark-theme "on-surface" emphasis.
val IndigoPrimaryLight = Color(0xFF818CF8)

// Indigo 700 — darker variant for light-theme container backgrounds.
val IndigoPrimaryDark = Color(0xFF4338CA)

// Indigo 100 — primary container (light theme badge backgrounds, chip fills).
val IndigoPrimaryContainer = Color(0xFFE0E7FF)

// Indigo 900 — on-primary-container text colour in light theme.
val IndigoPrimaryContainerOnLight = Color(0xFF312E81)

// ─── Secondary ────────────────────────────────────────────────────────────────
// Violet 500 — secondary accent for selection indicators and progress bars.
val VioletSecondary = Color(0xFF8B5CF6)
val VioletSecondaryContainer = Color(0xFFEDE9FE)  // Violet 100

// ─── Background / Surface (Dark Theme) ───────────────────────────────────────
// Deep neutral backgrounds for dark mode — not pure black (avoids OLED harsh edges).
val DarkBackground = Color(0xFF0F0F14)   // Very dark indigo-tinted black
val DarkSurface = Color(0xFF1A1A24)      // Cards, bottom sheets on dark background
val DarkSurfaceVariant = Color(0xFF252535) // Elevated cards, dialogs
val DarkSurfaceTint = Color(0xFF6366F1)  // Surface tint colour = primary

// ─── Background / Surface (Light Theme) ──────────────────────────────────────
val LightBackground = Color(0xFFF8F8FF)  // Ghosted white with indigo tint
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F1FA)

// ─── On-colours ───────────────────────────────────────────────────────────────
// "On" colours define text/icon legibility on their respective backgrounds.
val OnPrimary = Color(0xFFFFFFFF)            // White on indigo buttons
val OnPrimaryDark = Color(0xFFFFFFFF)
val OnSurfaceDark = Color(0xFFE8E8F0)        // Slightly off-white for dark surface text
val OnSurfaceVariantDark = Color(0xFFB0B0C8) // Subdued secondary text on dark cards
val OnSurfaceLight = Color(0xFF1A1A2E)       // Near-black text on light surfaces
val OnSurfaceVariantLight = Color(0xFF4A4A6A)

// ─── Status / Semantic ────────────────────────────────────────────────────────
// Used for transfer status badges, notification icons, and inline error/success states.
val StatusOnline = Color(0xFF22C55E)    // Green 500 — device online indicator
val StatusOffline = Color(0xFF6B7280)   // Gray 500 — device offline/unknown
val StatusError = Color(0xFFEF4444)     // Red 500 — transfer failed
val StatusWarning = Color(0xFFF59E0B)   // Amber 500 — paused / reconnecting
val StatusSuccess = Color(0xFF10B981)   // Emerald 500 — transfer complete checkmark

// ─── Outline ──────────────────────────────────────────────────────────────────
val OutlineDark = Color(0xFF3A3A50)     // Subtle borders on dark surfaces
val OutlineLight = Color(0xFFD1D5DB)    // Light gray borders on light surfaces
