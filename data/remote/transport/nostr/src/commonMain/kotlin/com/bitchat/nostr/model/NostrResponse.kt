package com.bitchat.nostr.model

import com.bitchat.nostr.util.NostrResponseSerializer
import kotlinx.serialization.Serializable

/**
 * Nostr protocol response messages
 * Handles EVENT, EOSE, OK, and NOTICE responses
 */
@Serializable(with = NostrResponseSerializer::class)
sealed class NostrResponse {

    /**
     * EVENT response - received event from subscription
     */
    @Serializable
    data class Event(
        val subscriptionId: String,
        val event: NostrEvent
    ) : NostrResponse()

    /**
     * EOSE response - end of stored events
     */
    @Serializable
    data class EndOfStoredEvents(
        val subscriptionId: String
    ) : NostrResponse()

    /**
     * OK response - event publication result
     */
    @Serializable
    data class Ok(
        val eventId: String,
        val accepted: Boolean,
        val message: String?
    ) : NostrResponse()

    /**
     * NOTICE response - relay notice
     */
    @Serializable
    data class Notice(
        val message: String
    ) : NostrResponse()

    /**
     * Unknown response type
     */
    @Serializable
    data class Unknown(
        val raw: String
    ) : NostrResponse()
}
