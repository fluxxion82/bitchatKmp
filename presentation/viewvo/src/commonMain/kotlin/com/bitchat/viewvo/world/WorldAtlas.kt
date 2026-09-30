package com.bitchat.viewvo.world

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The bundled Natural Earth 110m world (public domain), parsed once and shared by every UI.
 *
 * The data and this parser used to live in `presentation:design`, loaded through Compose
 * Resources. The terminal UI needs the same land and the same cities and must not depend on
 * Compose, so both moved here, to the one module every presentation layer already depends on, and
 * the geojson is compiled in as Kotlin source (see the `generateWorldData` task) rather than read
 * from a resource that Kotlin/Native has no way to open.
 *
 * Rings are flat arrays of (latitude, longitude) pairs in degrees, in the file's own order and
 * precision. Nothing here projects anything: each renderer projects for itself.
 */
object WorldAtlas {

    /** One closed polygon ring, or one open line, as a flat array of (lat, lon) degree pairs. */
    class Ring(val coords: FloatArray, val size: Int)

    /** A populated place: its name, where it is, and how prominent Natural Earth thinks it is. */
    class Place(
        val name: String,
        val lat: Float,
        val lon: Float,
        /** Natural Earth's scale rank, 0 (most prominent) to 10. */
        val rank: Int,
        val capital: Boolean,
        val megacity: Boolean,
    )

    /** Land polygon rings, outer rings and holes alike, in file order. */
    val land: List<Ring> by lazy { parseGeometries(worldLandJson()) }

    /** Country border lines (Natural Earth admin-0 boundary lines). */
    val borders: List<Ring> by lazy { parseGeometries(worldBordersJson()) }

    /** Populated places, in file order. */
    val cities: List<Place> by lazy { parseCities(worldCitiesJson()) }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Both the land and border files are GeometryCollections; they differ only in whether the
     * members are polygons or line strings, and a polygon is one nesting level deeper.
     */
    internal fun parseGeometries(text: String): List<Ring> {
        val root = json.parseToJsonElement(text).jsonObject
        val geometries = root["geometries"]?.jsonArray ?: return emptyList()
        val out = mutableListOf<Ring>()
        for (element in geometries) {
            val geometry = element.jsonObject
            val coordinates = geometry["coordinates"]?.jsonArray ?: continue
            when (geometry["type"]?.jsonPrimitive?.content) {
                "Polygon" -> parsePolygon(coordinates, out)
                "MultiPolygon" -> coordinates.forEach { parsePolygon(it.jsonArray, out) }
                "LineString" -> parseLine(coordinates, out)
                "MultiLineString" -> coordinates.forEach { parseLine(it.jsonArray, out) }
            }
        }
        return out
    }

    internal fun parseCities(text: String): List<Place> {
        val root = json.parseToJsonElement(text).jsonObject
        val cities = root["cities"]?.jsonArray ?: return emptyList()
        return cities.map { element ->
            val city = element.jsonObject
            Place(
                name = city["n"]?.jsonPrimitive?.content.orEmpty(),
                lat = city["lat"]?.jsonPrimitive?.float ?: 0f,
                lon = city["lon"]?.jsonPrimitive?.float ?: 0f,
                rank = city["r"]?.jsonPrimitive?.int ?: 0,
                capital = (city["cap"]?.jsonPrimitive?.int ?: 0) == 1,
                megacity = (city["mega"]?.jsonPrimitive?.int ?: 0) == 1,
            )
        }
    }

    /** geojson orders each point as [lon, lat]; the rings hold (lat, lon). */
    private fun parseLine(lineJson: JsonArray, out: MutableList<Ring>) {
        val n = lineJson.size
        if (n < 2) return
        val coords = FloatArray(n * 2)
        for (p in 0 until n) {
            val point = lineJson[p].jsonArray
            coords[p * 2] = point[1].jsonPrimitive.float
            coords[p * 2 + 1] = point[0].jsonPrimitive.float
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
                val point = ringJson[p].jsonArray
                coords[p * 2] = point[1].jsonPrimitive.float
                coords[p * 2 + 1] = point[0].jsonPrimitive.float
            }
            out.add(Ring(coords, n))
        }
    }
}
