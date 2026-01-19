package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.local.bridge.NativeLocationBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class JvmLocationService : LocationService {
    private val defaultLocation = GeoPoint(lat = 37.7749, lon = -122.4194)
    private val nativeEnabled = System.getProperty("location.native")?.lowercase() == "macos"
    private val nativeAvailable = if (nativeEnabled) {
        println("JvmLocationService: native mode enabled, initializing...")
        NativeLocationBridge.init()
    } else {
        println("JvmLocationService: native mode disabled, using IP fallback")
        false
    }

    override suspend fun getCurrentLocation(): GeoPoint {
        if (nativeAvailable) {
            NativeLocationBridge.getCurrentLocation()?.let { (lat, lon) ->
                println("JvmLocationService: got native location ($lat, $lon)")
                return GeoPoint(lat, lon)
            }
            println("JvmLocationService: native location failed, falling back to IP")
        }

        val ipLocation = getIpBasedLocation()
        if (ipLocation != null) {
            println("JvmLocationService: got IP-based location (${ipLocation.lat}, ${ipLocation.lon})")
            return ipLocation
        }

        // Last resort: return default location
        println("JvmLocationService: all methods failed, returning default")
        return defaultLocation
    }

    override fun locationUpdates(): Flow<GeoPoint> = flow {
        emit(getCurrentLocation())
    }

    override suspend fun hasLocationPermission(): Boolean {
        return if (nativeAvailable) {
            NativeLocationBridge.hasPermission()
        } else {
            // IP geolocation doesn't require permission
            true
        }
    }

    override suspend fun requestLocationPermission() {
        if (nativeAvailable) {
            NativeLocationBridge.requestPermission()
        }
        // IP fallback doesn't need permission
    }

    /**
     * Get approximate location from IP address using ip-api.com.
     * Returns city-level accuracy (typically within a few km).
     */
    private suspend fun getIpBasedLocation(): GeoPoint? = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://ip-api.com/json/?fields=lat,lon,status")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                // Simple JSON parsing (avoid adding dependencies)
                if (response.contains("\"status\":\"success\"")) {
                    val lat = extractJsonDouble(response, "lat")
                    val lon = extractJsonDouble(response, "lon")
                    if (lat != null && lon != null) {
                        return@withContext GeoPoint(lat, lon)
                    }
                }
            }
            null
        } catch (e: Exception) {
            println("JvmLocationService: IP geolocation failed: ${e.message}")
            null
        }
    }

    private fun extractJsonDouble(json: String, key: String): Double? {
        // Match "key":number (handles both "lat":37.7 and "lat": 37.7)
        val regex = """"$key"\s*:\s*(-?\d+\.?\d*)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
