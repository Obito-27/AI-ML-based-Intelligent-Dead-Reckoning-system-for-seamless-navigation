package com.dracarys.idr.ui.state

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Architectural tests for [NavigationMode.color] — the single source of truth for mode color.
 *
 * These tests assert the DATA CONTRACT (enum property values), not rendered output.
 * If any hex value changes, the badge, vehicle icon, and corridor will all update
 * automatically because they read mode.color directly. These tests catch accidental
 * color regressions at the source.
 */
class NavigationModeColorTest {

    @Test
    fun `acquiring gps mode color is neutral gray 94A3B8`() {
        assertEquals(Color(0xFF94A3B8), NavigationMode.AcquiringGps.color)
    }

    @Test
    fun `gnss mode color is teal 2DD4BF`() {
        assertEquals(Color(0xFF2DD4BF), NavigationMode.Gnss.color)
    }

    @Test
    fun `fused mode color is amber F5A623`() {
        assertEquals(Color(0xFFF5A623), NavigationMode.Fused.color)
    }

    @Test
    fun `dead reckoning mode color is purple C084FC`() {
        assertEquals(Color(0xFFC084FC), NavigationMode.DeadReckoning.color)
    }

    @Test
    fun `all four modes have distinct colors`() {
        val colors = NavigationMode.cycle.map { it.color }
        assertEquals(
            "Expected 4 distinct mode colors, found duplicates: $colors",
            4,
            colors.toSet().size,
        )
    }

    @Test
    fun `gnss and dead reckoning colors are not the same`() {
        assertNotEquals(NavigationMode.Gnss.color, NavigationMode.DeadReckoning.color)
    }

    @Test
    fun `mode cycle contains all four modes`() {
        val cycle = NavigationMode.cycle
        assertEquals(4, cycle.size)
        assert(NavigationMode.AcquiringGps in cycle)
        assert(NavigationMode.Gnss in cycle)
        assert(NavigationMode.Fused in cycle)
        assert(NavigationMode.DeadReckoning in cycle)
    }

    /**
     * Structural test: [NavigationMode] subclasses expose [color] as a property,
     * not a method. If this compiles and the property is accessible, the architectural
     * constraint (composables read mode.color, not a lookup map) is enforceable at the type level.
     */
    @Test
    fun `mode color is accessible as a property on all modes`() {
        NavigationMode.cycle.forEach { mode ->
            val color: Color = mode.color    // type-checked at compile time
            assertNotEquals("Mode ${mode.label} has transparent color", Color.Transparent, color)
        }
    }
}
