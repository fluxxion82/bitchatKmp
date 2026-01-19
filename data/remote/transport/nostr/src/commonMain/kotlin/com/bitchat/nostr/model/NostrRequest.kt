package com.bitchat.nostr.model

/**
 * Nostr protocol request messages
 * Supports EVENT, REQ, and CLOSE message types
 */
sealed class NostrRequest {

    /**
     * EVENT message - publish an event
     */
    data class Event(val event: NostrEvent) : NostrRequest()

    /**
     * REQ message - subscribe to events
     */
    data class Subscribe(
        val subscriptionId: String,
        val filters: List<NostrFilter>
    ) : NostrRequest()

    /**
     * CLOSE message - close a subscription
     */
    data class Close(val subscriptionId: String) : NostrRequest()
}
