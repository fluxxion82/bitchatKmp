package com.bitchat.design.globe

import bitchatkmp.presentation.design.generated.resources.Res
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Loads the bundled Natural Earth 110m land polygons (public domain) and exposes them as flat
 * rings of lat/lon pairs for vector globe rendering.
 *
 * Ported from the upstream Android app. Two things changed for multiplatform: the geojson comes
 * from Compose resources rather than Android assets, and parsing uses kotlinx.serialization
 * rather than org.json. Loading is suspending because reading a Compose resource is, so the
 * caller does it once from a LaunchedEffect rather than on a frame.
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

    private val json = Json { ignoreUnknownKeys = true }
    private val loadLock = Mutex()

    private var cached: List<Ring>? = null
    private var cachedBorders: List<Ring>? = null
    private var cachedCities: List<City>? = null

    /** Land polygon rings; each ring is a flat array of (lat, lon) pairs. */
    suspend fun load(): List<Ring> {
        cached?.let { return it }
        return loadLock.withLock {
            cached ?: parseGeometries("files/world_land.geojson", lines = false).also { cached = it }
        }
    }

    /** Country border lines (Natural Earth admin-0 boundary lines, public domain). */
    suspend fun loadBorders(): List<Ring> {
        cachedBorders?.let { return it }
        return loadLock.withLock {
            cachedBorders
                ?: parseGeometries("files/world_borders.geojson", lines = true).also { cachedBorders = it }
        }
    }

    /** Populated places (Natural Earth 50m, public domain) with name and scale rank. */
    suspend fun loadCities(): List<City> {
        cachedCities?.let { return it }
        return loadLock.withLock {
            cachedCities ?: parseCities().also { cachedCities = it }
        }
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun readResource(path: String): String =
        Res.readBytes(path).decodeToString()

    /**
     * Both the land and border files are GeometryCollections; they differ only in whether the
     * members are polygons or line strings, and a polygon is one nesting level deeper.
     */
    private suspend fun parseGeometries(path: String, lines: Boolean): List<Ring> {
        val root = json.parseToJsonElement(readResource(path)).jsonObject
        val geometries = root["geometries"]?.jsonArray ?: return emptyList()
        val out = mutableListOf<Ring>()
        for (element in geometries) {
            val geom = element.jsonObject
            val coordinates = geom["coordinates"]?.jsonArray ?: continue
            when (geom["type"]?.jsonPrimitive?.content) {
                "Polygon" -> parsePolygon(coordinates, out)
                "MultiPolygon" -> coordinates.forEach { parsePolygon(it.jsonArray, out) }
                "LineString" -> parseLine(coordinates, out)
                "MultiLineString" -> coordinates.forEach { parseLine(it.jsonArray, out) }
            }
        }
        return out
    }

    private suspend fun parseCities(): List<City> {
        val root = json.parseToJsonElement(readResource("files/world_cities.geojson")).jsonObject
        val arr = root["cities"]?.jsonArray ?: return emptyList()
        return arr.map { element ->
            val c = element.jsonObject
            City(
                name = c["n"]?.jsonPrimitive?.content.orEmpty(),
                lat = c["lat"]?.jsonPrimitive?.float ?: 0f,
                lon = c["lon"]?.jsonPrimitive?.float ?: 0f,
                rank = c["r"]?.jsonPrimitive?.int ?: 0,
                capital = (c["cap"]?.jsonPrimitive?.int ?: 0) == 1,
                megacity = (c["mega"]?.jsonPrimitive?.int ?: 0) == 1
            )
        }
    }

    /** geojson orders each point as [lon, lat]; the rings hold (lat, lon). */
    private fun parseLine(lineJson: JsonArray, out: MutableList<Ring>) {
        val n = lineJson.size
        if (n < 2) return
        val coords = FloatArray(n * 2)
        for (p in 0 until n) {
            val pt = lineJson[p].jsonArray
            coords[p * 2] = pt[1].jsonPrimitive.float
            coords[p * 2 + 1] = pt[0].jsonPrimitive.float
        }
        out.add(Ring(coords, n))
    }

    private fun parsePolygon(ringsJson: JsonArray, out: MutableList<Ring>) {
        for (r in 0 until ringsJson.size) {
            val ringJson = ringsJson[r].jsonArray
            val n = ringJson.size
            if (n < 3) continue
            val coords = FloatArray(n * 2)
            for (p in 0 until n) {
                val pt = ringJson[p].jsonArray
                coords[p * 2] = pt[1].jsonPrimitive.float
                coords[p * 2 + 1] = pt[0].jsonPrimitive.float
            }
            out.add(Ring(coords, n))
        }
    }

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
