package com.dracarys.idr.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MountAzimuthStreamingTest {

    @Test
    fun testLowSpeedSuppressesCalibration() {
        val calibrator = DeviceOrientationCalibrator()
        val gravity = doubleArrayOf(0.0, 0.0, 9.80665)
        val devAcc = doubleArrayOf(1.0, 0.0, 0.0)

        // Feed 50 samples at low speed (v = 1.5 m/s < 3.0 m/s threshold)
        for (i in 0 until 50) {
            val isCal = calibrator.updateMountAzimuthStreaming(
                devLinearAcc = devAcc,
                gravityVec = gravity,
                gnssSpeedMs = 1.5,
                gnssForwardAcc = 0.8,
            )
            assertFalse("Low speed must never trigger azimuth calibration", isCal)
        }

        assertFalse(calibrator.isAzimuthCalibrated)
        assertEquals(0, calibrator.calibrationSampleCount)
    }

    @Test
    fun testStreamingCalibrationRecoversArbitraryMountAngle() {
        val calibrator = DeviceOrientationCalibrator()
        val trueMountAngleRad = Math.toRadians(55.0) // arbitrary mount angle: 55 degrees

        val gravity = doubleArrayOf(0.0, 0.0, 9.80665)

        // In device frame: axH = a_ref * cos(55 deg), ayH = a_ref * sin(55 deg)
        val aRef = 1.2 // 1.2 m/s^2 forward vehicle acceleration
        val devAcc = doubleArrayOf(
            aRef * cos(trueMountAngleRad),
            aRef * sin(trueMountAngleRad),
            0.0
        )

        // Feed 35 qualifying samples at driving speed v = 14.0 m/s (> 3.0 m/s)
        for (i in 0 until 35) {
            calibrator.updateMountAzimuthStreaming(
                devLinearAcc = devAcc,
                gravityVec = gravity,
                gnssSpeedMs = 14.0,
                gnssForwardAcc = aRef,
            )
        }

        assertTrue("Azimuth should be calibrated after 35 qualifying motion samples", calibrator.isAzimuthCalibrated)
        val recoveredDeg = Math.toDegrees(calibrator.psiMount)
        assertEquals(55.0, recoveredDeg, 0.1) // matches within 0.1 degree
    }

    @Test
    fun testStreamingCalibrationWithTiltAndPitch() {
        val calibrator = DeviceOrientationCalibrator()

        // Upright cradle tilt: pitch = 45 degrees
        // Gravity has Y and Z components
        val g = 9.80665
        val pitchRad = Math.toRadians(45.0)
        val gravity = doubleArrayOf(0.0, g * sin(pitchRad), g * cos(pitchRad))

        // Phone mounted with 90 degree yaw in cradle
        val trueMountAngleRad = Math.toRadians(90.0)
        val aRef = 0.8

        // Before leveling, devAcc has both Y and Z due to pitch
        val rLevel = DeviceOrientationCalibrator.computeLevelingMatrix(gravity)
        // We want leveled acc to be [aRef * cos(90), aRef * sin(90), 0] = [0, aRef, 0]
        // leveled = R_level @ devAcc => devAcc = R_level^T @ leveled
        val targetLeveled = doubleArrayOf(aRef * cos(trueMountAngleRad), aRef * sin(trueMountAngleRad), 0.0)
        val devAcc = doubleArrayOf(
            rLevel[0][0] * targetLeveled[0] + rLevel[1][0] * targetLeveled[1] + rLevel[2][0] * targetLeveled[2],
            rLevel[0][1] * targetLeveled[0] + rLevel[1][1] * targetLeveled[1] + rLevel[2][1] * targetLeveled[2],
            rLevel[0][2] * targetLeveled[0] + rLevel[1][2] * targetLeveled[1] + rLevel[2][2] * targetLeveled[2],
        )

        for (i in 0 until 40) {
            calibrator.updateMountAzimuthStreaming(
                devLinearAcc = devAcc,
                gravityVec = gravity,
                gnssSpeedMs = 12.0,
                gnssForwardAcc = aRef,
            )
        }

        assertTrue(calibrator.isAzimuthCalibrated)
        val recoveredDeg = Math.toDegrees(calibrator.psiMount)
        assertEquals(90.0, recoveredDeg, 0.5)
    }
}
