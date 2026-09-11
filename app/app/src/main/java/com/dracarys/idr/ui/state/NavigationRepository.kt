package com.dracarys.idr.ui.state

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for the navigation data source consumed by [com.dracarys.idr.ui.NavigationViewModel].
 *
 * Implementations:
 * - [FakeNavigationRepository] — synthetic timer-driven mode cycling for UI development and previews.
 * - `LiveNavigationRepository` (future) — reads from the real ESKF/ML pipeline without touching UI code.
 *
 * The ViewModel accepts this interface, so swapping from fake to live requires no UI changes.
 */
interface NavigationRepository {
    /** Hot [StateFlow] that always holds the latest [NavigationState]. Never completes. */
    val state: StateFlow<NavigationState>

    /** Hot [StateFlow] indicating whether debug outage simulation is currently active. */
    val isDebugOutageActive: StateFlow<Boolean>

    /** Hot [StateFlow] holding historical completed validation records for field tests. */
    val outageHistory: StateFlow<List<com.dracarys.idr.logging.OutageValidationRecord>>

    /** Hot [StateFlow] streaming live high-frequency sensor and neural network diagnostic telemetry. */
    val diagnosticState: StateFlow<DiagnosticState>

    /** Enables or disables debug outage simulation. */
    fun setDebugOutage(active: Boolean)

    /** Toggles debug outage simulation on or off. */
    fun toggleDebugOutage()

    /** Requests immediate re-evaluation and subscription of device location providers. */
    fun refreshLocation() {}
}

