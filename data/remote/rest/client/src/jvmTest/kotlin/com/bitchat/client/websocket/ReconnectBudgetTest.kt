package com.bitchat.client.websocket

import com.bitchat.client.WebSocketRouteProvider
import com.bitchat.client.TorRouteProvenance
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.WebSocketSession
import java.net.ServerSocket
import java.net.Socket
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The retry budget decides whether a relay killed by a policy the user has since changed can come
 * back without restarting the app.
 */
class ReconnectBudgetTest {

    private fun routes(client: HttpClient) = object : WebSocketRouteProvider {
        override suspend fun <T> useWebSocketRoute(block: suspend (HttpClient, TorRouteProvenance) -> T): T =
            block(client, route)

        private val route = object : TorRouteProvenance {
            override val usedTorProxy: Boolean = false
            override fun isCurrent(): Boolean = true
        }
    }

    private val failures = AtomicInteger()

    private val listener = object : WebSocketListener {
        override fun onOpen(url: String, route: TorRouteProvenance) {}
        override fun onMessage(url: String, text: String) {}
        override fun onClosing(url: String, code: Int, reason: String) {}
        override fun onClosed(url: String, code: Int, reason: String) {}
        override fun onFailure(url: String, t: Throwable) { failures.incrementAndGet() }
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
        val client = KtorWebSocketClient(routes(HttpClient()))

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
        val client = KtorWebSocketClient(routes(HttpClient()))

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
        val client = KtorWebSocketClient(routes(HttpClient()))

        client.disconnect(deadUrl)
        delay(50)

        assertEquals(0, failures.get(), "disconnect attempted a connection")
        client.shutdown()
    }

    @Test
    fun `an invalid provenance session is retired before reconnecting`() = runBlocking {
        val client = KtorWebSocketClient(routes(HttpClient()))
        val staleRoute = object : TorRouteProvenance {
            override val usedTorProxy = true
            override fun isCurrent() = false
        }
        val staleSession = Proxy.newProxyInstance(
            KtorWebSocketClient::class.java.classLoader,
            arrayOf(WebSocketSession::class.java),
        ) { _, method, _ ->
            if (method.name == "getCoroutineContext") EmptyCoroutineContext else null
        } as WebSocketSession
        client.activeConnections[deadUrl] = KtorWebSocketClient.WebSocketConnection(
            url = deadUrl,
            session = staleSession,
            route = staleRoute,
        )

        client.connect(deadUrl, listener, maxReconnectAttempts = 0)
        delay(150)

        assertTrue(failures.get() > 0, "stale session made connect() return without replacing it")
        client.shutdown()
    }

    @Test
    fun `a send failure retires a live reader without cancelling its automatic reconnect`() = runBlocking {
        val client = KtorWebSocketClient(routes(HttpClient()))
        client.connect(deadUrl, listener, maxReconnectAttempts = 1, initialBackoffMs = 100)
        withTimeout(1_000) {
            while (failures.get() == 0) delay(5)
        }

        val connection = client.activeConnections.getValue(deadUrl)
        connection.reconnectJob?.cancelAndJoin()
        connection.reconnectJob = null
        connection.session = failingSendSession()
        connection.route = object : TorRouteProvenance {
            override val usedTorProxy = false
            override fun isCurrent() = true
        }
        connection.job = Job()
        val failuresBeforeSend = failures.get()

        client.send(deadUrl, "send races a transport failure")

        withTimeout(1_000) {
            while (connection.reconnectJob?.isActive != true) delay(5)
        }
        withTimeout(1_000) {
            while (failures.get() == failuresBeforeSend) delay(5)
        }
        assertFalse(connection.job?.isActive == true, "the stale reader was not retired")
        client.shutdown()
    }

    @Test
    fun `a late old-reader teardown failure neither clears nor reports against its replacement`() = runBlocking {
        val server = ReplacementWebSocketServer()
        val http = HttpClient(CIO) { install(WebSockets) }
        val routes = LateFailingRoutes(http)
        val client = KtorWebSocketClient(routes)
        val opens = AtomicInteger()
        val failures = AtomicInteger()
        val listener = object : WebSocketListener {
            override fun onOpen(url: String, route: TorRouteProvenance) { opens.incrementAndGet() }
            override fun onMessage(url: String, text: String) = Unit
            override fun onClosing(url: String, code: Int, reason: String) = Unit
            override fun onClosed(url: String, code: Int, reason: String) = Unit
            override fun onFailure(url: String, t: Throwable) { failures.incrementAndGet() }
        }

        try {
            client.connect(server.url, listener, maxReconnectAttempts = 0)
            server.firstReady.await()
            withTimeout(1_000) { while (opens.get() != 1) delay(5) }
            val oldReader = client.activeConnections.getValue(server.url).job!!
            server.closeFirst()
            routes.firstReaderReturned.await()

            client.connect(server.url, listener, maxReconnectAttempts = 0)
            withTimeout(1_000) { while (opens.get() != 2) delay(5) }

            val failuresBeforeRelease = failures.get()
            routes.releaseOldTeardown.complete(Unit)
            withTimeout(1_000) { oldReader.join() }

            assertTrue(client.isConnected(server.url), "an old reader cleared the replacement session")
            // Nostr marks a relay disconnected on onFailure, so a stale report would orphan the
            // healthy replacement from new subscriptions.
            assertEquals(failuresBeforeRelease, failures.get(), "an old reader reported failure against its replacement")
        } finally {
            client.shutdown()
            http.close()
            server.close()
        }
    }

    @Test
    fun `a replacement that fails while the old reader is still unwinding reports and retries`() = runBlocking {
        val server = ReplacementWebSocketServer()
        val http = HttpClient(CIO) { install(WebSockets) }
        val routes = FailingReplacementRoutes(http)
        val client = KtorWebSocketClient(routes)
        val opens = AtomicInteger()
        val failures = AtomicInteger()
        val listener = object : WebSocketListener {
            override fun onOpen(url: String, route: TorRouteProvenance) { opens.incrementAndGet() }
            override fun onMessage(url: String, text: String) = Unit
            override fun onClosing(url: String, code: Int, reason: String) = Unit
            override fun onClosed(url: String, code: Int, reason: String) = Unit
            override fun onFailure(url: String, t: Throwable) { failures.incrementAndGet() }
        }

        try {
            client.connect(server.url, listener, maxReconnectAttempts = 0)
            server.firstReady.await()
            withTimeout(1_000) { while (opens.get() != 1) delay(5) }
            server.closeFirst()
            // Reader A is now held in route teardown with its dead session still stored.
            routes.firstReaderReturned.await()
            client.activeConnections.getValue(server.url).session = deadSession()

            client.connect(server.url, listener, maxReconnectAttempts = 2, initialBackoffMs = 10_000)
            withTimeout(1_000) { routes.replacementFailed.await() }
            val connection = client.activeConnections.getValue(server.url)
            withTimeout(1_000) { while (connection.reconnectJob?.isActive != true) delay(5) }

            assertTrue(failures.get() >= 1, "the current reader's failure was treated as obsolete")
        } finally {
            routes.releaseOldTeardown.complete(Unit)
            client.shutdown()
            http.close()
            server.close()
        }
    }

    private fun deadSession(): WebSocketSession = Proxy.newProxyInstance(
        KtorWebSocketClient::class.java.classLoader,
        arrayOf(WebSocketSession::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getCoroutineContext" -> Job().apply { cancel() }
            else -> null
        }
    } as WebSocketSession

    private fun failingSendSession(): WebSocketSession = Proxy.newProxyInstance(
        KtorWebSocketClient::class.java.classLoader,
        arrayOf(WebSocketSession::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getCoroutineContext" -> EmptyCoroutineContext
            "send" -> error("synthetic live-session send failure")
            else -> null
        }
    } as WebSocketSession

    private class LateFailingRoutes(private val client: HttpClient) : WebSocketRouteProvider {
        private var calls = 0
        private var firstRouteCurrent = true
        val firstReaderReturned = CompletableDeferred<Unit>()
        val releaseOldTeardown = CompletableDeferred<Unit>()

        override suspend fun <T> useWebSocketRoute(block: suspend (HttpClient, TorRouteProvenance) -> T): T {
            val call = ++calls
            val route = object : TorRouteProvenance {
                override val usedTorProxy = false
                override fun isCurrent() = call != 1 || firstRouteCurrent
            }
            val result = block(client, route)
            if (call == 1) {
                firstRouteCurrent = false
                firstReaderReturned.complete(Unit)
                withContext(NonCancellable) { releaseOldTeardown.await() }
                error("old reader teardown failed after replacement opened")
            }
            return result
        }
    }

    private class FailingReplacementRoutes(private val client: HttpClient) : WebSocketRouteProvider {
        private var calls = 0
        val firstReaderReturned = CompletableDeferred<Unit>()
        val releaseOldTeardown = CompletableDeferred<Unit>()
        val replacementFailed = CompletableDeferred<Unit>()

        override suspend fun <T> useWebSocketRoute(block: suspend (HttpClient, TorRouteProvenance) -> T): T {
            val call = ++calls
            if (call > 1) {
                replacementFailed.complete(Unit)
                error("replacement dial failed before publishing a session")
            }
            val route = object : TorRouteProvenance {
                override val usedTorProxy = false
                override fun isCurrent() = true
            }
            val result = block(client, route)
            firstReaderReturned.complete(Unit)
            withContext(NonCancellable) { releaseOldTeardown.await() }
            return result
        }
    }

    private class ReplacementWebSocketServer : AutoCloseable {
        private val server = ServerSocket(0)
        private var first: Socket? = null
        private var second: Socket? = null
        val firstReady = CompletableDeferred<Unit>()
        val url = "ws://127.0.0.1:${server.localPort}/relay"

        init {
            thread(isDaemon = true, name = "replacement-websocket-server") {
                try {
                    first = server.accept().also(::upgrade)
                    firstReady.complete(Unit)
                    while (first?.isClosed == false) Thread.sleep(5)
                    second = server.accept().also(::upgrade)
                    while (second?.isClosed == false) Thread.sleep(5)
                } catch (_: Exception) {
                    // close() unblocks accept and the test owns all expected assertions.
                }
            }
        }

        fun closeFirst() { first?.close() }

        override fun close() {
            first?.close()
            second?.close()
            server.close()
        }

        private fun upgrade(socket: Socket) {
            val request = socket.getInputStream().bufferedReader()
            var key: String? = null
            while (true) {
                val line = request.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                    key = line.substringAfter(':').trim()
                }
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest("${key ?: error("missing websocket key")}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".encodeToByteArray()),
            )
            val response = socket.getOutputStream().bufferedWriter()
            response.write("HTTP/1.1 101 Switching Protocols\r\n")
            response.write("Upgrade: websocket\r\n")
            response.write("Connection: Upgrade\r\n")
            response.write("Sec-WebSocket-Accept: $accept\r\n\r\n")
            response.flush()
        }
    }
}
