package com.bitchat.client.websocket

import com.bitchat.client.TorRouteProvenance
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [NostrWebSocketClient] is the client the app builds. It takes no limits from anyone, so the ones a
 * relay is held to in the app are the defaults, and this is where they are seen at work.
 */
class NostrWebSocketClientLimitsTest {
    @Test
    fun `the client the app uses holds a relay to the default limits`() = runBlocking {
        val limits = InboundLimits()
        val beyond = 100
        val relay = LoopbackRelay()
        val http = HttpClient(OkHttp) { install(WebSockets) }
        val client = NostrWebSocketClient(directRoutes(http))
        val mayGoOn = CountDownLatch(1)
        val received = Collections.synchronizedList(mutableListOf<String>())
        val drained = CopyOnWriteArrayList<Pair<String, Long>>()
        try {
            client.connect(relay.url, object : NostrWebSocketListener {
                override fun onOpen(relayUrl: String, route: TorRouteProvenance) = Unit
                override fun onMessage(relayUrl: String, text: String) {
                    received += text
                    mayGoOn.await(60, TimeUnit.SECONDS)
                }
                override fun onClosing(relayUrl: String, code: Int, reason: String) = Unit
                override fun onClosed(relayUrl: String, code: Int, reason: String) = Unit
                override fun onFailure(relayUrl: String, t: Throwable) = Unit
                override fun onBacklogDrained(relayUrl: String, framesDropped: Long) {
                    drained += relayUrl to framesDropped
                }
            })

            relay.send((0 until limits.maxFramesInFlight + beyond).map { "frame-$it" })
            awaitUntil("what is beyond the limit was dropped") { client.inboundState(relay.url)?.framesDropped == beyond.toLong() }
            assertEquals(limits.maxFramesInFlight, client.inboundState(relay.url)?.framesInFlight)

            mayGoOn.countDown()
            awaitUntil("nothing in flight") { client.inboundState(relay.url)?.framesInFlight == 0 }
            assertEquals((0 until limits.maxFramesInFlight).map { "frame-$it" }, synchronized(received) { received.toList() })
            // And the app's listener is told, so that it can ask the relay again.
            awaitUntil("the report that frames were dropped") { drained.isNotEmpty() }
            assertEquals(listOf(relay.url to beyond.toLong()), drained.toList())
        } finally {
            mayGoOn.countDown()
            runCatching { client.shutdown() }
            http.close()
            relay.close()
        }
    }
}
