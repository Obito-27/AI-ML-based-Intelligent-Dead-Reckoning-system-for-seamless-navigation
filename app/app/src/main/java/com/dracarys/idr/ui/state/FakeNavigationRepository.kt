package com.dracarys.idr.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Synthetic [NavigationRepository] implementation that drives the UI without a real sensor pipeline.
 *
 * ### Mode Cycle (default)
 * Every [modeDwellMs] the repository advances through [NavigationMode.cycle]:
 * `Gnss → Fused → DeadReckoning → Gnss …`
 *
 * Within each non-Gnss mode, [NavigationState.driftPercent] increases by [driftStepPercent] per tick
 * and [NavigationState.outageDurationSec] counts up, so the capsule's expanded metrics are exercised.
 *
 * ### Deterministic control for tests
 * Call [setMode] to jump directly to any mode and suppress the cycle. Call [startCycle] to resume.
 *
 * @param modeDwellMs   Time in each mode before auto-advancing (default 5 000 ms).
 * @param tickMs        How often numeric fields update within a mode (default 500 ms).
 * @param driftStepPercent How much drift accumulates per tick (default 0.5 %).
 * @param scope         CoroutineScope for background work; defaults to a private SupervisorJob scope.
 */
class FakeNavigationRepository(
    private val modeDwellMs: Long = 5_000L,
    private val tickMs: Long = 500L,
    private val driftStepPercent: Float = 0.5f,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : NavigationRepository {

    private val _state = MutableStateFlow(NavigationState.INITIAL)
    override val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _isDebugOutageActive = MutableStateFlow(false)
    override val isDebugOutageActive: StateFlow<Boolean> = _isDebugOutageActive.asStateFlow()

    private val _outageHistory = MutableStateFlow<List<com.dracarys.idr.logging.OutageValidationRecord>>(emptyList())
    override val outageHistory: StateFlow<List<com.dracarys.idr.logging.OutageValidationRecord>> = _outageHistory.asStateFlow()

    private val _diagnosticState = MutableStateFlow(DiagnosticState())
    override val diagnosticState: StateFlow<DiagnosticState> = _diagnosticState.asStateFlow()

    private var cycleJob: Job? = null
    private var modeIndex: Int = 0

    init {
        startCycle()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    override fun setDebugOutage(active: Boolean) {
        _isDebugOutageActive.value = active
        if (active) {
            setMode(NavigationMode.DeadReckoning)
        } else {
            setMode(NavigationMode.Gnss)
            startCycle()
        }
    }

    override fun toggleDebugOutage() {
        setDebugOutage(!_isDebugOutageActive.value)
    }

    /** Override mode immediately; stops the automatic cycle. Call [startCycle] to resume. */
    fun setMode(mode: NavigationMode) {
        cycleJob?.cancel()
        _state.value = buildState(mode, drift = 0f, outageSec = 0L)
    }

    /** Resume automatic mode cycling from the current mode. */
    fun startCycle() {
        cycleJob?.cancel()
        cycleJob = scope.launch {
            while (true) {
                val mode = NavigationMode.cycle[modeIndex % NavigationMode.cycle.size]
                val ticksPerMode = modeDwellMs / tickMs
                repeat(ticksPerMode.toInt()) { tick ->
                    val drift = if (mode == NavigationMode.Gnss || mode == NavigationMode.AcquiringGps) 0f
                                else (tick + 1) * driftStepPercent
                    val outage = if (mode == NavigationMode.Gnss || mode == NavigationMode.AcquiringGps) 0L
                                 else (tick + 1) * (tickMs / 1_000)
                    val distM = drift * 100f          // rough proxy: 1% drift ≈ 100 m error
                    _state.value = buildState(mode, drift, outage, distM)
                    delay(tickMs)
                }
                modeIndex++
            }
        }
    }

    // ── Snapshot factories for Preview composables ────────────────────────────

    /** Snapshot matching the initial Acquiring GPS preview state. */
    fun snapshotAcquiringGps(): NavigationState = NavigationState(
        mode = NavigationMode.AcquiringGps,
        confidence = 0.0f,
        driftPercent = 0.0f,
        outageDurationSec = 0L,
        distanceToLastFixM = 0f,
    )

    /** Snapshot matching the collapsed (GNSS healthy) preview state. */
    fun snapshotGnss(): NavigationState = NavigationState(
        mode = NavigationMode.Gnss,
        confidence = 0.97f,
        driftPercent = 0.3f,
        outageDurationSec = 0L,
        distanceToLastFixM = 0f,
    )

    /** Snapshot matching the expanded / Dead-Reckoning preview state. */
    fun snapshotDeadReckoning(): NavigationState = NavigationState(
        mode = NavigationMode.DeadReckoning,
        confidence = 0.61f,
        driftPercent = 11.22f,
        outageDurationSec = 47L,
        distanceToLastFixM = 312f,
    )

    /** Snapshot matching a high-stress outage preview state. */
    fun snapshotHighOutage(): NavigationState = NavigationState(
        mode = NavigationMode.DeadReckoning,
        confidence = 0.42f,
        driftPercent = 24.8f,
        outageDurationSec = 182L,
        distanceToLastFixM = 1450f,
    )

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildState(
        mode: NavigationMode,
        drift: Float,
        outageSec: Long,
        distM: Float = 0f,
    ) = NavigationState(
        mode = mode,
        confidence = when (mode) {
            NavigationMode.AcquiringGps -> 0.0f
            NavigationMode.Gnss -> 0.97f
            NavigationMode.Fused -> (0.85f - drift * 0.005f).coerceIn(0.60f, 0.90f)
            NavigationMode.DeadReckoning -> (0.70f - drift * 0.01f).coerceIn(0.30f, 0.75f)
        },
        driftPercent = drift,
        outageDurationSec = outageSec,
        distanceToLastFixM = distM,
    )
}
