package com.dracarys.idr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dracarys.idr.ui.state.FakeNavigationRepository
import com.dracarys.idr.ui.state.NavigationMode
import com.dracarys.idr.ui.theme.DracarysBackground
import com.dracarys.idr.ui.theme.DracarysTextPrimary
import com.dracarys.idr.ui.theme.DracarysTheme
import com.dracarys.idr.ui.theme.DracarysTypography

/**
 * Pill-shaped mode badge.
 *
 * Background color is [NavigationMode.color] — the single source of truth.
 * No hex literal or Color constant is referenced inside this composable.
 *
 * Text color adapts for WCAG AA contrast: white on dark mode colors, graphite on light.
 */
@Composable
fun ModeBadge(
    mode: NavigationMode,
    modifier: Modifier = Modifier,
) {
    // Derive on-badge text color from mode.color luminance — no hardcoded hex
    val textColor = if (mode.color.luminance() > 0.30f) DracarysBackground else DracarysTextPrimary

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(mode.color)          // ← reads mode.color only
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = mode.label,
            style = DracarysTypography.labelLarge,
            color = textColor,
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "Badge — GNSS", showBackground = true, backgroundColor = 0xFF0B0E14)
@Composable
private fun PreviewBadgeGnss() {
    DracarysTheme { ModeBadge(mode = NavigationMode.Gnss) }
}

@Preview(name = "Badge — Fused", showBackground = true, backgroundColor = 0xFF0B0E14)
@Composable
private fun PreviewBadgeFused() {
    DracarysTheme { ModeBadge(mode = NavigationMode.Fused) }
}

@Preview(name = "Badge — Dead Reckoning", showBackground = true, backgroundColor = 0xFF0B0E14)
@Composable
private fun PreviewBadgeDR() {
    DracarysTheme { ModeBadge(mode = NavigationMode.DeadReckoning) }
}
