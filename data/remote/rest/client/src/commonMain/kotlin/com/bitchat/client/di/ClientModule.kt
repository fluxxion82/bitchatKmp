package com.bitchat.client.di

import com.bitchat.client.BaseApiClient
import com.bitchat.client.NostrGeoRelayClient
import com.bitchat.client.HttpEngineProvider
import com.bitchat.client.getEngine
import com.bitchat.client.ktorHttpClient
import com.bitchat.client.ktorWebSocketHttpClient
import com.bitchat.client.model.ClientType
import com.bitchat.domain.initialization.models.AppInformation
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
                    // Present only where a platform has registered one; elsewhere the default
                    // engine is used and nothing about routing changes.
                    engine = getOrNull<HttpEngineProvider>()?.engine()
                        ?: getEngine(get<AppInformation>().debug, getOrNull()),
                ),
                retryConfig = RetryConfig()
            ),
        )
    }

    single {
        NostrWebSocketClient(
            httpClient = ktorWebSocketHttpClient(
                torManager = getOrNull(),
                engine = getOrNull<HttpEngineProvider>()?.engine()
                    ?: getEngine(get<AppInformation>().debug, getOrNull()),
            )
        )
    }
}
