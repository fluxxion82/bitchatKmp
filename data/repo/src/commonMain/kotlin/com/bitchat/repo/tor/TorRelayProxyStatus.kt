package com.bitchat.repo.tor

import com.bitchat.nostr.TorProxyStatus
import com.bitchat.repo.repositories.TorRepo

/**
 * Answers "is this relay socket really going over Tor?" for the relay log lines.
 *
 * [TorRepo.isProxyReady] is the whole answer: it is false whenever Tor is off, still bootstrapping,
 * unavailable because the native library is missing, or - on the Apple and Linux engines - because
 * nothing in this build can be routed through a SOCKS proxy in the first place.
 */
class TorRelayProxyStatus(
    private val torRepo: TorRepo,
) : TorProxyStatus {
    override fun isRoutingThroughTor(): Boolean = torRepo.isProxyReady()
}
