package com.dracarys.idr.ui.theme

import androidx.compose.ui.graphics.Color

// ── Background / Surface ──────────────────────────────────────────────────────
/** Near-black graphite; full-bleed screen background. */
val DracarysBackground = Color(0xFF0B0E14)

/** Slightly lighter graphite; instrument capsule surface. */
val DracarysSurface = Color(0xFF12161F)

// ── Text ──────────────────────────────────────────────────────────────────────
/** Primary text (headings, numeric readouts). */
val DracarysTextPrimary = Color(0xFFEDEFF3)

/** Secondary text (labels, sub-headings). */
val DracarysTextSecondary = Color(0xFF8B93A3)

// ── Alert ─────────────────────────────────────────────────────────────────────
/** Alert red — used by [com.dracarys.idr.ui.components.DriftBar] when drift exceeds 10 % target. */
val AlertRed = Color(0xFFF87171)

// Mode colors
// NOTE: Mode colors are authoritative on NavigationMode.color (com.dracarys.idr.ui.state.NavigationMode).
// These theme constants exist ONLY for the ColorScheme declaration in Theme.kt.
// All composables must read `mode.color` — never reference ModeColorGnss etc. directly in layouts.
internal val ModeColorGnss          = Color(0xFF2DD4BF)
internal val ModeColorFused         = Color(0xFFF5A623)
internal val ModeColorDeadReckoning = Color(0xFFC084FC)

// ── Convenient aliases for components and sheets ─────────────────────────────
val DracarysGnssTeal = ModeColorGnss
val DracarysAlertRed = AlertRed
val DracarysCardSurface = DracarysSurface
val DracarysPrimaryText = DracarysTextPrimary
val DracarysSecondaryText = DracarysTextSecondary
