package com.bitchat.client

import com.bitchat.client.harness.LocalScriptedServer
import com.bitchat.client.harness.PosixNet
import com.bitchat.client.harness.ServerScript
import com.bitchat.client.websocket.InboundLimits
import com.bitchat.client.websocket.InboundState
import com.bitchat.client.websocket.KtorWebSocketClient
import com.bitchat.client.websocket.WebSocketListener
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The per-relay limit on frames in flight, through the Darwin engine: the same client code as on the
 * JVM, fed by NSURLSession's own receive loop. A listener held inside its first frame is the slow
 * consumer; each step waits for a state the client reports and fails if it never comes.
 */
@OptIn(ExperimentalAtomicApi::class)
class DarwinInboundFloodTest {
    @Test
    fun aRelayHasOnlySoManyFramesInFlightAndWhatArrivesBeyondThemIsDropped() = runBlocking {
        PosixNet.ignoreSigpipe()
        val server = LocalScriptedServer(ServerScript.WebSocketFrames((0 until 100).map { "frame-$it" }))
        val http = HttpClient(Darwin) { install(WebSockets) }
        val reports = AtomicReference<List<String>>(emptyList())
        val client = KtorWebSocketClient(
            routeProvider = routes(http),
            limits = InboundLimits(maxFramesInFlight = 8),
            report = { line -> while (true) { val seen = reports.load(); if (reports.compareAndSet(seen, seen + line)) break } },
        )
        val url = "ws://127.0.0.1:${server.port}/"
        val mayGoOn = CompletableDeferred<Unit>()
        val received = AtomicReference<List<String>>(emptyList())
        val drained = AtomicReference<List<Long>>(emptyList())
        val listener = object : WebSocketListener {
            override fun onBacklogDrained(url: String, framesDropped: Long) {
                while (true) { val seen = drained.load(); if (drained.compareAndSet(seen, seen + framesDropped)) break }
            }

            override fun onOpen(url: String, route: TorRouteProvenance) = Unit
            override fun onMessage(url: String, text: String) {
                while (true) { val seen = received.load(); if (received.compareAndSet(seen, seen + text)) break }
                runBlocking { mayGoOn.await() }
            }
            override fun onClosing(url: String, code: Int, reason: String) = Unit
            override fun onClosed(url: String, code: Int, reason: String) = Unit
            override fun onFailure(url: String, t: Throwable) = Unit
        }
        try {
            client.connect(url, listener, maxReconnectAttempts = 0)

            // The listener is inside the first frame, seven wait behind it, the other 92 were read and let go.
            awaitUntil("92 frames dropped: ${server.describe()}") { client.inboundState(url)?.framesDropped == 92L }
            assertEquals(8, client.inboundState(url)?.framesInFlight)

            mayGoOn.complete(Unit)
            awaitUntil("nothing in flight") { client.inboundState(url) == InboundState(0, 0, 92, 0) }
            assertEquals((0 until 8).map { "frame-$it" }, received.load(), "what was accepted arrives, in order, and nothing else")
            assertEquals(1, reports.load().count { url in it }, "one line for 92 dropped frames: ${reports.load()}")
            awaitUntil("the report that frames were dropped") { drained.load().isNotEmpty() }
            assertEquals(listOf(92L), drained.load())
        } finally {
            mayGoOn.complete(Unit)
            client.shutdown()
            http.close()
            server.stop()
        }
    }

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        withTimeoutOrNull(30.seconds) { while (!condition()) delay(5) } ?: fail("never happened: $what")
    }

    private fun routes(client: HttpClient) = object : WebSocketRouteProvider {
        private val route = object : TorRouteProvenance {
            override val usedTorProxy = false
            override fun isCurrent() = true
        }

        override suspend fun <T> useWebSocketRoute(block: suspend (HttpClient, TorRouteProvenance) -> T): T = block(client, route)
    }
}
