package com.bitchat.client.websocket

import com.bitchat.client.TorRouteProvenance
import com.bitchat.client.WebSocketRouteProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A frame is read by a connection's reader and handled later, by the controller. In between, the
 * connection can be retired, and what its reader had read must then not reach the listener. Every
 * frame goes on to the dispatcher now (that is where its room is given back), so this is the rule
 * that keeps a retired connection's frames from being delivered on the way.
 */
class RetiredConnectionFramesTest {
    @Test
    fun `a frame read from a connection that was retired meanwhile does not reach the listener`() = runBlocking {
        val relay = LoopbackRelay()
        val http = HttpClient(OkHttp) { install(WebSockets) }
        val route = RouteThatCanBeAskedSlowly()
        val client = KtorWebSocketClient(route.provider(http))
        val listener = HeldListener().also { it.letGo() }
        try {
            // No second connection within this test: what follows is about the first one only.
            client.connect(relay.url, listener, initialBackoffMs = 600_000)
            relay.send(listOf("before"))
            // Handled and done with: nothing of the first frame is in flight any more.
            awaitUntil("the first frame was handled") {
                listener.frames() == listOf("before") && client.inboundState(relay.url)?.framesInFlight == 0
            }

            // A send finds the route no longer current, which retires the connection. The controller is
            // held at that very question while the relay says one more thing on the old connection.
            route.holdNextQuestion()
            val sending = async(Dispatchers.IO) { client.send(relay.url, "anything") }
            assertTrue(route.asked.await(30, TimeUnit.SECONDS), "the send never asked whether the route is current")
            relay.send(listOf("after the route changed"))
            // In flight now is this frame and nothing else: its reader read it, the controller is held.
            awaitUntil("the frame was read") { client.inboundState(relay.url)?.framesInFlight == 1 }
            route.answerNotCurrent()
            sending.await()

            awaitUntil("the frame was dealt with") { client.inboundState(relay.url)?.framesInFlight == 0 }
            assertEquals(listOf("before"), listener.frames())
        } finally {
            route.answerNotCurrent()
            runCatching { client.shutdown() }
            http.close()
            relay.close()
        }
    }

    /** A route that is current until the test holds one question and answers it "no". */
    private class RouteThatCanBeAskedSlowly {
        val asked = CountDownLatch(1)
        private val hold = AtomicBoolean(false)
        private val answer = CountDownLatch(1)
        private val current = AtomicBoolean(true)

        fun holdNextQuestion() = hold.set(true)
        fun answerNotCurrent() {
            current.set(false)
            answer.countDown()
        }

        fun provider(client: HttpClient) = object : WebSocketRouteProvider {
            private val route = object : TorRouteProvenance {
                override val usedTorProxy = false
                override fun isCurrent(): Boolean {
                    if (hold.compareAndSet(true, false)) {
                        asked.countDown()
                        answer.await(60, TimeUnit.SECONDS)
                    }
                    return current.get()
                }
            }

            override suspend fun <T> useWebSocketRoute(block: suspend (HttpClient, TorRouteProvenance) -> T): T = block(client, route)
        }
    }
}
