package com.bitchat.client.harness

import com.bitchat.client.RouteAwareClientProvider
import com.bitchat.client.getEngine
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.tor.TorRouteSource
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A Tor route that is READY on [port] with generation 1: the provider must route through it. */
internal class ReadyTorRouteSource(port: Int) : TorRouteSource {
    override val statusFlow: StateFlow<TorStatus> = MutableStateFlow(
        TorStatus(
            mode = TorMode.ON,
            running = true,
            state = TorState.RUNNING,
            socksPort = port,
            routeGeneration = 1,
        ),
    )

    override val isAvailable: Boolean = true

    override fun getSocksProxyAddress(): Pair<String, Int> = "127.0.0.1" to statusFlow.value.socksPort

    override fun isProxyReady(): Boolean = true
}

internal object TorOnIntent : RequestedTorIntent {
    override val current: TorMode = TorMode.ON
    override val updates: StateFlow<TorMode> = MutableStateFlow(TorMode.ON)
}

internal object TorOffIntent : RequestedTorIntent {
    override val current: TorMode = TorMode.OFF
    override val updates: StateFlow<TorMode> = MutableStateFlow(TorMode.OFF)
}

/**
 * The real provider with Ktor Darwin's own SOCKS mapping enabled, as the flag flip would.
 * [engineFactory] defaults to production's engine; the teardown spike passes an owned-session factory.
 */
internal fun harnessProvider(
    intent: RequestedTorIntent,
    socksPort: Int,
    engineFactory: (isDebug: Boolean) -> HttpClientEngineFactory<*> = { getEngine(it) },
) = RouteAwareClientProvider(
    appInformation = AppInformation(Version("test", "test", ""), 1, "test", false),
    requestedIntent = intent,
    torRouteSource = ReadyTorRouteSource(socksPort),
    engineSupportsTorProxy = true,
    engineFactory = engineFactory,
)
