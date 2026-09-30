package com.bitchat.tui

import com.bitchat.domain.location.geo.Geohash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeohashGridTest {
    @Test fun childrenAreTheParentPlusEachBase32Character() {
        val children = geohashChildren("9q")
        assertEquals(32, children.size)
        assertEquals("9q0", children.first())
        assertEquals("9qz", children.last())
        assertEquals(emptyList(), geohashChildren("9q8yyzzzzzzz")) // Twelve characters is the finest.
    }

    @Test fun evenParentsLayOutEightColumnsByFourRowsNorthUp() {
        // The standard geohash table for an even-length parent: longitude bits first.
        assertEquals(
            listOf("bcfguvyz", "89destwx", "2367kmqr", "0145hjnp"),
            geohashGrid("").map { row -> row.joinToString("") { it.takeLast(1) } },
        )
    }

    @Test fun oddParentsLayOutFourColumnsByEightRowsNorthUp() {
        assertEquals(
            listOf("prxz", "nqwy", "jmtv", "hksu", "57eg", "46df", "139c", "028b"),
            geohashGrid("9").map { row -> row.joinToString("") { it.takeLast(1) } },
        )
    }

    @Test fun gridMatchesTheDomainDecoderEverywhere() {
        // Cross-check against Geohash.decodeToCenter: north up, east right, for both parities.
        for (parent in listOf("", "9", "9q", "9q8", "u4pruyd")) {
            val grid = geohashGrid(parent)
            for (row in grid.indices) {
                for (col in grid[row].indices) {
                    val (lat, lon) = Geohash.decodeToCenter(grid[row][col])
                    if (col > 0) assertTrue(lon > Geohash.decodeToCenter(grid[row][col - 1]).second, "$parent $row $col east")
                    if (col > 0) assertEquals(lat, Geohash.decodeToCenter(grid[row][col - 1]).first, "$parent $row $col same row")
                    if (row > 0) assertTrue(lat < Geohash.decodeToCenter(grid[row - 1][col]).first, "$parent $row $col south")
                }
            }
        }
    }

    @Test fun cellPositionIsFoundByCharacter() {
        assertEquals(6 to 0, gridPosition("9q8y", 'y'))
        assertEquals(3 to 7, gridPosition("9", 'b'))
    }

    @Test fun coordinatesAreRoundedHalfUp() {
        assertEquals("68", formatCoordinate(67.5, 0))
        assertEquals("-158", formatCoordinate(-157.5, 0))
        assertEquals("37.77", formatCoordinate(37.77099609375, 2))
        assertEquals("-122.41", formatCoordinate(-122.40966796875, 2))
        assertEquals("0.0", formatCoordinate(-0.04, 1))
    }

    @Test fun onlyBase32GeohashesOfTwoToTwelveAreAccepted() {
        assertEquals("9q8yy", parseGeohash("#9Q8YY"))
        assertEquals(null, parseGeohash("9"))
        assertEquals(null, parseGeohash("9a"))
        assertEquals(null, parseGeohash("9q8yyzzzzzzzz"))
    }

    @Test fun neighboursFollowGeohashNeighborsSamePrecision() {
        assertEquals("9q9nb", geohashNeighbour("9q8yz", 1, 0))
        assertEquals("bp", geohashNeighbour("zz", 1, 0)) // East across the antimeridian.
        assertEquals("zz", geohashNeighbour("bp", -1, 0)) // And back west.
        assertEquals("b", geohashNeighbour("z", 1, 0))
        for ((geohash, east, north) in listOf(Triple("9q8yz", 1, 0), Triple("zz", 1, 0), Triple("9q8y", 0, 1), Triple("u4pru", -1, -1))) {
            assertTrue(geohashNeighbour(geohash, east, north)!! in Geohash.neighborsSamePrecision(geohash), geohash)
        }
    }

    @Test fun thePolesStopTheCursor() {
        assertEquals(null, geohashNeighbour("b", 0, 1))
        assertEquals(null, geohashNeighbour("zz", 0, 1))
        assertEquals(null, geohashNeighbour("0", 0, -1))
        assertEquals(null, geohashNeighbour("00", 0, -1))
    }

    @Test fun theBreadcrumbNamesEveryStepAndKeepsTheTailWhenItIsTooLong() {
        assertEquals("World", breadcrumb("", 40))
        assertEquals("World > 9 > 9q", breadcrumb("9q", 40))
        assertEquals("World > 9 > 9q > 9q8 > 9q8y > 9q8yy", breadcrumb("9q8yy", 40))
        // Too wide: the tail says where one is, so the head is what goes.
        assertEquals("...9q8y > 9q8yy", breadcrumb("9q8yy", 15))
        assertEquals("q8yy", breadcrumb("9q8yy", 4)) // Too narrow even for the ellipsis.
    }

    @Test fun theCellLineDropsItsSizeBeforeItsPlaceAndItsPlaceBeforeItsName() {
        assertEquals(" #9q8yy  city 5  37.77, -122.41  ~3.8 x 4.8 km", summaryLine("9q8yy", 85))
        assertEquals(" #9q8yy  city 5  37.77, -122.41", summaryLine("9q8yy", 40))
        assertEquals(" #9q8yy  city 5", summaryLine("9q8yy", 20))
        assertEquals(" #9q8yy", summaryLine("9q8yy", 10))
        assertEquals(" #9q8yy", summaryLine("9q8yy", 7))
        assertEquals(" #9...", summaryLine("9q8yy", 6)) // Not even the geohash fits.
        assertEquals(" #b  region 1  68, -158  ~1,900 x 5,000 km", summaryLine("b", 85))
    }
}
