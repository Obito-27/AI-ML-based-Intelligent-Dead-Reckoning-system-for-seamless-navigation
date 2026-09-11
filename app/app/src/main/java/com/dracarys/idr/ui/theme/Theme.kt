package com.dracarys.idr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Fixed dark color scheme — no call to dynamicColorScheme() anywhere.
 *
 * Color assignments:
 * - background / surface: locked graphite tokens
 * - primary: used for system-level interactive elements (e.g. SnackBar confirm)
 * - The three mode colors are NOT mapped here — they are consumed from
 *   [com.dracarys.idr.ui.state.NavigationMode.color] in composables.
 */
private val DracarysColorScheme = darkColorScheme(
    background          = DracarysBackground,
    surface             = DracarysSurface,
    onBackground        = DracarysTextPrimary,
    onSurface           = DracarysTextPrimary,
    onSurfaceVariant    = DracarysTextSecondary,
    primary             = ModeColorGnss,          // system interactive default
    onPrimary           = DracarysBackground,
    secondary           = ModeColorFused,
    onSecondary         = DracarysBackground,
    error               = AlertRed,
    onError             = DracarysBackground,
)

/**
 * Root theme composable. Wrap the entire navigation screen inside this.
 *
 * There is intentionally **no** `if (Build.VERSION.SDK_INT >= 31) dynamicColorScheme(...)` branch.
 * The color scheme is fixed for brand consistency and WCAG contrast guarantees.
 *
 * Font resolution: on a real device/emulator, bundled TTFs (Space Grotesk + Inter) are
 * loaded from res/font/ at runtime. If the font files are missing (fresh checkout),
 * the app falls back to [FontFamily.SansSerif] — the build never fails due to missing fonts.
 */
@Composable
fun DracarysTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val typography = remember(context) {
        val headingFamily = resolveSpaceGroteskFamily(context)
        val bodyFamily = resolveInterFamily(context)
        dracarysTypography(headingFamily, bodyFamily)
    }

    MaterialTheme(
        colorScheme = DracarysColorScheme,
        typography  = typography,
        content     = content,
    )
}
