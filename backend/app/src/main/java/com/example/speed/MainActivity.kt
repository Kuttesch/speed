package com.example.speed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat

/**
 * Main entry point of the app. Handles permissions, UI, and location binding.
 */
class MainActivity : ComponentActivity() {
    private val latitude = mutableStateOf<Double?>(null)
    private val longitude = mutableStateOf<Double?>(null)
    private val speedLimit = mutableStateOf<String?>(null)

    private lateinit var locationService: LocationService

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            Log.d("MainActivity", "Permission granted, starting location updates.")
            locationService.start()
        } else {
            Log.d("MainActivity", "Permission denied.")
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called.")

        locationService = LocationService(
            context = this,
            onUpdate = { lat, lon, speed ->
                Log.d("MainActivity", "Location updated: $lat, $lon, speed limit: $speed")
                latitude.value = lat
                longitude.value = lon
                speedLimit.value = speed
            }
        )

        setContent {
            LocationApp(
                latitude = latitude.value,
                longitude = longitude.value,
                speedLimit = speedLimit.value,
                onGetFromLocal = { requestLocationPermission() }
            )
        }
    }

    /**
     * Requests fine location permission if not already granted.
     */
    private fun requestLocationPermission() {
        Log.d("MainActivity", "Requesting location permissions...")
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("MainActivity", "Permission already granted.")
            locationService.start()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "Stopping location service.")
        locationService.stop()
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewLocationApp() {
    LocationApp(
        latitude = 48.1372,
        longitude = 11.5756,
        speedLimit = "50",
        onGetFromLocal = {}
    )
}
