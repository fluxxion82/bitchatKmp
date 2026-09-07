package com.bitchat.nostr

/**
 * Tells [NostrRelay] whether a relay socket opened right now would actually travel through the
 * Tor SOCKS proxy.
 *
 * This is deliberately narrower than "the user switched Tor on": it has to be false when Tor is
 * enabled but not bootstrapped, when the native Tor library is missing, and on the platforms whose
 * HTTP engine ignores the proxy altogether. Relay log lines are shown to the user as Tor status,
 * so anything less strict makes the app claim protection it is not providing.
 */
fun interface TorProxyStatus {
    fun isRoutingThroughTor(): Boolean
}
