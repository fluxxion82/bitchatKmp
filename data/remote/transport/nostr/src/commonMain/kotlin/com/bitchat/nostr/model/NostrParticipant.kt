package com.bitchat.nostr.model

import kotlin.time.Instant

/**
 * Represents a participant in a Nostr geohash channel.
 * This is the nostr module's internal model.
 */
data class NostrParticipant(
    val id: String,           // pubkey hex (lowercased)
    val displayName: String,  // nickname
    val lastSeen: Instant     // activity timestamp
)
