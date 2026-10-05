package com.bitchat.domain.chat.model

import kotlin.time.Instant

/** A peer discovered by the active LoRa transport. */
data class LoRaPerson(
    val id: String,
    val displayName: String,
    val lastSeen: Instant,
    val meshDeviceId: String?,
)
