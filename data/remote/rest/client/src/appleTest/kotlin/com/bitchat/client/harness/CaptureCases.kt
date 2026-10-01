@file:OptIn(ExperimentalNativeApi::class)

package com.bitchat.client.harness

import com.bitchat.client.RouteAwareClientProvider
import com.bitchat.client.model.ClientType
import com.bitchat.domain.tor.RequestedTorIntent
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal typealias ProviderFactory = (RequestedTorIntent, Int) -> RouteAwareClientProvider

/** What the A1 cases run against: Ktor's own session handling or the owned-session factory. */
internal class CaptureSubject(
    val label: String,
    val providerFactory: ProviderFactory,
    /** Runs after every case, before the servers report; the owned factory tears its sessions down here. */
    val cleanup: suspend () -> Unit = {},
)

internal class CaptureHarness(
    val case: String,
    val detector: DnsLeakDetector,
    val socks: SocksCaptureServer,
    private val providerFactory: ProviderFactory,
) {
    val name: String = DnsLeakDetector.freshName()

    fun provider(intent: RequestedTorIntent, socksPort: Int = socks.port) = providerFactory(intent, socksPort)

    suspend fun attempt(bound: Duration = REQUEST_BOUND, block: suspend () -> Unit): Outcome = attempt(case, bound, block)

    /** Late retries or lookups would land after the request returned; give them a moment. */
    suspend fun settle() = delay(SETTLE)

    fun observations(): String = "${socks.describe()} ${detector.describe()}"

    fun assertOnlyDomainConnects(expectedHosts: List<String>, port: Int, minimum: Int = expectedHosts.size) =
        socks.assertOnlyDomainConnects(case, expectedHosts, port, minimum) { observations() }

    /** Detector-dependent: skips loudly when the resolver file is missing, never passes silently. */
    fun assertNoLocalResolution(vararg names: String) {
        detector.notWiredReason()?.let { reason ->
            println("$reason -- DNS assertion for [$case] SKIPPED, not a pass")
            return
        }
        val leaked = names.flatMap { detector.queriesFor(it) }
        assertTrue(leaked.isEmpty(), "LEAK: the local resolver saw $leaked for a Tor-routed request; ${observations()}")
        println("[$case] detector: zero queries for ${names.toList()}")
    }

    companion object {
        val CASE_BOUND = 40.seconds
        val FAILURE_BOUND = 15.seconds
        val SETTLE = 750.milliseconds
    }
}

internal fun captureCase(case: String, mode: SocksMode, subject: CaptureSubject, block: suspend CaptureHarness.() -> Unit) = runBlocking {
    PosixNet.ignoreSigpipe()
    val detector = DnsLeakDetector.start()
    val socks = SocksCaptureServer(mode)
    val label = "$case/${subject.label}"
    val harness = CaptureHarness(label, detector, socks, subject.providerFactory)
    println("[$label] start name=${harness.name} socks=127.0.0.1:${socks.port} detectorWired=${detector.wired}")
    detector.notWiredReason()?.let { println("[$label] $it") }
    try {
        withTimeout(CaptureHarness.CASE_BOUND) {
            harness.block()
            subject.cleanup()
        }
    } finally {
        socks.stop()
        detector.stop()
        println("[$label] end ${harness.observations()}")
    }
}

internal fun deadPort(): Int {
    val listener = PosixNet.tcpListener()
    val port = PosixNet.boundPort(listener)
    PosixNet.closeFd(listener)
    return port
}

/**
 * The A1 cases (docs/plans/2026-09-30-item2-apple-tor.md), parameterised by the session owner so
 * the same evidence is collected for Ktor's sessions and for the owned factory.
 */
internal object CaptureCases {
    /** Detector-dependent. A direct lookup of a probe name MUST reach the local DNS server. */
    fun positiveControl(subject: CaptureSubject) = captureCase("positive-control", SocksMode.Close, subject) {
        // The request runs even without the detector, so the subject's cleanup still sees its session.
        val outcome = attempt(10.seconds) {
            provider(TorOffIntent).withRestClient(ClientType.NOSTR, emptyList()) { client -> client.get("https://$name/") }
        }
        detector.notWiredReason()?.let { reason ->
            println("$reason -- [$case] SKIPPED, not a pass")
            return@captureCase
        }
        val seen = detector.awaitQuery(name, 5.seconds)
        println("[$case] direct request outcome=$outcome ${detector.describe()}")
        assertTrue(seen, "INCONCLUSIVE: detector did not see the positive control for $name; ${detector.describe()} $outcome")
        assertEquals(0, socks.accepted(), "a direct request must not touch the SOCKS capture: ${socks.describe()}")
        println("[$case] PASS: detector logged ${detector.queriesFor(name)}")
    }

    fun httpsGet(subject: CaptureSubject) = captureCase("https-get", SocksMode.Close, subject) {
        val outcome = attempt {
            provider(TorOnIntent).withRestClient(ClientType.NOSTR, emptyList()) { client -> client.get("https://$name/") }
        }
        assertTrue(socks.awaitConnects(1, 10.seconds), "no CONNECT reached SOCKS; $outcome ${observations()}")
        settle()
        assertOnlyDomainConnects(listOf(name), 443)
        assertNotNull(outcome.error, "a request through a failing SOCKS proxy completed: something bypassed it; ${observations()}")
        assertNoLocalResolution(name)
    }

    fun wssConnect(subject: CaptureSubject) = captureCase("wss-connect", SocksMode.Close, subject) {
        val outcome = attempt { openWebSocket(provider(TorOnIntent), "wss://$name/") }
        assertTrue(socks.awaitConnects(1, 10.seconds), "no CONNECT reached SOCKS for the WebSocket; $outcome ${observations()}")
        settle()
        assertOnlyDomainConnects(listOf(name), 443)
        assertNotNull(outcome.error, "a WebSocket through a failing SOCKS proxy opened: something bypassed it; ${observations()}")
        assertNoLocalResolution(name)
    }

    fun refused(subject: CaptureSubject) = captureCase("socks-refuse", SocksMode.Refuse, subject) {
        val outcome = attempt {
            provider(TorOnIntent).withRestClient(ClientType.NOSTR, emptyList()) { client -> client.get("https://$name/") }
        }
        assertTrue(socks.awaitConnects(1, 10.seconds), "no CONNECT reached SOCKS; $outcome ${observations()}")
        settle()
        assertNotNull(outcome.error, "a request the SOCKS proxy refused completed: something bypassed it; ${observations()}")
        assertTrue(outcome.elapsed < CaptureHarness.FAILURE_BOUND, "refused SOCKS took ${outcome.elapsed}, bound is ${CaptureHarness.FAILURE_BOUND}")
        assertOnlyDomainConnects(listOf(name), 443, minimum = 1)
        assertNoLocalResolution(name)
    }

    fun deadPort(subject: CaptureSubject) = captureCase("socks-dead-port", SocksMode.Close, subject) {
        val deadPort = deadPort()
        val outcome = attempt {
            provider(TorOnIntent, socksPort = deadPort).withRestClient(ClientType.NOSTR, emptyList()) { client ->
                client.get("https://$name/")
            }
        }
        settle()
        assertNotNull(outcome.error, "a request to a dead SOCKS port completed: something bypassed it; ${observations()}")
        assertTrue(outcome.elapsed < CaptureHarness.FAILURE_BOUND, "dead SOCKS port took ${outcome.elapsed}, bound is ${CaptureHarness.FAILURE_BOUND}")
        assertEquals(0, socks.accepted(), "the live capture port must stay untouched: ${observations()}")
        assertNoLocalResolution(name)
    }

    /** macOS target. On iOS the test host may be under ATS, which blocks plain http; then it skips loudly. */
    fun httpRedirect(subject: CaptureSubject) {
        val first = DnsLeakDetector.freshName()
        val second = DnsLeakDetector.freshName()
        val mode = SocksMode.ServeHttp { request ->
            if (request.host.equals(first, ignoreCase = true)) TunnelHttpReply.redirect("http://$second/") else TunnelHttpReply.ok("hello")
        }
        captureCase("http-redirect", mode, subject) {
            var status = 0
            var body = ""
            val outcome = attempt {
                provider(TorOnIntent).withRestClient(ClientType.NOSTR, emptyList()) { client ->
                    val response = client.get("http://$first/")
                    status = response.status.value
                    body = response.bodyAsText()
                }
            }
            settle()
            if (Platform.osFamily == OsFamily.IOS && outcome.isAppTransportSecurityBlock()) {
                println("[$case] SKIPPED on iOS: ATS blocks plain http in the test host (${outcome.error?.message}); ${observations()}")
                assertEquals(0, socks.accepted(), "ATS blocked the request but SOCKS was still touched: ${observations()}")
                assertNoLocalResolution(first, second)
                return@captureCase
            }
            assertNull(outcome.error, "redirect request failed: ${outcome.error}; ${observations()}")
            assertEquals(200, status, "final status; ${observations()}")
            assertEquals("hello", body, "final body; ${observations()}")
            assertOnlyDomainConnects(listOf(first, second), 80)
            assertEquals(listOf(first, second), socks.httpRequests().map { it.host.lowercase() }, "HTTP requests on the tunnel; ${observations()}")
            assertNoLocalResolution(first, second)
        }
    }

    fun wssReconnect(subject: CaptureSubject) = captureCase("wss-reconnect", SocksMode.Close, subject) {
        val provider = provider(TorOnIntent)
        val firstAttempt = attempt { openWebSocket(provider, "wss://$name/") }
        assertTrue(socks.awaitConnects(1, 10.seconds), "first WebSocket attempt never reached SOCKS; $firstAttempt ${observations()}")
        assertNotNull(firstAttempt.error, "first WebSocket attempt opened through a closing proxy; ${observations()}")

        // Production reconnects by calling useWebSocketRoute again (KtorWebSocketClient): same shape here.
        val secondAttempt = attempt { openWebSocket(provider, "wss://$name/") }
        assertTrue(socks.awaitConnects(2, 10.seconds), "reconnect never reached SOCKS; $secondAttempt ${observations()}")
        settle()
        assertNotNull(secondAttempt.error, "reconnect opened through a closing proxy; ${observations()}")
        assertOnlyDomainConnects(listOf(name, name), 443, minimum = 2)
        assertNoLocalResolution(name)
    }

    /** CONNECT succeeded before the tunnel EOF; a later reconnect must still be SOCKS-routed. */
    fun wssReconnectAfterConnectEof(subject: CaptureSubject) = captureCase("wss-reconnect-eof", SocksMode.EofAfterConnect, subject) {
        val provider = provider(TorOnIntent)
        val firstAttempt = attempt { openWebSocket(provider, "wss://$name/") }
        assertTrue(socks.awaitConnects(1, 10.seconds), "first CONNECT never reached SOCKS; $firstAttempt ${observations()}")
        assertNotNull(firstAttempt.error, "first WebSocket opened despite an EOF tunnel; ${observations()}")
        val secondAttempt = attempt { openWebSocket(provider, "wss://$name/") }
        assertTrue(socks.awaitConnects(2, 10.seconds), "reconnect never reached SOCKS; $secondAttempt ${observations()}")
        settle()
        assertNotNull(secondAttempt.error, "reconnect opened despite an EOF tunnel; ${observations()}")
        assertOnlyDomainConnects(listOf(name, name), 443, minimum = 2)
        assertNoLocalResolution(name)
    }

    /** Plain ws:// is intentional: the SOCKS tunnel terminates at a local scripted WebSocket server. */
    fun establishedWsReconnectAfterTunnelDrop(subject: CaptureSubject) {
        val server = LocalScriptedServer(ServerScript.WebSocketUpgrade)
        try {
            captureCase("established-ws-reconnect", SocksMode.TunnelToLocalServer(server.port), subject) {
                val provider = provider(TorOnIntent)
                val initialReady = CompletableDeferred<Unit>()
                coroutineScope {
                    val first = async {
                        provider.useWebSocketRoute { client, _ ->
                            val session = client.webSocketSession("ws://$name:${server.port}/")
                            check(server.awaitResponded(0, 5.seconds)) { "first WebSocket handshake did not complete: ${server.describe()}" }
                            session.send(Frame.Text("frame-before-tunnel-drop"))
                            check(server.awaitBytesAfterResponse(0, 1, 5.seconds)) { "WebSocket frame did not traverse the tunnel: ${server.describe()}" }
                            initialReady.complete(Unit)
                            withTimeout(5.seconds) { session.incoming.receiveCatching().isClosed }
                        }
                    }
                    withTimeout(5.seconds) { initialReady.await() }
                    assertOnlyDomainConnects(listOf(name), server.port)
                    socks.dropActiveTunnels()
                    assertTrue(first.await(), "client did not observe the dropped established WebSocket tunnel")

                    val reconnect = attempt { openWebSocket(provider, "ws://$name:${server.port}/") }
                    assertTrue(socks.awaitConnects(2, 10.seconds), "reconnect did not create a new SOCKS CONNECT; $reconnect ${observations()}")
                    check(server.awaitResponded(1, 5.seconds)) { "reconnect WebSocket handshake did not complete: ${server.describe()}" }
                    assertNull(reconnect.error, "reconnect through the new SOCKS tunnel failed: ${reconnect.error}; ${observations()}")
                    settle()
                    assertOnlyDomainConnects(listOf(name, name), server.port, minimum = 2)
                    assertNoLocalResolution(name)
                }
            }
        } finally {
            runBlocking { server.stop() }
        }
    }

    /** A pending SOCKS handshake: the client must neither resolve locally nor connect elsewhere meanwhile. */
    fun heldHandshake(subject: CaptureSubject) = captureCase("socks-hold", SocksMode.Hold, subject) {
        val outcome = attempt(3.seconds) {
            provider(TorOnIntent).withRestClient(ClientType.NOSTR, emptyList()) { client -> client.get("https://$name/") }
        }
        assertTrue(socks.awaitAccepted(1, 10.seconds), "no connection reached the held SOCKS listener; $outcome ${observations()}")
        settle()
        assertTrue(outcome.timedOut, "expected the request to still be pending after 3 s, got $outcome; ${observations()}")
        assertTrue(socks.greetings().isNotEmpty(), "SOCKS greeting was not received; ${observations()}")
        assertTrue(socks.connects().isEmpty(), "CONNECT arrived although the greeting was never answered; ${observations()}")
        assertNoLocalResolution(name)
    }
}
