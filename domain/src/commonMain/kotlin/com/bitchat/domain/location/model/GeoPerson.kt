package com.bitchat.domain.location.model

import kotlin.time.Instant

data class GeoPerson(
    val id: String,
    val displayName: String,
    val lastSeen: Instant,
)
