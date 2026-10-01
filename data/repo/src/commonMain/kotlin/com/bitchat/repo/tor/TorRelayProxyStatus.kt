package com.bitchat.repo.tor

import com.bitchat.nostr.TorProxyStatus
import com.bitchat.repo.repositories.TorRepo

/**
 * Answers "is this relay socket really going over Tor?" for the relay log lines.
 *
 * [TorRepo.isProxyReady] is the manager's side of the answer: it is false whenever Tor is off, still
 * bootstrapping, stopped in the background, unavailable because the native library is missing, or
 * on an engine that cannot be routed through a SOCKS proxy. Whether a particular socket was opened
 * through Tor is the route provenance of the routed client that owns it.
 */
class TorRelayProxyStatus(
    private val torRepo: TorRepo,
) : TorProxyStatus {
    override fun isRoutingThroughTor(): Boolean = torRepo.isProxyReady()
}
