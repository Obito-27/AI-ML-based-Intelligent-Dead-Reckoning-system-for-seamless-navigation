package com.dracarys.idr.fusion

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Scale factor applied to AI neural network gyro residual predictions to prevent high-frequency noise from over-correcting the calibrated physical gyro. */
const val DEFAULT_AI_GYRO_SCALE: Double = 0.10

/** Lower clipping bound for neural network velocity confidence weighting in ESKF measurement update. */
const val DEFAULT_AI_CONF_MIN: Double = 0.80

/** Upper clipping bound for neural network velocity confidence weighting in ESKF measurement update. */
const val DEFAULT_AI_CONF_MAX: Double = 0.95

/**
 * Confidence-Aware ESKF with multi-rate GNSS/IMU fusion and Non-Holonomic Constraints (NHC).
 *
 * Implements:
 * - Rotating body-frame Coriolis coupling
 * - Multi-rate 10 Hz IMU / 1 Hz GPS fusion
 * - Continuous signal quality covariance scaling (HDOP, sats, fix age)
 * - NHC pseudo-measurements (vl ~ 0) and online gyro bias estimation
 */
class ESKFFusion(
    val dt: Double = 0.10,
    val kNhc: Double = 0.50,
    val kGyrobias: Double = 0.008,
    val maxGyroBias: Double = 0.02,
) {
    companion object {
        const val ZUPT_ACCEL_THRESH: Double = 0.35
        const val ZUPT_GYRO_THRESH: Double = 0.05
        const val ZUPT_VEL_THRESH: Double = 0.50
        const val ZUPT_GPS_SPEED_THRESH: Double = 0.25

        /**
         * Evaluates Zero-Velocity Update (ZUPT) stationary condition.
         *
         * @param linAccMag Magnitude of linear acceleration (m/s^2)
         * @param gyroMag Magnitude of angular velocity / yaw rate (rad/s)
         * @param currentVf Current forward velocity estimate from filter (m/s)
         * @param gpsSpeed Optional current GPS speed reading (m/s), or null if no GNSS fix
         * @return true if device/vehicle is physically stationary
         */
        fun isStationary(
            linAccMag: Double,
            gyroMag: Double,
            currentVf: Double,
            gpsSpeed: Double? = null,
        ): Boolean {
            if (gpsSpeed != null && gpsSpeed < ZUPT_GPS_SPEED_THRESH && linAccMag < ZUPT_ACCEL_THRESH) {
                return true
            }
            return currentVf < ZUPT_VEL_THRESH && linAccMag < ZUPT_ACCEL_THRESH && gyroMag < ZUPT_GYRO_THRESH
        }
    }

    // Current filter state
    var x: Double = 0.0
    var y: Double = 0.0
    var vf: Double = 0.0
    var vl: Double = 0.0
    var psi: Double = 0.0
    var vx: Double = 0.0
    var vy: Double = 0.0
    var gyroBiasHat: Double = 0.0
    var pPos: Double = 4.0
    val qPos: Double = 0.05
    var pVf: Double = 1.0   // Velocity error covariance
    val qVf: Double = 2.0   // Velocity process noise base

    /**
     * Initializes filter state.
     */
    fun reset(
        x0: Double = 0.0,
        y0: Double = 0.0,
        v0: Double = 0.0,
        psi0: Double = 0.0,
        initialPosVar: Double = 4.0,
    ) {
        x = x0
        y = y0
        vf = v0
        vl = 0.0
        psi = psi0
        vx = v0 * cos(psi0)
        vy = v0 * sin(psi0)
        gyroBiasHat = 0.0
        pPos = initialPosVar
        pVf = 1.0
    }

    /**
     * Computes adaptive GNSS measurement covariance from satellite metrics.
     */
    fun computeGnssCovariance(
        accuracyM: Double,
        sats: Int,
        fixAgeS: Double,
        baseVar: Double = 9.0,
    ): Double {
        val accScale = max(1.0, (accuracyM / 3.0) * (accuracyM / 3.0))
        val satScale = when {
            sats >= 12 -> 1.0
            sats > 4 -> 1.0 + 0.35 * (12 - sats)
            else -> 20.0
        }
        val ageScale = 1.0 + 1.5 * max(0.0, fixAgeS - 1.2)
        val variance = baseVar * accScale * satScale * ageScale
        return min(variance, 1e6)
    }

    /**
     * Single-step IMU prediction tick (e.g. at 10 Hz).
     * Implements Brossard et al. AI-IMU adaptive Kalman covariance and denoising.
     */
    fun predict(
        af: Double,
        al: Double,
        gyroZ: Double,
        stepDt: Double = this.dt,
        aiVf: Double? = null,
        aiGyroCorr: Double? = null,
        aiConf: Double? = null,
        aiGyroScale: Double = DEFAULT_AI_GYRO_SCALE,
        isStationary: Boolean = false,
        aiRv: Double? = null,
        aiQScale: Double? = null,
        denoiseAf: Double? = null,
        denoiseGz: Double? = null,
    ) {
        if (isStationary) {
            vf = 0.0
            vl = 0.0
            vx = 0.0
            vy = 0.0
            pVf = 0.01
            pPos += qPos * 0.1
            return
        }

        val cleanAf = af - 0.25 * (denoiseAf ?: 0.0)
        var rawOmega = gyroZ - 0.25 * (denoiseGz ?: 0.0)
        if (aiGyroCorr != null) {
            rawOmega -= aiGyroCorr * aiGyroScale
        }

        val omegaCorrected = rawOmega - gyroBiasHat
        psi += omegaCorrected * stepDt

        val qScaleVal = (aiQScale ?: 1.0).coerceIn(0.1, 10.0)
        pVf += qVf * qScaleVal * stepDt

        val vfRaw = vf + (cleanAf + omegaCorrected * vl) * stepDt
        val vfPred = when {
            aiVf == null -> vfRaw
            aiRv != null -> {
                val rv = aiRv.coerceIn(0.01, 50.0)
                val kv = pVf / (pVf + rv)
                val updated = vfRaw + kv * (aiVf - vfRaw)
                pVf = (1.0 - kv) * pVf
                updated
            }
            else -> {
                val conf = (aiConf ?: 0.85).coerceIn(0.1, 0.95)
                (1.0 - conf) * vfRaw + conf * aiVf
            }
        }

        val vlPred = vl + (al - omegaCorrected * vf) * stepDt

        // NHC constraint: vehicle lateral velocity should be ~0
        val resLat = 0.0 - vlPred
        vl = vlPred + kNhc * resLat
        vf = max(0.0, vfPred)

        // Conservative online gyro bias estimation with dead zone
        if (kotlin.math.abs(vf) > 3.0 && kotlin.math.abs(resLat) > 0.05) {
            val biasUpdate = kGyrobias * (resLat / vf) * stepDt
            gyroBiasHat += biasUpdate
            gyroBiasHat = gyroBiasHat.coerceIn(-maxGyroBias, maxGyroBias)
        }

        val cosP = cos(psi)
        val sinP = sin(psi)
        vx = vf * cosP - vl * sinP
        vy = vf * sinP + vl * cosP

        x += vx * stepDt
        y += vy * stepDt
        pPos += qPos * qScaleVal
    }

    /**
     * Single-step GNSS correction tick (e.g. at 1 Hz when fix arrives).
     */
    fun correctGnss(
        gpsX: Double,
        gpsY: Double,
        accuracyM: Double,
        sats: Int,
        fixAgeS: Double,
        gpsBearingDeg: Double? = null,
        gpsSpeedMs: Double? = null,
    ) {
        val rK = computeGnssCovariance(accuracyM, sats, fixAgeS)
        val kGain = pPos / (pPos + rK)
        x += kGain * (gpsX - x)
        y += kGain * (gpsY - y)
        pPos = (1.0 - kGain) * pPos

        // Course-over-ground heading anchoring when moving with good GNSS quality
        if (gpsBearingDeg != null && gpsSpeedMs != null && gpsSpeedMs > 2.0 && sats >= 6) {
            val gpsPsi = (Math.PI / 2.0) - Math.toRadians(gpsBearingDeg)
            var dPsi = gpsPsi - psi
            while (dPsi > Math.PI) dPsi -= 2.0 * Math.PI
            while (dPsi < -Math.PI) dPsi += 2.0 * Math.PI

            val kHeading = 0.20
            psi += kHeading * dPsi
            psi = (psi % (2.0 * Math.PI) + (2.0 * Math.PI)) % (2.0 * Math.PI)
        }
    }

    /**
     * Batch filter matching Python ESKFFusion.filter for validation and offline replay.
     */
    fun filterBatch(
        t: DoubleArray,
        af: DoubleArray,
        al: DoubleArray,
        gyroZ: DoubleArray,
        gpsX: DoubleArray,
        gpsY: DoubleArray,
        gpsSpeed: DoubleArray,
        gpsAccuracy: DoubleArray,
        gpsSats: IntArray,
        gpsFresh: BooleanArray,
        fixAge: DoubleArray,
        v0: Double,
        psi0: Double,
        x0: Double = 0.0,
        y0: Double = 0.0,
        gnssOutageMask: BooleanArray? = null,
        vfMeasurement: DoubleArray? = null,
        gyroCorrection: DoubleArray? = null,
        aiConfidence: DoubleArray? = null,
        aiRv: DoubleArray? = null,
        aiQScale: DoubleArray? = null,
        denoiseAf: DoubleArray? = null,
        denoiseGz: DoubleArray? = null,
    ): FilterOutput {
        val n = t.size
        reset(x0, y0, v0, psi0, 4.0)

        val outX = DoubleArray(n)
        val outY = DoubleArray(n)
        val outVf = DoubleArray(n)
        val outVl = DoubleArray(n)
        val outPsi = DoubleArray(n)
        val outVx = DoubleArray(n)
        val outVy = DoubleArray(n)
        val outGyroBias = DoubleArray(n)

        outX[0] = x0
        outY[0] = y0
        outVf[0] = v0
        outVl[0] = 0.0
        outPsi[0] = psi0
        outVx[0] = v0 * cos(psi0)
        outVy[0] = v0 * sin(psi0)
        outGyroBias[0] = 0.0

        for (k in 1 until n) {
            val stepDt = if (k > 0) t[k] - t[k - 1] else this.dt

            val cleanAf = af[k] - 0.25 * (denoiseAf?.get(k) ?: 0.0)
            var rawOmega = gyroZ[k] - 0.25 * (denoiseGz?.get(k) ?: 0.0)
            if (gyroCorrection != null) {
                rawOmega -= gyroCorrection[k]
            }

            val omegaCorrected = rawOmega - gyroBiasHat
            psi += omegaCorrected * stepDt

            val isOutage = gnssOutageMask?.get(k) ?: false
            val hasFresh = gpsFresh[k] && !isOutage

            val isStat = isStationary(
                linAccMag = kotlin.math.hypot(cleanAf, al[k]),
                gyroMag = kotlin.math.abs(omegaCorrected),
                currentVf = vf,
                gpsSpeed = if (hasFresh && gpsSpeed.size > k) gpsSpeed[k] else null,
            )

            val qScaleVal = (aiQScale?.get(k) ?: 1.0).coerceIn(0.1, 10.0)

            if (isStat) {
                vf = 0.0
                vl = 0.0
                vx = 0.0
                vy = 0.0
                pVf = 0.01
            } else {
                val vfRaw = vf + (cleanAf + omegaCorrected * vl) * stepDt
                pVf += qVf * qScaleVal * stepDt

                val vfPred = when {
                    vfMeasurement == null -> vfRaw
                    aiRv != null -> {
                        val rv = aiRv[k].coerceIn(0.01, 50.0)
                        val kv = pVf / (pVf + rv)
                        val updated = vfRaw + kv * (vfMeasurement[k] - vfRaw)
                        pVf = (1.0 - kv) * pVf
                        updated
                    }
                    else -> {
                        val conf = (aiConfidence?.get(k) ?: 0.85).coerceIn(0.1, 0.95)
                        (1.0 - conf) * vfRaw + conf * vfMeasurement[k]
                    }
                }

                val vlPred = vl + (al[k] - omegaCorrected * vf) * stepDt
                val resLat = 0.0 - vlPred
                vl = vlPred + kNhc * resLat
                vf = max(0.0, vfPred)

                if (kotlin.math.abs(vf) > 3.0 && kotlin.math.abs(resLat) > 0.05) {
                    val biasUpdate = kGyrobias * (resLat / vf) * stepDt
                    gyroBiasHat += biasUpdate
                    gyroBiasHat = gyroBiasHat.coerceIn(-maxGyroBias, maxGyroBias)
                }

                val cosP = cos(psi)
                val sinP = sin(psi)
                vx = vf * cosP - vl * sinP
                vy = vf * sinP + vl * cosP
            }

            val xPred = x + vx * stepDt
            val yPred = y + vy * stepDt
            pPos += qPos * qScaleVal

            if (hasFresh) {
                val rK = computeGnssCovariance(gpsAccuracy[k], gpsSats[k], fixAge[k])
                val kGain = pPos / (pPos + rK)
                x = xPred + kGain * (gpsX[k] - xPred)
                y = yPred + kGain * (gpsY[k] - yPred)
                pPos = (1.0 - kGain) * pPos
            } else {
                x = xPred
                y = yPred
            }

            outX[k] = x
            outY[k] = y
            outVf[k] = vf
            outVl[k] = vl
            outPsi[k] = psi
            outVx[k] = vx
            outVy[k] = vy
            outGyroBias[k] = gyroBiasHat
        }

        return FilterOutput(outX, outY, outVf, outVl, outPsi, outVx, outVy, outGyroBias)
    }

    data class FilterOutput(
        val x: DoubleArray,
        val y: DoubleArray,
        val vf: DoubleArray,
        val vl: DoubleArray,
        val psi: DoubleArray,
        val vx: DoubleArray,
        val vy: DoubleArray,
        val gyroBias: DoubleArray,
    )
}
