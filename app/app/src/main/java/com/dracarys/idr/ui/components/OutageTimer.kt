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
 * Outage duration display for the instrument capsule expanded state.
 *
 * Formats [outageDurationSec] as `mm:ss` using Space Grotesk with tabular figures,
 * so digits don't shift horizontally as the timer counts up.
 *
 * @param outageDurationSec Elapsed seconds since last GNSS fix. Zero when GNSS is active
 *                          (this composable should not be shown in the collapsed state).
 */
@Composable
fun OutageTimer(
    outageDurationSec: Long,
    modifier: Modifier = Modifier,
) {
    val minutes = outageDurationSec / 60
    val seconds = outageDurationSec % 60
    val formatted = "%02d:%02d".format(minutes, seconds)

    Column(modifier = modifier) {
        Text(
            text = "Outage",
            style = DracarysTypography.bodySmall,
        )
        Text(
            text = formatted,
            style = DracarysTypography.displaySmall,   // Space Grotesk, fontFeatureSettings = "tnum"
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "OutageTimer — 47s", showBackground = true, backgroundColor = 0xFF12161F)
@Composable
private fun PreviewOutageTimer47() {
    DracarysTheme {
        OutageTimer(outageDurationSec = 47L, modifier = Modifier.padding(16.dp))
    }
}

@Preview(name = "OutageTimer — 3m 02s", showBackground = true, backgroundColor = 0xFF12161F)
@Composable
private fun PreviewOutageTimer182() {
    DracarysTheme {
        OutageTimer(outageDurationSec = 182L, modifier = Modifier.padding(16.dp))
    }
}
