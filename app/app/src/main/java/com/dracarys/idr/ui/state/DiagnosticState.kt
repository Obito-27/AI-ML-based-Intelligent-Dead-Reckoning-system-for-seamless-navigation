package com.dracarys.idr.ui.state

/**
 * Real-time diagnostic state capturing raw sensor values, leveling/yaw projections,
 * and live MotionNet inference metrics at 10 Hz for physical on-device verification.
 */
data class DiagnosticState(
    val rawAcc: FloatArray = FloatArray(3),
    val rawGyro: FloatArray = FloatArray(3),
    val rawGravity: FloatArray = FloatArray(3) { if (it == 2) 9.80665f else 0f },
    val uUp: DoubleArray = doubleArrayOf(0.0, 0.0, 1.0),
    val vehicleYawRate: Double = 0.0,
    val af: Double = 0.0,
    val al: Double = 0.0,
    val az: Double = 0.0,
    val mountAzimuthDeg: Double = 0.0,
    val isMountCalibrated: Boolean = false,
    val mountSampleCount: Int = 0,
    val isModelLoaded: Boolean = false,
    val predictCallCount: Long = 0L,
    val aiVf: Double = 0.0,
    val aiGyroCorr: Double = 0.0,
    val aiConfidence: Double = 0.0,
    val headingDeg: Float = 0f,
    val isStationary: Boolean = false,
    val gyroBiasHat: Double = 0.0,
    val gpsSpeed: Double = 0.0,
    val gpsSats: Int = 0,
    val hasFreshGps: Boolean = false,
) {
    val accMag: Float
        get() = kotlin.math.sqrt(rawAcc[0] * rawAcc[0] + rawAcc[1] * rawAcc[1] + rawAcc[2] * rawAcc[2])

    val gyroMag: Float
        get() = kotlin.math.sqrt(rawGyro[0] * rawGyro[0] + rawGyro[1] * rawGyro[1] + rawGyro[2] * rawGyro[2])

    val gravMag: Float
        get() = kotlin.math.sqrt(rawGravity[0] * rawGravity[0] + rawGravity[1] * rawGravity[1] + rawGravity[2] * rawGravity[2])
}
