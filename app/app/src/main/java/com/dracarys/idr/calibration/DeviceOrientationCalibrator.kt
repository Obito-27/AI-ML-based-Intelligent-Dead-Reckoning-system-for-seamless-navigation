package com.dracarys.idr.calibration

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live Device Orientation & Mount Calibration Engine for Dracarys IDR.
 *
 * Implements autonomous, ground-truth-free sensor calibration using:
 * 1. Gravity Vector Leveling (Pitch/Roll) via Android Sensor.TYPE_GRAVITY
 * 2. Vertical Vehicle Yaw Rate Projection (invariant to phone tilt / cradle mount)
 * 3. GNSS Course-Coupled Mount Azimuth Alignment (Forward / Lateral acceleration)
 * 4. Zero-Velocity Detection (ZUPT) & Gyroscope Bias Estimation
 */
class DeviceOrientationCalibrator(
    val gMag: Double = 9.80665,
) {
    var psiMount: Double = 0.0
        private set

    var isAzimuthCalibrated: Boolean = false
        private set

    var gyroBias: DoubleArray = DoubleArray(3)
        private set

    var stationaryGyroBias: DoubleArray = DoubleArray(3)
        private set

    /** Updates running average of sensor zero-rate bias while device is physically resting. */
    fun updateStationaryGyroBias(gyroMeas: DoubleArray) {
        for (i in 0 until 3) {
            // Only update when measured rate is within physical sensor rest noise (< 0.06 rad/s)
            if (kotlin.math.abs(gyroMeas[i]) < 0.06) {
                stationaryGyroBias[i] = 0.98 * stationaryGyroBias[i] + 0.02 * gyroMeas[i]
                stationaryGyroBias[i] = stationaryGyroBias[i].coerceIn(-0.04, 0.04)
            }
        }
    }

    /**
     * Extracts vehicle yaw rate around the vertical Up axis, corrected for stationary bias.
     * Follows standard SAE/ISO vehicle dynamics convention:
     * Positive sign corresponds to CCW rotation (turning left).
     * Negative sign corresponds to CW rotation (turning right).
     *
     * Vehicle yaw rate is the projection of 3D angular velocity onto vehicle Up:
     *     omega_yaw = (omega_gyro - bias) . u_up = (omega_unbiased . g) / ||g||
     */
    fun extractVehicleYawRate(gyroMeas: DoubleArray, gravityVec: DoubleArray): Double {
        val uUp = computeGravityUnitUp(gravityVec)
        val unbiasedGx = gyroMeas[0] - stationaryGyroBias[0]
        val unbiasedGy = gyroMeas[1] - stationaryGyroBias[1]
        val unbiasedGz = gyroMeas[2] - stationaryGyroBias[2]
        return unbiasedGx * uUp[0] + unbiasedGy * uUp[1] + unbiasedGz * uUp[2]
    }

    /**
     * Extracts rotation rate around the vehicle Up axis, corrected for stationary bias.
     *
     * SIGN CONVENTION (MANDATORY & INVARIANT):
     * Standard mathematical / vehicle dynamics convention (ISO 8855 / SAE J670):
     * - Positive value (> 0) = COUNTER-CLOCKWISE (CCW, turning left) around the vehicle Up axis.
     * - Negative value (< 0) = CLOCKWISE (CW, turning right) around the vehicle Up axis.
     *
     * This method conforms to standard Cartesian math convention (NOT clockwise compass bearing).
     * Navigational bearing conversion (clockwise from North = 0°) is performed explicitly at geodetic
     * boundaries via (90° - psi), ensuring that internal kinematics and filters consistently operate
     * on right-handed Cartesian coordinates without hidden sign inversions.
     */
    fun extractVehicleHeadingRate(gyroMeas: DoubleArray, gravityVec: DoubleArray): Double {
        return extractVehicleYawRate(gyroMeas, gravityVec)
    }

    companion object {
        private const val TWO_PI = 2.0 * Math.PI

        /**
         * Extracts the vehicle Up unit vector from measured Earth gravity.
         *
         * In Android Sensor.TYPE_GRAVITY, the sensor measures upward normal force
         * against gravity (+9.81 m/s^2 on Z when device is flat screen-up).
         * Therefore, the measured gravity vector directly points in the vehicle UP direction:
         *     u_up = +g / ||g||
         */
        fun computeGravityUnitUp(gravityVec: DoubleArray): DoubleArray {
            val norm = sqrt(
                gravityVec[0] * gravityVec[0] +
                    gravityVec[1] * gravityVec[1] +
                    gravityVec[2] * gravityVec[2]
            )
            if (norm < 1e-4) {
                return doubleArrayOf(0.0, 0.0, 1.0)
            }
            return doubleArrayOf(
                gravityVec[0] / norm,
                gravityVec[1] / norm,
                gravityVec[2] / norm
            )
        }

        /**
         * Extracts rotation around the vehicle vertical (Up) axis from 3D gyro.
         *
         * Vehicle yaw rate is the projection of 3D angular velocity onto vehicle Up:
         *     omega_yaw = omega_gyro . u_up = (omega_gyro . g) / ||g||
         * Positive sign corresponds to CCW rotation (turning left).
         */
        fun extractVehicleYawRate(gyroMeas: DoubleArray, gravityVec: DoubleArray): Double {
            val uUp = computeGravityUnitUp(gravityVec)
            return gyroMeas[0] * uUp[0] + gyroMeas[1] * uUp[1] + gyroMeas[2] * uUp[2]
        }

        /**
         * Builds a 3x3 rotation matrix R_dev_to_level that rotates device frame
         * such that the measured gravity vector aligns with [0, 0, +g] (vehicle Up).
         * Uses Rodrigues' rotation formula.
         */
        fun computeLevelingMatrix(gravityVec: DoubleArray): Array<DoubleArray> {
            val uUp = computeGravityUnitUp(gravityVec)
            val target = doubleArrayOf(0.0, 0.0, 1.0)

            // v = uUp x target
            val vx = uUp[1] * target[2] - uUp[2] * target[1]
            val vy = uUp[2] * target[0] - uUp[0] * target[2]
            val vz = uUp[0] * target[1] - uUp[1] * target[0]

            val s = sqrt(vx * vx + vy * vy + vz * vz)
            val c = uUp[0] * target[0] + uUp[1] * target[1] + uUp[2] * target[2]

            if (s < 1e-6) {
                return if (c > 0) {
                    arrayOf(
                        doubleArrayOf(1.0, 0.0, 0.0),
                        doubleArrayOf(0.0, 1.0, 0.0),
                        doubleArrayOf(0.0, 0.0, 1.0)
                    )
                } else {
                    arrayOf(
                        doubleArrayOf(1.0, 0.0, 0.0),
                        doubleArrayOf(0.0, -1.0, 0.0),
                        doubleArrayOf(0.0, 0.0, -1.0)
                    )
                }
            }

            // Skew-symmetric cross product matrix
            val k = (1.0 - c) / (s * s)
            val vMat = arrayOf(
                doubleArrayOf(0.0, -vz, vy),
                doubleArrayOf(vz, 0.0, -vx),
                doubleArrayOf(-vy, vx, 0.0)
            )

            // vMat @ vMat
            val vMat2 = Array(3) { DoubleArray(3) }
            for (i in 0 until 3) {
                for (j in 0 until 3) {
                    var sum = 0.0
                    for (m in 0 until 3) {
                        sum += vMat[i][m] * vMat[m][j]
                    }
                    vMat2[i][j] = sum
                }
            }

            // R = I + vMat + vMat2 * k
            val r = Array(3) { DoubleArray(3) }
            for (i in 0 until 3) {
                for (j in 0 until 3) {
                    val eye = if (i == j) 1.0 else 0.0
                    r[i][j] = eye + vMat[i][j] + vMat2[i][j] * k
                }
            }
            return r
        }

        /**
         * Multiplies a 3x3 matrix by a 3-element vector.
         */
        fun matVecMul(m: Array<DoubleArray>, v: DoubleArray): DoubleArray {
            return doubleArrayOf(
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]
            )
        }
    }

    /**
     * Estimates horizontal mount azimuth offset psi_mount by solving the
     * closed-form least-squares projection between leveled horizontal accelerometer
     * readings and vehicle longitudinal acceleration derived from GNSS.
     *
     * @param linearAccDev Array of 3 DoubleArrays: [ax, ay, az] across N time steps
     * @param gravityVec Reference gravity vector (3 elements)
     * @param gnssForwardAcc Array of longitudinal vehicle acceleration from GNSS (N elements)
     * @param minSpeedMask Optional boolean mask for samples where speed > threshold
     * @return Recovered mount azimuth in degrees
     */
    fun calibrateMountAzimuthFromGnss(
        linearAccDev: Array<DoubleArray>,
        gravityVec: DoubleArray,
        gnssForwardAcc: DoubleArray,
        minSpeedMask: BooleanArray? = null,
    ): Double {
        val rLevel = computeLevelingMatrix(gravityVec)
        val n = gnssForwardAcc.size

        val axH = DoubleArray(n)
        val ayH = DoubleArray(n)

        for (i in 0 until n) {
            val devAcc = doubleArrayOf(linearAccDev[0][i], linearAccDev[1][i], linearAccDev[2][i])
            val leveled = matVecMul(rLevel, devAcc)
            axH[i] = leveled[0]
            ayH[i] = leveled[1]
        }

        var dotX = 0.0
        var dotY = 0.0

        val maskCount = minSpeedMask?.count { it } ?: 0
        val useMask = minSpeedMask != null && maskCount > 20

        for (i in 0 until n) {
            if (!useMask || minSpeedMask!![i]) {
                val aRef = gnssForwardAcc[i]
                dotX += axH[i] * aRef
                dotY += ayH[i] * aRef
            }
        }

        // Optimal closed-form least-squares solution: psi = atan2(ay . a_ref, ax . a_ref)
        var psi = atan2(dotY, dotX)
        psi = (psi % TWO_PI + TWO_PI) % TWO_PI

        this.psiMount = psi
        this.isAzimuthCalibrated = true
        return Math.toDegrees(psi)
    }

    // ── Live Streaming Mount-Azimuth Calibration ──────────────────────────────
    private var streamDotX = 0.0
    private var streamDotY = 0.0
    private var streamEnergyRef = 0.0
    var calibrationSampleCount: Int = 0
        private set

    /**
     * Streams per-sample horizontal acceleration and GNSS longitudinal acceleration
     * to progressively estimate mount azimuth while the vehicle is driving.
     *
     * Gates:
     * - Vehicle speed must be > 3.0 m/s (~10.8 km/h).
     * - Absolute vehicle acceleration |a_ref| must be > 0.25 m/s^2 to ensure signal-to-noise ratio.
     * - Requires minimum 30 qualifying motion samples (cumulative forward acceleration energy)
     *   before setting isAzimuthCalibrated = true.
     *
     * @return true if mount azimuth is officially calibrated and converged
     */
    fun updateMountAzimuthStreaming(
        devLinearAcc: DoubleArray,
        gravityVec: DoubleArray,
        gnssSpeedMs: Double,
        gnssForwardAcc: Double,
    ): Boolean {
        if (gnssSpeedMs < 3.0 || kotlin.math.abs(gnssForwardAcc) < 0.25) {
            return isAzimuthCalibrated
        }

        val rLevel = computeLevelingMatrix(gravityVec)
        val leveled = matVecMul(rLevel, devLinearAcc)
        val axH = leveled[0]
        val ayH = leveled[1]

        streamDotX += axH * gnssForwardAcc
        streamDotY += ayH * gnssForwardAcc
        streamEnergyRef += gnssForwardAcc * gnssForwardAcc
        calibrationSampleCount++

        if (calibrationSampleCount >= 30 && streamEnergyRef > 2.0) {
            var psi = atan2(streamDotY, streamDotX)
            psi = (psi % TWO_PI + TWO_PI) % TWO_PI
            this.psiMount = psi
            this.isAzimuthCalibrated = true
        }

        return isAzimuthCalibrated
    }

    /** Resets the streaming mount-azimuth calibration buffer. */
    fun resetStreamingCalibration() {
        streamDotX = 0.0
        streamDotY = 0.0
        streamEnergyRef = 0.0
        calibrationSampleCount = 0
        isAzimuthCalibrated = false
    }

    /**
     * Projects raw linear acceleration into vehicle body axes: (a_forward, a_lateral, a_vertical).
     */
    fun projectVehicleBodyAccel(
        linearAccDev: DoubleArray,
        gravityVec: DoubleArray,
    ): Triple<Double, Double, Double> {
        val rLevel = computeLevelingMatrix(gravityVec)
        val leveled = matVecMul(rLevel, linearAccDev)

        val axH = leveled[0]
        val ayH = leveled[1]
        val azV = leveled[2]

        val cosPsi = cos(psiMount)
        val sinPsi = sin(psiMount)

        val aForward = cosPsi * axH + sinPsi * ayH
        val aLateral = -sinPsi * axH + cosPsi * ayH

        return Triple(aForward, aLateral, azV)
    }
}
