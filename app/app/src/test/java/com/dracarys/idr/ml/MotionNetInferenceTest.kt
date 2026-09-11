package com.dracarys.idr.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStreamReader

/**
 * Unit & Numerical Verification Tests for the Custom On-Device MotionNet Inference Engine.
 *
 * Verifies:
 * 1. Model binary loading and basic inference execution.
 * 2. Strict floating-point numerical equivalence against the NumPy/PyTorch reference
 *    forward pass evaluated on identical 15x8 temporal input windows within 1e-4 tolerance.
 */
class MotionNetInferenceTest {

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
    fun `load custom model binary and verify forward inference output bounds`() {
        val targetFile = findModelBinary()
        val inference = MotionNetInference()
        inference.loadModel(targetFile.readBytes())

        assertTrue("Model must be loaded successfully", inference.isLoaded)

        // Push 15 simulated driving samples (forward acc 0.5 m/s^2, low noise)
        for (i in 0 until 15) {
            inference.pushSample(
                af = 0.5f,
                al = 0.02f,
                az = 0.0f,
                gx = 0.001f,
                gy = 0.001f,
                gz = 0.01f,
            )
        }

        val result = inference.predict()

        // Verify speed is non-negative and finite
        assertTrue("Predicted speed should be >= 0", result.predictedVelocity >= 0.0)
        assertTrue("Predicted speed should be finite", !result.predictedVelocity.isNaN())

        // Verify gyro correction is finite
        assertTrue("Gyro correction should be finite", !result.gyroCorrection.isNaN())

        // Verify confidence score is bounded in [0.10, 0.95]
        assertTrue(
            "Confidence must be in [0.10, 0.95], was ${result.confidence}",
            result.confidence in 0.10..0.95
        )
    }

    @Test
    fun `numerical verification of custom kotlin forward pass against numpy reference`() {
        val stream = MotionNetInferenceTest::class.java.classLoader
            ?.getResourceAsStream("motion_net_test_vector.json")
            ?: error("Could not find motion_net_test_vector.json resource")
        val json = InputStreamReader(stream, Charsets.UTF_8).readText()

        // Extract reference outputs
        val refVf = """ "ref_vf":\s*([0-9.eE+-]+) """.trim().toRegex().find(json)!!.groupValues[1].toDouble()
        val refGyro = """ "ref_gyro":\s*([0-9.eE+-]+) """.trim().toRegex().find(json)!!.groupValues[1].toDouble()
        val refConf = """ "ref_conf":\s*([0-9.eE+-]+) """.trim().toRegex().find(json)!!.groupValues[1].toDouble()

        // Extract 15x8 samples
        val samplesMatch = """ "input_samples":\s*\[\s*(\[[^\]]*\](?:\s*,\s*\[[^\]]*\])*)\s*\] """.trim().toRegex().find(json)!!
        val innerRegex = """\[([^\]]*)\]""".toRegex()
        val samples = innerRegex.findAll(samplesMatch.groupValues[1]).map { m ->
            m.groupValues[1].split(",").map { it.trim().toFloat() }.toFloatArray()
        }.toList()

        assertEquals("Must contain 15 temporal samples", 15, samples.size)

        val targetFile = findModelBinary()
        val inference = MotionNetInference()
        inference.loadModel(targetFile.readBytes())

        inference.setWindowBuffer(samples)

        val result = inference.predict()

        // Numerical equality assertions against NumPy/PyTorch forward pass within 1e-4 tolerance
        assertEquals("Predicted forward speed vf mismatch vs NumPy reference", refVf, result.predictedVelocity, 1e-4)
        assertEquals("Predicted gyro correction mismatch vs NumPy reference", refGyro, result.gyroCorrection, 1e-4)
        assertEquals("Predicted confidence score mismatch vs NumPy reference", refConf, result.confidence, 1e-4)
    }
}
