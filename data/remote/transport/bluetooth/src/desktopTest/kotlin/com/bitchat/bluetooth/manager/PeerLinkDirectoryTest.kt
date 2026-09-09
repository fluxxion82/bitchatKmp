package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recognising one device behind several addresses.
 *
 * The addresses in these tests are the ones the device journal recorded for a single Android phone,
 * peer `269e37bb6be7caf9`, inside a few minutes. Nothing about them relates them to each other:
 * only the peer ID in the packets they carried does.
 */
class PeerLinkDirectoryTest {

    private val phone = "269e37bb6be7caf9"
    private val otherPeer = "9343bbdb113d0118"

    private val first = "5C:00:46:51:7B:0E"
    private val second = "63:F5:53:74:B0:6F"
    private val third = "74:6D:62:4B:4E:57"

    @Test
    fun anAddressStillCarryingTrafficIsNotSuperseded() {
        // A relayed packet carries its ORIGINATOR's peer ID but arrives on the RELAY's address, so
        // the originator looks like it moved. It has not: both links are live. Superseding here
        // tore down the peer's real link, and the mesh then rebuilt and tore it down again -- the
        // Pixel 4 XL journal shows peer 9343bbdb "moving" 54 times between two addresses, and the
        // Pi (one fixed public address, incapable of moving) doing it 11 times.
        val directory = PeerLinkDirectory()
        directory.bind(phone, first, now = 0L)

        // The peer keeps using `first` while a relayed copy of its packet arrives on `second`.
        directory.bind(phone, first, now = 20_000L)
        val binding = directory.bind(phone, second, now = 21_000L)

        assertTrue(binding.isNewLink)
        assertTrue(
            binding.superseded.isEmpty(),
            "an address seen 1s ago is a live link, not one the peer moved off"
        )
    }

    @Test
    fun anAddressGoneQuietIsSuperseded() {
        // Genuine RPA rotation: the old address stops carrying anything the moment the phone
        // rotates, so once it has been quiet past the window it is released as before.
        val directory = PeerLinkDirectory()
        directory.bind(phone, first, now = 0L)

        val binding = directory.bind(phone, second, now = 0L + PeerLinkDirectory.LIVE_LINK_WINDOW_MS + 1)

        assertTrue(binding.isNewLink)
        assertEquals(listOf(first), binding.superseded)
    }

    @Test
    fun aFirstSightingIsANewLinkAndSupersedesNothing() {
        val directory = PeerLinkDirectory()

        val binding = directory.bind(phone, first, now = 60000L)

        assertTrue(binding.isNewLink)
        assertTrue(binding.superseded.isEmpty())
        assertEquals(phone, directory.peerFor(first))
    }

    @Test
    fun seeingTheSamePeerOnTheSameAddressAgainIsNotANewLink() {
        val directory = PeerLinkDirectory()
        directory.bind(phone, first, now = 120000L)

        val binding = directory.bind(phone, first, now = 180000L)

        assertTrue(!binding.isNewLink)
        assertTrue(binding.superseded.isEmpty())
    }

    @Test
    fun aRotatedAddressSupersedesTheOnesThePeerLeftBehind() {
        val directory = PeerLinkDirectory()
        directory.bind(phone, first, now = 240000L)
        directory.bind(phone, second, now = 300000L)

        val binding = directory.bind(phone, third, now = 360000L)

        assertTrue(binding.isNewLink)
        assertEquals(setOf(first, second), binding.superseded.toSet())

        // The peer is still known on all three until the caller releases the old links; releasing
        // is the caller's job because it also has to drop the radio link.
        assertEquals(setOf(first, second, third), directory.addressesFor(phone))
    }

    @Test
    fun releasingAnAddressLeavesThePeerReachableOnTheOthers() {
        val directory = PeerLinkDirectory()
        directory.bind(phone, first, now = 420000L)
        directory.bind(phone, second, now = 480000L)

        directory.release(first)

        assertNull(directory.peerFor(first))
        assertEquals(phone, directory.peerFor(second))
        assertEquals(setOf(second), directory.addressesFor(phone))
    }

    @Test
    fun releasingThePeersLastAddressForgetsThePeer() {
        val directory = PeerLinkDirectory()
        directory.bind(phone, first, now = 540000L)

        directory.release(first)

        assertTrue(directory.addressesFor(phone).isEmpty())
        assertTrue(directory.snapshot().isEmpty())
    }

    @Test
    fun anAddressHandedToADifferentPeerIsRePointed() {
        val directory = PeerLinkDirectory()
        directory.bind(phone, first, now = 600000L)

        // BlueZ hands out an address to one device at a time, so a resolvable private address that
        // now carries another peer's packets has been recycled.
        val binding = directory.bind(otherPeer, first, now = 660000L)

        assertTrue(binding.isNewLink)
        assertEquals(otherPeer, directory.peerFor(first))
        assertTrue(directory.addressesFor(phone).isEmpty())
    }

    @Test
    fun theSnapshotSeesEveryBoundAddress() {
        val directory = PeerLinkDirectory()
        directory.bind(phone, first, now = 720000L)
        directory.bind(phone, second, now = 780000L)
        directory.bind(otherPeer, third, now = 840000L)

        assertEquals(
            mapOf(first to phone, second to phone, third to otherPeer),
            directory.snapshot()
        )

        // This is what the connection service reads to answer "am I already linked to this peer?"
        // without suspending: the addresses of the peer behind one address.
        val snapshot = directory.snapshot()
        val addressesOfPhone = snapshot.filterValues { it == snapshot[second] }.keys
        assertEquals(setOf(first, second), addressesOfPhone)
    }

    @Test
    fun clearForgetsEverything() {
        val directory = PeerLinkDirectory()
        directory.bind(phone, first, now = 900000L)
        directory.bind(otherPeer, third, now = 960000L)

        directory.clear()

        assertTrue(directory.snapshot().isEmpty())
        assertNull(directory.peerFor(first))
        assertNull(directory.peerFor(third))
    }
}
