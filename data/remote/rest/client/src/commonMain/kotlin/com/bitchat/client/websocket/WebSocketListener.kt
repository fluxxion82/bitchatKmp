package com.bitchat.client.websocket

import com.bitchat.client.TorRouteProvenance

interface WebSocketListener {
    fun onOpen(url: String, route: TorRouteProvenance)
    fun onMessage(url: String, text: String)
    fun onClosing(url: String, code: Int, reason: String)
    fun onClosed(url: String, code: Int, reason: String)
    fun onFailure(url: String, t: Throwable)
}
