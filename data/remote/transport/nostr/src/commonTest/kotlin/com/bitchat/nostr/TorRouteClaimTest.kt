package com.bitchat.nostr

import com.bitchat.client.TorRouteProvenance
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
    fun `a proxied session in the current route generation is reported as Tor`() {
        assertTrue(claimsTorRoute(Route(true, true)))
    }

    @Test
    fun `a proxied session from a revoked generation is not reported as Tor`() {
        assertFalse(claimsTorRoute(Route(true, false)))
    }

    @Test
    fun `a direct session in the current generation is not reported as Tor`() {
        assertFalse(claimsTorRoute(Route(false, true)))
    }

    @Test
    fun `a missing session provenance is not reported as Tor`() {
        assertFalse(claimsTorRoute(null))
    }

    @Test
    fun `clearing claims after a terminal Tor transition removes prior session evidence`() {
        val claims = TorRouteClaims()
        claims.remember("wss://relay.example", Route(true, true))

        val retired = claims.removeAll()

        assertTrue(retired.single() == ("wss://relay.example" to true))
        assertFalse(claims.claimsTorRoute("wss://relay.example"))
    }

    private class Route(
        override val usedTorProxy: Boolean,
        private val current: Boolean,
    ) : TorRouteProvenance {
        override fun isCurrent(): Boolean = current
    }
}
