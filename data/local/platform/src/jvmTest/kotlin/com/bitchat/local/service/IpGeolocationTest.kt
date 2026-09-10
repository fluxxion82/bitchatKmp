package com.bitchat.local.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * The response bodies below are real, captured from each provider. They disagree about the shape
 * of a coordinate, which is the whole reason this parser exists.
 */
class IpGeolocationTest {

    @Test
    fun `reads ipwho_is numeric coordinates`() {
        val body = """
            {"ip":"2601:645:c68b::1","success":true,"type":"IPv6","continent":"North America",
             "country":"United States","region":"California","city":"San Francisco",
             "latitude":37.774926,"longitude":-122.4194208}
        """.trimIndent()

        val point = assertNotNull(IpGeolocation.parse(body))
        assertEquals(37.774926, point.lat)
        assertEquals(-122.4194208, point.lon)
    }

    @Test
    fun `reads ipbase numeric coordinates`() {
        val body = """{"ip":"2601:645:c68b::1","country_code":"US","city":"San Francisco",""" +
            """"zip_code":"94199","latitude":37.778008,"longitude":-122.431272,"metro_code":0}"""

        val point = assertNotNull(IpGeolocation.parse(body))
        assertEquals(37.778008, point.lat)
        assertEquals(-122.431272, point.lon)
    }

    @Test
    fun `reads geojs coordinates quoted as strings`() {
        // geojs returns its numbers as JSON strings; a parser that assumed numbers read nothing.
        val body = """{"accuracy":5,"asn":7922,"city":"San Francisco","country_code":"US",""" +
            """"latitude":"37.7308","longitude":"-122.3838","organization":"AS7922 Comcast"}"""

        val point = assertNotNull(IpGeolocation.parse(body))
        assertEquals(37.7308, point.lat)
        assertEquals(-122.3838, point.lon)
    }

    @Test
    fun `reads ipwho_is when it pretty-prints the success flag`() {
        // Exactly as the wire delivers it. ipwho.is puts a space after the colon, and a literal
        // contains("\"success\":true") check therefore discarded every answer it gave -- the
        // provider dropped out of the chain in complete silence.
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

        val point = assertNotNull(IpGeolocation.parse(body))
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

        assertNull(IpGeolocation.parse(body))
    }

    @Test
    fun `rejects a lookup the provider reports as failed`() {
        // ipwho.is answers 200 with success:false rather than an error status.
        val body = """{"ip":"127.0.0.1","success":false,"message":"Reserved range",""" +
            """"latitude":0,"longitude":0}"""

        assertNull(IpGeolocation.parse(body))
    }

    @Test
    fun `rejects Null Island`() {
        assertNull(IpGeolocation.parse("""{"latitude":0,"longitude":0}"""))
    }

    @Test
    fun `rejects the United States centroid`() {
        // 37.751/-97.822 is a field in Kansas that several databases return for any US address
        // they cannot place. Dropping a user there is worse than reporting no fix.
        assertNull(IpGeolocation.parse("""{"latitude":37.751,"longitude":-97.822}"""))
    }

    @Test
    fun `accepts a coordinate close to but not at the centroid`() {
        val point = assertNotNull(IpGeolocation.parse("""{"latitude":37.80,"longitude":-97.90}"""))
        assertEquals(37.80, point.lat)
    }

    @Test
    fun `rejects out of range coordinates`() {
        assertNull(IpGeolocation.parse("""{"latitude":91.0,"longitude":10.0}"""))
        assertNull(IpGeolocation.parse("""{"latitude":10.0,"longitude":181.0}"""))
    }

    @Test
    fun `returns nothing when the body carries no coordinates`() {
        assertNull(IpGeolocation.parse("""{"city":"San Francisco","country":"United States"}"""))
        assertNull(IpGeolocation.parse("not json at all"))
    }
}
