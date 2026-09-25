package com.dracarys.idr.sensors

import android.content.Context
import com.dracarys.idr.calibration.DeviceOrientationCalibrator
import com.dracarys.idr.fusion.ESKFFusion
import com.dracarys.idr.mapmatch.SoftMapMatcher
import com.dracarys.idr.ml.MotionNetInference
import com.dracarys.idr.ui.state.NavigationMode
import com.dracarys.idr.ui.state.NavigationRepository
import com.dracarys.idr.ui.state.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Production navigation repository executing the full live pipeline on real device sensors:
 * 1. Android SensorManager + Location capture via [SensorCollector]
 * 2. Device orientation leveling & yaw extraction via [DeviceOrientationCalibrator]
 * 3. Neural motion feature buffering & inference via [MotionNetInference]
 * 4. Multi-rate ESKF fusion & NHC constraints via [ESKFFusion]
 * 5. Soft map matching via [SoftMapMatcher]
 * 6. Streaming [NavigationState] updates to the UI layer at 10 Hz
 */
class LiveNavigationRepository(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : NavigationRepository {

    private val _state = MutableStateFlow(NavigationState.INITIAL)
    override val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _isDebugOutageActive = MutableStateFlow(false)
    override val isDebugOutageActive: StateFlow<Boolean> = _isDebugOutageActive.asStateFlow()

    // Track previous outage state for edge detection
    private var wasOutageActive: Boolean = false

    override fun setDebugOutage(active: Boolean) {
        _isDebugOutageActive.value = active
    }

    override fun toggleDebugOutage() {
        _isDebugOutageActive.value = !_isDebugOutageActive.value
    }

    private val _outageHistory = MutableStateFlow<List<com.dracarys.idr.logging.OutageValidationRecord>>(emptyList())
    override val outageHistory: StateFlow<List<com.dracarys.idr.logging.OutageValidationRecord>> = _outageHistory.asStateFlow()

    private val _diagnosticState = MutableStateFlow(com.dracarys.idr.ui.state.DiagnosticState())
    override val diagnosticState: StateFlow<com.dracarys.idr.ui.state.DiagnosticState> = _diagnosticState.asStateFlow()

    private var predictCallCount: Long = 0L

    private val sensorCollector = SensorCollector(context)
    private val calibrator = DeviceOrientationCalibrator()
    private val fusion = ESKFFusion(dt = 0.10)
    private val mapMatcher = SoftMapMatcher()
    private val motionNet = MotionNetInference()
    private val diagnosticLogger = com.dracarys.idr.logging.DiagnosticLogger(context)
    private val outageValidator = com.dracarys.idr.logging.OutageValidator(context)

    private var loopJob: Job? = null

    // Reference origin for local ENU coordinates
    private var refLat: Double? = null
    private var refLon: Double? = null

    private var lastFixX: Double = 0.0
    private var lastFixY: Double = 0.0
    private var lastFixTimestampMs: Long = 0L

    // Position at outage start (for displacement measurement)
    private var outageStartX: Double = 0.0
    private var outageStartY: Double = 0.0

    private var outageStartDist: Double = 0.0
    private var totalDistTraveled: Double = 0.0

    private var prevGpsSpeedMs: Double = 0.0
    private var prevGpsTimeMs: Long = 0L
    private val trailBuffer = mutableListOf<Pair<Double, Double>>()
    private var lastStableHeadingDeg: Float = 0f
    private var forwardAccelAccum: Double = 0.0

    init {
        // Forward outage validator updates to repo interface
        scope.launch {
            outageValidator.outages.collect { records ->
                _outageHistory.value = records
            }
        }

        // Load custom model binary asset asynchronously
        try {
            val assetList = context.assets.list("")?.toList() ?: emptyList()
            val assetName = when {
                assetList.contains("dracarys_motion_net.bin") -> "dracarys_motion_net.bin"
                assetList.contains("dracarys_motion_net.tflite") -> "dracarys_motion_net.tflite"
                else -> "dracarys_motion_net.bin"
            }
            context.assets.open(assetName).use { stream ->
                motionNet.loadModel(stream)
            }
            android.util.Log.i("LiveNavRepo", "Loaded $assetName successfully, isLoaded=${motionNet.isLoaded}")
        } catch (e: Exception) {
            android.util.Log.e("LiveNavRepo", "CRITICAL: Failed to load model asset", e)
            diagnosticLogger.logError("ModelLoad", e)
        }

        sensorCollector.start()
        startPipeline()
    }

    private fun startPipeline() {
        loopJob = scope.launch {
            var lastTickTime = System.currentTimeMillis()
            var nextTickTime = System.currentTimeMillis()
            var previousMode: NavigationMode? = null
            var lastGpsRetryMs = 0L

            while (isActive) {
                try {
                    nextTickTime += 100L
                    val now = System.currentTimeMillis()
                    val delayMs = max(0L, nextTickTime - now)
                    if (delayMs > 0L) {
                        delay(delayMs)
                    } else if (now - nextTickTime > 500L) {
                        nextTickTime = now
                    }
                    val tickNow = System.currentTimeMillis()
                    val dt = max(0.01, (tickNow - lastTickTime) / 1000.0)
                    lastTickTime = tickNow

                    // If still waiting for initial GPS lock, retry starting providers gently
                    if (sensorCollector.latestLocation == null && (tickNow - lastGpsRetryMs > 5000L)) {
                        lastGpsRetryMs = tickNow
                        sensorCollector.tryStartLocation()
                    }

                    // 1. Read sensor snapshots with NaN/Inf sanitization
                    val gDev = DoubleArray(3)
                    val aDev = DoubleArray(3)
                    val gyroDev = DoubleArray(3)

                    synchronized(sensorCollector.gravity) {
                        for (i in 0 until 3) {
                            val v = sensorCollector.gravity[i].toDouble()
                            gDev[i] = if (v.isNaN() || v.isInfinite()) (if (i == 2) 9.80665 else 0.0) else v
                        }
                    }
                    synchronized(sensorCollector.linearAcc) {
                        for (i in 0 until 3) {
                            val v = sensorCollector.linearAcc[i].toDouble()
                            aDev[i] = if (v.isNaN() || v.isInfinite()) 0.0 else v
                        }
                    }
                    synchronized(sensorCollector.gyro) {
                        for (i in 0 until 3) {
                            val v = sensorCollector.gyro[i].toDouble()
                            gyroDev[i] = if (v.isNaN() || v.isInfinite()) 0.0 else v
                        }
                    }

                    // 2. Calibrate orientation & project vehicle axes
                    val (af, al, az) = calibrator.projectVehicleBodyAccel(aDev, gDev)
                    val vehicleYawRate = calibrator.extractVehicleYawRate(gyroDev, gDev)

                    // 3. Zero-Velocity Detection (ZUPT)
                    val linAccMag = hypot(hypot(aDev[0], aDev[1]), aDev[2])
                    val gyroMag = hypot(hypot(gyroDev[0], gyroDev[1]), gyroDev[2])
                    val loc = sensorCollector.latestLocation
                    val isGpsFresh = sensorCollector.isFreshGps
                    val isManualOutage = _isDebugOutageActive.value
                    val isHardwareLocationOn = sensorCollector.isLocationHardwareEnabled
                    val isTunnelOutage = fusion.vf > 1.5 && (now - lastFixTimestampMs) > 4000L
                    val isOutageActive = isManualOutage || !isHardwareLocationOn || isTunnelOutage

                    val isVehicleSpeed = prevGpsSpeedMs >= 3.5 || fusion.vf >= 3.5 || forwardAccelAccum > 0.60

                    // Track sustained forward acceleration to distinguish real vehicle motion from hand tremors
                    if (af > 0.45) {
                        forwardAccelAccum = min(3.0, forwardAccelAccum + af * dt)
                    } else {
                        forwardAccelAccum = max(0.0, forwardAccelAccum - 2.0 * dt)
                    }

                    // Strict Zero-Acceleration and Stationary Detection
                    // When the device is resting or acceleration is near zero, motion must completely cease.
                    val isNearZeroAccel = linAccMag < 0.28 && kotlin.math.abs(af) < 0.22 && kotlin.math.abs(al) < 0.22
                    val isNearZeroGyro = gyroMag < 0.08
                    val isPhysicallyResting = isNearZeroAccel && isNearZeroGyro

                    val isVehicleMoving = if (isPhysicallyResting && forwardAccelAccum < 0.15) {
                        false
                    } else {
                        (isVehicleSpeed && fusion.vf > 0.30) || forwardAccelAccum > 0.40
                    }

                    val isStationary = when {
                        !isVehicleMoving -> true
                        isPhysicallyResting -> true
                        linAccMag < 0.35 && gyroMag < 0.06 && fusion.vf < 0.50 -> true
                        else -> false
                    }

                    // Adaptively learn zero-rate gyro bias ONLY when physically resting (gyro rate near zero)
                    if (isPhysicallyResting) {
                        calibrator.updateStationaryGyroBias(gyroDev)
                    }

                    // 4. Push to neural motion model & infer
                    motionNet.pushSample(
                        af = af.toFloat(),
                        al = al.toFloat(),
                        az = az.toFloat(),
                        gx = gyroDev[0].toFloat(),
                        gy = gyroDev[1].toFloat(),
                        gz = gyroDev[2].toFloat(),
                    )
                    val aiPrediction = motionNet.predict()
                    predictCallCount++

                    // 5. Predict step (Vehicle Dead Reckoning) with Zero-Acceleration freeze
                    if (isStationary) {
                        // ZERO ACCELERATION / ZERO MOTION STOP:
                        // Ensure velocity and displacement increments are strictly clamped to zero
                        fusion.vf = 0.0
                        fusion.vl = 0.0
                        fusion.vx = 0.0
                        fusion.vy = 0.0
                    } else {
                        // Vehicle Dead Reckoning (VDR)
                        // Suppress neural network positive velocity bias if vehicle is not moving or acceleration is resting
                        val aiVfInput = if (!isVehicleMoving || isPhysicallyResting) {
                            0.0
                        } else {
                            aiPrediction.predictedVelocity
                        }

                        // ESKF predict step at 10 Hz with learned Brossard et al. Kalman covariance & denoising
                        fusion.predict(
                            af = af,
                            al = al,
                            gyroZ = vehicleYawRate,
                            stepDt = dt,
                            aiVf = aiVfInput,
                            aiGyroCorr = aiPrediction.gyroCorrection,
                            aiConf = aiPrediction.confidence,
                            isStationary = isStationary,
                            aiRv = aiPrediction.measurementVarianceRv,
                            aiQScale = aiPrediction.processVarianceScaleQ,
                            denoiseAf = aiPrediction.denoisedAf,
                            denoiseGz = aiPrediction.denoisedGz,
                        )
                        totalDistTraveled += fusion.vf * dt
                    }

                    // 6. Detect outage toggle edge transitions
                    if (isOutageActive && !wasOutageActive) {
                        outageStartX = fusion.x
                        outageStartY = fusion.y
                        outageStartDist = totalDistTraveled
                    }
                    wasOutageActive = isOutageActive

                    // Update outage validator on-tick tracking
                    outageValidator.onTick(
                        isOutageActive = isOutageActive,
                        currentX = fusion.x,
                        currentY = fusion.y,
                        totalDistanceM = totalDistTraveled,
                        currentConfidence = aiPrediction.confidence.toFloat(),
                    )

                    // 7. Check for GNSS correction
                    // Incoming live GPS fixes are ALWAYS accepted when NOT in manual simulated outage!
                    // This allows immediate recovery when exiting tunnels or turning GPS back on.
                    if (loc != null && isGpsFresh && !isManualOutage) {
                        sensorCollector.isFreshGps = false
                        lastFixTimestampMs = now

                        val currentGpsSpeed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0

                        if (loc.hasBearing() && currentGpsSpeed >= 1.2) {
                            lastStableHeadingDeg = loc.bearing
                            fusion.psi = (Math.PI / 2.0) - Math.toRadians(loc.bearing.toDouble())
                        } else if (sensorCollector.compassHeadingDeg != 0f) {
                            lastStableHeadingDeg = sensorCollector.compassHeadingDeg
                            fusion.psi = (Math.PI / 2.0) - Math.toRadians(sensorCollector.compassHeadingDeg.toDouble())
                        }

                        // Feed in-motion mount azimuth calibration when moving (> 3.0 m/s)
                        if (prevGpsTimeMs > 0L) {
                            val dtGps = max(0.1, (now - prevGpsTimeMs) / 1000.0)
                            val gpsForwardAcc = (currentGpsSpeed - prevGpsSpeedMs) / dtGps
                            calibrator.updateMountAzimuthStreaming(
                                devLinearAcc = aDev,
                                gravityVec = gDev,
                                gnssSpeedMs = currentGpsSpeed,
                                gnssForwardAcc = gpsForwardAcc,
                            )
                        }
                        prevGpsSpeedMs = currentGpsSpeed
                        prevGpsTimeMs = now

                        refLat = loc.latitude
                        refLon = loc.longitude
                        lastFixX = 0.0
                        lastFixY = 0.0
                        fusion.x = 0.0
                        fusion.y = 0.0
                        fusion.vf = currentGpsSpeed
                        outageStartDist = totalDistTraveled

                        // Notify validator of GNSS reacquisition to evaluate real displacement gap
                        outageValidator.onGnssReacquired(0.0, 0.0)
                    } else if (isManualOutage && isGpsFresh) {
                        // Only suppress incoming GPS fixes if manual simulated outage switch is intentionally ON
                        sensorCollector.isFreshGps = false
                    }

                    // 8. Soft Map Match position
                    val (matchedX, matchedY, mapConf) = mapMatcher.matchPoint(fusion.x, fusion.y, fusion.psi)

                    // 9. Compute UI status metrics
                    val timeSinceLastFixMs = if (lastFixTimestampMs == 0L) 0L else (now - lastFixTimestampMs)
                    val outageDurationSec = timeSinceLastFixMs / 1000L

                    val distToLastFixM = if (!isOutageActive || lastFixTimestampMs == 0L) {
                        0f
                    } else {
                        hypot(matchedX - lastFixX, matchedY - lastFixY).toFloat()
                    }

                    val mode: NavigationMode
                    val confidence: Float
                    val driftPercent: Float
                    val curLat: Double
                    val curLon: Double
                    val currentHeadingDeg: Float

                    if (loc == null && refLat == null) {
                        // Waiting for initial GPS lock on launch — neutral gray mode
                        mode = NavigationMode.AcquiringGps
                        confidence = 0.0f
                        driftPercent = 0.0f
                        curLat = 0.0
                        curLon = 0.0
                        currentHeadingDeg = if (sensorCollector.compassHeadingDeg != 0f) sensorCollector.compassHeadingDeg else lastStableHeadingDeg
                    } else if (!isOutageActive) {
                        // Normal Navigation: position is ALWAYS the true device location!
                        mode = NavigationMode.Gnss
                        confidence = 0.98f
                        driftPercent = 0.0f
                        curLat = loc?.latitude ?: refLat ?: 0.0
                        curLon = loc?.longitude ?: refLon ?: 0.0
                        currentHeadingDeg = if (loc != null && loc.hasBearing() && (loc.hasSpeed() && loc.speed >= 1.2f)) {
                            lastStableHeadingDeg = loc.bearing
                            loc.bearing
                        } else if (sensorCollector.compassHeadingDeg != 0f) {
                            lastStableHeadingDeg = sensorCollector.compassHeadingDeg
                            sensorCollector.compassHeadingDeg
                        } else {
                            lastStableHeadingDeg
                        }

                        // Keep dead reckoning anchor continuously synced to current location
                        if (loc != null) {
                            refLat = loc.latitude
                            refLon = loc.longitude
                            lastFixX = 0.0
                            lastFixY = 0.0
                            fusion.x = 0.0
                            fusion.y = 0.0
                            if (loc.hasSpeed()) {
                                fusion.vf = loc.speed.toDouble()
                            }
                            outageStartDist = totalDistTraveled
                        }
                    } else {
                        // Outage active (simulated outage, location disabled, or driving tunnel outage):
                        // Driven through IMU sensors (Dead Reckoning)!
                        val outageDist = max(0.0, totalDistTraveled - outageStartDist)
                        val sigmaPos = sqrt(max(0.01, fusion.pPos))

                        driftPercent = if (outageDist < 1.0) {
                            0.0f
                        } else {
                            ((sigmaPos / outageDist) * 100.0).toFloat().coerceIn(0.1f, 99.9f)
                        }

                        val decayFactor = exp(-min(10.0, sigmaPos / 40.0))
                        val baseConf = aiPrediction.confidence.toDouble().coerceIn(0.60, 0.95)
                        val effectiveConf = (baseConf * decayFactor).toFloat().coerceIn(0.15f, 0.95f)
                        confidence = effectiveConf

                        mode = if (isManualOutage || !isHardwareLocationOn) {
                            NavigationMode.DeadReckoning
                        } else if (effectiveConf >= 0.70f) {
                            NavigationMode.Fused
                        } else {
                            NavigationMode.DeadReckoning
                        }

                        val (drLat, drLon) = if (refLat != null && refLon != null) {
                            enuToGeodetic(matchedX, matchedY, refLat!!, refLon!!)
                        } else if (loc != null) {
                            Pair(loc.latitude, loc.longitude)
                        } else {
                            Pair(0.0, 0.0)
                        }
                        curLat = drLat
                        curLon = drLon
                        currentHeadingDeg = if (isStationary) {
                            if (sensorCollector.compassHeadingDeg != 0f) {
                                lastStableHeadingDeg = sensorCollector.compassHeadingDeg
                                sensorCollector.compassHeadingDeg
                            } else {
                                lastStableHeadingDeg
                            }
                        } else {
                            val deg = ((90.0 - Math.toDegrees(fusion.psi)) % 360.0 + 360.0) % 360.0
                            lastStableHeadingDeg = deg.toFloat()
                            deg.toFloat()
                        }
                    }

                    // Update breadcrumb trail
                    if (curLat != 0.0 && curLon != 0.0) {
                        synchronized(trailBuffer) {
                            val lastPt = trailBuffer.lastOrNull()
                            if (lastPt == null) {
                                trailBuffer.add(Pair(curLat, curLon))
                            } else {
                                val distM = hypot((curLat - lastPt.first) * 111000.0, (curLon - lastPt.second) * 85000.0)
                                if (distM > 50.0) {
                                    trailBuffer.clear()
                                    trailBuffer.add(Pair(curLat, curLon))
                                } else if (distM >= 1.5) {
                                    trailBuffer.add(Pair(curLat, curLon))
                                    if (trailBuffer.size > 60) trailBuffer.removeAt(0)
                                }
                            }
                        }
                    }

                    // Log mode transitions to persistent diagnostics
                    if (previousMode != null && previousMode != mode) {
                        diagnosticLogger.logModeTransition(
                            oldMode = previousMode?.javaClass?.simpleName ?: "None",
                            newMode = mode.javaClass.simpleName,
                            reason = if (isOutageActive) "debug_outage_active" else "gnss_status_change"
                        )
                    }
                    previousMode = mode

                    // Log continuous 10 Hz telemetry
                    val uUp = DeviceOrientationCalibrator.computeGravityUnitUp(gDev)
                    diagnosticLogger.logTick(
                        rawAcc = aDev,
                        gravity = gDev,
                        gyro = gyroDev,
                        uUp = uUp,
                        mountAzimuthDeg = Math.toDegrees(calibrator.psiMount),
                        vehicleYawRate = vehicleYawRate,
                        af = af,
                        al = al,
                        az = az,
                        aiVf = aiPrediction.predictedVelocity,
                        aiGyroCorr = aiPrediction.gyroCorrection,
                        aiConf = aiPrediction.confidence,
                        navMode = mode.javaClass.simpleName,
                        fusionVf = fusion.vf,
                        fusionVl = fusion.vl,
                        fusionVx = fusion.vx,
                        fusionVy = fusion.vy,
                        fusionX = fusion.x,
                        fusionY = fusion.y,
                        fusionPsi = fusion.psi,
                        gyroBiasHat = fusion.gyroBiasHat,
                        zuptActive = isStationary,
                        hasGnss = (loc != null && !isOutageActive),
                        gpsLat = loc?.latitude ?: 0.0,
                        gpsLon = loc?.longitude ?: 0.0,
                        gpsSpeed = if (loc?.hasSpeed() == true) loc.speed.toDouble() else 0.0,
                        gpsSats = loc?.extras?.getInt("satellites", 0) ?: 0,
                        gpsAccuracy = if (loc?.hasAccuracy() == true) loc.accuracy.toDouble() else 0.0,
                    )

                    _diagnosticState.value = com.dracarys.idr.ui.state.DiagnosticState(
                        rawAcc = floatArrayOf(aDev[0].toFloat(), aDev[1].toFloat(), aDev[2].toFloat()),
                        rawGyro = floatArrayOf(gyroDev[0].toFloat(), gyroDev[1].toFloat(), gyroDev[2].toFloat()),
                        rawGravity = floatArrayOf(gDev[0].toFloat(), gDev[1].toFloat(), gDev[2].toFloat()),
                        uUp = uUp,
                        vehicleYawRate = vehicleYawRate,
                        af = af,
                        al = al,
                        az = az,
                        mountAzimuthDeg = Math.toDegrees(calibrator.psiMount),
                        isMountCalibrated = calibrator.isAzimuthCalibrated,
                        mountSampleCount = calibrator.calibrationSampleCount,
                        isModelLoaded = motionNet.isLoaded,
                        predictCallCount = predictCallCount,
                        aiVf = aiPrediction.predictedVelocity,
                        aiGyroCorr = aiPrediction.gyroCorrection,
                        aiConfidence = aiPrediction.confidence,
                        headingDeg = currentHeadingDeg,
                        isStationary = isStationary,
                        gyroBiasHat = fusion.gyroBiasHat,
                        gpsSpeed = if (loc?.hasSpeed() == true) loc.speed.toDouble() else 0.0,
                        gpsSats = loc?.extras?.getInt("satellites", 0) ?: 0,
                        hasFreshGps = (loc != null && !isOutageActive),
                        rawGpsLat = loc?.latitude ?: 0.0,
                        rawGpsLon = loc?.longitude ?: 0.0,
                        rawGpsAccuracyM = loc?.accuracy ?: 0f,
                        gpsProvider = loc?.provider ?: "none",
                        isGpsProviderEnabled = sensorCollector.isGpsProviderEnabled,
                        isNetworkProviderEnabled = sensorCollector.isNetworkProviderEnabled,
                    )

                    _state.value = NavigationState(
                        mode = mode,
                        confidence = confidence,
                        driftPercent = driftPercent,
                        outageDurationSec = outageDurationSec,
                        distanceToLastFixM = distToLastFixM,
                        latitude = curLat,
                        longitude = curLon,
                        headingDeg = currentHeadingDeg,
                        recentTrail = synchronized(trailBuffer) { trailBuffer.toList() },
                        isMountCalibrated = calibrator.isAzimuthCalibrated,
                        mountAzimuthDeg = Math.toDegrees(calibrator.psiMount),
                    )
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // Crash resilience: isolate failure so a bad sample doesn't terminate the drive
                    android.util.Log.e("LiveNavRepo", "Transient pipeline tick exception: ${t.message}", t)
                    diagnosticLogger.logError("PipelineTick", t)
                }
            }
        }
    }

    private fun geodeticToEnu(lat: Double, lon: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val rEarth = 6378137.0
        val dLat = Math.toRadians(lat - lat0)
        val dLon = Math.toRadians(lon - lon0)
        val lat0Rad = Math.toRadians(lat0)
        val x = dLon * rEarth * cos(lat0Rad)
        val y = dLat * rEarth
        return Pair(x, y)
    }

    private fun enuToGeodetic(x: Double, y: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val rEarth = 6378137.0
        val dLat = Math.toDegrees(y / rEarth)
        val dLon = Math.toDegrees(x / (rEarth * cos(Math.toRadians(lat0))))
        return Pair(lat0 + dLat, lon0 + dLon)
    }

    override fun refreshLocation() {
        sensorCollector.refreshLocation()
    }

    fun stop() {
        loopJob?.cancel()
        sensorCollector.stop()
        diagnosticLogger.close()
    }
}
