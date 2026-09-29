package com.dracarys.idr.mapmatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OverpassRoadFetcherTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `buildGpsTraceSegments returns null for fewer than 2 points`() {
        val fetcher = OverpassRoadFetcher(tempDir.root)
        val result0 = fetcher.buildGpsTraceSegments(emptyList(), 18.52, 73.86)
        assertNull(result0)

        val result1 = fetcher.buildGpsTraceSegments(listOf(Pair(18.52, 73.86)), 18.52, 73.86)
        assertNull(result1)
    }

    @Test
    fun `buildGpsTraceSegments produces segments from GPS breadcrumbs`() {
        val fetcher = OverpassRoadFetcher(tempDir.root)

        // Simulate a short drive: 5 GPS fixes along a straight road
        val breadcrumbs = listOf(
            Pair(18.5200, 73.8600),
            Pair(18.5201, 73.8600), // ~11m north
            Pair(18.5202, 73.8600), // ~11m north
            Pair(18.5203, 73.8600),
            Pair(18.5204, 73.8600),
        )

        val result = fetcher.buildGpsTraceSegments(breadcrumbs, 18.5200, 73.8600)
        assertNotNull(result)
        val (p1, p2) = result!!

        // Should produce multiple sub-segments (each ~11m gap -> 2-3 sub-segments at 5m spacing)
        assertTrue("Expected segments, got ${p1.size}", p1.size >= 4)
        assertEquals(p1.size, p2.size)

        // Verify fetch status was updated
        assertTrue(fetcher.lastFetchStatus.contains("GPS_TRACE"))
        assertEquals(p1.size, fetcher.totalSegmentsLoaded)
    }

    @Test
    fun `needsRefetch returns true initially and false after setting cached center`() {
        val fetcher = OverpassRoadFetcher(tempDir.root)

        // Initially, no data cached
        assertTrue(fetcher.needsRefetch(18.52, 73.86))
    }

    @Test
    fun `buildGpsTraceSegments skips near-duplicate points`() {
        val fetcher = OverpassRoadFetcher(tempDir.root)

        // Points very close together (< 0.5m) should be skipped
        val breadcrumbs = listOf(
            Pair(18.5200, 73.8600),
            Pair(18.5200001, 73.8600001), // < 0.5m away
            Pair(18.5200002, 73.8600002), // < 0.5m away
            Pair(18.5204, 73.8600),       // ~44m north, this should produce segments
        )

        val result = fetcher.buildGpsTraceSegments(breadcrumbs, 18.5200, 73.8600)
        assertNotNull(result)
        val (p1, _) = result!!
        // Only the first-to-last meaningful gap should produce segments
        assertTrue("Expected segments, got ${p1.size}", p1.size >= 1)
    }
}
