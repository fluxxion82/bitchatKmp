package com.bitchat.client

import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.tor.TorManager
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.config
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Decides whether a request may leave the machine, and through what.
 *
 * Separated from the engine so the decision can be tested without standing up a client. Both inputs
 * are functions rather than the objects behind them, because [TorManager] is an `expect class` and
 * awkward to substitute.
 */
internal class TorRoutingGate(
    private val intent: () -> TorMode,
    private val socksAddress: () -> Pair<String, Int>?,
) {
    /**
     * True when the user asked for Tor and there is no proven Tor to send this through.
     *
     * Reads the *requested* intent, never the effective mode or readiness: those report OFF while
     * Tor is failing or still starting, which is precisely the window a direct connection must not
     * slip through.
     */
    fun mustRefuse(): Boolean = intent() == TorMode.ON && socksAddress() == null

    /**
     * Routes only to a listener whose bind was observed.
     *
     * The port is a fixed 9050, so pointing at it unconditionally would not be fail-closed: another
     * process may hold it, and an ordinary SOCKS forwarder there would carry the traffic happily
     * with no Tor in it anywhere. [TorManager.getSocksProxyAddress] answers only once the native
     * side has reported its listening socket bound.
     */
    fun proxy(): Proxy = socksAddress()
        ?.let { (host, port) -> Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)) }
        ?: Proxy.NO_PROXY
}

/**
 * An engine that refuses to connect directly while the user has asked for Tor.
 *
 * Lives in the shared JVM source set because that is where the OkHttp engine is, but it is inert
 * unless something registers it as an [HttpEngineProvider]. Only the desktop app does; Android
 * keeps the default engine and today's behaviour.
 *
 * The dispatcher and connection pool are supplied explicitly rather than left to OkHttp's defaults
 * so that this owns them. Ktor derives a client per timeout configuration and installs a *new*
 * dispatcher on each, while inheriting this connection pool -- so a pool held here reaches every
 * derived client, and a dispatcher held here does not.
 */
class TorEnforcingEngineProvider(
    private val requestedIntent: RequestedTorIntent,
    torManager: TorManager,
    scope: CoroutineScope,
) : HttpEngineProvider {

    private val gate = TorRoutingGate(
        intent = { requestedIntent.current },
        socksAddress = { torManager.getSocksProxyAddress() },
    )

    private val dispatcher = Dispatcher()
    private val connectionPool = ConnectionPool()

    private val ownedClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(connectionPool)
        .proxySelector(GateProxySelector(gate))
        // An *application* interceptor, deliberately: network interceptors are skipped for
        // WebSockets, and relay connections are exactly what this most needs to cover.
        .addInterceptor(TorEnforcingInterceptor(gate))
        .build()

    init {
        scope.launch {
            requestedIntent.updates
                .drop(1)
                .filter { it == TorMode.ON }
                .collect { retireDirectConnections() }
        }
    }

    override fun engine(): HttpClientEngineFactory<*> = OkHttp.config {
        preconfigured = ownedClient
    }

    /**
     * Makes connections opened while traffic went direct unusable, the moment Tor is asked for.
     *
     * Refusing new requests is not enough on its own: an idle pooled connection would otherwise be
     * handed to the next request without the proxy selector ever being consulted again.
     *
     * What this reaches, and what it does not. The connection pool is shared with every client
     * Ktor derives from ours, so evicting here covers all of them -- but `evictAll` skips any
     * connection with a call in flight, so a request already executing survives to completion. The
     * dispatcher is not shared: Ktor installs a fresh one on each derived client, so cancelling
     * ours reaches only calls made through the client we built. Live WebSockets are closed by the
     * relay layer instead, which owns the sessions and can close them properly.
     */
    private fun retireDirectConnections() {
        println("TorEnforcingEngineProvider: Tor requested - retiring direct connections")
        dispatcher.cancelAll()
        connectionPool.evictAll()
    }
}

/** Refuses before anything reaches the wire. */
internal class TorEnforcingInterceptor(private val gate: TorRoutingGate) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (gate.mustRefuse()) throw TorRequiredException(chain.request().url.host)
        return chain.proceed(chain.request())
    }
}

internal class GateProxySelector(private val gate: TorRoutingGate) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = listOf(gate.proxy())

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        println("TorEnforcingEngineProvider: connection to $uri via $sa failed: ${ioe?.message}")
    }
}
