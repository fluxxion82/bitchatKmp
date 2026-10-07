package com.bitchat.client.websocket

import com.bitchat.client.TorRouteProvenance

interface WebSocketListener {
    fun onOpen(url: String, route: TorRouteProvenance)
    fun onMessage(url: String, text: String)
    fun onClosing(url: String, code: Int, reason: String)
    fun onClosed(url: String, code: Int, reason: String)
    fun onFailure(url: String, t: Throwable)

    /**
     * [url] lost [framesDropped] frames because it had its limit in flight, and everything that was
     * in flight then has been handled: its backlog was empty when this was decided (a frame that
     * arrived since may already be on its way). Nobody knows what was in the lost frames; what the
     * relay stores can be had by asking again. Like any other call here, this one can still arrive
     * just after a disconnect.
     */
    fun onBacklogDrained(url: String, framesDropped: Long) {}
}
