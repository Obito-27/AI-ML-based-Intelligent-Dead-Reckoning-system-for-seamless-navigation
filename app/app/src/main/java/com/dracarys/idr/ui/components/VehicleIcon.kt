package com.dracarys.idr.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dracarys.idr.ui.state.NavigationMode
import com.dracarys.idr.ui.theme.DracarysTheme

/**
 * Vehicle direction arrow colored by the current [NavigationMode].
 *
 * Tint is [NavigationMode.color] — the single source of truth.
 * No hex literal or Color constant is referenced inside this composable.
 *
 * [heading] rotates the icon to match vehicle bearing.
 *
 * @param mode     Current navigation mode; provides tint color.
 * @param heading  Vehicle heading in degrees (0 = north, clockwise).
 * @param size     Icon size; defaults to 32.dp for capsule context.
 */
@Composable
fun VehicleIcon(
    mode: NavigationMode,
    modifier: Modifier = Modifier,
    heading: Float = 0f,
    size: Dp = 32.dp,
) {
    Icon(
        imageVector = Icons.Default.Navigation,
        contentDescription = "${mode.label} navigation",
        tint = mode.color,                   // ← reads mode.color only
        modifier = modifier
            .size(size)
            .rotate(heading),
    )
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "VehicleIcon — GNSS", showBackground = true, backgroundColor = 0xFF0B0E14)
@Composable
private fun PreviewVehicleIconGnss() {
    DracarysTheme { VehicleIcon(mode = NavigationMode.Gnss, size = 48.dp) }
}

@Preview(name = "VehicleIcon — Dead Reckoning", showBackground = true, backgroundColor = 0xFF0B0E14)
@Composable
private fun PreviewVehicleIconDR() {
    DracarysTheme { VehicleIcon(mode = NavigationMode.DeadReckoning, size = 48.dp) }
}
