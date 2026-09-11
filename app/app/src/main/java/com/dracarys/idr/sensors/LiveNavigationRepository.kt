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
            var previousMode: NavigationMode? = null
            var lastGpsRetryMs = 0L

            while (isActive) {
                try {
                    delay(100L) // 10 Hz tick
                    val now = System.currentTimeMillis()
                    val dt = max(0.01, (now - lastTickTime) / 1000.0)
                    lastTickTime = now

                    // If still waiting for initial GPS lock, retry starting providers gently
                    if (sensorCollector.latestLocation == null && (now - lastGpsRetryMs > 5000L)) {
                        lastGpsRetryMs = now
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
                    val vehicleHeadingRate = calibrator.extractVehicleHeadingRate(gyroDev, gDev)

                    // 3. Zero-Velocity Detection (ZUPT)
                    val linAccMag = hypot(hypot(aDev[0], aDev[1]), aDev[2])
                    val gyroMag = hypot(hypot(gyroDev[0], gyroDev[1]), gyroDev[2])
                    val loc = sensorCollector.latestLocation
                    val isGpsFresh = sensorCollector.isFreshGps
                    val isOutageActive = _isDebugOutageActive.value
                    val isGpsActive = sensorCollector.isGpsActive && !isOutageActive
                    val gpsSpeed = if (loc != null && loc.hasSpeed() && isGpsFresh && isGpsActive) loc.speed.toDouble() else null

                    val isStationary = when {
                        gpsSpeed != null && gpsSpeed < 0.25 -> true
                        linAccMag < 0.40 && gyroMag < 0.08 && fusion.vf < 0.50 -> true
                        linAccMag < 0.20 && gyroMag < 0.03 -> true
                        else -> false
                    }

                    // When physically stationary, adaptively learn zero-rate gyro bias
                    if (isStationary) {
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

                    // Suppress neural network positive velocity bias if vehicle is stopped and not accelerating
                    val aiVfInput = if (fusion.vf < 0.10 && af < 0.35) {
                        0.0
                    } else {
                        aiPrediction.predictedVelocity
                    }

                    // 5. ESKF predict step at 10 Hz (ZUPT forces vf = vl = 0 and freezes heading)
                    fusion.predict(
                        af = af,
                        al = al,
                        gyroZ = vehicleHeadingRate,
                        stepDt = dt,
                        aiVf = aiVfInput,
                        aiGyroCorr = aiPrediction.gyroCorrection,
                        aiConf = aiPrediction.confidence,
                        isStationary = isStationary,
                    )

                    totalDistTraveled += fusion.vf * dt

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

                    // 7. Check for GNSS correction (suppressed when debug outage is active or GPS is inactive)
                    if (loc != null && isGpsFresh && isGpsActive) {
                        sensorCollector.isFreshGps = false
                        lastFixTimestampMs = now

                        val currentGpsSpeed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0

                        if (loc.hasBearing() && (currentGpsSpeed > 0.5 || !loc.hasSpeed())) {
                            lastStableHeadingDeg = loc.bearing
                            fusion.psi = Math.toRadians(loc.bearing.toDouble())
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
                    } else if (!isGpsActive && isGpsFresh) {
                        // Suppress incoming live GPS updates during simulated outage or GPS inactive
                        sensorCollector.isFreshGps = false
                    }

                    // 8. Soft Map Match position
                    val (matchedX, matchedY, mapConf) = mapMatcher.matchPoint(fusion.x, fusion.y, fusion.psi)

                    // 9. Compute UI status metrics
                    val timeSinceLastFixMs = if (lastFixTimestampMs == 0L) 0L else (now - lastFixTimestampMs)
                    val outageDurationSec = timeSinceLastFixMs / 1000L

                    val isGnssAvailable = isGpsActive && loc != null && timeSinceLastFixMs < 3000L

                    val distToLastFixM = if (isGnssAvailable || lastFixTimestampMs == 0L) {
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

                    if (refLat == null || lastFixTimestampMs == 0L) {
                        // Waiting for initial GPS lock on launch — neutral gray mode
                        mode = NavigationMode.AcquiringGps
                        confidence = 0.0f
                        driftPercent = 0.0f
                        curLat = loc?.latitude ?: 0.0
                        curLon = loc?.longitude ?: 0.0
                        currentHeadingDeg = lastStableHeadingDeg
                    } else if (isGnssAvailable) {
                        val currentLoc = loc!!
                        // GPS is ON and available: position is directly based on GPS!
                        mode = NavigationMode.Gnss
                        confidence = 0.98f
                        driftPercent = 0.0f
                        curLat = currentLoc.latitude
                        curLon = currentLoc.longitude
                        currentHeadingDeg = if (currentLoc.hasBearing() && (currentLoc.speed > 0.5f || !currentLoc.hasSpeed())) {
                            lastStableHeadingDeg = currentLoc.bearing
                            currentLoc.bearing
                        } else {
                            lastStableHeadingDeg
                        }
                    } else {
                        // GPS is OFF / lost (tunnel, settings off, debug outage): works through IMU sensors!
                        val outageDist = max(0.0, totalDistTraveled - outageStartDist)
                        val sigmaPos = sqrt(max(0.01, fusion.pPos))

                        // Physically meaningful drift percentage: (position uncertainty / distance traveled) * 100%
                        driftPercent = if (outageDist < 1.0) {
                            0.0f
                        } else {
                            ((sigmaPos / outageDist) * 100.0).toFloat().coerceIn(0.1f, 99.9f)
                        }

                        // Confidence decays gracefully with accumulated position uncertainty
                        val decayFactor = exp(-min(10.0, sigmaPos / 40.0))
                        val baseConf = aiPrediction.confidence.toDouble().coerceIn(0.60, 0.95)
                        val effectiveConf = (baseConf * decayFactor).toFloat().coerceIn(0.15f, 0.95f)
                        confidence = effectiveConf

                        mode = if (isOutageActive || !sensorCollector.isLocationHardwareEnabled) {
                            // Enforce dead-reckoning mode with real sensors as source
                            NavigationMode.DeadReckoning
                        } else if (effectiveConf >= 0.70f) {
                            NavigationMode.Fused
                        } else {
                            NavigationMode.DeadReckoning
                        }

                        // Compute dead-reckoned coordinates from IMU filter relative to last known GPS fix
                        val (drLat, drLon) = enuToGeodetic(matchedX, matchedY, refLat!!, refLon!!)
                        curLat = drLat
                        curLon = drLon
                        currentHeadingDeg = Math.toDegrees(fusion.psi).toFloat()
                    }

                    // Update breadcrumb trail
                    if (curLat != 0.0 && curLon != 0.0) {
                        synchronized(trailBuffer) {
                            val lastPt = trailBuffer.lastOrNull()
                            if (lastPt == null || hypot((curLat - lastPt.first) * 111000.0, (curLon - lastPt.second) * 85000.0) > 1.0) {
                                trailBuffer.add(Pair(curLat, curLon))
                                if (trailBuffer.size > 60) trailBuffer.removeAt(0)
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
                        vehicleYawRate = vehicleHeadingRate,
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
                        vehicleYawRate = vehicleHeadingRate,
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
