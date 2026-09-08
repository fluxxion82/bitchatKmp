package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GattClientRegistryTest {

    // The two resolvable private addresses one Android phone presented to the Pi in the captured
    // failure, plus a third peer.
    private val phoneFirstAddress = "5C:00:46:51:7B:0E"
    private val phoneSecondAddress = "63:F5:53:74:B0:6F"
    private val otherPeer = "64:A8:3E:11:22:33"

    @Test
    fun aClientThatDisconnectsIsRemoved() {
        val registry = GattClientRegistry()
        registry.onConnected(phoneFirstAddress)
        assertTrue(registry.isConnected(phoneFirstAddress))

        assertTrue(registry.onDisconnected(phoneFirstAddress), "the drop should be reported once")

        assertFalse(registry.isConnected(phoneFirstAddress))
        assertTrue(registry.isEmpty())
    }

    @Test
    fun droppingAClientTwiceIsReportedOnlyOnce() {
        val registry = GattClientRegistry()
        registry.onConnected(phoneFirstAddress)

        assertTrue(registry.onDisconnected(phoneFirstAddress))
        // Connected=false and InterfacesRemoved can both arrive for one link; the second must not
        // reach the delegate again.
        assertFalse(registry.onDisconnected(phoneFirstAddress))
    }

    @Test
    fun connectingTwiceIsReportedOnlyOnce() {
        val registry = GattClientRegistry()

        assertTrue(registry.onConnected(phoneFirstAddress))
        assertFalse(registry.onConnected(phoneFirstAddress))
        assertEquals(1, registry.size())
    }

    @Test
    fun anAddressRotationLeavesNoStaleEntryBehind() {
        val registry = GattClientRegistry()

        // What the journal showed: a phone connects, drops, and comes back under a new address.
        registry.onConnected(phoneFirstAddress)
        registry.onDisconnected(phoneFirstAddress)
        registry.onConnected(phoneSecondAddress)
        registry.onDisconnected(phoneSecondAddress)
        registry.onConnected(otherPeer)

        assertEquals(setOf(otherPeer), registry.addresses())
    }

    @Test
    fun aSendToAPeerWithNoLiveLinkIsNotDeliverable() {
        val registry = GattClientRegistry()
        registry.onConnected(phoneFirstAddress)
        registry.onConnected(phoneSecondAddress)
        registry.onDisconnected(phoneFirstAddress)
        registry.onDisconnected(phoneSecondAddress)

        val targets = registry.partitionTargets(listOf(phoneFirstAddress, phoneSecondAddress))

        assertFalse(targets.deliverable, "a broadcast into dead links must not report success")
        assertEquals(emptyList(), targets.live)
        assertEquals(listOf(phoneFirstAddress, phoneSecondAddress), targets.stale)
    }

    @Test
    fun aBroadcastReportsWhichTargetsAreLiveAndWhichAreStale() {
        val registry = GattClientRegistry()
        registry.onConnected(phoneSecondAddress)

        val targets = registry.partitionTargets(listOf(phoneFirstAddress, phoneSecondAddress, otherPeer))

        assertTrue(targets.deliverable)
        assertEquals(listOf(phoneSecondAddress), targets.live)
        assertEquals(listOf(phoneFirstAddress, otherPeer), targets.stale)
    }

    @Test
    fun addressesIsASnapshot() {
        val registry = GattClientRegistry()
        registry.onConnected(phoneFirstAddress)

        val snapshot = registry.addresses()
        registry.onDisconnected(phoneFirstAddress)

        assertEquals(setOf(phoneFirstAddress), snapshot)
        assertTrue(registry.addresses().isEmpty())
    }
}
