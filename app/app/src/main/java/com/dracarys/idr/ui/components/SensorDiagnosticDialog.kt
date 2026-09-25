package com.dracarys.idr.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dracarys.idr.ui.state.DiagnosticState
import java.util.Locale
import kotlin.math.abs

@Composable
fun SensorDiagnosticDialog(
    state: DiagnosticState,
    onDismiss: () -> Unit
) {
    val yawRateDegS = Math.toDegrees(state.vehicleYawRate)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F172A),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE SENSOR TELEMETRY",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "10 Hz",
                        color = Color(0xFF4ADE80),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = Color(0xFF334155)
                )

                // 1. RAW SENSORS (Phone Frame)
                DiagnosticSectionHeader("1. RAW IMU (PHONE FRAME)")
                TelemetryRow(
                    "Raw Accel [m/s²]",
                    String.format(
                        Locale.US, "X:%+5.1f Y:%+5.1f Z:%+5.1f",
                        state.rawAcc.getOrElse(0) { 0f },
                        state.rawAcc.getOrElse(1) { 0f },
                        state.rawAcc.getOrElse(2) { 0f }
                    )
                )
                TelemetryRow(
                    "Raw Gyro [rad/s]",
                    String.format(
                        Locale.US, "X:%+5.2f Y:%+5.2f Z:%+5.2f",
                        state.rawGyro.getOrElse(0) { 0f },
                        state.rawGyro.getOrElse(1) { 0f },
                        state.rawGyro.getOrElse(2) { 0f }
                    )
                )
                TelemetryRow(
                    "Gravity [m/s²]",
                    String.format(
                        Locale.US, "X:%+5.1f Y:%+5.1f Z:%+5.1f",
                        state.rawGravity.getOrElse(0) { 0f },
                        state.rawGravity.getOrElse(1) { 0f },
                        state.rawGravity.getOrElse(2) { 0f }
                    )
                )
                TelemetryRow(
                    "Stationary (ZUPT)",
                    if (state.isStationary) "YES (ZUPT ACTIVE)" else "NO (IN MOTION)",
                    valueColor = if (state.isStationary) Color(0xFFFBBF24) else Color(0xFF4ADE80)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 2. LEVELING & CALIBRATION
                DiagnosticSectionHeader("2. LEVELING & CALIBRATION")
                TelemetryRow(
                    "u_up (Unit Grav)",
                    String.format(
                        Locale.US, "[%+4.2f, %+4.2f, %+4.2f]",
                        state.uUp.getOrElse(0) { 0.0 },
                        state.uUp.getOrElse(1) { 0.0 },
                        state.uUp.getOrElse(2) { 1.0 }
                    )
                )
                TelemetryRow(
                    "Mount Azimuth",
                    if (state.isMountCalibrated) String.format(Locale.US, "%.1f° (CONVERGED)", state.mountAzimuthDeg)
                    else String.format(Locale.US, "CALIBRATING (%d smp)", state.mountSampleCount),
                    valueColor = if (state.isMountCalibrated) Color(0xFF4ADE80) else Color(0xFFF5A623)
                )
                TelemetryRow(
                    "Veh. Yaw Rate [deg/s]",
                    String.format(Locale.US, "%+6.2f°/s", yawRateDegS),
                    valueColor = if (abs(yawRateDegS) > 2.0) Color(0xFFF87171) else Color.White
                )
                TelemetryRow(
                    "Body Accel (af, al)",
                    String.format(Locale.US, "fwd:%+4.1f lat:%+4.1f z:%+4.1f", state.af, state.al, state.az)
                )
                TelemetryRow(
                    "Gyro Bias Hat",
                    String.format(Locale.US, "%+6.4f rad/s", state.gyroBiasHat)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 3. AI MOTIONNET INFERENCE
                DiagnosticSectionHeader("3. AI MOTIONNET INFERENCE")
                TelemetryRow(
                    "Engine Status",
                    if (state.isModelLoaded) "ONLINE (Custom Engine)" else "OFFLINE",
                    valueColor = if (state.isModelLoaded) Color(0xFF4ADE80) else Color(0xFFEF4444)
                )
                TelemetryRow(
                    "predict() Calls",
                    String.format(Locale.US, "%d ticks", state.predictCallCount),
                    valueColor = Color(0xFF38BDF8)
                )
                TelemetryRow(
                    "Predicted vf [m/s]",
                    String.format(Locale.US, "%.2f m/s (%.1f km/h)", state.aiVf, state.aiVf * 3.6),
                    valueColor = Color.White
                )
                TelemetryRow(
                    "Gyro Correction",
                    String.format(Locale.US, "%+6.3f rad/s", state.aiGyroCorr),
                    valueColor = Color.White
                )
                TelemetryRow(
                    "Model Confidence",
                    String.format(Locale.US, "%.1f%%", state.aiConfidence * 100.0),
                    valueColor = if (state.aiConfidence > 0.6) Color(0xFF4ADE80) else Color(0xFFFBBF24)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 4. ESKF HEADING & GNSS
                DiagnosticSectionHeader("4. ESKF HEADING & GNSS")
                TelemetryRow(
                    "Fused Heading",
                    String.format(Locale.US, "%05.1f°", state.headingDeg),
                    valueColor = Color(0xFF38BDF8)
                )
                TelemetryRow(
                    "GPS Providers",
                    String.format(
                        Locale.US, "GPS: %s | NET: %s",
                        if (state.isGpsProviderEnabled) "ON" else "OFF",
                        if (state.isNetworkProviderEnabled) "ON" else "OFF"
                    ),
                    valueColor = if (state.isGpsProviderEnabled) Color(0xFF4ADE80) else Color(0xFFEF4444)
                )
                TelemetryRow(
                    "Raw Fix (Lat/Lon)",
                    if (state.rawGpsLat != 0.0 || state.rawGpsLon != 0.0)
                        String.format(Locale.US, "%.5f, %.5f", state.rawGpsLat, state.rawGpsLon)
                    else "NO FIX",
                    valueColor = if (state.rawGpsLat != 0.0) Color.White else Color(0xFFFBBF24)
                )
                TelemetryRow(
                    "Accuracy / Provider",
                    String.format(
                        Locale.US, "±%.1fm (%s)",
                        state.rawGpsAccuracyM,
                        state.gpsProvider
                    ),
                    valueColor = if (state.rawGpsAccuracyM in 0.01f..25.0f) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                )
                TelemetryRow(
                    "GPS Speed / Sats",
                    String.format(Locale.US, "%.1f m/s (%d sats)", state.gpsSpeed, state.gpsSats)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E293B),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("CLOSE DIAGNOSTICS", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticSectionHeader(title: String) {
    Text(
        text = title,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF94A3B8),
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun TelemetryRow(
    label: String,
    value: String,
    valueColor: Color = Color(0xFFE2E8F0)
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color(0xFF64748B)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}
