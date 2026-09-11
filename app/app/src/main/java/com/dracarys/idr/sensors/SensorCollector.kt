package com.dracarys.idr.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

private const val TAG = "SensorCollector"

/**
 * Universal Android Sensor & Location Collector.
 *
 * Captures:
 * - Accelerometer (Sensor.TYPE_ACCELEROMETER) with automatic gravity decomposition fallback
 * - Gravity (Sensor.TYPE_GRAVITY) if available
 * - Linear Acceleration (Sensor.TYPE_LINEAR_ACCELERATION) if available
 * - Gyroscope (Sensor.TYPE_GYROSCOPE)
 * - Multi-provider real GPS & Network Location updates (GPS, FUSED, NETWORK, PASSIVE)
 */
class SensorCollector(private val context: Context) : SensorEventListener, LocationListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // Latest sensor snapshots
    val gravity = FloatArray(3) { if (it == 2) 9.80665f else 0f }
    val linearAcc = FloatArray(3)
    val gyro = FloatArray(3)
    val rawAccel = FloatArray(3)

    var hasGravityHardwareSensor: Boolean = false
        private set
    var hasLinearAccHardwareSensor: Boolean = false
        private set
    var hasGyroSample: Boolean = false
        private set
    var hasAccSample: Boolean = false
        private set

    // Latest GPS state
    var latestLocation: Location? = null
        private set
    var isFreshGps: Boolean = false
    var lastGpsTimestampMs: Long = 0L
        private set

    private var isLocationRegistered: Boolean = false

    fun start() {
        sensorManager?.let { sm ->
            // 1. Raw Accelerometer — universally present on 100% of Android phones
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }

            // 2. Hardware Gravity if OEM HAL supports it
            val gravSensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            if (gravSensor != null) {
                hasGravityHardwareSensor = true
                sm.registerListener(this, gravSensor, SensorManager.SENSOR_DELAY_GAME)
            }

            // 3. Hardware Linear Accel if OEM HAL supports it
            val linAccSensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            if (linAccSensor != null) {
                hasLinearAccHardwareSensor = true
                sm.registerListener(this, linAccSensor, SensorManager.SENSOR_DELAY_GAME)
            }

            // 4. Gyroscope
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        tryStartLocation()
    }

    @SuppressLint("MissingPermission")
    fun tryStartLocation() {
        locationManager?.let { lm ->
            try {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.FUSED_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )

                // 1. Immediately pick up best available last known location across all providers
                if (latestLocation == null) {
                    var bestLocation: Location? = null
                    for (provider in providers) {
                        try {
                            val loc = lm.getLastKnownLocation(provider)
                            if (loc != null) {
                                if (bestLocation == null || loc.time > bestLocation.time) {
                                    bestLocation = loc
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    if (bestLocation != null) {
                        onLocationChanged(bestLocation)
                        Log.i(TAG, "Acquired initial device location: ${bestLocation.latitude}, ${bestLocation.longitude} via ${bestLocation.provider}")
                    }
                }

                // 2. Register for live location updates across all active providers (do not spam)
                if (!isLocationRegistered) {
                    val activeProviders = providers.filter {
                        try { lm.isProviderEnabled(it) } catch (_: Exception) { false }
                    }
                    for (provider in activeProviders) {
                        try {
                            lm.requestLocationUpdates(
                                provider,
                                500L, // 2 Hz update rate
                                0f,
                                this,
                                android.os.Looper.getMainLooper()
                            )
                            isLocationRegistered = true
                            Log.i(TAG, "Registered live location updates on provider: $provider")
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not register provider $provider: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Location permission not yet granted: ${e.message}")
            }
        }
    }

    fun refreshLocation() {
        isLocationRegistered = false
        tryStartLocation()
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        try {
            isLocationRegistered = false
            locationManager?.removeUpdates(this)
        } catch (_: SecurityException) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                synchronized(rawAccel) {
                    System.arraycopy(event.values, 0, rawAccel, 0, 3)
                    hasAccSample = true

                    // Fallback gravity filter if hardware TYPE_GRAVITY is missing or unresponsive
                    if (!hasGravityHardwareSensor) {
                        synchronized(gravity) {
                            val alpha = 0.92f
                            gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0]
                            gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1]
                            gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2]
                        }
                    }

                    // Fallback linear acceleration if hardware TYPE_LINEAR_ACCELERATION is missing
                    if (!hasLinearAccHardwareSensor) {
                        synchronized(linearAcc) {
                            linearAcc[0] = event.values[0] - gravity[0]
                            linearAcc[1] = event.values[1] - gravity[1]
                            linearAcc[2] = event.values[2] - gravity[2]
                        }
                    }
                }
            }
            Sensor.TYPE_GRAVITY -> {
                synchronized(gravity) {
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                synchronized(linearAcc) {
                    System.arraycopy(event.values, 0, linearAcc, 0, 3)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                synchronized(gyro) {
                    System.arraycopy(event.values, 0, gyro, 0, 3)
                    hasGyroSample = true
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(location: Location) {
        synchronized(this) {
            latestLocation = location
            isFreshGps = true
            lastGpsTimestampMs = System.currentTimeMillis()
        }
    }

    val isLocationHardwareEnabled: Boolean
        get() {
            val lm = locationManager ?: return false
            return try {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (_: Exception) { false }
        }

    val isGpsActive: Boolean
        get() {
            if (!isLocationHardwareEnabled) return false
            return (System.currentTimeMillis() - lastGpsTimestampMs) < 3000L && latestLocation != null
        }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {
        Log.i(TAG, "Location provider enabled: $provider")
        refreshLocation()
    }
    override fun onProviderDisabled(provider: String) {
        Log.i(TAG, "Location provider disabled: $provider")
        if (!isLocationHardwareEnabled) {
            isFreshGps = false
        }
    }
}
