package com.dracarys.idr.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dracarys.idr.ui.state.FakeNavigationRepository
import com.dracarys.idr.ui.state.NavigationMode
import com.dracarys.idr.ui.theme.DracarysBackground
import com.dracarys.idr.ui.theme.DracarysTheme
import com.dracarys.idr.ui.theme.DracarysTypography

/**
 * Horizontal confidence bar for the instrument capsule expanded state.
 *
 * Fill color is [mode.color] — the single source of truth; no hex literals here.
 *
 * @param confidence Model velocity confidence in [0, 1]. 1.0 = full bar.
 * @param mode       Current navigation mode; provides bar fill color.
 */
@Composable
fun ConfidenceBar(
    confidence: Float,
    mode: NavigationMode,
    modifier: Modifier = Modifier,
) {
    val fraction = confidence.coerceIn(0f, 1f)

    Column(modifier = modifier) {
        Text(
            text = "Confidence  ${(fraction * 100).toInt()}%",
            style = DracarysTypography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        // Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            // Fill — colored by mode.color only
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(mode.color),           // ← single source of truth
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "ConfidenceBar — 61% DR", showBackground = true, backgroundColor = 0xFF12161F)
@Composable
private fun PreviewConfidenceBarDR() {
    DracarysTheme {
        ConfidenceBar(
            confidence = 0.61f,
            mode = NavigationMode.DeadReckoning,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "ConfidenceBar — 97% GNSS", showBackground = true, backgroundColor = 0xFF12161F)
@Composable
private fun PreviewConfidenceBarGnss() {
    DracarysTheme {
        ConfidenceBar(
            confidence = 0.97f,
            mode = NavigationMode.Gnss,
            modifier = Modifier.padding(16.dp),
        )
    }
}
