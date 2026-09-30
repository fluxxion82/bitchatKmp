package com.bitchat.design.globe

import com.bitchat.viewvo.world.WorldAtlas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The bundled Natural Earth 110m world (public domain) as flat rings of lat/lon pairs, with the
 * per-point trigonometry the vector globe projects with.
 *
 * The geojson and its parser now live in `presentation:viewvo` ([WorldAtlas]), compiled in as
 * Kotlin source, so the terminal UI can draw the same coastline without depending on Compose.
 * Everything below only adds the globe's projection terms to what that parser returns; the rings,
 * their order and their coordinates are the file's own, unchanged.
 *
 * The data ships with the app on purpose. The picker this replaces fetched Leaflet from unpkg.com
 * and map tiles from basemaps.cartocdn.com inside a WebView, which disclosed the user's IP and
 * every tile coordinate they panned over, outside any proxy.
 */
object LandData {

    data class Ring(
        val coords: FloatArray,
        val size: Int,
        /**
         * Per-point sin(latitude), cos(latitude), sin(longitude), cos(longitude).
         * Preparing this once removes nearly all trigonometry from animated frames.
         */
        val projectionTerms: FloatArray = prepareProjectionTerms(coords, size)
    ) {
        // FloatArray gives identity equals/hashCode, which is wrong for a data class holding one.
        // Rings are compared only in tests, and by content.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ring) return false
            return size == other.size && coords.contentEquals(other.coords)
        }

        override fun hashCode(): Int = 31 * coords.contentHashCode() + size
    }

    data class City(
        val name: String,
        val lat: Float,
        val lon: Float,
        val rank: Int,
        val capital: Boolean,
        val megacity: Boolean,
        val projectionTerms: FloatArray = prepareProjectionTerms(
            floatArrayOf(lat, lon),
            size = 1
        )
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is City) return false
            return name == other.name && lat == other.lat && lon == other.lon &&
                rank == other.rank && capital == other.capital && megacity == other.megacity
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + lat.hashCode()
            result = 31 * result + lon.hashCode()
            result = 31 * result + rank
            return result
        }
    }

    private val landRings: List<Ring> by lazy { WorldAtlas.land.map { Ring(it.coords, it.size) } }
    private val borderLines: List<Ring> by lazy { WorldAtlas.borders.map { Ring(it.coords, it.size) } }
    private val places: List<City> by lazy {
        WorldAtlas.cities.map { City(it.name, it.lat, it.lon, it.rank, it.capital, it.megacity) }
    }

    // These stay suspending: the callers load them from a LaunchedEffect, off the first frame, and
    // whether the work is a resource read or a parse is this object's business, not theirs.

    /** Land polygon rings; each ring is a flat array of (lat, lon) pairs. */
    @Suppress("RedundantSuspendModifier")
    suspend fun load(): List<Ring> = landRings

    /** Country border lines (Natural Earth admin-0 boundary lines, public domain). */
    @Suppress("RedundantSuspendModifier")
    suspend fun loadBorders(): List<Ring> = borderLines

    /** Populated places (Natural Earth, public domain) with name and scale rank. */
    @Suppress("RedundantSuspendModifier")
    suspend fun loadCities(): List<City> = places

    private fun prepareProjectionTerms(coords: FloatArray, size: Int): FloatArray {
        val result = FloatArray(size * 4)
        for (index in 0 until size) {
            val latRadians = coords[index * 2] * PI / 180.0
            val lonRadians = coords[index * 2 + 1] * PI / 180.0
            result[index * 4] = sin(latRadians).toFloat()
            result[index * 4 + 1] = cos(latRadians).toFloat()
            result[index * 4 + 2] = sin(lonRadians).toFloat()
            result[index * 4 + 3] = cos(lonRadians).toFloat()
        }
        return result
    }
}
