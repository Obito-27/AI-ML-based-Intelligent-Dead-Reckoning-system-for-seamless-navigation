package com.dracarys.idr.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max

/**
 * Record of a single field-test outage validation scenario.
 *
 * Captures real ground-truth-free drift by comparing the final dead-reckoning position
 * with the actual GPS coordinates upon satellite reacquisition.
 *
 * IMPORTANT NOTE ON DRIFT CALCULATION & BENCHMARK COMPARABILITY:
 * The field drift percentage is calculated as:
 *     driftPercent = (displacementGapM / drDistanceTraveledM) * 100.0
 * The denominator here is the dead-reckoning filter's ESTIMATED distance travelled,
 * NOT the true GPS or ground-truth CAN distance (which is unavailable during an outage
 * in consumer vehicles without dual-antenna RTK or CAN taps).
 *
 * This is fundamentally different from the offline benchmark (e.g. the 11.22% LODO-CV
 * headline number), which evaluates against true distance from reference dGPS/CAN:
 *     driftPercent_offline = (endpointErrorM / trueGroundTruthDistanceM) * 100.0
 *
 * These two metrics are NOT directly comparable:
 * - If the DR pipeline under-estimates distance (e.g. vehicle was moving faster than AI predicted),
 *   the denominator is smaller and the field drift % appears artificially worse.
 * - If the DR pipeline over-estimates distance, the field drift % appears artificially better.
 * DO NOT quote this on-device field validation metric directly against the 11.22% benchmark number
 * in decks or technical reports without this explicit caveat.
 */
data class OutageValidationRecord(
    val id: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationSec: Long,
    val drDistanceTraveledM: Double,
    val drEndpointX: Double,
    val drEndpointY: Double,
    val gpsReacquiredX: Double,
    val gpsReacquiredY: Double,
    val displacementGapM: Double,
    val driftPercent: Double,
    val passedTarget: Boolean, // driftPercent < 10.0%
    val minConfidence: Float,
    val maxConfidence: Float,
)

/**
 * Post-drive field validation tooling.
 *
 * Evaluates real-world dead reckoning performance without requiring CAN hardware:
 * 1. Automatically begins tracking when the simulated outage toggle is activated.
 * 2. When outage is toggled off and fresh GNSS reacquires, computes the Euclidean
 *    displacement gap between the dead-reckoned endpoint and the real GPS fix.
 * 3. Saves structured JSON and CSV summaries in the app's diagnostic storage.
 * 4. Exposes a live [StateFlow] for on-device presentation in the Compose UI.
 *
 * Note: Denominator for drift % is DR-estimated distance travelled, not true ground truth.
 */
class OutageValidator(private val context: Context) {

    private val _outages = MutableStateFlow<List<OutageValidationRecord>>(emptyList())
    val outages: StateFlow<List<OutageValidationRecord>> = _outages.asStateFlow()

    private val summaryDir: File = File(
        context.getExternalFilesDir("diagnostics") ?: context.filesDir,
        "validations"
    ).apply { mkdirs() }

    val summaryCsvFile: File = File(summaryDir, "outage_validation_summary.csv")
    val summaryJsonFile: File = File(summaryDir, "outage_validation_summary.json")

    private var activeOutageId = 0
    private var isTracking = false
    private var awaitingGpsReacquisition = false

    private var outageStartTimeMs = 0L
    private var outageEndTimeMs = 0L
    private var outageStartDist = 0.0
    private var drEndX = 0.0
    private var drEndY = 0.0
    private var drEndDist = 0.0
    private var minConf = 1.0f
    private var maxConf = 0.0f

    init {
        ensureCsvHeader()
    }

    private fun ensureCsvHeader() {
        if (!summaryCsvFile.exists() || summaryCsvFile.length() == 0L) {
            try {
                summaryCsvFile.writeText(
                    "id,start_time_ms,end_time_ms,duration_sec,dr_distance_m,dr_x,dr_y,gps_x,gps_y,displacement_gap_m,drift_percent,passed_10pct_target,min_conf,max_conf\n"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write validation CSV header: ${e.message}")
            }
        }
    }

    /**
     * Called on each 10 Hz tick to manage outage tracking state transitions.
     */
    fun onTick(
        isOutageActive: Boolean,
        currentX: Double,
        currentY: Double,
        totalDistanceM: Double,
        currentConfidence: Float,
    ) {
        if (isOutageActive) {
            if (!isTracking) {
                // Outage just started
                activeOutageId++
                isTracking = true
                awaitingGpsReacquisition = false
                outageStartTimeMs = System.currentTimeMillis()
                outageStartDist = totalDistanceM
                minConf = currentConfidence
                maxConf = currentConfidence
                Log.d(TAG, "Outage #$activeOutageId started at $outageStartTimeMs")
            } else {
                // Outage currently running
                if (currentConfidence < minConf) minConf = currentConfidence
                if (currentConfidence > maxConf) maxConf = currentConfidence
            }
        } else {
            if (isTracking) {
                // Outage just toggled off
                isTracking = false
                outageEndTimeMs = System.currentTimeMillis()
                drEndX = currentX
                drEndY = currentY
                drEndDist = totalDistanceM
                awaitingGpsReacquisition = true
                Log.d(TAG, "Outage #$activeOutageId ended at $outageEndTimeMs. Awaiting GPS reacquisition...")
            }
        }
    }

    /**
     * Called when a fresh GNSS fix arrives after an outage has ended.
     */
    fun onGnssReacquired(gpsX: Double, gpsY: Double) {
        if (!awaitingGpsReacquisition) return
        awaitingGpsReacquisition = false

        val durationSec = max(1L, (outageEndTimeMs - outageStartTimeMs) / 1000L)
        val drDistanceM = max(0.0, drEndDist - outageStartDist)
        val gapM = hypot(drEndX - gpsX, drEndY - gpsY)
        val driftPct = (gapM / max(1.0, drDistanceM)) * 100.0
        val passed = driftPct < 10.0

        val record = OutageValidationRecord(
            id = activeOutageId,
            startTimeMs = outageStartTimeMs,
            endTimeMs = outageEndTimeMs,
            durationSec = durationSec,
            drDistanceTraveledM = drDistanceM,
            drEndpointX = drEndX,
            drEndpointY = drEndY,
            gpsReacquiredX = gpsX,
            gpsReacquiredY = gpsY,
            displacementGapM = gapM,
            driftPercent = driftPct,
            passedTarget = passed,
            minConfidence = minConf,
            maxConfidence = maxConf,
        )

        val updated = _outages.value + record
        _outages.value = updated

        saveRecord(record, updated)
        Log.i(TAG, "Outage #$activeOutageId validated: dist=%.1fm, gap=%.1fm, drift=%.2f%%, passed=%b"
            .format(Locale.US, drDistanceM, gapM, driftPct, passed))
    }

    private fun saveRecord(record: OutageValidationRecord, all: List<OutageValidationRecord>) {
        try {
            // Append CSV row
            val csvRow = String.format(
                Locale.US,
                "%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%b,%.2f,%.2f\n",
                record.id,
                record.startTimeMs,
                record.endTimeMs,
                record.durationSec,
                record.drDistanceTraveledM,
                record.drEndpointX,
                record.drEndpointY,
                record.gpsReacquiredX,
                record.gpsReacquiredY,
                record.displacementGapM,
                record.driftPercent,
                record.passedTarget,
                record.minConfidence,
                record.maxConfidence
            )
            FileWriter(summaryCsvFile, true).use { it.write(csvRow) }

            // Write JSON array
            val jsonBuilder = StringBuilder("[\n")
            all.forEachIndexed { i, r ->
                jsonBuilder.append(String.format(
                    Locale.US,
                    "  {\"id\": %d, \"duration_sec\": %d, \"distance_m\": %.2f, \"gap_m\": %.2f, \"drift_percent\": %.2f, \"passed\": %b}%s\n",
                    r.id, r.durationSec, r.drDistanceTraveledM, r.displacementGapM, r.driftPercent, r.passedTarget,
                    if (i < all.size - 1) "," else ""
                ))
            }
            jsonBuilder.append("]\n")
            summaryJsonFile.writeText(jsonBuilder.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist outage validation record: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "OutageValidator"
    }
}
