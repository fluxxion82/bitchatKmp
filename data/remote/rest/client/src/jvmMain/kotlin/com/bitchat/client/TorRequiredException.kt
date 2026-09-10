package com.bitchat.client

import java.io.IOException

/**
 * Thrown instead of connecting, when the user has asked for Tor and there is no proven Tor to send
 * the request through.
 *
 * An [IOException] so it travels the same path as any other network failure and nothing has to be
 * taught a new failure mode; typed so callers that want to explain it can. The alternative -- a
 * silent direct connection -- is the failure this whole exercise exists to prevent.
 */
class TorRequiredException(host: String) : IOException(
    "Refusing to connect to $host: Tor is requested but its SOCKS proxy is not listening"
)
