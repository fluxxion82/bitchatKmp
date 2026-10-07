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

            override fun onBacklogDrained(url: String, framesDropped: Long) {
                listener.onBacklogDrained(relayUrl, framesDropped)
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

    suspend fun shutdown() {
        wsClient.shutdown()
    }

    /** What [relayUrl] has in flight and has lost to its limits; null for a relay never connected to. */
    internal fun inboundState(relayUrl: String): InboundState? = wsClient.inboundState(relayUrl)
}

interface NostrWebSocketListener {
    fun onOpen(relayUrl: String, route: TorRouteProvenance)
    fun onMessage(relayUrl: String, text: String)
    fun onClosing(relayUrl: String, code: Int, reason: String)
    fun onClosed(relayUrl: String, code: Int, reason: String)
    fun onFailure(relayUrl: String, t: Throwable)

    /**
     * [relayUrl] lost [framesDropped] frames because it had its limit in flight, and everything that
     * was in flight then has been handled: its backlog was empty when this was decided (a frame that
     * arrived since may already be on its way). Nobody knows what was in the lost frames; what the
     * relay stores can be had by asking again. Like any other call here, this one can still arrive
     * just after a disconnect.
     */
    fun onBacklogDrained(relayUrl: String, framesDropped: Long) {}
}
