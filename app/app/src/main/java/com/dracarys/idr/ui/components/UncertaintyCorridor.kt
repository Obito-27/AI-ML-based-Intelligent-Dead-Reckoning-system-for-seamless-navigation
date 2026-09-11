package com.dracarys.idr.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dracarys.idr.ui.state.NavigationMode
import com.dracarys.idr.ui.theme.DracarysTheme

/**
 * Map uncertainty corridor overlay.
 *
 * Draws a dashed pill/ellipse representing the positional uncertainty corridor
 * around the vehicle's estimated trajectory. Stroke color is [NavigationMode.color] —
 * the single source of truth. No hex literals inside this composable.
 *
 * In a full implementation, [corridorHalfWidthPx] would be derived from the ESKF
 * covariance estimate; here it defaults to a representative constant for preview use.
 *
 * @param mode                Current navigation mode; provides corridor stroke color.
 * @param corridorHalfWidthPx Half-width of the uncertainty corridor in pixels.
 */
@Composable
fun UncertaintyCorridor(
    mode: NavigationMode,
    modifier: Modifier = Modifier,
    corridorHalfWidthPx: Float = 40f,
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawOval(
            color = mode.color.copy(alpha = 0.25f),    // ← reads mode.color only
            topLeft = Offset(cx - corridorHalfWidthPx, cy - size.height * 0.3f),
            size = androidx.compose.ui.geometry.Size(
                width = corridorHalfWidthPx * 2f,
                height = size.height * 0.6f,
            ),
        )
        drawOval(
            color = mode.color,                         // ← reads mode.color only
            topLeft = Offset(cx - corridorHalfWidthPx, cy - size.height * 0.3f),
            size = androidx.compose.ui.geometry.Size(
                width = corridorHalfWidthPx * 2f,
                height = size.height * 0.6f,
            ),
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), phase = 0f),
                cap = StrokeCap.Round,
            ),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "Corridor — GNSS", showBackground = true, backgroundColor = 0xFF0B0E14, widthDp = 200, heightDp = 200)
@Composable
private fun PreviewCorridorGnss() {
    DracarysTheme { UncertaintyCorridor(mode = NavigationMode.Gnss, modifier = Modifier.fillMaxSize()) }
}

@Preview(name = "Corridor — Dead Reckoning", showBackground = true, backgroundColor = 0xFF0B0E14, widthDp = 200, heightDp = 200)
@Composable
private fun PreviewCorridorDR() {
    DracarysTheme { UncertaintyCorridor(mode = NavigationMode.DeadReckoning, modifier = Modifier.fillMaxSize()) }
}
