package com.bitchat.client.di

import com.bitchat.client.BaseApiClient
import com.bitchat.client.NostrGeoRelayClient
import com.bitchat.client.ktorHttpClient
import com.bitchat.client.ktorWebSocketHttpClient
import com.bitchat.client.model.ClientType
import com.bitchat.client.model.RetryConfig
import com.bitchat.client.websocket.NostrWebSocketClient
import org.koin.dsl.module

val clientModule = module {
    single {
        NostrGeoRelayClient(
            baseApiClient = BaseApiClient(
                client = ktorHttpClient(
                    clientType = ClientType.NOSTR,
                    interceptors = listOf(),
                    torManager = getOrNull(),
                ),
                retryConfig = RetryConfig()
            ),
        )
    }

    single {
        NostrWebSocketClient(
            httpClient = ktorWebSocketHttpClient(
                torManager = getOrNull()
            )
        )
    }
}
