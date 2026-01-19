package com.bitchat.client.websocket

interface WebSocketListener {
    fun onOpen(url: String)
    fun onMessage(url: String, text: String)
    fun onClosing(url: String, code: Int, reason: String)
    fun onClosed(url: String, code: Int, reason: String)
    fun onFailure(url: String, t: Throwable)
}