package com.bitchat.nostr

object RelayLogFormatter {
    fun connectAttempt(relayUrl: String): String? {
        val endpoint = RelayEndpoint.fromUrl(relayUrl) ?: return null
        return "SOCKS5 CONNECT to ${endpoint.host}:${endpoint.port}"
    }

    fun connected(relayUrl: String): String? {
        val endpoint = RelayEndpoint.fromUrl(relayUrl) ?: return null
        return "Tor connection established to ${endpoint.host}:${endpoint.port}"
    }

    fun disconnected(relayUrl: String): String? {
        val endpoint = RelayEndpoint.fromUrl(relayUrl) ?: return null
        return "SOCKS connection closed for ${endpoint.host}:${endpoint.port}"
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
