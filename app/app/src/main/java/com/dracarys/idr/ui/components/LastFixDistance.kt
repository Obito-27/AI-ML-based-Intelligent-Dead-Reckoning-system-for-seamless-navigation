package com.dracarys.idr.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dracarys.idr.ui.theme.DracarysTheme
import com.dracarys.idr.ui.theme.DracarysTypography

/**
 * Last GNSS fix distance readout for the instrument capsule expanded state.
 *
 * Displays [distanceToLastFixM] as:
 * - `"NNN m"` for distances below 1 000 m.
 * - `">1 km"` for distances ≥ 1 000 m (avoids implying false precision in dead-reckoning).
 *
 * Uses Space Grotesk with tabular figures (inherited from [DracarysTypography.displaySmall]).
 *
 * @param distanceToLastFixM Distance in metres from current estimated position to last GNSS fix.
 */
@Composable
fun LastFixDistance(
    distanceToLastFixM: Float,
    modifier: Modifier = Modifier,
) {
    val formatted = if (distanceToLastFixM >= 1_000f) {
        ">1 km"
    } else {
        "${"%.0f".format(distanceToLastFixM)} m"
    }

    Column(modifier = modifier) {
        Text(
            text = "Last fix",
            style = DracarysTypography.bodySmall,
        )
        Text(
            text = formatted,
            style = DracarysTypography.displaySmall,   // Space Grotesk, tnum
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "LastFixDistance — 312 m", showBackground = true, backgroundColor = 0xFF12161F)
@Composable
private fun PreviewLastFix312() {
    DracarysTheme {
        LastFixDistance(distanceToLastFixM = 312f, modifier = Modifier.padding(16.dp))
    }
}

@Preview(name = "LastFixDistance — >1 km", showBackground = true, backgroundColor = 0xFF12161F)
@Composable
private fun PreviewLastFixOver1km() {
    DracarysTheme {
        LastFixDistance(distanceToLastFixM = 1450f, modifier = Modifier.padding(16.dp))
    }
}
