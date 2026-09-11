package com.dracarys.idr.mapmatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class SoftMapMatcherTest {

    @Test
    fun `point far from road corridor returns unpulled coordinates with zero confidence`() {
        val matcher = SoftMapMatcher(corridorM = 35.0, pullFactor = 0.45)
        // Road segment from (0, 0) to (100, 0)
        val p1 = arrayOf(doubleArrayOf(0.0, 0.0))
        val p2 = arrayOf(doubleArrayOf(100.0, 0.0))
        matcher.loadSegments(p1, p2)

        // Point at (50, 100) — 100m away, outside 35m corridor
        val (xOut, yOut, conf) = matcher.matchPoint(50.0, 100.0, headingRad = 0.0)

        assertEquals(50.0, xOut, 1e-6)
        assertEquals(100.0, yOut, 1e-6)
        assertEquals(0.0, conf, 1e-6)
    }

    @Test
    fun `point inside road corridor is gently pulled toward centerline`() {
        val matcher = SoftMapMatcher(corridorM = 35.0, pullFactor = 0.45, sigmaDist = 12.0)
        // Horizontal road segment along y=0 from x=0 to x=100
        val p1 = arrayOf(doubleArrayOf(0.0, 0.0))
        val p2 = arrayOf(doubleArrayOf(100.0, 0.0))
        matcher.loadSegments(p1, p2)

        // Point at (50, 10) — 10m off road, heading east (0 rad) aligned with road
        val (xOut, yOut, conf) = matcher.matchPoint(50.0, 10.0, headingRad = 0.0)

        // Expected closest point is (50, 0)
        // pulledY = (1 - 0.45) * 10.0 + 0.45 * 0.0 = 5.5
        assertEquals(50.0, xOut, 1e-4)
        assertEquals(5.5, yOut, 0.1)
        assertTrue("Confidence should be positive", conf > 0.5)
    }

    @Test
    fun `heading consistency downweights perpendicular roads`() {
        val matcher = SoftMapMatcher(corridorM = 35.0, pullFactor = 0.45)
        // Road running North-South along x=0
        val p1 = arrayOf(doubleArrayOf(0.0, 0.0))
        val p2 = arrayOf(doubleArrayOf(0.0, 100.0))
        matcher.loadSegments(p1, p2)

        // Car at (5, 50) heading East (perpendicular to road)
        val (_, _, confPerp) = matcher.matchPoint(5.0, 50.0, headingRad = 0.0)

        // Car at (5, 50) heading North (parallel to road)
        val (_, _, confParallel) = matcher.matchPoint(5.0, 50.0, headingRad = Math.PI / 2.0)

        assertTrue("Parallel road must have higher confidence than perpendicular", confParallel > confPerp)
    }

    @Test
    fun `closed loop trajectory matching propagates corrections`() {
        val matcher = SoftMapMatcher(corridorM = 35.0, pullFactor = 0.45)
        val p1 = arrayOf(doubleArrayOf(0.0, 0.0))
        val p2 = arrayOf(doubleArrayOf(100.0, 0.0))
        matcher.loadSegments(p1, p2)

        val x = doubleArrayOf(10.0, 20.0, 30.0)
        val y = doubleArrayOf(8.0, 8.0, 8.0)
        val psi = doubleArrayOf(0.0, 0.0, 0.0)

        val (xOut, yOut) = matcher.matchTrajectory(x, y, psi)

        assertEquals(3, xOut.size)
        // With repeated propagation toward y=0, lateral error should decrease
        assertTrue(yOut[0] < 8.0)
        assertTrue(yOut[2] < yOut[0])
    }
}
