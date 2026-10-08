package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which addresses the embedded node's sweep may offer to the link policy again.
 *
 * Seen on the terminal UI board on 2026-10-06: bluetoothd had dropped every device object
 * (`busctl tree org.bluez` listed none), gattlib had freed its records with them, and the sweep
 * went on dialling the addresses it had once been told about. Each dial failed at once with
 * `gattlib_connect: Cannot find connection`, once a minute per address, for as long as the app ran.
 */
class KnownPeersTest {

    private val board = "90:82:8D:69:79:2D"
    private val phone = "6E:29:7F:0C:24:8D"

    @Test
    fun aPeerTheScannerNamedIsOfferedAgain() {
        val peers = KnownPeers()

        peers.onSighted(board)
        peers.onSighted(phone)

        // BlueZ announces a device once, so the sweep is what gives a failed connect a second try.
        assertEquals(setOf(board, phone), peers.candidates(held = emptySet()))
    }

    @Test
    fun aPeerWeHoldALinkToIsNotACandidate() {
        val peers = KnownPeers()

        peers.onSighted(board)
        peers.onSighted(phone)

        assertEquals(setOf(phone), peers.candidates(held = setOf(board)))
    }

    @Test
    fun aPeerBlueZNoLongerHasIsNotOfferedAgain() {
        val peers = KnownPeers()
        peers.onSighted(board)
        peers.onSighted(phone)

        // The dial was refused because there is no such device any more. Nothing about that can
        // change until BlueZ sees the peer again, and gattlib announces it when that happens.
        val sighting = peers.sightingOf(board)
        assertTrue(peers.onUnknownToBlueZ(board, sighting))

        assertEquals(setOf(phone), peers.candidates(held = emptySet()))
    }

    @Test
    fun aDroppedPeerIsOfferedAgainOnceTheScannerNamesItAgain() {
        val peers = KnownPeers()
        peers.onSighted(board)
        peers.onUnknownToBlueZ(board, peers.sightingOf(board))

        peers.onSighted(board)

        assertEquals(setOf(board), peers.candidates(held = emptySet()))
    }

    @Test
    fun aPeerNamedAgainWhileItsDialWasBeingRefusedIsKept() {
        val peers = KnownPeers()
        peers.onSighted(board)

        // The dial starts against the device as it was last named ...
        val sightingDialled = peers.sightingOf(board)
        // ... BlueZ re-creates the device and the scanner names it, on another thread, before the
        // refusal of that dial has been recorded.
        peers.onSighted(board)

        // Dropping it now would lose a peer BlueZ does have: it is announced only once, so nothing
        // would ever offer it again.
        assertFalse(peers.onUnknownToBlueZ(board, sightingDialled))
        assertEquals(setOf(board), peers.candidates(held = emptySet()))
    }

    @Test
    fun aRefusalForAPeerNeverNamedChangesNothing() {
        val peers = KnownPeers()
        peers.onSighted(phone)

        assertFalse(peers.onUnknownToBlueZ(board, peers.sightingOf(board)))

        assertEquals(setOf(phone), peers.candidates(held = emptySet()))
    }
}
