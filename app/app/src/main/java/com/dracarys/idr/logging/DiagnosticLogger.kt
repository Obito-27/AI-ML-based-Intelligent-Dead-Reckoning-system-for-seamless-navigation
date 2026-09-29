package com.dracarys.idr.logging

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * High-performance on-device diagnostic logger for real-vehicle field testing.
 *
 * Captures 10 Hz continuous telemetry to a local CSV file in the app's diagnostic folder:
 * - Timestamps (epoch ms and elapsed time)
 * - Device battery level (%) and thermal status
 * - Raw IMU snapshots (linear acceleration, gravity vector, angular velocity)
 * - Calibrator outputs (u_up vector, recovered mount azimuth, projected yaw rate)
 * - Calibrated vehicle body accelerations (af, al, az)
 * - Neural network inferences (predicted forward speed, gyro correction, confidence)
 * - ESKF state (mode, velocities vf/vl/vx/vy, local coordinates x/y, heading psi, gyro bias)
 * - Zero-Velocity Detection (ZUPT) engagement state
 * - GNSS status (fix presence, geodetic lat/lon, speed, satellite count, accuracy radius)
 * - Mode transition events and transient error exceptions
 *
 * Uses a single-threaded background executor with buffered I/O to ensure zero stutter
 * on the 10 Hz real-time sensor/fusion loop.
 */
class DiagnosticLogger(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val startTimestampMs = System.currentTimeMillis()
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val logDir: File = File(
        context.getExternalFilesDir("diagnostics") ?: context.filesDir,
        "drives"
    ).apply { mkdirs() }

    val logFile: File = File(
        logDir,
        "dracarys_drive_${dateFormat.format(Date(startTimestampMs))}.csv"
    )

    private var writer: BufferedWriter? = null
    private var isHeaderWritten = false
    private var rowsWritten = 0

    init {
        executor.execute {
            try {
                writer = BufferedWriter(FileWriter(logFile, true))
                writeHeader()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize diagnostic log file: ${e.message}", e)
            }
        }
    }

    private fun writeHeader() {
        val w = writer ?: return
        if (!isHeaderWritten && logFile.length() == 0L) {
            val header = listOf(
                "timestamp_ms",
                "elapsed_sec",
                "battery_pct",
                "thermal_status",
                "raw_ax", "raw_ay", "raw_az",
                "grav_x", "grav_y", "grav_z",
                "gyro_x", "gyro_y", "gyro_z",
                "u_up_x", "u_up_y", "u_up_z",
                "mount_psi_deg",
                "vehicle_yaw_rate_rads",
                "af", "al", "az",
                "ai_vf_ms", "ai_gyro_corr_rads", "ai_conf",
                "nav_mode",
                "fusion_vf", "fusion_vl", "fusion_vx", "fusion_vy",
                "fusion_x", "fusion_y", "fusion_psi_rad", "gyro_bias_hat",
                "zupt_active",
                "has_gnss", "gps_lat", "gps_lon", "gps_speed_ms", "gps_sats", "gps_acc_m",
                "smm_seg_count", "smm_candidates", "smm_nearest_m", "smm_correction_m"
            ).joinToString(",")
            w.write(header)
            w.newLine()
            w.flush()
            isHeaderWritten = true
        }
    }

    private var lastSysQueryMs = 0L
    private var cachedBatteryPct = -1
    private var cachedThermalStatus = "NONE"

    /**
     * Queries current battery percentage [0, 100]. Cached for 1 second to eliminate IPC overhead.
     */
    fun getBatteryPercentage(): Int {
        checkRefreshSystemStatus()
        return cachedBatteryPct
    }

    /**
     * Queries current thermal status string. Cached for 1 second to eliminate IPC overhead.
     */
    fun getThermalStatus(): String {
        checkRefreshSystemStatus()
        return cachedThermalStatus
    }

    private fun checkRefreshSystemStatus() {
        val now = System.currentTimeMillis()
        if (now - lastSysQueryMs < 1000L) return
        lastSysQueryMs = now

        cachedBatteryPct = try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val bStatus = context.registerReceiver(null, ifilter)
            val level = bStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else -1
        } catch (_: Exception) {
            -1
        }

        cachedThermalStatus = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                when (pm?.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "NONE"
                    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                    else -> "UNKNOWN"
                }
            } else {
                "LEGACY"
            }
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    /**
     * Enqueues a single 10 Hz telemetric tick snapshot to the background writer.
     */
    fun logTick(
        rawAcc: DoubleArray,
        gravity: DoubleArray,
        gyro: DoubleArray,
        uUp: DoubleArray,
        mountAzimuthDeg: Double,
        vehicleYawRate: Double,
        af: Double,
        al: Double,
        az: Double,
        aiVf: Double,
        aiGyroCorr: Double,
        aiConf: Double,
        navMode: String,
        fusionVf: Double,
        fusionVl: Double,
        fusionVx: Double,
        fusionVy: Double,
        fusionX: Double,
        fusionY: Double,
        fusionPsi: Double,
        gyroBiasHat: Double,
        zuptActive: Boolean,
        hasGnss: Boolean,
        gpsLat: Double,
        gpsLon: Double,
        gpsSpeed: Double,
        gpsSats: Int,
        gpsAccuracy: Double,
        smmSegCount: Int = 0,
        smmCandidates: Int = 0,
        smmNearestM: Double = -1.0,
        smmCorrectionM: Double = 0.0,
    ) {
        val now = System.currentTimeMillis()
        val elapsedSec = (now - startTimestampMs) / 1000.0

        executor.execute {
            try {
                val w = writer ?: return@execute
                val row = StringBuilder()
                    .append(now).append(',')
                    .append(String.format(Locale.US, "%.2f", elapsedSec)).append(',')
                    .append(getBatteryPercentage()).append(',')
                    .append(getThermalStatus()).append(',')
                    .append(String.format(Locale.US, "%.4f,%.4f,%.4f,", rawAcc[0], rawAcc[1], rawAcc[2]))
                    .append(String.format(Locale.US, "%.4f,%.4f,%.4f,", gravity[0], gravity[1], gravity[2]))
                    .append(String.format(Locale.US, "%.4f,%.4f,%.4f,", gyro[0], gyro[1], gyro[2]))
                    .append(String.format(Locale.US, "%.5f,%.5f,%.5f,", uUp[0], uUp[1], uUp[2]))
                    .append(String.format(Locale.US, "%.2f,", mountAzimuthDeg))
                    .append(String.format(Locale.US, "%.5f,", vehicleYawRate))
                    .append(String.format(Locale.US, "%.4f,%.4f,%.4f,", af, al, az))
                    .append(String.format(Locale.US, "%.3f,%.5f,%.3f,", aiVf, aiGyroCorr, aiConf))
                    .append(navMode).append(',')
                    .append(String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f,", fusionVf, fusionVl, fusionVx, fusionVy))
                    .append(String.format(Locale.US, "%.2f,%.2f,%.4f,%.6f,", fusionX, fusionY, fusionPsi, gyroBiasHat))
                    .append(if (zuptActive) "1" else "0").append(',')
                    .append(if (hasGnss) "1" else "0").append(',')
                    .append(String.format(Locale.US, "%.6f,%.6f,%.2f,%d,%.1f,", gpsLat, gpsLon, gpsSpeed, gpsSats, gpsAccuracy))
                    .append(String.format(Locale.US, "%d,%d,%.2f,%.3f", smmSegCount, smmCandidates, smmNearestM, smmCorrectionM))
                    .toString()

                w.write(row)
                w.newLine()
                rowsWritten++

                // Flush every 10 samples (1 second at 10 Hz)
                if (rowsWritten % 10 == 0) {
                    w.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing tick log: ${e.message}")
            }
        }
    }

    /**
     * Logs an explicit discrete mode transition.
     */
    fun logModeTransition(oldMode: String, newMode: String, reason: String) {
        val now = System.currentTimeMillis()
        executor.execute {
            try {
                val w = writer ?: return@execute
                w.write("# MODE_CHANGE,$now,$oldMode->$newMode,$reason")
                w.newLine()
                w.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error logging mode transition: ${e.message}")
            }
        }
    }

    /**
     * Logs a transient caught exception so it is preserved for post-drive diagnostics.
     */
    fun logError(tag: String, throwable: Throwable) {
        val now = System.currentTimeMillis()
        executor.execute {
            try {
                val w = writer ?: return@execute
                w.write("# ERROR,$now,$tag,${throwable.javaClass.simpleName}:${throwable.message}")
                w.newLine()
                w.flush()
            } catch (_: Exception) {}
        }
    }

    fun close() {
        executor.execute {
            try {
                writer?.flush()
                writer?.close()
                writer = null
            } catch (_: Exception) {}
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "DiagnosticLogger"
    }
}
