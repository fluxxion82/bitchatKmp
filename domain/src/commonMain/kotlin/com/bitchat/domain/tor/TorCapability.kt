package com.bitchat.domain.tor

/**
 * Whether this platform can route network traffic through Tor right now.
 *
 * A fresh Tor preference defaults to OFF unless both the HTTP engine can proxy and the Tor
 * library is available. This avoids the former JVM trap where an ON default could block relay
 * traffic when Arti was unavailable, and keeps Apple OFF until its engine supports proxying.
 */
fun interface TorCapability {
    fun canRouteTraffic(): Boolean
}
