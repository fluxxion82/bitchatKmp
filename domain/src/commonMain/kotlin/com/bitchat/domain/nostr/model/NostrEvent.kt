package com.bitchat.domain.nostr.model

sealed class NostrEvent {
    data object PowSettingsUpdated : NostrEvent()
}
