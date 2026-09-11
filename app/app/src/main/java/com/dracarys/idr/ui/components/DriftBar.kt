package com.dracarys.idr.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dracarys.idr.ui.state.NavigationState
import com.dracarys.idr.ui.theme.AlertRed
import com.dracarys.idr.ui.theme.DracarysTheme
import com.dracarys.idr.ui.theme.DracarysTypography
import com.dracarys.idr.ui.theme.ModeColorGnss

/**
 * Drift-vs-10%-target bar for the instrument capsule expanded state.
 *
 * ### Color logic
 * - Drift ≤ [NavigationState.DRIFT_TARGET_PERCENT] (10 %): bar is teal ([ModeColorGnss] — below target is good).
 * - Drift > target: bar is [AlertRed].
 *
 * The target threshold is sourced from [NavigationState.DRIFT_TARGET_PERCENT] — not hardcoded.
 * A vertical target-line marker is drawn at the 10 % position for visual reference.
 *
 * @param driftPercent Accumulated drift as a percentage of travelled distance (e.g. 11.22).
 * @param maxDisplayPercent Upper end of the bar scale (default 30 %). Drift beyond this clips to full.
 */
@Composable
fun DriftBar(
    driftPercent: Float,
    modifier: Modifier = Modifier,
    maxDisplayPercent: Float = 30f,
) {
    val fraction = (driftPercent / maxDisplayPercent).coerceIn(0f, 1f)
    val targetFraction = NavigationState.DRIFT_TARGET_PERCENT / maxDisplayPercent
    val isAboveTarget = driftPercent > NavigationState.DRIFT_TARGET_PERCENT
    val barColor = if (isAboveTarget) AlertRed else ModeColorGnss

    Column(modifier = modifier) {
        Text(
            text = "Drift  ${"%.1f".format(driftPercent)}%  (target < ${NavigationState.DRIFT_TARGET_PERCENT.toInt()}%)",
            style = DracarysTypography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        ) {
            // Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            // Fill
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor),
            )
            // Target-line marker at DRIFT_TARGET_PERCENT position
            Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                val x = size.width * targetFraction
                drawLine(
                    color = Color.White.copy(alpha = 0.60f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "DriftBar — 3.2% (below target)", showBackground = true, backgroundColor = 0xFF12161F)
@Composable
private fun PreviewDriftBarGood() {
    DracarysTheme {
        DriftBar(driftPercent = 3.2f, modifier = Modifier.padding(16.dp))
    }
}

@Preview(name = "DriftBar — 11.22% (above target)", showBackground = true, backgroundColor = 0xFF12161F)
@Composable
private fun PreviewDriftBarAlert() {
    DracarysTheme {
        DriftBar(driftPercent = 11.22f, modifier = Modifier.padding(16.dp))
    }
}

@Preview(name = "DriftBar — 24.8% (high outage)", showBackground = true, backgroundColor = 0xFF12161F)
@Composable
private fun PreviewDriftBarHigh() {
    DracarysTheme {
        DriftBar(driftPercent = 24.8f, modifier = Modifier.padding(16.dp))
    }
}
