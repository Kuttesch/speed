package com.example.speed

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import okhttp3.*
import org.json.JSONObject
import java.io.*
import kotlin.math.*
import kotlinx.coroutines.*


data class RoadSegment(
    val id: Long,
    val maxspeed: String,
    val geometry: List<List<Double>> // [lon, lat]
)

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var latitude: MutableState<Double?> = mutableStateOf(null)
    private var longitude: MutableState<Double?> = mutableStateOf(null)
    private var speedLimit: MutableState<String?> = mutableStateOf(null)
    private var source: MutableState<String?> = mutableStateOf(null)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            when {
                permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                    getCurrentLocation(true)
                }
                permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                    getCurrentLocation(true)
                } else -> {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            LocationApp(
                latitude = latitude.value,
                longitude = longitude.value,
                speedLimit = speedLimit.value,
                source = source.value,
                onGetFromAPI = { requestLocationPermission(true) },
                onGetFromLocal = { requestLocationPermission(false) }
            )
        }
    }

    private fun requestLocationPermission(useApi: Boolean) {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                getCurrentLocation(useApi)
            }
            else -> {
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation(useApi: Boolean) {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    latitude.value = location.latitude
                    longitude.value = location.longitude

                    if (useApi) {
                        fetchSpeedLimitAPI(location.latitude, location.longitude)
                    } else {
                        // Run local lookup off the UI thread
                        CoroutineScope(Dispatchers.IO).launch {
                            val localSpeed = findNearestSpeedFromJson(
                                context = this@MainActivity,
                                lat = location.latitude,
                                lon = location.longitude
                            )
                            withContext(Dispatchers.Main) {
                                speedLimit.value = localSpeed ?: "Not found"
                                source.value = "Local"
                            }
                        }
                    }
                } else {
                    Toast.makeText(this, "Could not get location. Is GPS enabled?", Toast.LENGTH_SHORT).show()
                }
            }
    }


    private fun fetchSpeedLimitAPI(lat: Double, lon: Double) {
        val url =
            "https://overpass-api.de/api/interpreter?data=[out:json];way(around:20,$lat,$lon)[highway];out tags;"
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SpeedLimit", "API call failed: ${e.message}")
                runOnUiThread {
                    speedLimit.value = "Unavailable"
                    source.value = "API (failed)"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (responseBody == null || !response.isSuccessful) {
                    runOnUiThread {
                        speedLimit.value = "Unavailable"
                        source.value = "API (bad response)"
                    }
                    return
                }

                try {
                    val jsonObj = JSONObject(responseBody)
                    val elements = jsonObj.optJSONArray("elements")
                    var limit: String? = null

                    if (elements != null) {
                        for (i in 0 until elements.length()) {
                            val tags = elements.getJSONObject(i).optJSONObject("tags")
                            if (tags != null && tags.has("maxspeed")) {
                                limit = tags.getString("maxspeed")
                                break
                            }
                        }
                    }

                    runOnUiThread {
                        speedLimit.value = limit ?: "Not found"
                        source.value = "API"
                    }
                } catch (e: Exception) {
                    Log.e("SpeedLimit", "Parsing error: ${e.message}")
                    runOnUiThread {
                        speedLimit.value = "Parse error"
                        source.value = "API"
                    }
                }
            }
        })
    }

    private fun findNearestSpeedFromJson(
        context: Context,
        lat: Double,
        lon: Double,
        maxDistanceMeters: Double = 50.0
    ): String? {
        return try {
            val inputStream = context.assets.open("bavaria_maxspeed.json")
            val reader = JsonReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val gson = Gson()

            var closestSpeed: String? = null
            var minDist = Double.MAX_VALUE

            reader.beginArray()
            while (reader.hasNext()) {
                val road = gson.fromJson<RoadSegment>(reader, RoadSegment::class.java)
                for (point in road.geometry) {
                    val dist = haversine(lat, lon, point[1], point[0])
                    if (dist < minDist && dist <= maxDistanceMeters) {
                        minDist = dist
                        closestSpeed = road.maxspeed
                    }
                }
            }
            reader.endArray()
            reader.close()
            closestSpeed
        } catch (e: Exception) {
            Log.e("SpeedLimit", "Failed to stream local file: ${e.message}")
            null
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }
}

@Composable
fun LocationApp(
    latitude: Double?,
    longitude: Double?,
    speedLimit: String?,
    source: String?,
    onGetFromAPI: () -> Unit,
    onGetFromLocal: () -> Unit
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(onClick = onGetFromAPI) {
                    Text("Get Speed via API")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onGetFromLocal) {
                    Text("Get Speed from Local")
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text("Latitude: ${latitude ?: "Loading..."}")
                Text("Longitude: ${longitude ?: "Loading..."}")
                Text("Speed Limit: ${speedLimit ?: "Loading..."}")
                if (source != null) Text("Source: $source")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    LocationApp(
        latitude = 48.8588,
        longitude = 2.2943,
        speedLimit = "50",
        source = "Local",
        onGetFromAPI = {},
        onGetFromLocal = {}
    )
}
