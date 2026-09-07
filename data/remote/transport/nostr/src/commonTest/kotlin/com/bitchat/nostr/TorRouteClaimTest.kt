package com.bitchat.nostr

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These four cases are the ones the user sees as Tor status: [RelayLogFormatter.connected] turns a
 * true into "Tor connection established", which reads as confirmation that Tor is working.
 *
 * The window between the connect call and the reported open is where the HTTP engine's
 * ProxySelector actually runs, so it is the one place a relay log line can lie in either
 * direction. Only under-claiming is acceptable.
 */
class TorRouteClaimTest {

    @Test
    fun `a socket that was on Tor at both ends is reported as Tor`() {
        assertTrue(claimsTorRoute(viaTorAtConnect = true, routingThroughTorNow = true))
    }

    @Test
    fun `tor dropping while the socket opened is not reported as Tor`() {
        // The over-claim this exists to stop: the selector answered NO_PROXY and the socket left
        // directly, but the pre-connect snapshot still said Tor.
        assertFalse(claimsTorRoute(viaTorAtConnect = true, routingThroughTorNow = false))
    }

    @Test
    fun `tor arriving while the socket opened is not reported as Tor either`() {
        // The selector had already sent this one direct. Under-claiming costs the user nothing.
        assertFalse(claimsTorRoute(viaTorAtConnect = false, routingThroughTorNow = true))
    }

    @Test
    fun `a socket with no Tor at either end is reported as direct`() {
        assertFalse(claimsTorRoute(viaTorAtConnect = false, routingThroughTorNow = false))
    }
}
