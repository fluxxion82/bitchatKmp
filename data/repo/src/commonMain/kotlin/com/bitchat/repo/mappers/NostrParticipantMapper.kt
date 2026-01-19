package com.bitchat.repo.mappers

import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.nostr.model.NostrParticipant

fun NostrParticipant.toDomain(): GeoPerson {
    return GeoPerson(
        id = this.id,
        displayName = this.displayName,
        lastSeen = this.lastSeen
    )
}

fun List<NostrParticipant>.toDomain(): List<GeoPerson> {
    return this.map { it.toDomain() }
}
