package com.bitchat.tui

import com.bitchat.domain.location.geo.Geohash
import com.bitchat.viewvo.world.WorldAtlas
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every expected row here was worked out by hand from the projection, never copied from a run.
 *
 * The window and the sizes are chosen so the arithmetic is exact: pixel x runs 0 until columns and
 * pixel y 0 until 2 * rows, a pixel's centre sits half a pixel in, and a pixel is land when its
 * centre falls inside a filled span.
 */
class WorldMapTest {

    /** A closed ring from (lat, lon) pairs, as [WorldAtlas] holds them. */
    private fun ring(vararg latLon: Double) = WorldAtlas.Ring(
        FloatArray(latLon.size) { latLon[it].toFloat() },
        latLon.size / 2,
    )

    private val full = '\u2588'
    private val upper = '\u2580'
    private val lower = '\u2584'

    private fun rows(viewport: MapViewport, columns: Int, rows: Int, rings: List<WorldAtlas.Ring>): List<String> =
        halfBlockRows(rasterizeLand(viewport, columns, rows * 2, rings), columns, rows)

    // A square from lat -5..5, lon -10..10 in a window of lat -10..10, lon -20..20.
    // Pixel centres: lon -17.5, -12.5, -7.5, -2.5, 2.5, 7.5, 12.5, 17.5; lat 7.5, 2.5, -2.5, -7.5.
    // Only the two middle pixel rows and the four middle columns have their centres inside.
    @Test fun aSquareFillsThePixelsWhoseCentresAreInsideIt() {
        val square = ring(-5.0, -10.0, 5.0, -10.0, 5.0, 10.0, -5.0, 10.0)
        assertEquals(
            listOf("  $lower$lower$lower$lower  ", "  $upper$upper$upper$upper  "),
            rows(MapViewport(-10.0, 10.0, -20.0, 20.0), columns = 8, rows = 2, rings = listOf(square)),
        )
    }

    // The same square with an inner ring at lat -4..4, lon -5..5. One even-odd parity over both
    // rings makes the inner one a hole, so the two pixels whose centres are at lon -2.5 and 2.5
    // stay sea while those at -7.5 and 7.5 stay land.
    @Test fun anInnerRingIsAHole() {
        val outer = ring(-5.0, -10.0, 5.0, -10.0, 5.0, 10.0, -5.0, 10.0)
        val inner = ring(-4.0, -5.0, 4.0, -5.0, 4.0, 5.0, -4.0, 5.0)
        assertEquals(
            listOf("  $lower  $lower  ", "  $upper  $upper  "),
            rows(MapViewport(-10.0, 10.0, -20.0, 20.0), columns = 8, rows = 2, rings = listOf(outer, inner)),
        )
    }

    // A window from lon 170 to 190 straddles the antimeridian. Land at lon 172..176 is in it as
    // written; land at lon -178..-173 is in it as the copy 360 degrees east. Pixel centres are at
    // lon 172.5, 177.5, 182.5, 187.5, so one pixel of each lands.
    @Test fun aWindowPastTheAntimeridianSeesBothSidesOfIt() {
        val west = ring(-4.0, 172.0, 4.0, 172.0, 4.0, 176.0, -4.0, 176.0)
        val east = ring(-4.0, -178.0, 4.0, -178.0, 4.0, -173.0, -4.0, -173.0)
        assertEquals(
            listOf("$full $full "),
            rows(MapViewport(-5.0, 5.0, 170.0, 190.0), columns = 4, rows = 1, rings = listOf(west, east)),
        )
    }

    // An island one degree wide in a window of five degrees per pixel falls between two pixel
    // centres. It lights the pixel it sits in rather than disappearing: at 110m, an island worth a
    // pixel is worth more than an exact edge.
    @Test fun anIslandNarrowerThanAPixelStillDraws() {
        val island = ring(-8.0, -0.5, 8.0, -0.5, 8.0, 0.5, -8.0, 0.5)
        assertEquals(
            listOf("    $full   "),
            rows(MapViewport(-10.0, 10.0, -20.0, 20.0), columns = 8, rows = 1, rings = listOf(island)),
        )
    }

    // Land reaching the south pole draws on the bottom row of a world window and nowhere else.
    // The lowest pixel centre is at lat -85, which is inside lat -90..-80; the next one up is -75.
    @Test fun landAtThePoleDrawsOnTheBottomRowOnly() {
        val cap = ring(-90.0, -180.0, -80.0, -180.0, -80.0, 180.0, -90.0, 180.0)
        val drawn = rows(MapViewport(-90.0, 90.0, -180.0, 180.0), columns = 8, rows = 9, rings = listOf(cap))
        assertEquals(List(8) { "        " } + listOf("$lower$lower$lower$lower$lower$lower$lower$lower"), drawn)
    }

    @Test fun anEmptyWindowDrawsNothing() {
        val square = ring(-5.0, -10.0, 5.0, -10.0, 5.0, 10.0, -5.0, 10.0)
        assertEquals(
            listOf("    ", "    "),
            rows(MapViewport(40.0, 50.0, 40.0, 50.0), columns = 4, rows = 2, rings = listOf(square)),
        )
    }

    @Test fun theWorldWindowIsTheWholeWorldAndACellWindowIsItsCell() {
        assertEquals(MapViewport(-90.0, 90.0, -180.0, 180.0), mapViewport(""))
        for (parent in listOf("9", "9q", "u4pruy")) {
            val bounds = Geohash.decodeToBounds(parent)
            assertEquals(
                MapViewport(bounds.latMin, bounds.latMax, bounds.lonMin, bounds.lonMax),
                mapViewport(parent),
            )
        }
    }

    // The 32 cells divide the window evenly, so the character column a cell starts at is exactly
    // where its own bounds put it. Checked against the domain decoder, not against this code.
    @Test fun theCellGridLinesUpWithTheGeohashBounds() {
        for (parent in listOf("", "9", "9q", "9q8")) {
            val viewport = mapViewport(parent)
            val grid = geohashGrid(parent)
            val gridColumns = grid[0].size
            val gridRows = grid.size
            val columns = 240
            val rows = 60
            for (c in 0 until gridColumns) {
                val bounds = Geohash.decodeToBounds(grid[0][c])
                val expected = floor((bounds.lonMin - viewport.lonMin) / viewport.lonSpan * columns).toInt()
                assertEquals(expected, cellColumnStart(c, gridColumns, columns), "$parent column $c")
            }
            for (r in 0 until gridRows) {
                val bounds = Geohash.decodeToBounds(grid[r][0])
                val expected = floor((viewport.latMax - bounds.latMax) / viewport.latSpan * rows).toInt()
                assertEquals(expected, cellRowStart(r, gridRows, rows), "$parent row $r")
            }
            assertEquals(columns, cellColumnStart(gridColumns, gridColumns, columns))
            assertEquals(rows, cellRowStart(gridRows, gridRows, rows))
        }
    }

    @Test fun theWorldIsTooWideForCityDots() {
        assertEquals(emptyList(), placeMarks(mapViewport(""), 240, 60))
    }

    @Test fun cityDotsAreOnlyEverInsideTheWindow() {
        for (parent in listOf("9", "9q", "gc", "u4pru")) {
            val viewport = mapViewport(parent)
            val marks = placeMarks(viewport, 240, 60)
            for (mark in marks) {
                assertTrue(mark.place.lat >= viewport.latMin && mark.place.lat <= viewport.latMax, "${mark.place.name} lat")
                val lon = mark.place.lon.toDouble()
                assertTrue(lon >= viewport.lonMin && lon <= viewport.lonMax, "${mark.place.name} lon")
                assertTrue(mark.column in 0 until 240, "${mark.place.name} column")
                assertTrue(mark.row in 0 until 60, "${mark.place.name} row")
            }
        }
    }

    @Test fun cityDotsStartWithTheProminentOnesAndAreCapped() {
        // #9q holds California and the Baja peninsula.
        val marks = placeMarks(mapViewport("9q"), 240, 60)
        assertTrue(marks.isNotEmpty())
        assertTrue(marks.size <= 60)
        assertTrue(marks.map { it.place.name }.contains("San Francisco"))
        val ranks = marks.map { Triple(if (it.place.megacity) 0 else 1, if (it.place.capital) 0 else 1, it.place.rank) }
        assertEquals(ranks.sortedWith(compareBy({ it.first }, { it.second }, { it.third })), ranks)
    }

    @Test fun theNearestPlaceIsFoundOnlyWithinTheDistanceAsked() {
        // The centre of #9q8yy is in San Francisco.
        val (lat, lon) = Geohash.decodeToCenter("9q8yy")
        assertEquals("San Francisco", nearestPlace(lat, lon, 1.0)?.name)
        assertNull(nearestPlace(lat, lon, 0.0001))
    }

    // Past the zoom the bundled data resolves, a window is all sea or all land, and a screen full
    // of either says nothing about where it is. Both count as no coastline.
    @Test fun aWindowIsOnlyDrawnWhenItHoldsBothLandAndSea() {
        val world = mapPicture(mapViewport(""), 240, 60, consoleSafe = false)
        assertTrue(world.coastline)
        assertTrue(world.places.isEmpty())

        val pacific = mapPicture(mapViewport("8bh2n0p"), 240, 60, consoleSafe = false) // Mid-Pacific.
        assertTrue(!pacific.coastline)
        assertTrue(pacific.places.isEmpty())
        assertEquals(setOf(' '), pacific.glyphs.flatMap { it.toList() }.toSet())

        val inland = mapPicture(mapViewport("9qhh0pb"), 240, 60, consoleSafe = false) // Inland California.
        assertTrue(!inland.coastline)
        assertEquals(setOf('\u2588'), inland.glyphs.flatMap { it.toList() }.toSet())

    }

    @Test fun aCapitalIsStarredAndAnOrdinaryCityIsADotThatGoesAsciiOnTheConsole() {
        // #gc is the British Isles: London is its only rank-0 capital.
        val isles = mapPicture(mapViewport("gc"), 240, 60, consoleSafe = false)
        val london = isles.places.first { it.place.name == "London" }
        assertEquals('*', isles.glyphs[london.row][london.column])
        assertEquals(MapLayer.CAPITAL, isles.layers[london.row][london.column])

        // #u02 holds one ordinary town and nothing else, at a zoom that shows every rank.
        val poitou = mapPicture(mapViewport("u02"), 240, 60, consoleSafe = false)
        val plain = poitou.places.single()
        assertEquals("Poitier", plain.place.name)
        assertEquals('\u00B7', poitou.glyphs[plain.row][plain.column])
        assertEquals(MapLayer.CITY, poitou.layers[plain.row][plain.column])

        val console = mapPicture(mapViewport("u02"), 240, 60, consoleSafe = true)
        val same = console.places.single()
        assertEquals('.', console.glyphs[same.row][same.column])
    }

    @Test fun aMapNeedsARowPerGridRowAndTwoColumnsPerGridColumn() {
        assertTrue(mapFits(columns = 16, rows = 4, gridColumns = 8, gridRows = 4))
        assertTrue(!mapFits(columns = 15, rows = 4, gridColumns = 8, gridRows = 4))
        assertTrue(!mapFits(columns = 16, rows = 3, gridColumns = 8, gridRows = 4))
        assertTrue(!mapFits(columns = 40, rows = 7, gridColumns = 4, gridRows = 8))
    }

    @Test fun everyGlyphOfTheWorldMapIsOneOfTheFourTheConsoleFontHas() {
        val picture = mapPicture(mapViewport(""), 85, 20, consoleSafe = true)
        val seen = picture.glyphs.flatMap { it.toList() }.toSet()
        assertEquals(setOf(' ', full, upper, lower), seen)
    }
}
