package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

internal val IP_LOCATION_PROVIDERS = listOf(
    "https://ipwho.is/",
    "https://api.ipbase.com/v1/json/",
    "https://get.geojs.io/v1/ip/geo.json",
)

internal fun proxyNamedInEnvironment(getenv: (String) -> String?): Boolean =
    listOf("https_proxy", "HTTPS_PROXY", "all_proxy", "ALL_PROXY", "http_proxy").any { name ->
        !getenv(name).isNullOrBlank()
    }

internal fun parseIpLocation(body: String): GeoPoint? {
    // A cut-off answer can hold both fields with a number shortened; it does not end with the
    // closing brace. Not handed to a JSON parser: nesting within the size limit overflows its stack.
    if (!beginsAndEndsAsObject(body)) return null
    // ipwho.is answers 200 with success: false when it could not resolve.
    if (SUCCESS_FIELD.containsMatchIn(body) && !SUCCESS_TRUE.containsMatchIn(body)) return null

    val lat = extractDouble(body, "latitude") ?: extractDouble(body, "lat")
    val lon = extractDouble(body, "longitude") ?: extractDouble(body, "lon")
    if (lat == null || lon == null) return null

    return GeoPoint(lat, lon).takeIf(::isPlausible)
}

internal suspend fun lookUpIpLocation(
    admit: () -> Boolean,
    get: suspend (url: String, admit: () -> Boolean) -> String?,
    log: (String) -> Unit = ::println,
    providers: List<String> = IP_LOCATION_PROVIDERS,
): GeoPoint? {
    for (provider in providers) {
        if (!admit()) {
            log("IpLocation: lookup not permitted, $provider not asked")
            return null
        }
        val body = try {
            get(provider, admit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("IpLocation: $provider ${error::class.simpleName}")
            continue
        }
        val point = body?.let(::parseIpLocation)
        if (point != null) {
            log("IpLocation: $provider answered")
            return point
        }
        log("IpLocation: $provider no usable coordinate")
    }
    return null
}

private fun beginsAndEndsAsObject(body: String): Boolean {
    val text = body.trim()
    return text.startsWith('{') && text.endsWith('}')
}

private fun extractDouble(json: String, key: String): Double? {
    val regex = """"$key"\s*:\s*"?(-?\d+(?:\.\d+)?)"?""".toRegex()
    return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
}

private fun isPlausible(point: GeoPoint): Boolean {
    if (point.lat !in -90.0..90.0 || point.lon !in -180.0..180.0) return false
    if (point.lat == 0.0 && point.lon == 0.0) return false
    // Several databases answer the centre of the United States for an address they cannot place.
    return !isNear(point, 37.751, -97.822)
}

private fun isNear(point: GeoPoint, lat: Double, lon: Double, epsilon: Double = 0.01): Boolean =
    abs(point.lat - lat) < epsilon && abs(point.lon - lon) < epsilon

private val SUCCESS_FIELD = Regex(""""success"\s*:""")
private val SUCCESS_TRUE = Regex(""""success"\s*:\s*true""")
