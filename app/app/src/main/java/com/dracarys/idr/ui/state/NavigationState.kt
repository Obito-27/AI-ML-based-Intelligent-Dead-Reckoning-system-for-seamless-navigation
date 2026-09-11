package com.dracarys.idr.ui.state

/**
 * Immutable snapshot of the navigation pipeline's current state.
 *
 * Emitted via [NavigationRepository.state] and consumed by [com.dracarys.idr.ui.screen.NavigationScreen].
 * All numeric fields carry SI units; formatting is left to individual composables.
 *
 * @param mode              Current positioning mode; drives capsule color and expansion.
 * @param confidence        Model velocity confidence in [0, 1]. 1.0 = maximum confidence.
 * @param driftPercent      Accumulated dead-reckoning drift as a percentage of travelled distance
 *                          (e.g. 11.22 means 11.22 %). Target threshold is 10.0 %.
 * @param outageDurationSec Elapsed seconds since the last valid GNSS fix.
 *                          Zero when mode is [NavigationMode.Gnss].
 * @param distanceToLastFixM Distance (metres) from the current estimated position to the last
 *                          confirmed GNSS anchor. Zero when mode is [NavigationMode.Gnss].
 */
data class NavigationState(
    val mode: NavigationMode,
    val confidence: Float,
    val driftPercent: Float,
    val outageDurationSec: Long,
    val distanceToLastFixM: Float,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val headingDeg: Float = 0f,
    val recentTrail: List<Pair<Double, Double>> = emptyList(),
    val isMountCalibrated: Boolean = false,
    val mountAzimuthDeg: Double = 0.0,
) {
    companion object {
        /** Canonical initial state shown on app launch before any sensor data arrives. */
        val INITIAL: NavigationState = NavigationState(
            mode = NavigationMode.AcquiringGps,
            confidence = 0.0f,
            driftPercent = 0.0f,
            outageDurationSec = 0L,
            distanceToLastFixM = 0.0f,
            latitude = 0.0,
            longitude = 0.0,
            headingDeg = 0f,
            recentTrail = emptyList(),
            isMountCalibrated = false,
            mountAzimuthDeg = 0.0,
        )

        /** Drift threshold (%) above which [com.dracarys.idr.ui.components.DriftBar] turns alert-red. */
        const val DRIFT_TARGET_PERCENT: Float = 10.0f
    }
}
