package com.bitchat.client.websocket

import com.bitchat.client.TorRouteProvenance
import com.bitchat.client.WebSocketRouteProvider

class NostrWebSocketClient(
    routeProvider: WebSocketRouteProvider
) {
    private val wsClient = KtorWebSocketClient(routeProvider)
    fun connect(
        relayUrl: String,
        listener: NostrWebSocketListener,
        maxReconnectAttempts: Int = 10,
        initialBackoffMs: Long = 1000L,
        maxBackoffMs: Long = 60000L
    ) {
        val adaptedListener = object : WebSocketListener {
            override fun onOpen(url: String, route: TorRouteProvenance) {
                listener.onOpen(relayUrl, route)
            }

            override fun onMessage(url: String, text: String) {
                listener.onMessage(relayUrl, text)
            }

            override fun onClosing(url: String, code: Int, reason: String) {
                listener.onClosing(relayUrl, code, reason)
            }

            override fun onClosed(url: String, code: Int, reason: String) {
                listener.onClosed(relayUrl, code, reason)
            }

            override fun onFailure(url: String, t: Throwable) {
                listener.onFailure(relayUrl, t)
            }
        }

        wsClient.connect(
            url = relayUrl,
            listener = adaptedListener,
            maxReconnectAttempts = maxReconnectAttempts,
            initialBackoffMs = initialBackoffMs,
            maxBackoffMs = maxBackoffMs
        )
    }

    suspend fun send(relayUrl: String, message: String) {
        wsClient.send(relayUrl, message)
    }

    suspend fun disconnect(relayUrl: String) {
        wsClient.disconnect(relayUrl)
    }

    fun isConnecting(relayUrl: String): Boolean {
        return wsClient.isConnecting(relayUrl)
    }

    fun isConnected(relayUrl: String): Boolean {
        return wsClient.isConnected(relayUrl)
    }

    fun shutdown() {
        wsClient.shutdown()
    }
}

interface NostrWebSocketListener {
    fun onOpen(relayUrl: String, route: TorRouteProvenance)
    fun onMessage(relayUrl: String, text: String)
    fun onClosing(relayUrl: String, code: Int, reason: String)
    fun onClosed(relayUrl: String, code: Int, reason: String)
    fun onFailure(relayUrl: String, t: Throwable)
}
