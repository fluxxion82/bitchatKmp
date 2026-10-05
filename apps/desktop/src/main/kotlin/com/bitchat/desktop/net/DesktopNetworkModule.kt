package com.bitchat.desktop.net

import com.bitchat.client.HttpEngineProvider
import com.bitchat.client.TorEnforcingEngineProvider
import com.bitchat.domain.base.CoroutineScopeFacade
import org.koin.dsl.module

/**
 * Turns on Tor enforcement, for the desktop app only.
 *
 * The policy is registered here rather than built into `rest:client` because that module publishes
 * a single JVM artifact which the Android app also consumes: enforcing there would block Android's
 * traffic through bootstrap and its retry backoff as a side effect. Binding an [HttpEngineProvider]
 * is opt-in, so every other target keeps the default engine.
 *
 * Must be registered after `clientModule`, whose client definitions resolve this optionally.
 */
val desktopNetworkModule = module {
    single<HttpEngineProvider> {
        TorEnforcingEngineProvider(
            requestedIntent = get(),
            torManager = get(),
            scope = get<CoroutineScopeFacade>().applicationScope,
        )
    }
}
