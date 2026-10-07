package com.bitchat.client.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On the engines that hand a frame's bytes over as they came (Curl on the boards, CIO), a text frame
 * that is not UTF-8 fails to decode, and that ends the connection, as it always did. A relay's
 * account outlives its connections, so such a frame must leave nothing in it: a relay that sent as
 * many as it has room for would otherwise never be heard again.
 *
 * No engine on the JVM fails this way with a real frame, so the client is given a decoder that does.
 */
class UndecodableFrameTest {
    @Test
    fun `a frame that cannot be decoded takes no room with it`() = runBlocking {
        val relay = LoopbackRelay()
        val http = HttpClient(OkHttp) { install(WebSockets) }
        val client = KtorWebSocketClient(
            routeProvider = directRoutes(http),
            // Room for two frames: two that kept theirs would be all of it.
            limits = InboundLimits(maxFramesInFlight = 2),
            report = {},
            decode = { frame -> frame.readText().also { check(it != NOT_TEXT) { "not text" } } },
        )
        val listener = HeldListener().also { it.letGo() }
        try {
            client.connect(relay.url, listener, initialBackoffMs = 1)

            relay.send(listOf(NOT_TEXT))
            awaitUntil("the connection ended on the first such frame and was dialled again") { relay.connections() == 2 }
            relay.send(listOf(NOT_TEXT))
            awaitUntil("and on the second") { relay.connections() == 3 }

            relay.send(listOf("still heard"))
            awaitUntil("a frame after them") { listener.frames() == listOf("still heard") }
            awaitUntil("nothing in flight") { client.inboundState(relay.url) == InboundState(0, 0, 0, 0) }
            assertEquals(listOf("still heard"), listener.frames())
        } finally {
            runCatching { client.shutdown() }
            http.close()
            relay.close()
        }
    }

    private companion object {
        const val NOT_TEXT = "stands for bytes that are not text"
    }
}
