package com.dracarys.idr.fusion

import com.dracarys.idr.ml.MotionNetInference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM Unit Test Suite for Zero-Velocity Detection (ZUPT) and MotionNet Zero-Input Quirk Regression Guard.
 *
 * Verifies:
 * 1. Physical Zero-Velocity Update (ZUPT) detection logic under all operational boundaries:
 *    - True stationary condition (desk/parking: linAcc < 0.35, gyro < 0.05, vf < 0.50)
 *    - GPS stationary fix (gpsSpeed < 0.25 m/s, linAcc < 0.35)
 *    - Cruising at speed (vf = 10 m/s, linAcc = 0, gyro = 0) -> NOT stationary
 *    - Throttle acceleration from stop (linAcc = 1.5 m/s^2) -> NOT stationary
 *    - Steering / cornering (gyro = 0.2 rad/s) -> NOT stationary
 * 2. Regression guard for MotionNet zero-input quirk:
 *    - Due to continuous driving dataset training bias, passing all-zeros to MotionNet
 *      outputs ~8.97 m/s forward velocity with ~0.60 confidence score.
 * 3. ZUPT enforcement in ESKF:
 *    - Near-zero accel/gyro input forces vf = vl = 0 and (x, y) = (0, 0) over multiple ticks,
 *      completely overriding the raw neural network's ~8.97 m/s prediction.
 */
class ZuptTest {

    private fun findModelBinary(): File {
        val candidates = listOf(
            File("src/main/assets/dracarys_motion_net.bin"),
            File("app/src/main/assets/dracarys_motion_net.bin"),
            File("../training/dracarys_idr/models/dracarys_motion_net.bin"),
            File("src/main/assets/dracarys_motion_net.tflite"),
            File("app/src/main/assets/dracarys_motion_net.tflite"),
            File("../training/dracarys_idr/models/dracarys_motion_net.tflite"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not find dracarys_motion_net.bin in test search paths")
    }

    @Test
    fun `isStationary correctly identifies stationary vs driving boundary states`() {
        // 1. Stationary on table / parked: zero motion
        assertTrue(
            "Near-zero sensor noise with zero filter velocity must be stationary",
            ESKFFusion.isStationary(linAccMag = 0.05, gyroMag = 0.01, currentVf = 0.0)
        )

        // 2. Stationary with GPS confirmation (vel = 0.0)
        assertTrue(
            "GPS reporting zero velocity with low acceleration must be stationary",
            ESKFFusion.isStationary(linAccMag = 0.10, gyroMag = 0.01, currentVf = 0.0, gpsSpeed = 0.0)
        )

        // 3. Cruising vehicle: linAcc = 0 and gyro = 0, but current velocity = 15 m/s
        // Must NOT trigger ZUPT; vehicle is moving at constant velocity
        assertFalse(
            "Cruising vehicle with constant forward speed must NOT be marked stationary",
            ESKFFusion.isStationary(linAccMag = 0.02, gyroMag = 0.005, currentVf = 15.0)
        )

        // 4. Starting from stop: accelerator pressed, forward acceleration = 1.8 m/s^2
        assertFalse(
            "Accelerating vehicle from stop must break stationary condition",
            ESKFFusion.isStationary(linAccMag = 1.8, gyroMag = 0.01, currentVf = 0.0)
        )

        // 5. Turning vehicle / rotation detected: gyro = 0.25 rad/s
        assertFalse(
            "Rotating vehicle must break stationary condition",
            ESKFFusion.isStationary(linAccMag = 0.05, gyroMag = 0.25, currentVf = 0.0)
        )
    }

    @Test
    fun `regression guard for MotionNet zero-input quirk confirms ~8_97 m_s bias and ~0_60 confidence`() {
        val modelFile = findModelBinary()
        val inference = MotionNetInference()
        inference.loadModel(modelFile.readBytes())
        assertTrue("Model must be loaded", inference.isLoaded)

        // Feed 15 frames of pure stationary zeros
        for (i in 0 until 15) {
            inference.pushSample(af = 0f, al = 0f, az = 0f, gx = 0f, gy = 0f, gz = 0f)
        }

        val result = inference.predict()

        // Explicit regression guard: document the known zero-input driving bias
        assertEquals(
            "Raw MotionNet zero-input forward velocity must match known ~8.97 m/s network bias",
            8.9741,
            result.predictedVelocity,
            0.15
        )
        assertEquals(
            "Raw MotionNet zero-input confidence score must match known ~0.60 network bias",
            0.6053,
            result.confidence,
            0.05
        )
        assertEquals(
            "Raw MotionNet zero-input gyro correction must be near zero",
            0.0,
            result.gyroCorrection,
            0.02
        )
    }

    @Test
    fun `near-zero accel and gyro input forces vf and vl to 0 regardless of raw network prediction`() {
        val modelFile = findModelBinary()
        val inference = MotionNetInference()
        inference.loadModel(modelFile.readBytes())

        // Feed zeros to get the raw ~8.97 m/s prediction
        for (i in 0 until 15) {
            inference.pushSample(af = 0f, al = 0f, az = 0f, gx = 0f, gy = 0f, gz = 0f)
        }
        val rawAiResult = inference.predict()
        assertTrue("Raw AI prediction must output positive velocity bias (> 8.0 m/s)", rawAiResult.predictedVelocity > 8.0)

        // Initialize ESKF filter at rest
        val fusion = ESKFFusion(dt = 0.10)
        fusion.reset(x0 = 0.0, y0 = 0.0, v0 = 0.0, psi0 = 0.0)

        // Simulate 30 ticks (3.0 seconds) of stationary state while passing the raw 8.97 m/s network prediction
        for (tick in 1..30) {
            val linAcc = 0.02
            val gyroZ = 0.001
            val isStat = ESKFFusion.isStationary(
                linAccMag = linAcc,
                gyroMag = gyroZ,
                currentVf = fusion.vf,
            )
            assertTrue("ZUPT condition must remain true on stationary ticks", isStat)

            fusion.predict(
                af = linAcc,
                al = 0.0,
                gyroZ = gyroZ,
                stepDt = 0.10,
                aiVf = rawAiResult.predictedVelocity, // passing the raw 8.97 m/s!
                aiGyroCorr = rawAiResult.gyroCorrection,
                aiConf = rawAiResult.confidence,
                isStationary = isStat, // ZUPT override active
            )

            // Assert: vf, vl, vx, vy remain strictly 0.0 on every tick
            assertEquals("Forward velocity vf must be forced to 0.0 by ZUPT", 0.0, fusion.vf, 1e-9)
            assertEquals("Lateral velocity vl must be forced to 0.0 by ZUPT", 0.0, fusion.vl, 1e-9)
            assertEquals("Body vx must be 0.0", 0.0, fusion.vx, 1e-9)
            assertEquals("Body vy must be 0.0", 0.0, fusion.vy, 1e-9)
        }

        // Assert: after 3 seconds of simulated outage with ~8.97 m/s raw network input,
        // position displacement is exactly 0.0 meters!
        assertEquals("x position must not drift during stationary outage", 0.0, fusion.x, 1e-9)
        assertEquals("y position must not drift during stationary outage", 0.0, fusion.y, 1e-9)
    }
}
