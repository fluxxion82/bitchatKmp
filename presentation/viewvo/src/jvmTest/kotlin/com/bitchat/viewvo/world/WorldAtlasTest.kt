package com.bitchat.viewvo.world

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldAtlasTest {

    /**
     * The generated source is the only copy of the data that ships, so this is what says the ship
     * is the same world the repository holds. Everything downstream -- the Compose globe and the
     * terminal map alike -- parses these exact bytes with the parser below, so a pass here means
     * neither can have changed.
     */
    @Test fun theCompiledInWorldIsByteForByteTheCheckedInWorld() {
        assertEquals(File("worlddata/world_land.geojson").readText(), worldLandJson())
        assertEquals(File("worlddata/world_borders.geojson").readText(), worldBordersJson())
        assertEquals(File("worlddata/world_cities.geojson").readText(), worldCitiesJson())
    }

    @Test fun theLandIsTheNaturalEarth110mLandRings() {
        val land = WorldAtlas.land
        assertEquals(128, land.size)
        assertEquals(5143, land.sumOf { it.size })
        // geojson orders each point [lon, lat]; the rings hold (lat, lon).
        assertEquals(-80.04f, land[0].coords[0])
        assertEquals(-59.57f, land[0].coords[1])
        assertTrue(land.all { it.coords.size == it.size * 2 })
    }

    @Test fun theBordersAreLinesAndTheCitiesArePlaces() {
        assertEquals(333, WorldAtlas.borders.size)
        assertEquals(3108, WorldAtlas.borders.sumOf { it.size })
        val cities = WorldAtlas.cities
        assertEquals(1251, cities.size)
        assertEquals("Bombo", cities[0].name)
        assertEquals(0.583f, cities[0].lat)
        assertEquals(32.533f, cities[0].lon)
        assertEquals(10, cities[0].rank)
        assertTrue(!cities[0].capital && !cities[0].megacity)
        assertEquals(200, cities.count { it.capital })
        assertEquals(462, cities.count { it.megacity })
    }

    @Test fun aPolygonsInnerRingsComeThroughAsRingsOfTheirOwn() {
        val text = """
            {"type":"GeometryCollection","geometries":[
              {"type":"Polygon","coordinates":[[[0,0],[10,0],[10,10],[0,0]],[[2,2],[4,2],[4,4],[2,2]]]},
              {"type":"LineString","coordinates":[[0,0],[1,1]]}
            ]}
        """.trimIndent()
        val rings = WorldAtlas.parseGeometries(text)
        assertEquals(listOf(4, 4, 2), rings.map { it.size })
        assertEquals(listOf(0f, 0f, 0f, 10f, 10f, 10f, 0f, 0f), rings[0].coords.toList())
    }

    @Test fun aRingOfFewerThanThreePointsIsNotAPolygon() {
        val text = """{"type":"GeometryCollection","geometries":[{"type":"Polygon","coordinates":[[[0,0],[1,1]]]}]}"""
        assertEquals(emptyList(), WorldAtlas.parseGeometries(text))
    }
}
