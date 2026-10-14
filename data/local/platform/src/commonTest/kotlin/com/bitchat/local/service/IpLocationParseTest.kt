package com.bitchat.local.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IpLocationParseTest {
    @Test
    fun `reads ipwho_is numeric coordinates`() {
        val body = """
            {"ip":"2601:645:c68b::1","success":true,"type":"IPv6","continent":"North America",
             "country":"United States","region":"California","city":"San Francisco",
             "latitude":37.774926,"longitude":-122.4194208}
        """.trimIndent()

        val point = assertNotNull(parseIpLocation(body))
        assertEquals(37.774926, point.lat)
        assertEquals(-122.4194208, point.lon)
    }

    @Test
    fun `reads ipbase numeric coordinates`() {
        val body = """{"ip":"2601:645:c68b::1","country_code":"US","city":"San Francisco",""" +
            """"zip_code":"94199","latitude":37.778008,"longitude":-122.431272,"metro_code":0}"""

        val point = assertNotNull(parseIpLocation(body))
        assertEquals(37.778008, point.lat)
        assertEquals(-122.431272, point.lon)
    }

    @Test
    fun `reads geojs coordinates quoted as strings`() {
        val body = """{"accuracy":5,"asn":7922,"city":"San Francisco","country_code":"US",""" +
            """"latitude":"37.7308","longitude":"-122.3838","organization":"AS7922 Comcast"}"""

        val point = assertNotNull(parseIpLocation(body))
        assertEquals(37.7308, point.lat)
        assertEquals(-122.3838, point.lon)
    }

    @Test
    fun `reads ipwho_is when it pretty-prints the success flag`() {
        val body = """
            {
                "ip": "2601:645:c68b::1",
                "success": true,
                "country": "United States",
                "city": "San Francisco",
                "latitude": 37.774926,
                "longitude": -122.4194208
            }
        """.trimIndent()

        val point = assertNotNull(parseIpLocation(body))
        assertEquals(37.774926, point.lat)
    }

    @Test
    fun `rejects a pretty-printed failure`() {
        val body = """
            {
                "ip": "127.0.0.1",
                "success": false,
                "message": "Reserved range"
            }
        """.trimIndent()

        assertNull(parseIpLocation(body))
    }

    @Test
    fun `rejects a lookup the provider reports as failed`() {
        val body = """{"ip":"127.0.0.1","success":false,"message":"Reserved range",""" +
            """"latitude":0,"longitude":0}"""

        assertNull(parseIpLocation(body))
    }

    @Test
    fun `rejects a reported failure whatever coordinates come with it`() {
        assertNull(parseIpLocation("""{"success":false,"latitude":37.7749,"longitude":-122.4194}"""))
        assertNotNull(parseIpLocation("""{"success":true,"latitude":37.7749,"longitude":-122.4194}"""))
    }

    @Test
    fun `rejects Null Island`() {
        assertNull(parseIpLocation("""{"latitude":0,"longitude":0}"""))
    }

    @Test
    fun `rejects the United States centroid`() {
        assertNull(parseIpLocation("""{"latitude":37.751,"longitude":-97.822}"""))
    }

    @Test
    fun `accepts a coordinate close to but not at the centroid`() {
        val point = assertNotNull(parseIpLocation("""{"latitude":37.80,"longitude":-97.90}"""))
        assertEquals(37.80, point.lat)
    }

    @Test
    fun `rejects out of range coordinates`() {
        assertNull(parseIpLocation("""{"latitude":91.0,"longitude":10.0}"""))
        assertNull(parseIpLocation("""{"latitude":10.0,"longitude":181.0}"""))
    }

    @Test
    fun `returns nothing when the body carries no coordinates`() {
        assertNull(parseIpLocation("""{"city":"San Francisco","country":"United States"}"""))
        assertNull(parseIpLocation("not json at all"))
    }

    @Test
    fun `rejects an answer cut off inside a number`() {
        val whole = """{"latitude":37.7749,"longitude":-122.4194}"""
        assertNotNull(parseIpLocation(whole))
        assertNull(parseIpLocation(whole.dropLast(8)))
    }

    @Test
    fun `rejects an answer that does not begin and end as one object`() {
        assertNull(parseIpLocation("""[{"latitude":37.7749,"longitude":-122.4194}]"""))
        assertNull(parseIpLocation("""{"latitude":37.7749,"longitude":-122.4194} trailing"""))
        assertNull(parseIpLocation("""leading {"latitude":37.7749,"longitude":-122.4194}"""))
        assertNotNull(parseIpLocation(" {\"latitude\":37.7749,\"longitude\":-122.4194}\n"))
    }

    @Test
    fun `the longest answer cannot exhaust the stack`() {
        val nested = """{"latitude":1.5,"longitude":2.5,"x":""" + "[".repeat(8000) + "0" + "]".repeat(8000) + "}"
        assertEquals(1.5, parseIpLocation(nested)?.lat)

        val blank = """{"latitude":""" + " ".repeat(16000) + """1.5,"longitude":2.5}"""
        assertEquals(2.5, parseIpLocation(blank)?.lon)

        val digits = """{"latitude":""" + "1".repeat(16000) + ""","longitude":2.5}"""
        assertNull(parseIpLocation(digits))

        assertNull(parseIpLocation("{" + "\"latitude\":".repeat(1400) + "}"))
    }
}
