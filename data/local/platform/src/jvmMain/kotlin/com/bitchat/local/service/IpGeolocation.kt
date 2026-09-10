package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Approximate location from the public IP address, over HTTPS.
 *
 * This is a coarse answer and is meant to be one: the providers below resolve an address to the
 * city its ISP registered, which for a residential connection is routinely tens of kilometres from
 * where the user actually is. It exists so the channel list has something in it on a machine with
 * no GPS, not because it is accurate.
 *
 * Deliberately connects direct, with [Proxy.NO_PROXY] stated rather than left to the JVM default.
 * Sending this through Tor would report the exit node's city -- confidently and with no sign that
 * it was wrong -- so the lookup cannot be proxied and still mean anything. That makes it inherently
 * a request that reveals the user's address to a third party, which is why the caller gates it and
 * why every provider here is HTTPS. The previous implementation used cleartext http://ip-api.com,
 * whose free tier still refuses TLS with a 403.
 */
internal object IpGeolocation {

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /**
     * Tried in order, first plausible answer wins. More than one because these are free endpoints
     * with rate limits and outages, and a single one going down should not take location with it.
     */
    private val providers = listOf(
        "https://ipwho.is/",
        "https://api.ipbase.com/v1/json/",
        "https://get.geojs.io/v1/ip/geo.json"
    )

    suspend fun lookup(): GeoPoint? = withContext(Dispatchers.IO) {
        for (provider in providers) {
            val point = runCatching { query(provider) }.getOrElse { e ->
                println("IpGeolocation: $provider failed: ${e.message}")
                null
            }
            if (point != null) {
                println("IpGeolocation: $provider -> ${point.lat}, ${point.lon}")
                return@withContext point
            }
        }
        println("IpGeolocation: no provider returned a usable answer")
        null
    }

    private fun query(provider: String): GeoPoint? {
        val connection = (URL(provider).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }

        try {
            val code = connection.responseCode
            if (code != 200) {
                println("IpGeolocation: $provider returned HTTP $code")
                return null
            }
            val body = connection.inputStream.bufferedReader().readText()
            return parse(body) ?: null.also {
                // Without this a provider that answers 200 with something unusable drops out of
                // the chain silently, and the only visible symptom is a different provider's
                // coordinates appearing for no stated reason.
                val excerpt = body.replace(Regex("\\s+"), " ").take(160)
                println("IpGeolocation: $provider gave no usable coordinate: $excerpt")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Separated from the transport so the provider-specific response shapes can be tested. */
    internal fun parse(body: String): GeoPoint? {
        /*
         * ipwho.is reports a lookup it could not resolve as 200 with success:false, so the flag has
         * to be read rather than the status code trusted. Matched with a regex because it
         * pretty-prints its JSON -- the field arrives as `"success": true`, with a space, and a
         * literal contains("\"success\":true") silently rejected every answer it ever gave.
         */
        if (SUCCESS_FIELD.containsMatchIn(body) && !SUCCESS_TRUE.containsMatchIn(body)) return null

        val lat = extractDouble(body, "latitude") ?: extractDouble(body, "lat")
        val lon = extractDouble(body, "longitude") ?: extractDouble(body, "lon")
        if (lat == null || lon == null) return null

        return GeoPoint(lat, lon).takeIf { isPlausible(it) }
    }

    /**
     * Quotes are optional because the providers disagree: geojs.io returns its coordinates as JSON
     * strings ("latitude":"37.7308") where the others return numbers.
     */
    private fun extractDouble(json: String, key: String): Double? {
        val regex = """"$key"\s*:\s*"?(-?\d+(?:\.\d+)?)"?""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * Rejects the two answers that mean "no idea" rather than a place. Null Island is the usual
     * shape of a missing value, and 37.751/-97.822 is the geographic centre of the United States,
     * which several databases hand back for any US address they cannot place -- it is a field in
     * Kansas, and putting a user there is worse than admitting to no fix.
     */
    private fun isPlausible(point: GeoPoint): Boolean {
        if (point.lat !in -90.0..90.0 || point.lon !in -180.0..180.0) return false
        if (point.lat == 0.0 && point.lon == 0.0) return false
        if (isNear(point, lat = 37.751, lon = -97.822)) return false
        return true
    }

    private val SUCCESS_FIELD = Regex(""""success"\s*:""")
    private val SUCCESS_TRUE = Regex(""""success"\s*:\s*true""")

    private fun isNear(point: GeoPoint, lat: Double, lon: Double, epsilon: Double = 0.01): Boolean =
        kotlin.math.abs(point.lat - lat) < epsilon && kotlin.math.abs(point.lon - lon) < epsilon
}
