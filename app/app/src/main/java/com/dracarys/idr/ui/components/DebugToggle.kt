package com.dracarys.idr.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dracarys.idr.ui.theme.DracarysTextSecondary
import com.dracarys.idr.ui.theme.DracarysTheme

/**
 * Debug outage-simulation toggle.
 *
 * Subordinate control: alpha = 0.6, outline icon, no background or primary color.
 * Placed top-right of the capsule. Does NOT use any mode color.
 *
 * @param onToggle Callback invoked when the toggle is tapped.
 * @param enabled  Whether debug mode is currently active (toggles icon tint slightly).
 */
@Composable
fun DebugToggle(
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = false,
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier.alpha(if (enabled) 0.85f else 0.55f),
    ) {
        Icon(
            imageVector = Icons.Outlined.BugReport,
            contentDescription = "Toggle debug outage simulation",
            tint = DracarysTextSecondary,
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "DebugToggle — idle", showBackground = true, backgroundColor = 0xFF12161F)
@Composable
private fun PreviewDebugToggleIdle() {
    DracarysTheme { DebugToggle(onToggle = {}, modifier = Modifier.padding(8.dp)) }
}

@Preview(name = "DebugToggle — active", showBackground = true, backgroundColor = 0xFF12161F)
@Composable
private fun PreviewDebugToggleActive() {
    DracarysTheme { DebugToggle(onToggle = {}, enabled = true, modifier = Modifier.padding(8.dp)) }
}
