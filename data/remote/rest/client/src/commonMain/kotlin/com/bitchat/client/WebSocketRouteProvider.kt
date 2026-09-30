package com.bitchat.client

import io.ktor.client.HttpClient

/** Evidence attached to one opened route, never inferred from current Tor readiness alone. */
interface TorRouteProvenance {
    val usedTorProxy: Boolean
    fun isCurrent(): Boolean
}

interface WebSocketRouteProvider {
    suspend fun <T> useWebSocketRoute(block: suspend (HttpClient, TorRouteProvenance) -> T): T
}
