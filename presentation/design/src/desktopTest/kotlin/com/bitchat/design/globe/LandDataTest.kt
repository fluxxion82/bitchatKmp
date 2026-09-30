package com.bitchat.design.globe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The globe's input, pinned.
 *
 * The geojson and the parser moved to `presentation:viewvo` so the terminal UI could use them
 * without Compose. `WorldAtlasTest` says the compiled-in bytes are still the checked-in bytes;
 * this says the rings the globe is handed are still the same rings, in the same order, with the
 * same projection terms.
 */
class LandDataTest {

    @Test fun theLandRingsAreUnchanged() = runBlocking {
        val land = LandData.load()
        assertEquals(128, land.size)
        assertEquals(5143, land.sumOf { it.size })
        // geojson orders each point [lon, lat]; a ring holds (lat, lon).
        assertEquals(-80.04f, land[0].coords[0])
        assertEquals(-59.57f, land[0].coords[1])
        assertTrue(land.all { it.coords.size == it.size * 2 && it.projectionTerms.size == it.size * 4 })
    }

    @Test fun theProjectionTermsAreTheSinesAndCosinesOfEachPoint() = runBlocking {
        val ring = LandData.load()[0]
        val lat = ring.coords[0] * PI / 180.0
        val lon = ring.coords[1] * PI / 180.0
        assertEquals(sin(lat).toFloat(), ring.projectionTerms[0])
        assertEquals(cos(lat).toFloat(), ring.projectionTerms[1])
        assertEquals(sin(lon).toFloat(), ring.projectionTerms[2])
        assertEquals(cos(lon).toFloat(), ring.projectionTerms[3])
    }

    @Test fun theBordersAndCitiesAreUnchanged() = runBlocking {
        assertEquals(333, LandData.loadBorders().size)
        val cities = LandData.loadCities()
        assertEquals(1251, cities.size)
        assertEquals("Bombo", cities[0].name)
        assertEquals(0.583f, cities[0].lat)
        assertEquals(32.533f, cities[0].lon)
        assertEquals(10, cities[0].rank)
        assertEquals(200, cities.count { it.capital })
    }

    @Test fun everythingIsParsedOnce() = runBlocking {
        assertSame(LandData.load(), LandData.load())
        assertSame(LandData.loadBorders(), LandData.loadBorders())
        assertSame(LandData.loadCities(), LandData.loadCities())
    }
}
