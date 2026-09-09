package com.bitchat.bluetooth.linux

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A central that subscribes and never writes must still be reachable.
 *
 * BlueZ raises `StartNotify` once per characteristic and it carries no device identity, so such a
 * peer never enters the write-driven client registry. The notification itself is a signal on the
 * characteristic's own object path, which names no device, so it reaches the peer anyway --
 * refusing to emit is a self-inflicted deadlock, and it is the one that left the Orange Pi
 * subscribing, waiting, and giving up twice in a single session.
 *
 * The production decision lives in `LinuxGattServerService.canReachSubscriber()`; this pins the
 * rule itself so a future edit cannot quietly restore the registry-only gate.
 */
class SubscriberReachabilityTest {

    /** The same predicate `canReachSubscriber()` implements. */
    private fun canReach(registeredClients: Int, subscribed: AtomicBoolean): Boolean =
        registeredClients > 0 || subscribed.get()

    @Test
    fun `a subscriber that has never written is reachable`() {
        assertTrue(
            canReach(registeredClients = 0, subscribed = AtomicBoolean(true)),
            "StartNotify carries no device identity, so such a peer is absent from the registry, " +
                "but a device-agnostic notification still reaches it. Refusing to emit deadlocks " +
                "the link: it waits for our announce and we wait for its write."
        )
    }

    @Test
    fun `a registered client is reachable with no subscription flag`() {
        assertTrue(canReach(registeredClients = 1, subscribed = AtomicBoolean(false)))
    }

    @Test
    fun `no clients and no subscribers is not reachable`() {
        assertFalse(
            canReach(registeredClients = 0, subscribed = AtomicBoolean(false)),
            "broadcastPacket must keep reporting false into an empty room, or a Noise handshake " +
                "spends its whole retry budget on packets nobody can receive"
        )
    }

    @Test
    fun `unsubscribing clears reachability when no client is registered`() {
        val subscribed = AtomicBoolean(true)
        assertTrue(canReach(0, subscribed))
        subscribed.set(false)
        assertFalse(canReach(0, subscribed), "StopNotify means the last subscriber went away")
    }

    @Test
    fun `a registered client outlives the subscription flag`() {
        val subscribed = AtomicBoolean(true)
        subscribed.set(false)
        assertTrue(
            canReach(registeredClients = 2, subscribed = subscribed),
            "StopNotify says the last subscriber unsubscribed, not that written-to links are gone"
        )
    }
}
