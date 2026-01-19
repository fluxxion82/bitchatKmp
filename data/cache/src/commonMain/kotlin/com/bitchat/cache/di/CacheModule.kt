package com.bitchat.cache.di

import com.bitchat.cache.Cache
import com.bitchat.cache.impl.InMemoryCache
import org.koin.core.qualifier.named
import org.koin.dsl.module

const val DEVICE_CACHE_NAME = "device_cache"
const val GEOHASH_ALIAS_CACHE = "geohashAliasRegistry"
const val GEOHASH_CONVERSATION_CACHE = "geohashConversationRegistry"
const val RELAY_INFO_CACHE = "relayInfoCache"

val cacheModule = module {
    single<Cache<String, String>>(named(DEVICE_CACHE_NAME)) {
        InMemoryCache()
    }
}
