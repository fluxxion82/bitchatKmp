package com.bitchat.domain.nostr.model

data class PowSettings(
    val enabled: Boolean = false,
    val difficulty: Int = 12,
    val isMining: Boolean = false
)
