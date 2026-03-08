package com.bitchat.lora.bitchat.protocol

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RangePiBeaconTest {

    @Test
    fun `format then parse returns valid beacon`() {
        val text = RangePiBeacon.format(
            sequence = 42u,
            mode = RangePiBeacon.Mode.BEACON,
            channel = 18,
            frequencyHz = 868_125_000
        )

        val parsed = RangePiBeacon.parse(text.encodeToByteArray())

        assertIs<RangePiBeacon.ParseResult.Valid>(parsed)
        assertEquals(42u, parsed.beacon.sequence)
        assertEquals(RangePiBeacon.Mode.BEACON, parsed.beacon.mode)
        assertEquals(18, parsed.beacon.channel)
        assertEquals(868_125_000, parsed.beacon.frequencyHz)
    }

    @Test
    fun `parse returns invalid crc when checksum does not match`() {
        val valid = RangePiBeacon.format(
            sequence = 7u,
            mode = RangePiBeacon.Mode.BRIDGE,
            channel = 65,
            frequencyHz = 915_125_000
        )
        val tampered = valid.replace("seq=7", "seq=8")

        val parsed = RangePiBeacon.parse(tampered.encodeToByteArray())

        assertIs<RangePiBeacon.ParseResult.Invalid>(parsed)
        assertEquals(RangePiBeacon.InvalidReason.CRC_MISMATCH, parsed.reason)
    }

    @Test
    fun `parse returns malformed for missing fields`() {
        val malformed = "RPIB1|seq=1|mode=BEACON|crc=ABCD"

        val parsed = RangePiBeacon.parse(malformed.encodeToByteArray())

        assertIs<RangePiBeacon.ParseResult.Invalid>(parsed)
        assertEquals(RangePiBeacon.InvalidReason.MALFORMED, parsed.reason)
    }

    @Test
    fun `crc is 4 hex chars uppercase`() {
        val text = RangePiBeacon.format(
            sequence = 1u,
            mode = RangePiBeacon.Mode.BEACON,
            channel = 1,
            frequencyHz = 868_125_000
        )

        val crc = text.substringAfter("|crc=")
        assertEquals(4, crc.length)
        assertTrue(crc.all { it in '0'..'9' || it in 'A'..'F' })
    }
}
