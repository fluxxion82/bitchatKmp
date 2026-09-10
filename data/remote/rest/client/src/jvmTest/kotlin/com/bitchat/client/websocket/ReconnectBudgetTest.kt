package com.bitchat.client.websocket

import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The retry budget decides whether a relay killed by a policy the user has since changed can come
 * back without restarting the app.
 */
class ReconnectBudgetTest {

    private val failures = AtomicInteger()

    private val listener = object : WebSocketListener {
        override fun onOpen(relayUrl: String) {}
        override fun onMessage(relayUrl: String, text: String) {}
        override fun onClosing(relayUrl: String, code: Int, reason: String) {}
        override fun onClosed(relayUrl: String, code: Int, reason: String) {}
        override fun onFailure(relayUrl: String, t: Throwable) { failures.incrementAndGet() }
    }

    /** Port 1 is not listening, so every attempt fails immediately. */
    private val deadUrl = "ws://127.0.0.1:1/never"

    @Test
    fun `an exhausted budget is refilled by an explicit connect`() = runBlocking {
        /*
         * reconnectAttempts resets only on success, so once the ceiling was reached the connection
         * stayed dead for the life of the process: a later call tried once and then declined to
         * reschedule. Recovery from a policy change depends on an explicit call being a fresh
         * start.
         */
        val client = KtorWebSocketClient(HttpClient())

        client.connect(deadUrl, listener, maxReconnectAttempts = 1, initialBackoffMs = 1)
        delay(150)
        val afterExhaustion = failures.get()
        assertTrue(afterExhaustion >= 1, "expected the first attempt to fail")

        // Asking again must try again, rather than silently declining for ever.
        client.connect(deadUrl, listener, maxReconnectAttempts = 1, initialBackoffMs = 1)
        delay(150)

        assertTrue(
            failures.get() > afterExhaustion,
            "an explicit reconnect did not retry: budget was not refilled"
        )
        client.shutdown()
    }

    @Test
    fun `the retry loop cannot refill its own budget`() = runBlocking {
        // Guards the ceiling: were the internal reschedule to reset the count, a permanently dead
        // relay would be retried for ever.
        val client = KtorWebSocketClient(HttpClient())

        client.connect(deadUrl, listener, maxReconnectAttempts = 2, initialBackoffMs = 1, resetBudget = false)
        delay(400)

        assertTrue(failures.get() in 1..4, "unbounded retries: ${failures.get()} failures")
        client.shutdown()
    }

    @Test
    fun `disconnecting a connection that never opened does not dial`() = runBlocking {
        /*
         * disconnect() used to open a brand new WebSocket purely so it could close it. Pointless in
         * itself, and under Tor enforcement an outbound attempt made while disconnecting.
         */
        val client = KtorWebSocketClient(HttpClient())

        client.disconnect(deadUrl)
        delay(50)

        assertEquals(0, failures.get(), "disconnect attempted a connection")
        client.shutdown()
    }
}
