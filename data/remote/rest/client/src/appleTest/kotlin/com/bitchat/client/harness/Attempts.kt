package com.bitchat.client.harness

import com.bitchat.client.RouteAwareClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** NSURLErrorAppTransportSecurityRequiresSecureConnection. */
internal const val ATS_ERROR_CODE = -1022L

internal val REQUEST_BOUND: Duration = 20.seconds

/** What one outbound attempt did. Never thrown: outcomes are data for the spike verdict. */
internal class Outcome(val elapsed: Duration, val error: Throwable?, val timedOut: Boolean) {
    fun isAppTransportSecurityBlock(): Boolean =
        (error as? DarwinHttpRequestException)?.origin?.code == ATS_ERROR_CODE

    override fun toString(): String = when {
        timedOut -> "outcome[timed out after $elapsed]"
        error != null -> "outcome[failed after $elapsed: ${error::class.simpleName}: ${error.message}]"
        else -> "outcome[completed after $elapsed]"
    }
}

/** Runs [block] under [bound]; a timeout cancels the Ktor call (and its NSURLSessionTask). */
internal suspend fun attempt(case: String, bound: Duration = REQUEST_BOUND, block: suspend () -> Unit): Outcome {
    val start = TimeSource.Monotonic.markNow()
    var error: Throwable? = null
    val completed = withTimeoutOrNull(bound) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            error = failure
        }
        true
    }
    return Outcome(start.elapsedNow(), error, timedOut = completed == null).also { println("[$case] $it") }
}

/** One relay-style WebSocket route: connect, then close politely if it ever opened. */
internal suspend fun openWebSocket(provider: RouteAwareClientProvider, url: String) {
    provider.useWebSocketRoute { client: HttpClient, _ ->
        val session = client.webSocketSession(url)
        session.close(CloseReason(CloseReason.Codes.NORMAL, "harness"))
    }
}

/** Wall-clock milliseconds, for matching a packet capture afterwards. */
internal fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * Always runs, detector or not: every CONNECT the proxy saw carried the exact name as a SOCKS
 * domain (ATYP 3) on [port], so the client resolved nothing itself. [observations] is appended to
 * every failure message.
 */
internal fun SocksCaptureServer.assertOnlyDomainConnects(
    case: String,
    expectedHosts: List<String>,
    port: Int,
    minimum: Int = expectedHosts.size,
    observations: () -> String = { describe() },
) {
    val connects = connects()
    assertTrue(connects.size >= minimum, "expected at least $minimum CONNECT(s), saw ${connects.size}; ${observations()}")
    connects.forEach { connect ->
        assertTrue(
            connect.isDomain,
            "SOCKS CONNECT used address type ${connect.addressType} (${connect.host}) instead of a domain name: " +
                "the client resolved locally; ${observations()}",
        )
        assertEquals(port, connect.port, "CONNECT port; ${observations()}")
    }
    val hosts = connects.map { it.host.lowercase() }
    if (connects.size == expectedHosts.size) {
        assertEquals(expectedHosts.map { it.lowercase() }, hosts, "CONNECT hosts; ${observations()}")
    } else {
        // More CONNECTs than requests (a retry): every one must still be for an expected name.
        val allowed = expectedHosts.map { it.lowercase() }.toSet()
        assertTrue(hosts.all { it in allowed }, "CONNECT for an unexpected host; ${observations()}")
        println("[$case] NOTE: ${connects.size} CONNECT(s) for ${expectedHosts.size} request(s); ${observations()}")
    }
    println("[$case] SOCKS saw ${connects.size} domain CONNECT(s): $connects")
}
