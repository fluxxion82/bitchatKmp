package com.bitchat.client

import com.bitchat.client.model.ClientType
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.TorRouteLifecycle
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.tor.TorRouteSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TorRouteRequiredException : IllegalStateException("Tor is requested but no ready SOCKS route exists")

/** Implemented by engines whose native sockets outlive the client coroutine job. */
internal interface RouteTeardownAwaiter {
    /** Where [awaitRouteTeardown] is waiting, for the error when it does not finish. */
    val teardownStage: String? get() = null

    suspend fun awaitRouteTeardown()
}

class RoutedHttpClient internal constructor(
    val client: HttpClient,
    private val routes: RouteGenerations,
    private val routeLease: RouteLease,
    val provenance: TorRouteProvenance,
) : TorRouteProvenance by provenance {
    override fun isCurrent(): Boolean = provenance.isCurrent()
    fun close() = client.close()
    suspend fun closeAndJoin() {
        var step = CLOSING_CLIENT
        try {
            closeAndJoinOnce { step = it }
        } catch (error: Throwable) {
            // Preserve this exact native shutdown owner after the tracked coroutine completes.
            routes.retainFailedRetirement(blocksTorEnable = !provenance.usedTorProxy) {
                closeAndJoinOnce()
            }
            throw RouteRetirementException(
                "Outbound transport did not close within 15 seconds (still ${stepDescription(step)})",
                error,
            )
        }
    }

    private suspend fun closeAndJoinOnce(inStep: (String) -> Unit = {}) = withContext(NonCancellable) {
        // Bound cancellation, Ktor's engine job, and native processor teardown as one shutdown.
        withTimeout(15.seconds) {
            inStep(CLOSING_CLIENT)
            client.close()
            inStep(ENDING_CLIENT_WORK)
            client.coroutineContext[Job]?.cancelAndJoin()
            inStep(AWAITING_ENGINE)
            // Ktor cancels, not closes, the engine of a client whose job was cancelled, and an
            // engine releases its sockets only in close.
            client.engine.close()
            (client.engine as? RouteTeardownAwaiter)?.awaitRouteTeardown()
        }
    }

    private fun stepDescription(step: String): String {
        if (step != AWAITING_ENGINE) return step
        val stage = (client.engine as? RouteTeardownAwaiter)?.teardownStage ?: return step
        return "$step: $stage"
    }

    internal fun lease(): RouteLease = routeLease
}

private const val CLOSING_CLIENT = "closing the client"
private const val ENDING_CLIENT_WORK = "ending the client's work"
private const val AWAITING_ENGINE = "waiting for the engine to release its sockets"

private data class NativeTorRoute(
    val proxy: ProxyConfig,
    val generation: Long,
    val port: Int,
)

private class RouteProvenance(
    private val routes: RouteGenerations,
    private val routeLease: RouteLease,
    override val usedTorProxy: Boolean,
    private val nativeRouteIsCurrent: () -> Boolean,
) : TorRouteProvenance {
    override fun isCurrent(): Boolean = routes.isCurrent(routeLease) && nativeRouteIsCurrent()
}

/** Only factory for outbound REST and relay WebSocket clients. */
class RouteAwareClientProvider(
    private val appInformation: AppInformation,
    private val requestedIntent: RequestedTorIntent?,
    private val torRouteSource: TorRouteSource?,
    private val readyTimeout: Duration = 30.seconds,
    private val engineSupportsTorProxy: Boolean = httpEngineSupportsTorProxy,
    /** Engine per routed client; the Darwin teardown spike substitutes an owned-session factory. */
    private val engineFactory: (isDebug: Boolean) -> HttpClientEngineFactory<*> = { getEngine(it) },
) : TorRouteLifecycle, WebSocketRouteProvider {
    private val routes = RouteGenerations()

    suspend fun openWebSocket(): RoutedHttpClient = open { proxy ->
        ktorWebSocketHttpClient(
            engine = engineFactory(appInformation.debug),
            proxy = proxy,
        )
    }

    override suspend fun <T> useWebSocketRoute(block: suspend (HttpClient, TorRouteProvenance) -> T): T {
        val routed = openWebSocket()
        var tracked = false
        try {
            return routes.track(routed.lease()) {
                tracked = true
                try {
                    check(routed.isCurrent()) { "Route generation was revoked" }
                    block(routed.client, routed.provenance)
                } finally {
                    // Retain the route lease until the engine has released its sockets. A completed
                    // call is not safe to retire merely because its coroutine returned.
                    routed.closeAndJoin()
                }
            }
        } finally {
            // track() can fail while admission is being revoked or its mutex is cancelled.
            if (!tracked) routed.closeAndJoin()
        }
    }

    suspend fun <T> withRestClient(
        clientType: ClientType,
        interceptors: List<(HttpRequestBuilder) -> Unit>,
        block: suspend (HttpClient) -> T,
    ): T {
        val routed = open { proxy ->
            ktorHttpClient(
                clientType = clientType,
                interceptors = interceptors,
                engine = engineFactory(appInformation.debug),
                proxy = proxy,
            )
        }
        var tracked = false
        try {
            return routes.track(routed.lease()) {
                tracked = true
                try {
                    check(routed.isCurrent()) { "Route was revoked before REST request" }
                    block(routed.client)
                } finally {
                    routed.closeAndJoin()
                }
            }
        } finally {
            if (!tracked) routed.closeAndJoin()
        }
    }

    /**
     * For a request that only means something from this device's own address: null, and no client
     * built, unless Tor is requested off. Never waits for a Tor route.
     */
    suspend fun <T> withDirectClient(block: suspend (HttpClient) -> T): T? {
        val lease = routes.admit()
        return routes.track(lease) {
            // Tracked work is joined before a switch to Tor is published, so the answer read here
            // holds until this returns.
            if (requestedIntent != null && requestedIntent.current != TorMode.OFF) return@track null
            val routed = RoutedHttpClient(
                client = HttpClient(engineFactory(appInformation.debug)) { followRedirects = false },
                routes = routes,
                routeLease = lease,
                provenance = RouteProvenance(routes, lease, usedTorProxy = false) { true },
            )
            try {
                block(routed.client)
            } finally {
                routed.closeAndJoin()
            }
        }
    }

    override suspend fun transition(
        waitForDirectRetirements: Boolean,
        publishPolicy: suspend () -> Unit,
    ) = routes.transition(waitForDirectRetirements, publishPolicy)

    override suspend fun revokeAndJoin() = routes.revokeAndJoin()

    private suspend fun open(factory: (ProxyConfig?) -> HttpClient): RoutedHttpClient {
        val lease = routes.admit()
        val nativeRoute = proxyForCurrentIntent()
        check(routes.isCurrent(lease)) { "Route generation was revoked" }
        check(nativeRoute?.isCurrent() != false) { "Native Tor route was revoked" }
        return RoutedHttpClient(
            client = factory(nativeRoute?.proxy),
            routes = routes,
            routeLease = lease,
            provenance = RouteProvenance(
                routes,
                lease,
                usedTorProxy = nativeRoute != null,
                nativeRouteIsCurrent = { nativeRoute?.isCurrent() ?: true },
            ),
        )
    }

    private suspend fun proxyForCurrentIntent(): NativeTorRoute? {
        if (requestedIntent?.current != TorMode.ON) return null
        if (!engineSupportsTorProxy) throw TorRouteRequiredException()
        val routeSource = torRouteSource ?: throw TorRouteRequiredException()
        awaitReadyRoute(routeSource)
        // Intent may have changed while waiting. A switched-off user requested a direct route.
        if (requestedIntent.current != TorMode.ON) return null
        val (host, port) = routeSource.getSocksProxyAddress() ?: throw TorRouteRequiredException()
        val generation = routeSource.statusFlow.value.routeGeneration
        if (generation == 0L) throw TorRouteRequiredException()
        return NativeTorRoute(torSocksProxy(host, port), generation, port)
    }

    private fun NativeTorRoute.isCurrent(): Boolean {
        val routeSource = torRouteSource ?: return false
        val status = routeSource.statusFlow.value
        return routeSource.isProxyReady() &&
            status.state == TorState.RUNNING &&
            status.routeGeneration == generation &&
            status.socksPort == port
    }

    /**
     * Client construction is intentionally outside this wait. The Koin graph can therefore be
     * assembled while Arti bootstraps; only an outbound request or socket acquisition waits.
     */
    private suspend fun awaitReadyRoute(routeSource: TorRouteSource) {
        if (routeSource.isProxyReady()) return
        if (!routeSource.isAvailable) throw TorRouteRequiredException()

        withTimeoutOrNull(readyTimeout) {
            routeSource.statusFlow.first { status ->
                routeSource.isProxyReady() ||
                    status.state == TorState.ERROR ||
                    status.state == TorState.OFF ||
                    requestedIntent?.current != TorMode.ON
            }
        }

        if (!routeSource.isProxyReady()) throw TorRouteRequiredException()
    }
}
