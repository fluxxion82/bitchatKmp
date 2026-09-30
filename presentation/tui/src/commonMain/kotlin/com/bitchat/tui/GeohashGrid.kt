package com.bitchat.tui

import com.bitchat.domain.location.geo.Geohash
import com.bitchat.domain.location.model.GeohashChannelLevel
import kotlin.math.abs
import kotlin.math.floor

/** The geohash alphabet, in value order. */
internal const val GEOHASH_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

/** The finest geohash bitchat uses. */
internal const val MAX_GEOHASH_LENGTH = 12

/**
 * The 32 children of [parent] in base32 order (none past [MAX_GEOHASH_LENGTH] characters).
 * Note for 2.7: this belongs next to `Geohash.decodeToCenter` in `domain/location/geo`.
 */
fun geohashChildren(parent: String): List<String> =
    if (parent.length >= MAX_GEOHASH_LENGTH) emptyList() else GEOHASH_BASE32.map { parent + it }

/**
 * [parent]'s children laid out north up and west left, as rows of columns. A child character adds
 * five bits that continue the geohash's alternation, longitude first overall (`Geohash.encode`):
 * after an even-length parent they are lon, lat, lon, lat, lon (8 columns by 4 rows); after an
 * odd-length one lat, lon, lat, lon, lat (4 columns by 8 rows).
 */
internal fun geohashGrid(parent: String): List<List<String>> {
    val even = parent.length % 2 == 0
    val columns = if (even) 8 else 4
    val grid = List(32 / columns) { MutableList(columns) { "" } }
    GEOHASH_BASE32.forEachIndexed { value, char ->
        val (column, row) = cellOf(value, even)
        grid[row][column] = parent + char
    }
    return grid
}

/** Where the child ending in [char] sits in [parent]'s grid, as column to row. */
internal fun gridPosition(parent: String, char: Char): Pair<Int, Int> =
    cellOf(GEOHASH_BASE32.indexOf(char), parent.length % 2 == 0)

private fun cellOf(value: Int, evenParent: Boolean): Pair<Int, Int> {
    val bit = IntArray(5) { (value shr (4 - it)) and 1 }
    val threeBits = (bit[0] shl 2) or (bit[2] shl 1) or bit[4]
    val twoBits = (bit[1] shl 1) or bit[3]
    return if (evenParent) threeBits to (3 - twoBits) else twoBits to (7 - threeBits)
}

/**
 * The cell of the same precision [east] cells east and [north] cells north of [geohash], found as
 * `Geohash.neighborsSamePrecision` finds its neighbours (the centre moved by whole cell sizes, then
 * encoded): longitude wraps across the antimeridian, and past a pole there is no neighbour (null).
 */
internal fun geohashNeighbour(geohash: String, east: Int, north: Int): String? {
    val bounds = Geohash.decodeToBounds(geohash)
    val lat = (bounds.latMin + bounds.latMax) / 2 + north * (bounds.latMax - bounds.latMin)
    if (lat > 90.0 || lat < -90.0) return null
    var lon = (bounds.lonMin + bounds.lonMax) / 2 + east * (bounds.lonMax - bounds.lonMin)
    while (lon > 180.0) lon -= 360.0
    while (lon < -180.0) lon += 360.0
    return Geohash.encode(lat, lon, geohash.length).takeIf { it != geohash }
}

/** [value] rounded half up (away from zero) to [decimals] places, without a sign on a zero. */
internal fun formatCoordinate(value: Double, decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = floor(abs(value) * scale + 0.5).toLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    val whole = scaled / scale
    return if (decimals == 0) "$sign$whole" else "$sign$whole." + (scaled % scale).toString().padStart(decimals, '0')
}

/** Decimals worth showing for a cell of [precision] characters. */
internal fun coordinateDecimals(precision: Int): Int = ((precision - 1) / 2).coerceIn(0, 4)

/** [input] as a geohash if it is one of 2 to 12 base32 characters (case and a leading `#` ignored). */
internal fun parseGeohash(input: String): String? {
    val geohash = input.trim().removePrefix("#").lowercase()
    return geohash.takeIf { it.length in 2..MAX_GEOHASH_LENGTH && it.all { char -> char in GEOHASH_BASE32 } }
}

/** The channel level for a geohash of [length] characters, as `LocationChannelsViewModel` maps it. */
internal fun geohashLevel(length: Int): GeohashChannelLevel = when (length) {
    in 0..3 -> GeohashChannelLevel.REGION
    4 -> GeohashChannelLevel.PROVINCE
    5 -> GeohashChannelLevel.CITY
    6 -> GeohashChannelLevel.NEIGHBORHOOD
    7 -> GeohashChannelLevel.BLOCK
    else -> GeohashChannelLevel.BUILDING
}
