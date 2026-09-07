package com.bitchat.nostr

/**
 * Renders relay connection events for the Tor status card.
 *
 * Every line has to be truthful about whether the connection went through Tor: these lines end up
 * in `TorStatus.lastLogLine`, where a "Tor connection established" for a direct socket reads as
 * confirmation that Tor is working.
 */
object RelayLogFormatter {
    fun connectAttempt(relayUrl: String, viaTor: Boolean): String? {
        val endpoint = RelayEndpoint.fromUrl(relayUrl) ?: return null
        return if (viaTor) {
            "SOCKS5 CONNECT to ${endpoint.host}:${endpoint.port}"
        } else {
            "Direct connect to ${endpoint.host}:${endpoint.port} (not via Tor)"
        }
    }

    fun connected(relayUrl: String, viaTor: Boolean): String? {
        val endpoint = RelayEndpoint.fromUrl(relayUrl) ?: return null
        return if (viaTor) {
            "Tor connection established to ${endpoint.host}:${endpoint.port}"
        } else {
            "Direct connection established to ${endpoint.host}:${endpoint.port} (not via Tor)"
        }
    }

    fun disconnected(relayUrl: String, viaTor: Boolean): String? {
        val endpoint = RelayEndpoint.fromUrl(relayUrl) ?: return null
        return if (viaTor) {
            "SOCKS connection closed for ${endpoint.host}:${endpoint.port}"
        } else {
            "Connection closed for ${endpoint.host}:${endpoint.port}"
        }
    }
}

private data class RelayEndpoint(
    val host: String,
    val port: Int,
) {
    companion object {
        fun fromUrl(relayUrl: String): RelayEndpoint? {
            val trimmed = relayUrl.trim()
            if (trimmed.isEmpty()) return null

            val isSecure = trimmed.startsWith("wss://", ignoreCase = true)
            val schemeSplit = trimmed
                .removePrefix("wss://")
                .removePrefix("WSS://")
                .removePrefix("ws://")
                .removePrefix("WS://")

            val hostPort = schemeSplit.substringBefore("/")
            if (hostPort.isEmpty()) return null

            val parts = hostPort.split(":", limit = 2)
            val host = parts[0]
            if (host.isEmpty()) return null

            val port = if (parts.size > 1) {
                parts[1].toIntOrNull() ?: return null
            } else {
                if (isSecure) 443 else 80
            }

            return RelayEndpoint(host = host, port = port)
        }
    }
}
