package com.dracarys.idr.ml

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Custom On-Device MotionNet Neural Network Inference Engine for Dracarys IDR.
 *
 * Implements a hand-written, vectorized pure-Kotlin forward pass executing the 1D-CNN + GRU model
 * from a custom binary weights container (`dracarys_motion_net.bin`). This custom engine eliminates
 * 15+ MB APK bloat from native C++ runtimes and avoids JNI overhead on 10 Hz streaming loops, while
 * ensuring full deterministic execution across JVM unit tests and Android runtimes.
 *
 * Architecture:
 * - Input: Strictly trailing temporal window of 15 samples x 8 features:
 *          [af, al, az, gx, gy, gz, acc_norm, jerk]
 * - Layers:
 *     Conv1D(8 -> 32, k=3, pad=1) + BatchNorm1d(32) + ReLU
 *     Conv1D(32 -> 48, k=3, pad=1) + BatchNorm1d(48) + ReLU
 *     GRU(input=48, hidden=48, 1 layer)
 * - Heads:
 *     1. Forward Velocity vf (m/s): Linear(48->24) + ReLU + Linear(24->1) + ReLU
 *     2. Gyroscope Correction delta_omega (rad/s): Linear(48->24) + ReLU + Linear(24->1)
 *     3. Confidence score: Linear(48->16) + ReLU + Linear(16->1) + Sigmoid -> 0.1 + 0.85 * conf
 */
class MotionNetInference {

    companion object {
        const val WINDOW_SIZE = 15
        const val NUM_FEATURES = 8
        const val CNN_CHANNELS = 32
        const val GRU_HIDDEN = 48
    }

    // Weight containers
    private val weights = mutableMapOf<String, FloatArray>()
    private val tensorShapes = mutableMapOf<String, IntArray>()

    var isLoaded: Boolean = false
        private set

    // Trailing rolling buffer of size WINDOW_SIZE x NUM_FEATURES
    private val windowBuffer = Array(WINDOW_SIZE) { FloatArray(NUM_FEATURES) }
    private var sampleCount: Int = 0
    private var lastAf: Float = 0f

    /**
     * Loads the model weights from the binary TFL3 container.
     */
    fun loadModel(stream: InputStream) {
        val bytes = stream.readBytes()
        loadModel(bytes)
    }

    /**
     * Loads model directly from byte array.
     */
    fun loadModel(data: ByteArray) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Verify TFL3 header
        buf.position(4)
        val magic = ByteArray(4)
        buf.get(magic)
        val magicStr = String(magic)
        require(magicStr == "TFL3") { "Invalid model magic: $magicStr, expected TFL3" }

        val jsonLen = buf.getInt(8)
        val jsonBytes = ByteArray(jsonLen)
        buf.position(12)
        buf.get(jsonBytes)
        val jsonStr = String(jsonBytes, Charsets.UTF_8)

        // Parse JSON tensor metadata manually or via simple string parsing to avoid external dependencies
        val tensors = parseTensorMetadata(jsonStr)

        var pos = 12 + jsonLen
        val pad = (16 - (pos % 16)) % 16
        pos += pad

        for (meta in tensors) {
            buf.position(pos)
            val bufLen = buf.int
            pos += 4

            val numFloats = bufLen / 4
            val floatArr = FloatArray(numFloats)
            buf.position(pos)
            val floatBuf = buf.asFloatBuffer()
            floatBuf.get(floatArr)

            weights[meta.name] = floatArr
            tensorShapes[meta.name] = meta.shape

            pos += bufLen
            val bufPad = (16 - (pos % 16)) % 16
            pos += bufPad
        }

        isLoaded = true
        resetBuffer()
    }

    /**
     * Resets the rolling temporal window.
     */
    fun resetBuffer() {
        for (i in 0 until WINDOW_SIZE) {
            windowBuffer[i].fill(0f)
        }
        sampleCount = 0
        lastAf = 0f
    }

    /**
     * Directly sets the rolling temporal window buffer (WINDOW_SIZE x NUM_FEATURES).
     * Used for testing and deterministic evaluation against reference tensors.
     */
    fun setWindowBuffer(samples: List<FloatArray>) {
        require(samples.size == WINDOW_SIZE) { "Expected $WINDOW_SIZE samples, got ${samples.size}" }
        for (i in 0 until WINDOW_SIZE) {
            require(samples[i].size == NUM_FEATURES) { "Expected $NUM_FEATURES features per sample, got ${samples[i].size}" }
            System.arraycopy(samples[i], 0, windowBuffer[i], 0, NUM_FEATURES)
        }
        sampleCount = WINDOW_SIZE
    }

    /**
     * Pushes a new IMU sample at time k (10 Hz) into the trailing buffer.
     * Computes derived features: accNorm and jerk.
     */
    fun pushSample(
        af: Float,
        al: Float,
        az: Float,
        gx: Float,
        gy: Float,
        gz: Float,
    ) {
        val accNorm = sqrt(af * af + al * al)
        val jerk = if (sampleCount == 0) 0f else (af - lastAf)
        lastAf = af

        val feat = floatArrayOf(af, al, az, gx, gy, gz, accNorm, jerk)

        if (sampleCount < WINDOW_SIZE) {
            // Early buffer fill: replicate first sample across empty slots
            if (sampleCount == 0) {
                for (i in 0 until WINDOW_SIZE) {
                    System.arraycopy(feat, 0, windowBuffer[i], 0, NUM_FEATURES)
                }
            } else {
                for (i in 0 until WINDOW_SIZE - 1) {
                    System.arraycopy(windowBuffer[i + 1], 0, windowBuffer[i], 0, NUM_FEATURES)
                }
                System.arraycopy(feat, 0, windowBuffer[WINDOW_SIZE - 1], 0, NUM_FEATURES)
            }
        } else {
            // Shift window left by 1
            for (i in 0 until WINDOW_SIZE - 1) {
                System.arraycopy(windowBuffer[i + 1], 0, windowBuffer[i], 0, NUM_FEATURES)
            }
            System.arraycopy(feat, 0, windowBuffer[WINDOW_SIZE - 1], 0, NUM_FEATURES)
        }
        sampleCount++
    }

    /**
     * Runs forward inference on the current trailing window.
     *
     * @return Output (vf: Double, deltaGyro: Double, confidence: Double)
     */
    fun predict(): InferenceResult {
        if (!isLoaded) {
            return InferenceResult(0.0, 0.0, 0.5)
        }

        // 1. Conv1D Layer 1: (8 channels, length 15) -> (32 channels, length 15)
        // Weight shape: [32, 8, 3], bias: [32]
        val c1Weight = weights["conv1.weight"] ?: return fallback()
        val c1Bias = weights["conv1.bias"] ?: return fallback()
        val bn1Mean = weights["bn1.running_mean"] ?: return fallback()
        val bn1Var = weights["bn1.running_var"] ?: return fallback()
        val bn1Weight = weights["bn1.weight"] ?: return fallback()
        val bn1Bias = weights["bn1.bias"] ?: return fallback()

        // Permute to (channels, length): windowBuffer is (15, 8)
        val relu1 = Array(CNN_CHANNELS) { FloatArray(WINDOW_SIZE) }

        for (oc in 0 until CNN_CHANNELS) {
            val mean = bn1Mean[oc]
            val invStd = (1.0f / sqrt(bn1Var[oc] + 1e-5f)) * bn1Weight[oc]
            val b = bn1Bias[oc]
            val biasVal = c1Bias[oc]

            for (t in 0 until WINDOW_SIZE) {
                var sum = biasVal
                for (ic in 0 until NUM_FEATURES) {
                    val wBase = (oc * NUM_FEATURES + ic) * 3
                    for (k in 0 until 3) {
                        val inIdx = t + k - 1 // padding = 1
                        val inVal = if (inIdx in 0 until WINDOW_SIZE) windowBuffer[inIdx][ic] else 0f
                        sum += c1Weight[wBase + k] * inVal
                    }
                }
                // BatchNorm + ReLU
                val bnVal = (sum - mean) * invStd + b
                relu1[oc][t] = max(0f, bnVal)
            }
        }

        // 2. Conv1D Layer 2: (32 channels, length 15) -> (48 channels, length 15)
        val c2Weight = weights["conv2.weight"] ?: return fallback()
        val c2Bias = weights["conv2.bias"] ?: return fallback()
        val bn2Mean = weights["bn2.running_mean"] ?: return fallback()
        val bn2Var = weights["bn2.running_var"] ?: return fallback()
        val bn2Weight = weights["bn2.weight"] ?: return fallback()
        val bn2Bias = weights["bn2.bias"] ?: return fallback()

        val relu2 = Array(WINDOW_SIZE) { FloatArray(GRU_HIDDEN) }

        for (oc in 0 until GRU_HIDDEN) {
            val mean = bn2Mean[oc]
            val invStd = (1.0f / sqrt(bn2Var[oc] + 1e-5f)) * bn2Weight[oc]
            val b = bn2Bias[oc]
            val biasVal = c2Bias[oc]

            for (t in 0 until WINDOW_SIZE) {
                var sum = biasVal
                for (ic in 0 until CNN_CHANNELS) {
                    val wBase = (oc * CNN_CHANNELS + ic) * 3
                    for (k in 0 until 3) {
                        val inIdx = t + k - 1
                        val inVal = if (inIdx in 0 until WINDOW_SIZE) relu1[ic][inIdx] else 0f
                        sum += c2Weight[wBase + k] * inVal
                    }
                }
                // BatchNorm + ReLU
                val bnVal = (sum - mean) * invStd + b
                relu2[t][oc] = max(0f, bnVal) // Stored as (15, 48) for GRU
            }
        }

        // 3. GRU Layer (15 time steps, input 48, hidden 48)
        val wIh = weights["gru.weight_ih_l0"] ?: return fallback() // (144, 48)
        val wHh = weights["gru.weight_hh_l0"] ?: return fallback() // (144, 48)
        val bIh = weights["gru.bias_ih_l0"] ?: return fallback()   // (144)
        val bHh = weights["gru.bias_hh_l0"] ?: return fallback()   // (144)

        val h = FloatArray(GRU_HIDDEN)
        val gi = FloatArray(144)
        val gh = FloatArray(144)

        for (t in 0 until WINDOW_SIZE) {
            val xt = relu2[t]

            // gi = W_ih @ xt + b_ih
            for (i in 0 until 144) {
                var sum = bIh[i]
                val base = i * GRU_HIDDEN
                for (j in 0 until GRU_HIDDEN) {
                    sum += wIh[base + j] * xt[j]
                }
                gi[i] = sum
            }

            // gh = W_hh @ h + b_hh
            for (i in 0 until 144) {
                var sum = bHh[i]
                val base = i * GRU_HIDDEN
                for (j in 0 until GRU_HIDDEN) {
                    sum += wHh[base + j] * h[j]
                }
                gh[i] = sum
            }

            // PyTorch GRU gates: r (reset), z (update), n (new)
            for (j in 0 until GRU_HIDDEN) {
                val r = 1.0f / (1.0f + exp(-(gi[j] + gh[j])))
                val z = 1.0f / (1.0f + exp(-(gi[j + 48] + gh[j + 48])))
                val n = tanh(gi[j + 96] + r * gh[j + 96])
                h[j] = (1.0f - z) * n + z * h[j]
            }
        }

        // 4. Output Heads from latest GRU state h (48) per AI-IMU 3-head architecture
        // Head 1: IMU Denoising Head [delta_af, delta_gz]
        val denW0 = weights["head_denoise.0.weight"]
        val denB0 = weights["head_denoise.0.bias"]
        val denW2 = weights["head_denoise.2.weight"]
        val denB2 = weights["head_denoise.2.bias"]

        var denoiseAf = 0.0
        var denoiseGz = 0.0
        if (denW0 != null && denB0 != null && denW2 != null && denB2 != null) {
            val denH1 = FloatArray(24)
            for (i in 0 until 24) {
                var sum = denB0[i]
                val base = i * 48
                for (j in 0 until 48) sum += denW0[base + j] * h[j]
                denH1[i] = max(0f, sum)
            }
            var s0 = denB2[0]
            var s1 = denB2[1]
            for (j in 0 until 24) {
                s0 += denW2[j] * denH1[j]
                s1 += denW2[24 + j] * denH1[j]
            }
            denoiseAf = s0.toDouble()
            denoiseGz = s1.toDouble()
        }

        // Head 2: Motion State Regression Head [vf, delta_gyro]
        val motW0 = weights["head_motion.0.weight"]
        val motB0 = weights["head_motion.0.bias"]
        val motW2 = weights["head_motion.2.weight"]
        val motB2 = weights["head_motion.2.bias"]

        var predVf = 0.0
        var predGyro = 0.0
        if (motW0 != null && motB0 != null && motW2 != null && motB2 != null) {
            val motH1 = FloatArray(24)
            for (i in 0 until 24) {
                var sum = motB0[i]
                val base = i * 48
                for (j in 0 until 48) sum += motW0[base + j] * h[j]
                motH1[i] = max(0f, sum)
            }
            var s0 = motB2[0]
            var s1 = motB2[1]
            for (j in 0 until 24) {
                s0 += motW2[j] * motH1[j]
                s1 += motW2[24 + j] * motH1[j]
            }
            predVf = max(0.0, max(0f, s0).toDouble())
            predGyro = s1.toDouble()
        } else {
            // Graceful fallback to legacy heads if present
            val vfW0 = weights["head_vf.0.weight"]
            val vfB0 = weights["head_vf.0.bias"]
            val vfW2 = weights["head_vf.2.weight"]
            val vfB2 = weights["head_vf.2.bias"]
            if (vfW0 != null && vfB0 != null && vfW2 != null && vfB2 != null) {
                val vfH1 = FloatArray(24)
                for (i in 0 until 24) {
                    var sum = vfB0[i]
                    val base = i * 48
                    for (j in 0 until 48) sum += vfW0[base + j] * h[j]
                    vfH1[i] = max(0f, sum)
                }
                var vfSum = vfB2[0]
                for (j in 0 until 24) vfSum += vfW2[j] * vfH1[j]
                predVf = max(0.0, max(0f, vfSum).toDouble())
            }

            val gyroW0 = weights["head_gyro.0.weight"]
            val gyroB0 = weights["head_gyro.0.bias"]
            val gyroW2 = weights["head_gyro.2.weight"]
            val gyroB2 = weights["head_gyro.2.bias"]
            if (gyroW0 != null && gyroB0 != null && gyroW2 != null && gyroB2 != null) {
                val gyroH1 = FloatArray(24)
                for (i in 0 until 24) {
                    var sum = gyroB0[i]
                    val base = i * 48
                    for (j in 0 until 48) sum += gyroW0[base + j] * h[j]
                    gyroH1[i] = max(0f, sum)
                }
                var gyroSum = gyroB2[0]
                for (j in 0 until 24) gyroSum += gyroW2[j] * gyroH1[j]
                predGyro = gyroSum.toDouble()
            }
        }

        // Head 3: Context-Aware Uncertainty / Covariance Head [log_var_v, log_var_q]
        val uncW0 = weights["head_uncertainty.0.weight"]
        val uncB0 = weights["head_uncertainty.0.bias"]
        val uncW2 = weights["head_uncertainty.2.weight"]
        val uncB2 = weights["head_uncertainty.2.bias"]

        var logVarV = 0.0
        var logVarQ = 0.0
        var predConf = 0.85
        var rvVal = 1.0
        var qScaleVal = 1.0

        if (uncW0 != null && uncB0 != null && uncW2 != null && uncB2 != null) {
            val uncH1 = FloatArray(24)
            for (i in 0 until 24) {
                var sum = uncB0[i]
                val base = i * 48
                for (j in 0 until 48) sum += uncW0[base + j] * h[j]
                uncH1[i] = max(0f, sum)
            }
            var s0 = uncB2[0]
            var s1 = uncB2[1]
            for (j in 0 until 24) {
                s0 += uncW2[j] * uncH1[j]
                s1 += uncW2[24 + j] * uncH1[j]
            }
            val clS0 = s0.coerceIn(-4f, 4f)
            val clS1 = s1.coerceIn(-4f, 4f)
            logVarV = clS0.toDouble()
            logVarQ = clS1.toDouble()
            rvVal = exp(logVarV)
            qScaleVal = exp(logVarQ)
            predConf = (1.0 / (1.0 + exp(0.5 * logVarV))).coerceIn(0.1, 0.95)
        } else {
            // Graceful fallback to legacy head_conf
            val confW0 = weights["head_conf.0.weight"]
            val confB0 = weights["head_conf.0.bias"]
            val confW2 = weights["head_conf.2.weight"]
            val confB2 = weights["head_conf.2.bias"]
            if (confW0 != null && confB0 != null && confW2 != null && confB2 != null) {
                val confH1 = FloatArray(16)
                for (i in 0 until 16) {
                    var sum = confB0[i]
                    val base = i * 48
                    for (j in 0 until 48) sum += confW0[base + j] * h[j]
                    confH1[i] = max(0f, sum)
                }
                var confSum = confB2[0]
                for (j in 0 until 16) confSum += confW2[j] * confH1[j]
                val confSig = 1.0f / (1.0f + exp(-confSum))
                predConf = 0.1 + 0.85 * confSig.toDouble()
            }
        }

        return InferenceResult(
            predictedVelocity = predVf,
            gyroCorrection = predGyro,
            confidence = predConf,
            denoisedAf = denoiseAf,
            denoisedGz = denoiseGz,
            measurementVarianceRv = rvVal,
            processVarianceScaleQ = qScaleVal,
        )
    }

    private fun fallback(): InferenceResult = InferenceResult(
        predictedVelocity = 0.0,
        gyroCorrection = 0.0,
        confidence = 0.5,
        denoisedAf = 0.0,
        denoisedGz = 0.0,
        measurementVarianceRv = 1.0,
        processVarianceScaleQ = 1.0,
    )

    data class InferenceResult(
        val predictedVelocity: Double,
        val gyroCorrection: Double,
        val confidence: Double,
        val denoisedAf: Double = 0.0,
        val denoisedGz: Double = 0.0,
        val measurementVarianceRv: Double = 1.0,
        val processVarianceScaleQ: Double = 1.0,
    )

    private data class TensorMeta(val name: String, val shape: IntArray)

    private fun parseTensorMetadata(json: String): List<TensorMeta> {
        val list = mutableListOf<TensorMeta>()
        // Simple regex parser for JSON tensor list
        val tensorRegex = """"name":\s*"([^"]+)",\s*"shape":\s*\[([^\]]*)\]""".toRegex()
        for (match in tensorRegex.findAll(json)) {
            val name = match.groupValues[1]
            val shapeStr = match.groupValues[2].trim()
            val shape = if (shapeStr.isEmpty()) {
                intArrayOf()
            } else {
                shapeStr.split(",").map { it.trim().toInt() }.toIntArray()
            }
            list.add(TensorMeta(name, shape))
        }
        return list
    }
}
