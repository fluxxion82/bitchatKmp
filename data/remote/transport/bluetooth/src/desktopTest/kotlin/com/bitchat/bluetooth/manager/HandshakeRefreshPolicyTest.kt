package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals

class HandshakeRefreshPolicyTest {

    private val policy = HandshakeRefreshPolicy()
    private val grace = HandshakeRefreshPolicy.HANDSHAKE_RESTART_GRACE_MS

    @Test
    fun aResponderHandshakeIsLeftAlone() {
        // The peer sent message 1 and is waiting for our message 3 to be answered. We have no
        // handshake of our own to restart, and discarding this one strands the peer.
        assertEquals(
            HandshakeRefreshPolicy.Decision.LEAVE,
            policy.decide(established = false, handshaking = true, ourHandshakeStartedAt = null, now = 10_000L, owed = false)
        )
    }

    @Test
    fun aResponderHandshakeIsLeftAloneEvenWhenAHandshakeIsOwed() {
        assertEquals(
            HandshakeRefreshPolicy.Decision.LEAVE,
            policy.decide(established = false, handshaking = true, ourHandshakeStartedAt = null, now = 10_000L, owed = true)
        )
    }

    @Test
    fun ourOwnStaleHandshakeIsRestarted() {
        assertEquals(
            HandshakeRefreshPolicy.Decision.RESTART,
            policy.decide(established = false, handshaking = true, ourHandshakeStartedAt = 0L, now = grace, owed = false)
        )
    }

    @Test
    fun ourOwnFreshHandshakeIsGivenTimeToLand() {
        assertEquals(
            HandshakeRefreshPolicy.Decision.LEAVE,
            policy.decide(established = false, handshaking = true, ourHandshakeStartedAt = 0L, now = grace - 1, owed = false)
        )
    }

    @Test
    fun anEstablishedSessionIsNeverDisturbed() {
        assertEquals(
            HandshakeRefreshPolicy.Decision.LEAVE,
            policy.decide(established = true, handshaking = false, ourHandshakeStartedAt = null, now = 10_000L, owed = true)
        )
    }

    @Test
    fun anOwedHandshakeIsSentOnceTheLinkExists() {
        assertEquals(
            HandshakeRefreshPolicy.Decision.INITIATE,
            policy.decide(established = false, handshaking = false, ourHandshakeStartedAt = null, now = 10_000L, owed = true)
        )
    }

    @Test
    fun aPeerWeOweNothingIsLeftAlone() {
        assertEquals(
            HandshakeRefreshPolicy.Decision.LEAVE,
            policy.decide(established = false, handshaking = false, ourHandshakeStartedAt = null, now = 10_000L, owed = false)
        )
    }
}
