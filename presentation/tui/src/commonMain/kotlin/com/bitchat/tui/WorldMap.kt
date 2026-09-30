package com.bitchat.tui

import com.bitchat.domain.location.geo.Geohash
import com.bitchat.viewvo.world.WorldAtlas
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

// A world map drawn in terminal cells, for the geohash browser.
//
// The browser used to be 32 letters in a box, which asked the user to know the encoding before they
// could pick a place. This draws the coastline behind those letters instead, so the letters become
// regions one can recognise.
//
// Two pixels fit in a cell, stacked, as the half-block glyphs U+2588, U+2580 and U+2584 (the Linux
// console font has these; it has no braille). A window of N cells is therefore N pixels wide and
// twice its rows tall, projected equirectangularly: longitude to x, latitude to y, which is also
// the projection a geohash cell grid divides evenly.

/** A latitude/longitude window, drawn left to right from [lonMin] and top down from [latMax]. */
internal data class MapViewport(
    val latMin: Double,
    val latMax: Double,
    val lonMin: Double,
    val lonMax: Double,
) {
    val latSpan: Double get() = latMax - latMin
    val lonSpan: Double get() = lonMax - lonMin
}

/** What the browser is looking at while it shows [parent]'s children: the whole world, or [parent]. */
internal fun mapViewport(parent: String): MapViewport =
    if (parent.isEmpty()) {
        MapViewport(-90.0, 90.0, -180.0, 180.0)
    } else {
        val bounds = Geohash.decodeToBounds(parent)
        MapViewport(bounds.latMin, bounds.latMax, bounds.lonMin, bounds.lonMax)
    }

/** A city drawn on the map: where it landed, and what marks it. */
internal class MapPlace(val place: WorldAtlas.Place, val column: Int, val row: Int)

/** What drew a cell of a [MapPicture], which decides its colour. */
internal object MapLayer {
    const val SEA: Byte = 0
    const val LAND: Byte = 1
    const val CITY: Byte = 2
    const val CAPITAL: Byte = 3
}

/**
 * A drawn map: [glyphs] rows of exactly [columns] characters, and for each row a parallel
 * [MapLayer] per column.
 *
 * [coastline] is true only when the window holds both land and sea. All sea and all land are the
 * same answer drawn two ways, and neither says where anything is, so both count as nothing drawn.
 * Past about 0.01 degrees a pixel -- the precision the bundled coordinates are rounded to -- every
 * window is one or the other, which is where the screen has to say so and lean on the cities.
 */
internal class MapPicture(
    val columns: Int,
    val glyphs: List<String>,
    val layers: List<ByteArray>,
    val coastline: Boolean,
    val places: List<MapPlace>,
)

private const val FULL_BLOCK = '\u2588'
private const val UPPER_HALF = '\u2580'
private const val LOWER_HALF = '\u2584'

/** The marker for an ordinary city, and its ASCII stand-in where the font may lack `U+00B7`. */
private const val CITY_DOT = '\u00B7'
private const val CITY_DOT_ASCII = '.'

/** The marker for a capital or a megacity, at every zoom that draws cities. */
private const val CAPITAL_DOT = '*'

/** At most this many cities are drawn, most prominent first, however tight the window is. */
private const val MAX_PLACES = 60

/**
 * The map of [viewport] in [columns] by [rows] cells, land and cities both. Results are cached by
 * window and size: the browser redraws on every key, and rasterising 5143 land points is not free.
 */
internal fun mapPicture(viewport: MapViewport, columns: Int, rows: Int, consoleSafe: Boolean): MapPicture {
    // The cache is plain shared state: composition is the only thread that draws.
    val key = MapKey(viewport, columns, rows, consoleSafe)
    pictures[key]?.let { return it }
    val picture = drawMap(viewport, columns, rows, consoleSafe)
    // A handful of windows are enough: the one on screen, and the ones a few keys away.
    if (pictures.size >= MAX_CACHED) pictures.clear()
    pictures[key] = picture
    return picture
}

private data class MapKey(val viewport: MapViewport, val columns: Int, val rows: Int, val consoleSafe: Boolean)

private const val MAX_CACHED = 8

private val pictures = HashMap<MapKey, MapPicture>()

private fun drawMap(viewport: MapViewport, columns: Int, rows: Int, consoleSafe: Boolean): MapPicture {
    if (columns <= 0 || rows <= 0) {
        return MapPicture(0, emptyList(), emptyList(), coastline = false, places = emptyList())
    }
    val pixels = rasterizeLand(viewport, columns, rows * 2)
    var landPixels = 0
    for (pixel in pixels) if (pixel) landPixels++
    val coastline = landPixels > 0 && landPixels < pixels.size
    val glyphs = halfBlockRows(pixels, columns, rows).toMutableList()
    val layers = ArrayList<ByteArray>(rows)
    for (row in 0 until rows) {
        val layer = ByteArray(columns)
        for (column in 0 until columns) {
            layer[column] = if (glyphs[row][column] == ' ') MapLayer.SEA else MapLayer.LAND
        }
        layers += layer
    }
    val places = placeMarks(viewport, columns, rows)
    // Least prominent first, so a more prominent city keeps the cell when two share one.
    for (mark in places.asReversed()) {
        val line = glyphs[mark.row].toCharArray()
        val prominent = mark.place.capital || mark.place.megacity
        line[mark.column] = when {
            prominent -> CAPITAL_DOT
            consoleSafe -> CITY_DOT_ASCII
            else -> CITY_DOT
        }
        glyphs[mark.row] = line.concatToString()
        layers[mark.row][mark.column] = if (prominent) MapLayer.CAPITAL else MapLayer.CITY
    }
    return MapPicture(columns, glyphs, layers, coastline, places)
}

/**
 * [pixels], two rows of a [columns] wide bitmap to a cell, as half-block glyphs: both pixels
 * `U+2588`, the upper one `U+2580`, the lower one `U+2584`, neither a space.
 */
internal fun halfBlockRows(pixels: BooleanArray, columns: Int, rows: Int): List<String> =
    List(rows) { row ->
        val line = CharArray(columns)
        val top = row * 2 * columns
        val bottom = top + columns
        for (column in 0 until columns) {
            val up = pixels[top + column]
            val down = pixels[bottom + column]
            line[column] = when {
                up && down -> FULL_BLOCK
                up -> UPPER_HALF
                down -> LOWER_HALF
                else -> ' '
            }
        }
        line.concatToString()
    }

/**
 * Land inside [viewport] as a [columns] by [pixelRows] bitmap, row-major, north at row 0.
 *
 * A scanline fill, not a point-in-polygon test per pixel: for each row the polygon edges crossing
 * that latitude are collected, sorted by x and filled in pairs. Every ring of the file takes part
 * in one even-odd parity, which is what makes a polygon's inner rings holes.
 *
 * A window narrower than the world also sees the copies of the world 360 degrees either side, so a
 * window straddling the antimeridian (a [lonMax][MapViewport.lonMax] past 180, or a
 * [lonMin][MapViewport.lonMin] before -180) is whole. The copies never overlap on screen, so they
 * do not disturb each other's parity. The browser's own windows are geohash cells, which never
 * straddle it; this is what keeps that an accident rather than a requirement.
 */
internal fun rasterizeLand(
    viewport: MapViewport,
    columns: Int,
    pixelRows: Int,
    rings: List<WorldAtlas.Ring> = WorldAtlas.land,
): BooleanArray {
    val pixels = BooleanArray(columns * pixelRows)
    if (columns <= 0 || pixelRows <= 0) return pixels
    val offsets = if (viewport.lonSpan >= 360.0) ONE_COPY else THREE_COPIES
    val scale = columns / viewport.lonSpan
    var crossings = DoubleArray(256)
    for (y in 0 until pixelRows) {
        val lat = viewport.latMax - (y + 0.5) * viewport.latSpan / pixelRows
        var found = 0
        for (ring in rings) {
            val coords = ring.coords
            val n = ring.size
            var i = 0
            while (i < n) {
                val j = if (i + 1 == n) 0 else i + 1
                val lat1 = coords[i * 2].toDouble()
                val lat2 = coords[j * 2].toDouble()
                if ((lat1 > lat) != (lat2 > lat)) {
                    val lon1 = coords[i * 2 + 1].toDouble()
                    val lon2 = coords[j * 2 + 1].toDouble()
                    val lon = lon1 + (lat - lat1) * (lon2 - lon1) / (lat2 - lat1)
                    for (offset in offsets) {
                        if (found == crossings.size) crossings = crossings.copyOf(crossings.size * 2)
                        crossings[found++] = (lon + offset - viewport.lonMin) * scale
                    }
                }
                i++
            }
        }
        if (found < 2) continue
        val row = crossings.copyOf(found)
        row.sort()
        val base = y * columns
        var pair = 0
        while (pair + 1 < found) {
            // A pixel is land when its centre is inside the span. An island narrower than one
            // pixel would fall between two centres and vanish, so it lights the pixel it sits in
            // instead: at 110m an island worth a pixel is worth more than an exact edge.
            var from = firstPixel(row[pair])
            var to = lastPixel(row[pair + 1])
            if (to < from) {
                from = floor((row[pair] + row[pair + 1]) / 2).toInt()
                to = from
            }
            var x = maxOf(0, from)
            val last = minOf(columns - 1, to)
            while (x <= last) {
                pixels[base + x] = true
                x++
            }
            pair += 2
        }
    }
    return pixels
}

/** The leftmost pixel whose centre is at or right of [x]. */
private fun firstPixel(x: Double): Int = ceil(x - 0.5).toInt()

/** The rightmost pixel whose centre is at or left of [x]. */
private fun lastPixel(x: Double): Int = floor(x - 0.5).toInt()

private val ONE_COPY = doubleArrayOf(0.0)
private val THREE_COPIES = doubleArrayOf(-360.0, 0.0, 360.0)

/**
 * The cities to draw for [viewport], most prominent first, each placed in a cell.
 *
 * None at world zoom: 1251 dots say nothing. As the window narrows, less prominent places join,
 * because that is where the coastline stops saying anything and a place has to. A capital is worth
 * a dot at every zoom that draws any; being a megacity is not, since 462 of the 1251 are one and
 * at a continent's width they cover it.
 */
internal fun placeMarks(viewport: MapViewport, columns: Int, rows: Int): List<MapPlace> {
    if (viewport.lonSpan > 90.0) return emptyList()
    val keepRank = when {
        viewport.lonSpan > 20.0 -> 1
        viewport.lonSpan > 3.0 -> 3
        else -> 10
    }
    val pixelRows = rows * 2
    val marks = ArrayList<MapPlace>()
    for (place in WorldAtlas.cities) {
        if (place.rank > keepRank && !place.capital) continue
        val lat = place.lat.toDouble()
        if (lat < viewport.latMin || lat > viewport.latMax) continue
        val lon = wrapInto(place.lon.toDouble(), viewport) ?: continue
        // Both edges belong to the window, so a place exactly on one lands in the last cell.
        val column = floor((lon - viewport.lonMin) / viewport.lonSpan * columns).toInt().coerceIn(0, columns - 1)
        val pixel = floor((viewport.latMax - lat) / viewport.latSpan * pixelRows).toInt()
        val row = (pixel / 2).coerceIn(0, rows - 1)
        marks += MapPlace(place, column, row)
    }
    marks.sortWith(placeOrder)
    return if (marks.size > MAX_PLACES) marks.subList(0, MAX_PLACES).toList() else marks
}

/** Megacities, then capitals, then by Natural Earth's scale rank, then by name so ties are stable. */
private val placeOrder = compareBy<MapPlace>(
    { if (it.place.megacity) 0 else 1 },
    { if (it.place.capital) 0 else 1 },
    { it.place.rank },
    { it.place.name },
)

/** [lon] moved by whole turns into [viewport]'s longitudes, or null when it is outside them. */
private fun wrapInto(lon: Double, viewport: MapViewport): Double? {
    var value = lon
    while (value < viewport.lonMin) value += 360.0
    while (value >= viewport.lonMin + 360.0) value -= 360.0
    return value.takeIf { it <= viewport.lonMax }
}

/**
 * The bundled place nearest ([lat], [lon]) within [withinDegrees], or null. This is the screen's
 * one plain-language anchor: a geohash says where only to someone who already reads geohashes.
 */
internal fun nearestPlace(lat: Double, lon: Double, withinDegrees: Double): WorldAtlas.Place? {
    val scale = cos(lat * PI / 180.0)
    var best: WorldAtlas.Place? = null
    var bestDistance = withinDegrees * withinDegrees
    for (place in WorldAtlas.cities) {
        val dLat = place.lat - lat
        var dLon = place.lon - lon
        while (dLon > 180.0) dLon -= 360.0
        while (dLon < -180.0) dLon += 360.0
        val distance = dLat * dLat + (dLon * scale) * (dLon * scale)
        if (distance < bestDistance) {
            bestDistance = distance
            best = place
        }
    }
    return best
}

/** Whether a map of [columns] by [rows] cells can carry a [gridColumns] by [gridRows] cell grid. */
internal fun mapFits(columns: Int, rows: Int, gridColumns: Int, gridRows: Int): Boolean =
    rows >= gridRows && columns >= gridColumns * 2

/** The first cell column of geohash grid column [index], of [gridColumns], across [columns] cells. */
internal fun cellColumnStart(index: Int, gridColumns: Int, columns: Int): Int = index * columns / gridColumns

/** The first cell row of geohash grid row [index], of [gridRows], down [rows] cells. */
internal fun cellRowStart(index: Int, gridRows: Int, rows: Int): Int = index * rows / gridRows

/** Degrees across one cell of the map, used to decide how near a place has to be to be worth naming. */
internal fun anchorRadius(viewport: MapViewport, gridColumns: Int, gridRows: Int): Double =
    max(viewport.lonSpan / gridColumns, viewport.latSpan / gridRows)
