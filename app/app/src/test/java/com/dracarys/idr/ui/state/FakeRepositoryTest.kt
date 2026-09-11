package com.dracarys.idr.ui.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeRepositoryTest {

    @Test
    fun `initial state is AcquiringGps`() = runTest {
        val repo = FakeNavigationRepository(scope = backgroundScope)
        assertEquals(NavigationMode.AcquiringGps, repo.state.value.mode)
    }

    @Test
    fun `setMode jumps to dead reckoning immediately`() = runTest {
        val repo = FakeNavigationRepository(scope = backgroundScope)
        repo.setMode(NavigationMode.DeadReckoning)
        assertEquals(NavigationMode.DeadReckoning, repo.state.value.mode)
    }

    @Test
    fun `setMode jumps to fused immediately`() = runTest {
        val repo = FakeNavigationRepository(scope = backgroundScope)
        repo.setMode(NavigationMode.Fused)
        assertEquals(NavigationMode.Fused, repo.state.value.mode)
    }

    @Test
    fun `setMode stops the cycle — mode does not change after delay`() = runTest {
        val repo = FakeNavigationRepository(modeDwellMs = 100L, tickMs = 50L, scope = backgroundScope)
        repo.setMode(NavigationMode.DeadReckoning)
        advanceTimeBy(500L)   // would have cycled several times
        assertEquals(NavigationMode.DeadReckoning, repo.state.value.mode)
    }

    @Test
    fun `gnss state has zero outage and zero drift`() = runTest {
        val repo = FakeNavigationRepository(scope = backgroundScope)
        repo.setMode(NavigationMode.Gnss)
        val s = repo.state.value
        assertEquals(NavigationMode.Gnss, s.mode)
        assertEquals(0f, s.driftPercent)
        assertEquals(0L, s.outageDurationSec)
    }

    @Test
    fun `dead reckoning snapshot has non-zero drift and outage`() {
        val repo = FakeNavigationRepository()
        val s = repo.snapshotDeadReckoning()
        assertEquals(NavigationMode.DeadReckoning, s.mode)
        assert(s.driftPercent > 0f) { "driftPercent should be > 0, was ${s.driftPercent}" }
        assert(s.outageDurationSec > 0L) { "outageDurationSec should be > 0, was ${s.outageDurationSec}" }
    }

    @Test
    fun `high outage snapshot has drift above 10 percent target`() {
        val repo = FakeNavigationRepository()
        val s = repo.snapshotHighOutage()
        assert(s.driftPercent > NavigationState.DRIFT_TARGET_PERCENT) {
            "Expected drift > ${NavigationState.DRIFT_TARGET_PERCENT}, was ${s.driftPercent}"
        }
    }

    @Test
    fun `gnss snapshot has confidence near 1f`() {
        val repo = FakeNavigationRepository()
        val s = repo.snapshotGnss()
        assert(s.confidence >= 0.90f) { "Expected confidence >= 0.90, was ${s.confidence}" }
    }

    @Test
    fun `confidence is always in 0 to 1 range across snapshots`() {
        val repo = FakeNavigationRepository()
        listOf(repo.snapshotGnss(), repo.snapshotDeadReckoning(), repo.snapshotHighOutage())
            .forEach { s ->
                assert(s.confidence in 0f..1f) { "confidence ${s.confidence} out of range for mode ${s.mode}" }
            }
    }

    @Test
    fun `toggleDebugOutage forces DeadReckoning mode and updates isDebugOutageActive`() {
        val repo = FakeNavigationRepository()
        assert(!repo.isDebugOutageActive.value) { "Expected initially inactive" }

        repo.toggleDebugOutage()
        assert(repo.isDebugOutageActive.value) { "Expected active after toggle" }
        assert(repo.state.value.mode == NavigationMode.DeadReckoning) { "Expected DeadReckoning mode" }

        repo.toggleDebugOutage()
        assert(!repo.isDebugOutageActive.value) { "Expected inactive after second toggle" }
        assert(repo.state.value.mode == NavigationMode.Gnss) { "Expected Gnss mode" }
    }
}
