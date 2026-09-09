package com.bitchat.bluetooth.linux

import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the scanner that are decisions rather than plumbing.
 *
 * Everything else in `LinuxScanningService` is "whatever BlueZ did" and can only be exercised
 * against a live daemon. These three are not: whether a device is a bitchat peer, whether it is in
 * range, and whether it may be offered again yet are pure functions of data, and each of them has a
 * failure mode that is silent on a real radio -- a case-sensitive UUID compare finds nothing and
 * looks exactly like a room with no peers in it.
 */
class LinuxScanningServiceTest {

    private fun uuids(vararg values: String): Map<String, Variant<*>> =
        mapOf("UUIDs" to variantOfStrings(values.toList()))

    // -------------------------------------------------------------------------------------
    // advertisesBitchat
    // -------------------------------------------------------------------------------------

    /**
     * The case BlueZ actually produces. It normalises every UUID it reports to lower case while the
     * constant is spelled upper-case to match the Android and iOS peers, so a case-sensitive
     * comparison here would reject every real peer.
     */
    @Test
    fun `recognises the bitchat service UUID as BlueZ spells it`() {
        assertTrue(advertisesBitchat(uuids(BITCHAT_SERVICE_UUID.lowercase())))
    }

    @Test
    fun `recognises the bitchat service UUID as the wire constant spells it`() {
        assertTrue(advertisesBitchat(uuids(BITCHAT_SERVICE_UUID)))
    }

    @Test
    fun `finds the bitchat UUID among the others a peer advertises`() {
        val properties = uuids(
            "0000180a-0000-1000-8000-00805f9b34fb",
            BITCHAT_SERVICE_UUID.lowercase(),
            "0000180f-0000-1000-8000-00805f9b34fb"
        )
        assertTrue(advertisesBitchat(properties))
    }

    @Test
    fun `a device advertising other services is not a candidate`() {
        assertFalse(advertisesBitchat(uuids("0000180f-0000-1000-8000-00805f9b34fb")))
    }

    /**
     * BlueZ omits `UUIDs` entirely until it has parsed an advertisement carrying a service list,
     * which is the definition of "we have not seen it advertise bitchat". Absent must not throw and
     * must not be a match.
     */
    @Test
    fun `a device with no UUIDs property is not a candidate`() {
        assertFalse(advertisesBitchat(emptyMap()))
    }

    @Test
    fun `a UUIDs property of the wrong shape is not a candidate`() {
        assertFalse(advertisesBitchat(mapOf("UUIDs" to Variant("not-a-list"))))
    }

    // -------------------------------------------------------------------------------------
    // rssiOf
    // -------------------------------------------------------------------------------------

    /** D-Bus `n`, so BlueZ sends a Short; the negative value is the whole point of it being signed. */
    @Test
    fun `reads a signed RSSI`() {
        assertEquals(-72, rssiOf(mapOf("RSSI" to Variant((-72).toShort()))))
    }

    /**
     * Absence is meaningful and is not an error: BlueZ *removes* `RSSI` from a device it is no
     * longer hearing, which is how a cached peer that has left the building is told from one that
     * is here now.
     */
    @Test
    fun `a device BlueZ is no longer hearing reports no RSSI`() {
        assertNull(rssiOf(emptyMap()))
    }

    // -------------------------------------------------------------------------------------
    // DiscoveryOfferGate
    // -------------------------------------------------------------------------------------

    @Test
    fun `offers an address the first time it is seen`() {
        val gate = DiscoveryOfferGate(repeatIntervalMs = 5_000)
        assertTrue(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 1_000))
    }

    /**
     * The throttle's reason for existing: an advertising peer produces several `PropertiesChanged`
     * a second, and every one of them would otherwise become a connection decision.
     */
    @Test
    fun `refuses a repeat inside the interval`() {
        val gate = DiscoveryOfferGate(repeatIntervalMs = 5_000)
        assertTrue(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 1_000))
        assertFalse(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 1_100))
        assertFalse(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 5_999))
    }

    /**
     * And the reason it is a throttle rather than a one-shot: a peer whose backoff has expired has
     * to be offered again or it is never retried.
     */
    @Test
    fun `offers again once the interval has passed`() {
        val gate = DiscoveryOfferGate(repeatIntervalMs = 5_000)
        assertTrue(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 1_000))
        assertTrue(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 6_000))
        assertFalse(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 6_001))
    }

    /** The interval runs from the last *offer*, not from the first sighting. */
    @Test
    fun `the interval restarts at each accepted offer`() {
        val gate = DiscoveryOfferGate(repeatIntervalMs = 5_000)
        assertTrue(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 0))
        assertTrue(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 5_000))
        assertFalse(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 9_000))
        assertTrue(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 10_000))
    }

    @Test
    fun `throttles each address independently`() {
        val gate = DiscoveryOfferGate(repeatIntervalMs = 5_000)
        assertTrue(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 1_000))
        assertTrue(gate.shouldOffer("11:22:33:44:55:66", now = 1_000))
        assertFalse(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 1_001))
    }

    /**
     * BlueZ dropping a device object is what an Android address rotation looks like. If the same
     * address ever comes back it is a new peer as far as this node is concerned, so it must not sit
     * out the remainder of an interval it started before it vanished.
     */
    @Test
    fun `forgetting an address makes the next sighting immediate`() {
        val gate = DiscoveryOfferGate(repeatIntervalMs = 5_000)
        assertTrue(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 1_000))
        gate.forget("AA:BB:CC:DD:EE:FF")
        assertTrue(gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 1_001))
    }

    /**
     * Bounding the map is the whole job of `prune`: every Android address rotation leaves a key
     * behind that will never be seen again.
     */
    @Test
    fun `prunes only entries older than the ttl`() {
        val gate = DiscoveryOfferGate(repeatIntervalMs = 5_000)
        gate.shouldOffer("AA:BB:CC:DD:EE:FF", now = 0)
        gate.shouldOffer("11:22:33:44:55:66", now = 100_000)
        assertEquals(2, gate.size())

        assertEquals(1, gate.prune(now = 200_000, ttlMs = 150_000))
        assertEquals(1, gate.size())

        // The survivor kept its timestamp, so it is still inside its own interval.
        assertFalse(gate.shouldOffer("11:22:33:44:55:66", now = 100_001))
    }
}
