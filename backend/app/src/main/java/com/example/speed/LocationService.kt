package com.example.speed

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/**
 * Service to handle continuous location updates and nearby speed limit lookup.
 *
 * @param context Android context used for system services and DB access.
 * @param onUpdate Callback with latest latitude, longitude, and matched speed limit.
 */
class LocationService(
    private val context: Context,
    private val onUpdate: (Double, Double, String) -> Unit
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private lateinit var locationCallback: LocationCallback
    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        1000L // update interval in ms
    ).build()

    private var job: Job? = null

    /**
     * Starts location updates and triggers database queries on each fix.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        Log.d("LocationService", "Starting location updates...")
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val lat = loc.latitude
                val lon = loc.longitude

                Log.d("LocationService", "Got location: $lat, $lon")

                job?.cancel()
                job = CoroutineScope(Dispatchers.IO).launch {
                    val speed = queryDB(lat, lon)
                    withContext(Dispatchers.Main) {
                        Log.d("LocationService", "DB lookup result: $speed")
                        onUpdate(lat, lon, speed)
                    }
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    /**
     * Stops location updates and cancels any ongoing DB lookups.
     */
    fun stop() {
        Log.d("LocationService", "Stopping location updates...")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        job?.cancel()
    }

    /**
     * Queries the local SQLite database for the closest speed limit near a coordinate.
     *
     * @param lat Latitude in degrees.
     * @param lon Longitude in degrees.
     * @param radius Search radius in meters (default: 10).
     * @return Speed limit as a string, or "Not found".
     */
    private fun queryDB(lat: Double, lon: Double, radius: Double = 100.0): String {
        val dbFile = "speed_limits.sqlite"
        val dbPath = File(context.filesDir, dbFile)

        if (!dbPath.exists()) {
            Log.d("LocationService", "Copying DB from assets...")
            context.assets.open(dbFile).use { input ->
                FileOutputStream(dbPath).use { output -> input.copyTo(output) }
            }
        }

        val db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)

        val latDelta = radius / 111000.0
        val lonDelta = radius / (111320.0 * cos(Math.toRadians(lat)))

        val minLat = lat - latDelta
        val maxLat = lat + latDelta
        val minLon = lon - lonDelta
        val maxLon = lon + lonDelta

        val query = """
            SELECT speed_limit, lat, lon
            FROM speed_limits
            WHERE lat BETWEEN ? AND ?
              AND lon BETWEEN ? AND ?
        """.trimIndent()

        Log.d("LocationService", "Executing spatial query...")
        val cursor = db.rawQuery(query, arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString()))

        var nearest: Pair<String, Double>? = null
        while (cursor.moveToNext()) {
            val speed = cursor.getInt(0).toString()
            val rowLat = cursor.getDouble(1)
            val rowLon = cursor.getDouble(2)
            val dist = haversine(lat, lon, rowLat, rowLon)

            if (dist <= radius && (nearest == null || dist < nearest.second)) {
                nearest = speed to dist
            }
        }

        cursor.close()
        db.close()

        if (nearest == null) {
            Log.d("LocationService", "No nearby speed limit found.")
        } else {
            Log.d("LocationService", "Closest speed found: ${nearest.first}")
        }

        return nearest?.first ?: "Not found"
    }

    /**
     * Calculates the great-circle distance between two lat/lon points using Haversine formula.
     *
     * @return Distance in meters.
     */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
