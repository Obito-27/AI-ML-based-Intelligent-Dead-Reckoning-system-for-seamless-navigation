package com.dracarys.idr.ui.state

import androidx.compose.ui.graphics.Color

/**
 * Sealed hierarchy of navigation modes.
 *
 * [color] is the **single source of truth** for mode color throughout the entire UI.
 * Every consumer — [com.dracarys.idr.ui.components.ModeBadge],
 * [com.dracarys.idr.ui.components.VehicleIcon],
 * [com.dracarys.idr.ui.components.UncertaintyCorridor] — reads `mode.color` directly.
 * No hex literals or color lookup maps should exist in any composable.
 */
sealed class NavigationMode(val color: Color, val label: String) {

    /** Acquiring GPS fix; waiting for initial satellite lock. Neutral gray. */
    object AcquiringGps : NavigationMode(
        color = Color(0xFF94A3B8),
        label = "Acquiring GPS",
    )

    /** GNSS fix available; full satellite positioning. */
    object Gnss : NavigationMode(
        color = Color(0xFF2DD4BF),
        label = "GNSS",
    )

    /** Sensor-fusion mode: ESKF combining IMU + partial GNSS + map-matching. */
    object Fused : NavigationMode(
        color = Color(0xFFF5A623),
        label = "Fused",
    )

    /** Dead-reckoning only; no GNSS fix available. */
    object DeadReckoning : NavigationMode(
        color = Color(0xFFC084FC),
        label = "Dead Reckoning",
    )

    companion object {
        /** Ordered list used by [com.dracarys.idr.ui.state.FakeNavigationRepository] cycling logic. */
        val cycle: List<NavigationMode> get() = listOf(AcquiringGps, Gnss, Fused, DeadReckoning)
    }
}
