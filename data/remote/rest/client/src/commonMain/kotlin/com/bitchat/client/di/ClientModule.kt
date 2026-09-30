package com.bitchat.client.di

import com.bitchat.client.BaseApiClient
import com.bitchat.client.NostrGeoRelayClient
import com.bitchat.client.model.ClientType
import com.bitchat.client.model.RetryConfig
import com.bitchat.client.RouteAwareClientProvider
import com.bitchat.client.WebSocketRouteProvider
import com.bitchat.client.websocket.NostrWebSocketClient
import com.bitchat.domain.tor.TorRouteLifecycle
import org.koin.dsl.module
import org.koin.dsl.binds

val clientModule = module {
    single {
        RouteAwareClientProvider(
            appInformation = get(),
            requestedIntent = getOrNull(),
            torManager = getOrNull(),
        )
    } binds arrayOf(TorRouteLifecycle::class, WebSocketRouteProvider::class)

    single {
        NostrGeoRelayClient(
            baseApiClient = BaseApiClient(
                routeProvider = get(),
                clientType = ClientType.NOSTR,
                interceptors = listOf(),
                retryConfig = RetryConfig()
            ),
        )
    }

    single {
        NostrWebSocketClient(
            routeProvider = get(),
        )
    }
}
