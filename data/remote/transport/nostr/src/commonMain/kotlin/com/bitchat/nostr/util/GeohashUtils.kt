package com.bitchat.nostr.util

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeohashUtils {
    private val base32Chars = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray()
    private val charToValue: Map<Char, Int> = base32Chars.withIndex().associate { it.value to it.index }
    private val BIT_MASKS = intArrayOf(16, 8, 4, 2, 1)

    // Neighbor lookup tables for the standard geohash neighbor algorithm
    private val NEIGHBOR_MAP = mapOf(
        'n' to mapOf(
            "even" to "p0r21436x8zb9dcf5h7kjnmqesgutwvy",
            "odd" to "bc01fg45238967deuvhjyznpkmstqrwx"
        ),
        's' to mapOf(
            "even" to "14365h7k9dcfesgujnmqp0r2twvyx8zb",
            "odd" to "238967debc01fg45kmstqrwxuvhjyznp"
        ),
        'e' to mapOf(
            "even" to "bc01fg45238967deuvhjyznpkmstqrwx",
            "odd" to "p0r21436x8zb9dcf5h7kjnmqesgutwvy"
        ),
        'w' to mapOf(
            "even" to "238967debc01fg45kmstqrwxuvhjyznp",
            "odd" to "14365h7k9dcfesgujnmqp0r2twvyx8zb"
        )
    )

    private val BORDER_MAP = mapOf(
        'n' to mapOf("even" to "prxz", "odd" to "bcfguvyz"),
        's' to mapOf("even" to "028b", "odd" to "0145hjnp"),
        'e' to mapOf("even" to "bcfguvyz", "odd" to "prxz"),
        'w' to mapOf("even" to "0145hjnp", "odd" to "028b")
    )

    data class Bounds(
        val latMin: Double,
        val latMax: Double,
        val lonMin: Double,
        val lonMax: Double
    ) {
        val centerLat: Double get() = (latMin + latMax) / 2
        val centerLon: Double get() = (lonMin + lonMax) / 2
        val latHeight: Double get() = latMax - latMin
        val lonWidth: Double get() = lonMax - lonMin
    }

    /**
     * Encodes the provided coordinates into a geohash string.
     *
     * @param latitude Latitude in degrees (-90...90)
     * @param longitude Longitude in degrees (-180...180)
     * @param precision Number of geohash characters (1-12 typical, max 20)
     * @return Base32 geohash string of length `precision`
     * @throws IllegalArgumentException if precision is invalid
     */
    fun encode(latitude: Double, longitude: Double, precision: Int): String {
        require(precision > 0) { "Precision must be positive, got: $precision" }
        require(precision <= 20) { "Precision should not exceed 20, got: $precision" }

        var latInterval = -90.0 to 90.0
        var lonInterval = -180.0 to 180.0

        var isEven = true
        var bit = 0
        var ch = 0
        val geohash = StringBuilder(precision)

        val lat = latitude.coerceIn(-90.0, 90.0)
        val lon = longitude.coerceIn(-180.0, 180.0)

        while (geohash.length < precision) {
            if (isEven) {
                val mid = (lonInterval.first + lonInterval.second) / 2
                if (lon >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonInterval = mid to lonInterval.second
                } else {
                    lonInterval = lonInterval.first to mid
                }
            } else {
                val mid = (latInterval.first + latInterval.second) / 2
                if (lat >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latInterval = mid to latInterval.second
                } else {
                    latInterval = latInterval.first to mid
                }
            }

            isEven = !isEven
            if (bit < 4) {
                bit++
            } else {
                geohash.append(base32Chars[ch])
                bit = 0
                ch = 0
            }
        }

        return geohash.toString()
    }

    /**
     * Safely encodes coordinates, returning null if encoding fails.
     *
     * @return Geohash string or null if parameters are invalid
     */
    fun encodeOrNull(latitude: Double, longitude: Double, precision: Int): String? =
        runCatching { encode(latitude, longitude, precision) }.getOrNull()

    /**
     * Decodes a geohash string to the center latitude/longitude of its cell.
     *
     * @param geohash Valid geohash string
     * @return Pair(latitude, longitude)
     * @throws IllegalArgumentException if geohash is invalid
     */
    fun decodeToCenter(geohash: String): Pair<Double, Double> {
        val bounds = decodeToBounds(geohash)
        return bounds.centerLat to bounds.centerLon
    }

    /**
     * Safely decodes a geohash, returning null if decoding fails.
     *
     * @return Pair(latitude, longitude) or null if geohash is invalid
     */
    fun decodeToCenterOrNull(geohash: String): Pair<Double, Double>? =
        runCatching { decodeToCenter(geohash) }.getOrNull()

    /**
     * Decodes a geohash string to its bounding box (lat/lon min/max).
     *
     * @param geohash Valid geohash string
     * @return Bounds object containing the geohash cell boundaries
     * @throws IllegalArgumentException if geohash is invalid
     */
    fun decodeToBounds(geohash: String): Bounds {
        require(geohash.isNotEmpty()) { "Geohash cannot be empty" }

        val normalized = geohash.lowercase()
        require(normalized.all { it in charToValue }) {
            "Invalid geohash characters in: $geohash"
        }

        var latInterval = -90.0 to 90.0
        var lonInterval = -180.0 to 180.0
        var isEven = true

        normalized.forEach { ch ->
            val cd = charToValue[ch]!!
            for (mask in BIT_MASKS) {
                if (isEven) {
                    val mid = (lonInterval.first + lonInterval.second) / 2
                    lonInterval = if ((cd and mask) != 0) {
                        mid to lonInterval.second
                    } else {
                        lonInterval.first to mid
                    }
                } else {
                    val mid = (latInterval.first + latInterval.second) / 2
                    latInterval = if ((cd and mask) != 0) {
                        mid to latInterval.second
                    } else {
                        latInterval.first to mid
                    }
                }
                isEven = !isEven
            }
        }

        return Bounds(
            latMin = latInterval.first,
            latMax = latInterval.second,
            lonMin = lonInterval.first,
            lonMax = lonInterval.second
        )
    }

    /**
     * Safely decodes a geohash to bounds, returning null if decoding fails.
     */
    fun decodeToBoundsOrNull(geohash: String): Bounds? =
        runCatching { decodeToBounds(geohash) }.getOrNull()

    /**
     * Returns all 8 neighboring geohash cells at the same precision.
     * Uses the standard character-based neighbor algorithm for accuracy.
     *
     * @param geohash Valid geohash string
     * @return Set of 8 neighbor geohashes (or fewer near poles)
     * @throws IllegalArgumentException if geohash is invalid
     */
    fun neighbors(geohash: String): Set<String> {
        require(geohash.isNotEmpty()) { "Geohash cannot be empty" }

        val normalized = geohash.lowercase()
        require(normalized.all { it in charToValue }) {
            "Invalid geohash characters in: $geohash"
        }

        return listOfNotNull(
            getNeighbor(normalized, 'n'),
            getNeighbor(normalized, 's'),
            getNeighbor(normalized, 'e'),
            getNeighbor(normalized, 'w'),
            getNeighbor(normalized, 'n')?.let { getNeighbor(it, 'e') },
            getNeighbor(normalized, 'n')?.let { getNeighbor(it, 'w') },
            getNeighbor(normalized, 's')?.let { getNeighbor(it, 'e') },
            getNeighbor(normalized, 's')?.let { getNeighbor(it, 'w') }
        ).toSet()
    }

    /**
     * Returns the neighbor in a specific direction (n, s, e, w).
     *
     * @param geohash Valid geohash string
     * @param direction One of 'n', 's', 'e', 'w'
     * @return Neighbor geohash or null if invalid
     */
    fun getNeighbor(geohash: String, direction: Char): String? {
        if (geohash.isEmpty()) return null

        val dir = direction.lowercaseChar()
        require(dir in setOf('n', 's', 'e', 'w')) {
            "Direction must be one of n, s, e, w, got: $direction"
        }

        val lastChar = geohash.last()
        val parent = geohash.dropLast(1)
        val type = if (geohash.length % 2 == 0) "even" else "odd"

        // Check if we're at a border and need to adjust the parent
        val newParent = if (BORDER_MAP[dir]?.get(type)?.contains(lastChar) == true) {
            if (parent.isEmpty()) {
                return null // At world boundary
            }
            getNeighbor(parent, dir)
        } else {
            parent
        }

        if (newParent == null) return null

        val charIndex = base32Chars.indexOf(lastChar)
        val neighborChars = NEIGHBOR_MAP[dir]?.get(type) ?: return null

        return newParent + neighborChars[charIndex]
    }

    /**
     * Returns the length of the common prefix between two geohashes.
     * Longer prefix indicates closer proximity.
     *
     * @return Number of matching characters from the start (0 to min length)
     */
    fun commonPrefixLength(hash1: String, hash2: String): Int =
        hash1.zip(hash2).takeWhile { (a, b) ->
            a.lowercaseChar() == b.lowercaseChar()
        }.count()

    /**
     * Checks if two geohashes are neighbors (share a border).
     *
     * @return true if geohashes are adjacent
     */
    fun areNeighbors(hash1: String, hash2: String): Boolean {
        if (hash1.length != hash2.length) return false
        return runCatching {
            neighbors(hash1).contains(hash2.lowercase())
        }.getOrDefault(false)
    }

    /**
     * Returns the approximate error (in meters) for latitude and longitude
     * at a given geohash precision level.
     *
     * @param precision Geohash character length
     * @return Pair of (latitude error in meters, longitude error in meters)
     */
    fun precisionInMeters(precision: Int): Pair<Double, Double> {
        require(precision > 0) { "Precision must be positive" }

        // Each geohash character adds ~2.5 bits of precision (alternating lat/lon)
        // Rough approximation: error halves every 2.5 bits
        val latBits = (precision * 5) / 2 // Approximately half the bits go to latitude
        val lonBits = (precision * 5) - latBits

        // Earth's circumference: ~40,075 km (lat), ~40,075 km at equator (lon)
        val latError = (40075000.0 / 2.0) / (1 shl latBits) // meters
        val lonError = (40075000.0 / 2.0) / (1 shl lonBits) // meters at equator

        return latError to lonError
    }

    /**
     * Calculates the approximate distance between two geohashes using the Haversine formula.
     *
     * @param hash1 First geohash
     * @param hash2 Second geohash
     * @return Distance in meters
     * @throws IllegalArgumentException if either geohash is invalid
     */
    fun approximateDistance(hash1: String, hash2: String): Double {
        val (lat1, lon1) = decodeToCenter(hash1)
        val (lat2, lon2) = decodeToCenter(hash2)
        return haversineDistance(lat1, lon1, lat2, lon2)
    }


    /**
     * Returns all geohashes within a bounding box at a given precision.
     * Warning: Can return a large number of geohashes for low precision or large areas.
     *
     * @param minLat Minimum latitude
     * @param minLon Minimum longitude
     * @param maxLat Maximum latitude
     * @param maxLon Maximum longitude
     * @param precision Geohash precision
     * @param maxResults Maximum number of results to return (default 10000)
     * @return Set of geohashes covering the bounding box
     */
    fun geohashesInBounds(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        precision: Int,
        maxResults: Int = 10000
    ): Set<String> {
        require(minLat <= maxLat) { "minLat must be <= maxLat" }
        require(minLon <= maxLon) { "minLon must be <= maxLon" }

        val geohashes = mutableSetOf<String>()
        val (latError, lonError) = precisionInMeters(precision)

        // Convert meter errors to rough degree steps
        val latStep = latError / 111000.0 // 1 degree lat ≈ 111km
        val lonStep = lonError / (111000.0 * cos((minLat + maxLat) / 2 * PI / 180.0))

        var lat = minLat
        while (lat <= maxLat && geohashes.size < maxResults) {
            var lon = minLon
            while (lon <= maxLon && geohashes.size < maxResults) {
                geohashes.add(encode(lat, lon, precision))
                lon += lonStep
            }
            lat += latStep
        }

        return geohashes
    }

    private fun Double.toRadians(): Double = this * PI / 180

    fun haversineDistance(
        centerLat: Double,
        centerLon: Double,
        targetLat: Double,
        targetLon: Double,
    ): Double {
        // Mean radius of the Earth in kilometers.
        val earthRadius = 6371.0

        // Convert differences in coordinates from degrees to radians.
        val dLat = (targetLat - centerLat).toRadians()
        val dLon = (targetLon - centerLon).toRadians()

        // Apply the Haversine formula.
        val a = sin(dLat / 2).pow(2.0) +
                cos(centerLat.toRadians()) * cos(targetLat.toRadians()) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    /**
     * Validates if a string is a valid geohash.
     *
     * @return true if the string contains only valid base32 geohash characters
     */
    fun isValid(geohash: String): Boolean {
        if (geohash.isEmpty()) return false
        return geohash.lowercase().all { it in charToValue }
    }

    /**
     * Returns the parent geohash (one character shorter).
     *
     * @return Parent geohash or null if input is too short
     */
    fun parent(geohash: String): String? =
        if (geohash.length > 1) geohash.dropLast(1) else null

    /**
     * Returns all 32 children geohashes (one character longer).
     *
     * @param geohash Parent geohash
     * @return Set of 32 child geohashes
     */
    fun children(geohash: String): Set<String> {
        require(geohash.isNotEmpty()) { "Geohash cannot be empty" }
        return base32Chars.map { geohash + it }.toSet()
    }
}