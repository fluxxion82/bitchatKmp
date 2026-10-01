@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class, ExperimentalAtomicApi::class)

package com.bitchat.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import io.ktor.http.URLProtocol
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSNumber
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketTask
import platform.darwin.NSObject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Server-observed socket closure must settle within this before a new route is published. */
internal val DARWIN_ROUTE_TEARDOWN_SETTLE_BOUND = 250.milliseconds

/** The route used to build an owned Darwin session. */
internal sealed interface DarwinRoute {
    data object Direct : DarwinRoute
    data class Socks(val host: String, val port: Int) : DarwinRoute

    companion object {
        fun from(proxy: ProxyConfig?): DarwinRoute {
            val url = proxy?.url ?: return Direct
            require(url.protocol == URLProtocol.SOCKS) { "only SOCKS proxies are routed: ${url.protocol.name}" }
            return Socks(url.host, url.port)
        }
    }
}

/** Builds the configuration before NSURLSession exists; `usePreconfiguredSession` skips Ktor's hooks. */
internal object OwnedSessionConfigurations {
    const val SOCKS_ENABLE = "SOCKSEnable"
    const val SOCKS_PROXY = "SOCKSProxy"
    const val SOCKS_PORT = "SOCKSPort"
    const val EXCEPTIONS_LIST = "ExceptionsList"
    const val EXCLUDE_SIMPLE_HOSTNAMES = "ExcludeSimpleHostnames"

    fun build(route: DarwinRoute): NSURLSessionConfiguration =
        NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
            if (route is DarwinRoute.Socks) {
                connectionProxyDictionary = mapOf(
                    SOCKS_ENABLE to 1,
                    SOCKS_PROXY to route.host,
                    SOCKS_PORT to route.port,
                    EXCEPTIONS_LIST to emptyList<String>(),
                    EXCLUDE_SIMPLE_HOSTNAMES to 0,
                )
            }
            // Direct deliberately leaves connectionProxyDictionary unset: Tor OFF continues to
            // honour the system HTTP proxy exactly as the stock Darwin engine does.
            URLCache = null
            requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
            HTTPCookieStorage = null
            HTTPShouldSetCookies = false
            URLCredentialStorage = null
            waitsForConnectivity = false
        }

    fun intValue(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is NSNumber -> value.intValue
        else -> null
    }
}

/** Forwards every Ktor callback and observes the invalidation callback Ktor does not consume. */
internal class ForwardingSessionDelegate(
    private val ktor: KtorNSURLSessionDelegate,
) : NSObject(), NSURLSessionDataDelegateProtocol, NSURLSessionWebSocketDelegateProtocol {
    val invalidation = CompletableDeferred<NSError?>()
    private val invalidatedMark = AtomicReference<TimeSource.Monotonic.ValueTimeMark?>(null)

    override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) {
        invalidatedMark.store(TimeSource.Monotonic.markNow())
        invalidation.complete(didBecomeInvalidWithError)
    }

    fun invalidatedAt(): TimeSource.Monotonic.ValueTimeMark? = invalidatedMark.load()

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) =
        ktor.URLSession(session, dataTask = dataTask, didReceiveData = didReceiveData)

    override fun URLSession(session: NSURLSession, taskIsWaitingForConnectivity: NSURLSessionTask) =
        ktor.URLSession(session, taskIsWaitingForConnectivity = taskIsWaitingForConnectivity)

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) =
        ktor.URLSession(session, task = task, didCompleteWithError = didCompleteWithError)

    override fun URLSession(session: NSURLSession, webSocketTask: NSURLSessionWebSocketTask, didOpenWithProtocol: String?) =
        ktor.URLSession(session, webSocketTask = webSocketTask, didOpenWithProtocol = didOpenWithProtocol)

    override fun URLSession(session: NSURLSession, webSocketTask: NSURLSessionWebSocketTask, didCloseWithCode: NSURLSessionWebSocketCloseCode, reason: NSData?) =
        ktor.URLSession(session, webSocketTask = webSocketTask, didCloseWithCode = didCloseWithCode, reason = reason)

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, willPerformHTTPRedirection: NSHTTPURLResponse, newRequest: NSURLRequest, completionHandler: (NSURLRequest?) -> Unit) =
        ktor.URLSession(session, task = task, willPerformHTTPRedirection = willPerformHTTPRedirection, newRequest = newRequest, completionHandler = completionHandler)

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didReceiveChallenge: NSURLAuthenticationChallenge, completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit) =
        ktor.URLSession(session, task = task, didReceiveChallenge = didReceiveChallenge, completionHandler = completionHandler)
}

/** One routed client owns exactly one session and treats its invalidation acknowledgement as a barrier. */
internal class OwnedDarwinSession private constructor(
    val route: DarwinRoute,
    val configuration: NSURLSessionConfiguration,
    val session: NSURLSession,
    val ktorDelegate: KtorNSURLSessionDelegate,
    private val forwarding: ForwardingSessionDelegate,
) {
    suspend fun invalidateAndAwait(bound: Duration = 10.seconds): DarwinInvalidation {
        val started = TimeSource.Monotonic.markNow()
        session.invalidateAndCancel()
        val error = withTimeoutOrNull(bound) { forwarding.invalidation.await() }
        check(error != null || forwarding.invalidation.isCompleted) {
            "NSURLSession did not acknowledge invalidateAndCancel"
        }
        return DarwinInvalidation(started, forwarding.invalidatedAt(), error)
    }

    companion object {
        fun create(route: DarwinRoute): OwnedDarwinSession {
            val ktor = KtorNSURLSessionDelegate()
            val forwarding = ForwardingSessionDelegate(ktor)
            val configuration = OwnedSessionConfigurations.build(route)
            return OwnedDarwinSession(
                route, configuration,
                NSURLSession.sessionWithConfiguration(configuration, forwarding, delegateQueue = null),
                ktor, forwarding,
            )
        }
    }
}

internal class DarwinInvalidation(
    val startedAt: TimeSource.Monotonic.ValueTimeMark,
    val acknowledgedAt: TimeSource.Monotonic.ValueTimeMark?,
    val error: NSError?,
) {
    val acknowledged: Boolean get() = acknowledgedAt != null
    val ackDelay: Duration? get() = acknowledgedAt?.minus(startedAt)
    override fun toString(): String = if (acknowledged) "invalidation[acknowledged after $ackDelay]" else "invalidation[NOT acknowledged]"
}

/** The production retirement implementation: invalidation acknowledgement, then the measured settle bound. */
private class OwnedDarwinClientEngine(
    private val delegate: HttpClientEngine,
    private val owned: OwnedDarwinSession,
) : HttpClientEngine by delegate, RouteTeardownAwaiter {
    override suspend fun awaitRouteTeardown() {
        owned.invalidateAndAwait()
        delay(DARWIN_ROUTE_TEARDOWN_SETTLE_BOUND)
    }
}

/** Ktor Darwin engine factory backed by an NSURLSession whose lifecycle is owned by this module. */
internal class OwnedSessionEngineFactory(
    private val onSession: (OwnedDarwinSession) -> Unit = {},
) : HttpClientEngineFactory<DarwinClientEngineConfig> {
    override fun create(block: DarwinClientEngineConfig.() -> Unit): HttpClientEngine {
        lateinit var owned: OwnedDarwinSession
        val engine = Darwin.create {
            block()
            owned = OwnedDarwinSession.create(DarwinRoute.from(proxy))
            usePreconfiguredSession(owned.session, owned.ktorDelegate)
        }
        onSession(owned)
        return OwnedDarwinClientEngine(engine, owned)
    }
}
