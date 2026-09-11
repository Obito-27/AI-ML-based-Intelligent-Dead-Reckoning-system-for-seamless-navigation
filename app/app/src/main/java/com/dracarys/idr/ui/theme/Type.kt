package com.dracarys.idr.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle

// ── Font Families with fallback ──────────────────────────────────────────────

/**
 * Attempts to load a font resource by name. Returns null if the resource doesn't exist.
 * This allows the build to succeed even without the TTF files present.
 */
private fun tryLoadFont(context: Context, fontName: String, weight: FontWeight): Font? {
    val resId = context.resources.getIdentifier(fontName, "font", context.packageName)
    return if (resId != 0) Font(resId, weight) else null
}

/**
 * Builds [SpaceGroteskFamily] from bundled TTFs if available, otherwise falls back to
 * [FontFamily.SansSerif]. The fallback is visually inferior but the app remains functional
 * on a fresh checkout before running FONTS.md's download step.
 */
fun resolveSpaceGroteskFamily(context: Context): FontFamily {
    val regular = tryLoadFont(context, "space_grotesk_regular", FontWeight.Normal)
    val bold = tryLoadFont(context, "space_grotesk_bold", FontWeight.Bold)
    return if (regular != null && bold != null) {
        FontFamily(regular, bold)
    } else {
        FontFamily.SansSerif   // system fallback
    }
}

/**
 * Builds [InterFamily] from bundled TTF if available, otherwise falls back to
 * [FontFamily.SansSerif].
 */
fun resolveInterFamily(context: Context): FontFamily {
    val regular = tryLoadFont(context, "inter_regular", FontWeight.Normal)
    return if (regular != null) {
        FontFamily(regular)
    } else {
        FontFamily.SansSerif   // system fallback
    }
}

// ── Static fallbacks for @Preview (no Context available) ─────────────────────

/**
 * For @Preview composables and JVM unit tests where Context is unavailable.
 * Uses system sans-serif — previews render correctly, just with a different font face.
 */
val SpaceGroteskFallback = FontFamily.SansSerif
val InterFallback = FontFamily.SansSerif

// ── Tabular-figures helper ───────────────────────────────────────────────────

/**
 * [PlatformTextStyle] for numeric readouts.
 */
private val TnumPlatformStyle = PlatformTextStyle()

/**
 * Convenience extension that adds OpenType feature "tnum" to a [TextStyle].
 * Usage: `style.withTabularFigures()`
 */
fun TextStyle.withTabularFigures(): TextStyle = this.copy(
    fontFeatureSettings = "tnum",
    platformStyle = TnumPlatformStyle,
)

// ── Typography factory ───────────────────────────────────────────────────────

/**
 * Creates the full [Typography] using resolved font families.
 * Called from [DracarysTheme] with runtime-resolved families.
 */
fun dracarysTypography(
    headingFamily: FontFamily = SpaceGroteskFallback,
    bodyFamily: FontFamily = InterFallback,
): Typography = Typography(
    // Large mode label / speed readout (instrument cluster size)
    displayLarge = TextStyle(
        fontFamily = headingFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        color = DracarysTextPrimary,
        fontFeatureSettings = "tnum",
        platformStyle = TnumPlatformStyle,
    ),
    // Standard numeric readout (drift %, confidence, distance)
    displayMedium = TextStyle(
        fontFamily = headingFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        color = DracarysTextPrimary,
        fontFeatureSettings = "tnum",
        platformStyle = TnumPlatformStyle,
    ),
    // Outage timer, small numeric fields
    displaySmall = TextStyle(
        fontFamily = headingFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        color = DracarysTextPrimary,
        fontFeatureSettings = "tnum",
        platformStyle = TnumPlatformStyle,
    ),
    // Section headings (capsule title bar)
    headlineMedium = TextStyle(
        fontFamily = headingFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        color = DracarysTextPrimary,
    ),
    // Body / secondary labels
    bodyMedium = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = DracarysTextSecondary,
    ),
    bodySmall = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = DracarysTextSecondary,
    ),
    // Mode badge pill text
    labelLarge = TextStyle(
        fontFamily = headingFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = DracarysTextPrimary,
        textAlign = TextAlign.Center,
    ),
)

/**
 * Default Typography instance using system fallback fonts.
 * Used by composables and @Preview functions that reference DracarysTypography directly.
 * [DracarysTheme] overrides this at runtime with resolved bundled fonts when available.
 */
val DracarysTypography: Typography = dracarysTypography()
