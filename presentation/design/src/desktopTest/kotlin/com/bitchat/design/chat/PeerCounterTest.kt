package com.bitchat.design.chat

import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.GeohashChannelLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The header's number and the location sheet's "N people" read the same mesh peer list, so they must
 * never disagree. They did: `28f56be` moved an unwired `isConnected` flag out of the tint and into
 * the count, and every mesh count became 0 while the sheet and the sidebar showed the real peers.
 *
 * These tests pin the count to the peers the app actually knows about, whatever the transport.
 */
class PeerCounterTest {

    private fun geoPerson(id: String) = GeoPerson(
        id = id,
        displayName = id,
        lastSeen = Instant.fromEpochSeconds(0),
    )

    private fun meshCount(
        connectedPeers: List<String> = emptyList(),
        loraPeers: List<GeoPerson> = emptyList(),
        channel: Channel? = Channel.Mesh,
    ) = peerCountFor(
        selectedLocationChannel = channel,
        connectedPeers = connectedPeers,
        geohashPeople = emptyList(),
        loraPeers = loraPeers,
    )

    @Test
    fun `ble peers are counted and tinted as mesh`() {
        val result = meshCount(connectedPeers = listOf("269e37bb6be7caf9", "9343bbdb113d0118"))

        assertEquals(2, result.count)
        assertEquals(PeerCountTone.MESH, result.tone)
    }

    @Test
    fun `lora only peers are counted and tinted as lora`() {
        val result = meshCount(loraPeers = listOf(geoPerson("lora-aaaa"), geoPerson("lora-bbbb")))

        assertEquals(2, result.count)
        assertEquals(PeerCountTone.LORA, result.tone)
    }

    @Test
    fun `both transports contribute to one total`() {
        val result = meshCount(
            connectedPeers = listOf("269e37bb6be7caf9"),
            loraPeers = listOf(geoPerson("lora-aaaa"), geoPerson("lora-bbbb")),
        )

        assertEquals(3, result.count)
        assertEquals(PeerCountTone.MESH, result.tone, "BLE presence wins the tint when both are up")
    }

    @Test
    fun `no peers on any transport is the only zero`() {
        val result = meshCount()

        assertEquals(0, result.count)
        assertEquals(PeerCountTone.NONE, result.tone)
    }

    /**
     * The regression itself: two peers on the device, nothing anywhere reporting "the mesh is up",
     * and the title bar drew 0. There is no connectivity input to this function any more, so a
     * populated peer list can no longer be rendered as an empty room.
     */
    @Test
    fun `peers present with no connectivity signal still show the real count`() {
        val result = meshCount(connectedPeers = listOf("269e37bb6be7caf9", "9343bbdb113d0118"))

        assertEquals(2, result.count, "a known peer list must never render as 0")
    }

    /**
     * A bitchat LoRa heartbeat carries the same 16-hex id the BLE announce does, so the same
     * neighbour arrives on both lists - once bare, once namespaced.
     */
    @Test
    fun `a peer heard over both radios counts once`() {
        val result = meshCount(
            connectedPeers = listOf("269e37bb6be7caf9"),
            loraPeers = listOf(geoPerson("lora-269e37bb6be7caf9")),
        )

        assertEquals(1, result.count)
        assertEquals(PeerCountTone.MESH, result.tone)
    }

    @Test
    fun `duplicate and blank ids do not inflate the count`() {
        val result = meshCount(
            connectedPeers = listOf("269E37BB6BE7CAF9", "269e37bb6be7caf9", "  ", ""),
            loraPeers = listOf(geoPerson("lora-aaaa"), geoPerson("lora-AAAA")),
        )

        assertEquals(2, result.count)
    }

    @Test
    fun `meshtastic and a null channel share the mesh count`() {
        val peers = listOf("269e37bb6be7caf9", "9343bbdb113d0118")

        assertEquals(2, meshCount(connectedPeers = peers, channel = Channel.Meshtastic()).count)
        assertEquals(2, meshCount(connectedPeers = peers, channel = null).count)
    }

    @Test
    fun `a geohash channel counts its own participants`() {
        val result = peerCountFor(
            selectedLocationChannel = Channel.Location(GeohashChannelLevel.CITY, "9q8yy"),
            connectedPeers = listOf("269e37bb6be7caf9"),
            geohashPeople = listOf(geoPerson("npub-a"), geoPerson("npub-b")),
            loraPeers = emptyList(),
        )

        assertEquals(2, result.count, "mesh peers do not belong to a geohash channel")
        assertEquals(PeerCountTone.GEOHASH, result.tone)
    }

    @Test
    fun `an empty geohash channel is grey, not green`() {
        val result = peerCountFor(
            selectedLocationChannel = Channel.Location(GeohashChannelLevel.CITY, "9q8yy"),
            connectedPeers = emptyList(),
            geohashPeople = emptyList(),
            loraPeers = emptyList(),
        )

        assertEquals(0, result.count)
        assertEquals(PeerCountTone.NONE, result.tone)
    }

    @Test
    fun `direct messages and named channels carry no counter`() {
        val channels = listOf(
            Channel.MeshDM(peerID = "269e37bb6be7caf9"),
            Channel.NostrDM(peerID = "npub-a", fullPubkey = "npub-a", sourceGeohash = null),
            Channel.NamedChannel(channelName = "bitcoin"),
        )

        channels.forEach { channel ->
            val result = peerCountFor(
                selectedLocationChannel = channel,
                connectedPeers = listOf("269e37bb6be7caf9"),
                geohashPeople = listOf(geoPerson("npub-a")),
                loraPeers = listOf(geoPerson("lora-aaaa")),
            )

            assertEquals(0, result.count, "$channel renders its own header, without a counter")
            assertEquals(PeerCountTone.NONE, result.tone)
        }
    }
}
