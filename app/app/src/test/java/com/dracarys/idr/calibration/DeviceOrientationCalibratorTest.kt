package com.dracarys.idr.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unit Tests for Live Device Orientation & Mount Calibration Engine.
 *
 * Direct Kotlin port of test_calibration.py verifying against exact synthetic ground truth:
 * 1. Device Flat (standard horizontal placement)
 * 2. Device Upright Portrait (windshield cradle, pitch = -80° to -90°)
 * 3. Device at Arbitrary 3D Compound Angles (arbitrary yaw, pitch, roll)
 * 4. Mount Azimuth Offset Recovery from GNSS acceleration
 * 5. Directional Sign Invariance across left and right turns
 */
class DeviceOrientationCalibratorTest {

    @Test
    fun `test device flat orientation yaw extraction`() {
        val gDev = doubleArrayOf(0.0, 0.0, 9.80665)

        // Vehicle turns left (CCW from above): +0.35 rad/s
        val omegaDevLeft = doubleArrayOf(0.0, 0.0, 0.35)
        val yawRateLeft = DeviceOrientationCalibrator.extractVehicleYawRate(omegaDevLeft, gDev)
        assertEquals(0.35, yawRateLeft, 1e-5)

        // Vehicle turns right (CW from above): -0.35 rad/s
        val omegaDevRight = doubleArrayOf(0.0, 0.0, -0.35)
        val yawRateRight = DeviceOrientationCalibrator.extractVehicleYawRate(omegaDevRight, gDev)
        assertEquals(-0.35, yawRateRight, 1e-5)
    }

    @Test
    fun `test device upright portrait cradle yaw extraction`() {
        // Upright pose: top of phone points UP, gravity sensor reads +9.81 along phone +Y.
        val gDev = doubleArrayOf(0.0, 9.80665, 0.0)

        val omegaVehicleTurn = doubleArrayOf(0.0, 0.42, 0.0)
        val yawRate = DeviceOrientationCalibrator.extractVehicleYawRate(omegaVehicleTurn, gDev)
        assertEquals(0.42, yawRate, 1e-5)
    }

    @Test
    fun `test device arbitrary compound 3D tilt`() {
        val pitch = Math.toRadians(35.0)
        val roll = Math.toRadians(-22.0)

        // Rx(pitch)
        val rx = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, cos(pitch), sin(pitch)),
            doubleArrayOf(0.0, -sin(pitch), cos(pitch))
        )

        // Ry(roll)
        val ry = arrayOf(
            doubleArrayOf(cos(roll), 0.0, -sin(roll)),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(sin(roll), 0.0, cos(roll))
        )

        // R_v2d = Rx @ Ry
        val rV2d = Array(3) { DoubleArray(3) }
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var sum = 0.0
                for (k in 0 until 3) {
                    sum += rx[i][k] * ry[k][j]
                }
                rV2d[i][j] = sum
            }
        }

        // Gravity points UP (+Z) in vehicle frame
        val gVehicle = doubleArrayOf(0.0, 0.0, 9.80665)
        val gDev = DeviceOrientationCalibrator.matVecMul(rV2d, gVehicle)

        // True vehicle rotation: pure yaw of +0.28 rad/s (left turn) + roll/pitch perturbations
        val omegaVehicleCam = doubleArrayOf(0.15, -0.10, 0.28)
        val omegaDev = DeviceOrientationCalibrator.matVecMul(rV2d, omegaVehicleCam)

        val recoveredYaw = DeviceOrientationCalibrator.extractVehicleYawRate(omegaDev, gDev)
        assertEquals(0.28, recoveredYaw, 1e-5)
    }

    @Test
    fun `test mount azimuth alignment against known ground truth`() {
        val calibrator = DeviceOrientationCalibrator()

        val n = 500
        val t = DoubleArray(n) { i -> (50.0 / (n - 1)) * i }
        val afTrue = DoubleArray(n) { i -> 2.0 * sin(0.2 * t[i]) + 0.5 * cos(0.05 * t[i]) }
        val alTrue = DoubleArray(n) { 0.0 }

        val psiMountKnownDeg = 42.0
        val psiRad = Math.toRadians(psiMountKnownDeg)

        val axDev = DoubleArray(n) { i -> cos(psiRad) * afTrue[i] - sin(psiRad) * alTrue[i] }
        val ayDev = DoubleArray(n) { i -> sin(psiRad) * afTrue[i] + cos(psiRad) * alTrue[i] }
        val azDev = DoubleArray(n) { 0.0 }

        val linearAccDev = arrayOf(axDev, ayDev, azDev)
        val gDev = doubleArrayOf(0.0, 0.0, 9.80665)

        // GNSS forward acc matches true acceleration
        val gnssForwardAcc = DoubleArray(n) { i -> afTrue[i] }

        val recoveredAzimuthDeg = calibrator.calibrateMountAzimuthFromGnss(
            linearAccDev = linearAccDev,
            gravityVec = gDev,
            gnssForwardAcc = gnssForwardAcc,
        )

        assertTrue(calibrator.isAzimuthCalibrated)
        assertEquals(psiMountKnownDeg, recoveredAzimuthDeg, 0.5)

        // Test projection of sample acceleration at index 50
        val sampleAcc = doubleArrayOf(axDev[50], ayDev[50], 0.0)
        val (afEst, alEst, _) = calibrator.projectVehicleBodyAccel(sampleAcc, gDev)

        assertEquals(afTrue[50], afEst, 0.01)
        assertEquals(0.0, alEst, 0.01)
    }

    @Test
    fun `test directional heading integration sign`() {
        val gDev = doubleArrayOf(0.0, 0.0, 9.80665)
        var psi = Math.PI / 2.0
        val dt = 0.10

        // Vehicle turns left at 0.5 rad/s for 1 second (10 ticks)
        val omegaTurnLeft = doubleArrayOf(0.0, 0.0, 0.5)
        for (tick in 0 until 10) {
            val w = DeviceOrientationCalibrator.extractVehicleYawRate(omegaTurnLeft, gDev)
            psi += w * dt
        }

        assertTrue("Turning left must increase Cartesian heading", psi > (Math.PI / 2.0))
        assertEquals(Math.PI / 2.0 + 0.5, psi, 1e-5)
    }
}
