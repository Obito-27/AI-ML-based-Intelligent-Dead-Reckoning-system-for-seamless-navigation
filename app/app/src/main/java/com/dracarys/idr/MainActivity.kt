package com.dracarys.idr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.dracarys.idr.sensors.LiveNavigationRepository
import com.dracarys.idr.ui.NavigationViewModel
import com.dracarys.idr.ui.screen.NavigationScreen
import com.dracarys.idr.ui.theme.DracarysTheme

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {

    private val isPermissionDenied = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val fineGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val granted = fineGranted || coarseGranted
        isPermissionDenied.value = !granted
        if (granted) {
            liveViewModel.refreshLocation()
        }
    }

    private val liveViewModel by viewModels<NavigationViewModel> {
        NavigationViewModel.Factory(repo = LiveNavigationRepository(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen awake continuously while in vehicle cradle during test drive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request location permissions if not already granted
        checkAndRequestPermissions()

        setContent {
            DracarysTheme {
                NavigationScreen(
                    viewModel = liveViewModel,
                    isPermissionDenied = isPermissionDenied.value,
                    onRequestPermission = { checkAndRequestPermissions() },
                    onOpenSettings = { openAppSettings() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions when returning from settings
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            isPermissionDenied.value = false
            liveViewModel.refreshLocation()
        }
    }

    private fun checkAndRequestPermissions() {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fineLocation != PackageManager.PERMISSION_GRANTED && coarseLocation != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            isPermissionDenied.value = false
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }
}

