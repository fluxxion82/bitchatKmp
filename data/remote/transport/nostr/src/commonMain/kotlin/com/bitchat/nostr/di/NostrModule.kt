package com.bitchat.nostr.di

import com.bitchat.cache.Cache
import com.bitchat.cache.di.GEOHASH_ALIAS_CACHE
import com.bitchat.cache.di.GEOHASH_CONVERSATION_CACHE
import com.bitchat.cache.di.RELAY_INFO_CACHE
import com.bitchat.cache.impl.InMemoryCache
import com.bitchat.cache.impl.ThreadSafeCache
import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.NostrRelay
import com.bitchat.nostr.NostrTransport
import com.bitchat.nostr.model.RelayInfo
import com.bitchat.nostr.participant.NostrParticipantTracker
import com.bitchat.nostr.util.NostrEventDeduplicator
import com.bitchat.transport.TransportIdentityProvider
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

expect val nostrPlatformModule: Module

val nostrModule = module {
    includes(nostrPlatformModule)

    single<Cache<String, String>>(named(GEOHASH_ALIAS_CACHE)) {
        ThreadSafeCache(InMemoryCache())
    }

    single<Cache<String, String>>(named(GEOHASH_CONVERSATION_CACHE)) {
        ThreadSafeCache(InMemoryCache())
    }

    single<Cache<String, RelayInfo>>(named(RELAY_INFO_CACHE)) {
        ThreadSafeCache(InMemoryCache())
    }

    single {
        NostrClient(nostrPreferences = get(), identityProvider = get<TransportIdentityProvider>())
    }

    single {
        NostrEventDeduplicator()
    }

    single {
        NostrRelay(
            eventDeduplicator = get(),
            wsClient = get(),
            relayCache = get(named(RELAY_INFO_CACHE)),
            relayLogSink = getOrNull()
        )
    }

    single {
        NostrTransport(
            senderPeerID = "", // Will be set when user identity is established
            nostrClient = get(),
            nostrRelay = get()
        )
    }

    single {
        NostrParticipantTracker()
    }
}
