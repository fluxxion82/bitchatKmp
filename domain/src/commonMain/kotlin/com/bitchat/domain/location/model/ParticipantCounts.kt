package com.bitchat.domain.location.model

data class ParticipantCounts(
    val geohashCounts: Map<String, Int> = emptyMap(),
    val meshCount: Int = 0
)
