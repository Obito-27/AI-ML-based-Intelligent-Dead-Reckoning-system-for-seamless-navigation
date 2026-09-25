package com.dracarys.idr.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dracarys.idr.ui.NavigationViewModel
import com.dracarys.idr.ui.components.OutageValidationSheet
import com.dracarys.idr.ui.components.SensorDiagnosticDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.Color
import com.dracarys.idr.ui.map.MapViewComposable
import com.dracarys.idr.ui.state.FakeNavigationRepository
import com.dracarys.idr.ui.state.NavigationMode
import com.dracarys.idr.ui.theme.DracarysBackground
import com.dracarys.idr.ui.theme.DracarysCardSurface
import com.dracarys.idr.ui.theme.DracarysGnssTeal
import com.dracarys.idr.ui.theme.DracarysPrimaryText
import com.dracarys.idr.ui.theme.DracarysSecondaryText
import com.dracarys.idr.ui.theme.DracarysTheme
import com.dracarys.idr.ui.theme.DracarysTypography

/**
 * Root navigation screen.
 *
 * Layout:
 * - Full-bleed [MapViewComposable] rendering offline street network, vehicle heading arrow,
 *   breadcrumb trail, and expanding dashed uncertainty corridor.
 * - [InstrumentCapsule] anchored to the bottom, above navigation bars.
 * - Top diagnostic bar with real-time logging indicator, mount calibration status,
 *   and outage validation results sheet trigger.
 */
@Composable
fun NavigationScreen(
    modifier: Modifier = Modifier,
    viewModel: NavigationViewModel = viewModel(factory = NavigationViewModel.Factory()),
    isPermissionDenied: Boolean = false,
    onRequestPermission: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val diagnosticState by viewModel.diagnosticState.collectAsStateWithLifecycle()
    val debugEnabled by viewModel.isDebugOutageActive.collectAsStateWithLifecycle()
    val outages by viewModel.outageHistory.collectAsStateWithLifecycle()
    var showValidationSheet by rememberSaveable { mutableStateOf(false) }
    var showDiagnosticDialog by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DracarysBackground),
    ) {
        // Full-bleed offline map behind the capsule
        MapViewComposable(
            state = state,
            modifier = Modifier.fillMaxSize(),
        )

        // Top diagnostic / validation status bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Live logging status chip (clickable to open sensor diagnostics)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(DracarysCardSurface.copy(alpha = 0.85f))
                    .clickable { showDiagnosticDialog = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(state.mode.color)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "10Hz TELEMETRY",
                        style = DracarysTypography.bodySmall,
                        color = DracarysSecondaryText,
                        fontSize = 11.sp,
                    )
                }
            }

            // Mount Calibration Status Chip (shows when uncalibrated)
            if (!state.isMountCalibrated && state.mode != NavigationMode.AcquiringGps) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF5A623).copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "MOUNT CALIBRATING (>10km/h)",
                        color = Color(0xFFF5A623),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Raw IMU / AI Diagnostic Screen Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF38BDF8).copy(alpha = 0.2f))
                        .clickable { showDiagnosticDialog = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "DIAGNOSTICS",
                        style = DracarysTypography.bodySmall,
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Post-drive outage validation results button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (outages.isNotEmpty()) DracarysGnssTeal.copy(alpha = 0.2f)
                            else DracarysCardSurface.copy(alpha = 0.85f)
                        )
                        .clickable { showValidationSheet = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Outages (${outages.size})",
                        style = DracarysTypography.bodySmall,
                        color = if (outages.isNotEmpty()) DracarysGnssTeal else DracarysPrimaryText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Permission denial warning banner if location is blocked
        if (isPermissionDenied) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DracarysCardSurface.copy(alpha = 0.95f))
                    .padding(16.dp)
            ) {
                androidx.compose.foundation.layout.Column {
                    Text(
                        text = "Location Permission Required",
                        style = DracarysTypography.headlineMedium,
                        color = DracarysPrimaryText,
                        fontSize = 16.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Dracarys IDR needs fine location access to anchor the dead-reckoning filter and calibrate phone mount angles during real driving. Without GPS, the navigation pipeline cannot start.",
                        style = DracarysTypography.bodySmall,
                        color = DracarysSecondaryText,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = onRequestPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = DracarysGnssTeal),
                        ) {
                            Text("Grant Permission", color = DracarysBackground, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onOpenSettings,
                        ) {
                            Text("App Settings", color = DracarysPrimaryText, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Instrument capsule — bottom-anchored
        InstrumentCapsule(
            state = state,
            onDebugToggle = { viewModel.toggleDebugOutage() },
            debugEnabled = debugEnabled,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
        )

        // Bottom sheet displaying completed outage validation history
        if (showValidationSheet) {
            OutageValidationSheet(
                records = outages,
                onDismiss = { showValidationSheet = false },
            )
        }

        // Live sensor diagnostic telemetry dialog
        if (showDiagnosticDialog) {
            SensorDiagnosticDialog(
                state = diagnosticState,
                onDismiss = { showDiagnosticDialog = false },
            )
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

private val fakeRepo = FakeNavigationRepository()

@Preview(name = "NavigationScreen — full layout", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewNavigationScreen() {
    DracarysTheme {
        // Drive the preview with a static ViewModel backed by FakeNavigationRepository
        NavigationScreen(
            viewModel = NavigationViewModel(fakeRepo),
        )
    }
}
