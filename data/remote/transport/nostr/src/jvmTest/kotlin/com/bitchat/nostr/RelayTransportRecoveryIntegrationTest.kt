package com.bitchat.nostr

import com.bitchat.cache.impl.InMemoryCache
import com.bitchat.client.RouteAwareClientProvider
import com.bitchat.client.TorRouteProvenance
import com.bitchat.client.WebSocketRouteProvider
import com.bitchat.client.websocket.NostrWebSocketClient
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.domain.tor.MutableRequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrFilter
import com.bitchat.nostr.model.RelayInfo
import com.bitchat.nostr.util.NostrEventDeduplicator
import com.bitchat.tor.TorRouteSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RelayTransportRecoveryIntegrationTest {
    @Test
    fun `failed Tor websocket retirement recovers directly after requested OFF`() = runBlocking {
        withTimeout(10.seconds) {
            val relayServer = TorOffRecoveryRelayServer()
            val socks = ForwardingSocks5Proxy(relayServer.port)
            val intent = MutableIntent(TorMode.ON)
            val torRetirementFailed = CompletableDeferred<Unit>()
            val factoryCalls = AtomicInteger()
            val provider = RouteAwareClientProvider(
                appInformation = AppInformation(Version("test", "test", ""), 1, "test", false),
                requestedIntent = intent,
                torRouteSource = ReadyTorRouteSource(socks.port),
                engineFactory = {
                    if (factoryCalls.getAndIncrement() == 0) FailingCloseEngineFactory(torRetirementFailed) else OkHttp
                },
            )
            val routes = RecordingRoutes(provider)
            val client = NostrWebSocketClient(routes)
            val relay = NostrRelay(
                eventDeduplicator = NostrEventDeduplicator(),
                wsClient = client,
                relayCache = InMemoryCache<String, RelayInfo>().apply {
                    this[relayServer.url] = RelayInfo(relayServer.url, 0.0, 0.0)
                },
            )
            val torEvent = CompletableDeferred<NostrEvent>()
            val directEvent = CompletableDeferred<NostrEvent>()

            try {
                relay.subscribe(
                    subscriptionId = "tor-off-recovery",
                    filter = NostrFilter(kinds = listOf(1)),
                    handler = { event ->
                        if (event.id == torRecoveryEvent.id) torEvent.complete(event)
                        if (event.id == directRecoveryEvent.id) directEvent.complete(event)
                    },
                    targetRelayUrls = setOf(relayServer.url),
                )
                relay.ensureGeohashRelaysConnected("u4pruydqqvj", nRelays = 1)

                withTimeout(2.seconds) { relayServer.firstRequest.await() }
                assertEquals(torRecoveryEvent.id, withTimeout(2.seconds) { torEvent.await() }.id)
                assertEquals(1, socks.completedConnects.get(), socks.diagnostics())
                assertTrue(routes.snapshot().single().usedTorProxy, "first connection bypassed Tor: ${routes.diagnostics()}")

                relay.disconnectAll()
                withTimeout(2.seconds) { torRetirementFailed.await() }

                provider.transition { intent.set(TorMode.OFF) }
                relay.ensureGeohashRelaysConnected("u4pruydqqvj", nRelays = 1)

                withTimeout(2.seconds) { relayServer.secondRequest.await() }
                assertEquals(directRecoveryEvent.id, withTimeout(2.seconds) { directEvent.await() }.id)
                val openedRoutes = routes.snapshot()
                assertEquals(2, openedRoutes.size, routes.diagnostics())
                assertFalse(openedRoutes[1].usedTorProxy, "OFF recovery reused the Tor route: ${routes.diagnostics()}")
                assertTrue(openedRoutes[1].isCurrent(), "OFF route was not current: ${routes.diagnostics()}")
                // The transition must also have revoked the route the Tor connection held, or stale
                // work could still believe its lease is good after the policy changed.
                assertFalse(openedRoutes[0].isCurrent(), "the Tor route survived the transition: ${routes.diagnostics()}")
                assertEquals(1, socks.completedConnects.get(), socks.diagnostics())
                assertEquals(2, relayServer.completedHandshakes.get(), relayServer.diagnostics())
                assertEquals(2, relayServer.subscriptionRequests.get(), relayServer.diagnostics())
            } finally {
                runCatching { client.shutdown() }
                socks.close()
                relayServer.close()
            }
        }
    }

    @Test
    fun `real transport close reconnects replays subscription and delivers event`() = runBlocking {
        withTimeout(10.seconds) {
            val server = DroppingRelayServer()
            val http = HttpClient(CIO) { install(WebSockets) }
            val client = NostrWebSocketClient(directRoutes(http))
            val relay = NostrRelay(
                eventDeduplicator = NostrEventDeduplicator(),
                wsClient = client,
                relayCache = InMemoryCache<String, RelayInfo>().apply {
                    this[server.url] = RelayInfo(server.url, 0.0, 0.0)
                },
            )
            val delivered = CompletableDeferred<NostrEvent>()
            val subscriptionId = "loopback-recovery"

            try {
                relay.subscribe(
                    subscriptionId = subscriptionId,
                    filter = NostrFilter(kinds = listOf(1)),
                    handler = { delivered.complete(it) },
                    targetRelayUrls = setOf(server.url),
                )
                relay.ensureGeohashRelaysConnected("u4pruydqqvj", nRelays = 1)

                withTimeout(2.seconds) { server.firstRequest.await() }
                withTimeout(2.seconds) { server.secondRequest.await() }
                withTimeout(2.seconds) { server.eventWritten.await() }
                assertEquals(expectedEvent.id, withTimeout(2.seconds) { delivered.await() }.id)
                assertEquals(2, server.completedHandshakes.get(), server.diagnostics())
                assertEquals(2, server.subscriptionRequests.get(), server.diagnostics())
                assertEquals(0, server.extraConnections.get(), server.diagnostics())
            } finally {
                server.close()
                client.shutdown()
                http.close()
            }
        }
    }

    private fun directRoutes(http: HttpClient) = object : WebSocketRouteProvider {
        private val directRoute = object : TorRouteProvenance {
            override val usedTorProxy = false
            override fun isCurrent() = true
        }

        override suspend fun <T> useWebSocketRoute(block: suspend (HttpClient, TorRouteProvenance) -> T): T =
            block(http, directRoute)
    }

    private class RecordingRoutes(private val delegate: WebSocketRouteProvider) : WebSocketRouteProvider {
        private val opened = Collections.synchronizedList(mutableListOf<TorRouteProvenance>())

        override suspend fun <T> useWebSocketRoute(block: suspend (HttpClient, TorRouteProvenance) -> T): T =
            delegate.useWebSocketRoute { client, route ->
                opened.add(route)
                block(client, route)
            }

        fun snapshot(): List<TorRouteProvenance> = synchronized(opened) { opened.toList() }

        fun diagnostics() = snapshot().mapIndexed { index, route ->
            "route[$index]=usedTorProxy=${route.usedTorProxy}, current=${route.isCurrent()}"
        }.joinToString()
    }

    private class MutableIntent(initial: TorMode) : MutableRequestedTorIntent {
        private val state = MutableStateFlow(initial)
        override val current: TorMode get() = state.value
        override val updates: StateFlow<TorMode> = state
        override fun set(mode: TorMode) {
            state.value = mode
        }
    }

    private class ReadyTorRouteSource(port: Int) : TorRouteSource {
        override val statusFlow: StateFlow<TorStatus> = MutableStateFlow(
            TorStatus(
                mode = TorMode.ON,
                running = true,
                state = TorState.RUNNING,
                socksPort = port,
                routeGeneration = 1,
            ),
        )
        override val isAvailable = true
        override fun getSocksProxyAddress(): Pair<String, Int> = "127.0.0.1" to statusFlow.value.socksPort
        override fun isProxyReady() = true
    }

    /**
     * The production JVM engine, with a close that reports and then fails, so the first route's
     * retirement completes exceptionally. It has to be OkHttp: CIO ignores a SOCKS [ProxyConfig],
     * so a CIO client would reach the relay directly and the proxy would never see the route.
     */
    private class FailingCloseEngineFactory(
        private val closeAttempted: CompletableDeferred<Unit>,
    ) : HttpClientEngineFactory<OkHttpConfig> {
        override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine {
            val delegate = OkHttp.create(block)
            return object : HttpClientEngine by delegate {
                override fun close() {
                    delegate.close()
                    closeAttempted.complete(Unit)
                    error("Tor engine close failed")
                }
            }
        }
    }

    private class ForwardingSocks5Proxy(private val relayPort: Int) : AutoCloseable {
        private val server = ServerSocket(0)
        private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        private val failure = AtomicReference<Throwable?>(null)
        val completedConnects = AtomicInteger()
        private val worker = thread(isDaemon = true, name = "tor-off-recovery-socks") {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    sockets.add(socket)
                    thread(isDaemon = true, name = "tor-off-recovery-socks-client") { forward(socket) }
                } catch (error: Throwable) {
                    if (!server.isClosed) failure.compareAndSet(null, error)
                    return@thread
                }
            }
        }

        val port: Int get() = server.localPort

        fun diagnostics() = "SOCKS connects=${completedConnects.get()}, failure=${failure.get()?.javaClass?.simpleName}"

        private fun forward(client: Socket) {
            var relay: Socket? = null
            try {
                val input = client.getInputStream()
                val output = client.getOutputStream()
                check(input.readByte() == 5) { "SOCKS greeting version" }
                input.readExact(input.readByte())
                output.write(byteArrayOf(5, 0))
                output.flush()

                check(input.readByte() == 5) { "SOCKS CONNECT version" }
                check(input.readByte() == 1) { "SOCKS command was not CONNECT" }
                input.readByte()
                val host = when (input.readByte()) {
                    1 -> InetAddress.getByAddress(input.readExact(4)).hostAddress
                    3 -> input.readExact(input.readByte()).decodeToString()
                    4 -> InetAddress.getByAddress(input.readExact(16)).hostAddress
                    else -> error("SOCKS address type")
                }
                val destinationPort = (input.readByte() shl 8) or input.readByte()
                check(destinationPort == relayPort) { "SOCKS forwarded to unexpected port" }
                relay = Socket(host, destinationPort)
                sockets.add(relay)
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
                completedConnects.incrementAndGet()

                val upstream = relay ?: error("SOCKS relay socket was not opened")
                val relayToClient = thread(isDaemon = true, name = "tor-off-recovery-socks-upstream") {
                    runCatching { upstream.getInputStream().copyTo(output) }
                }
                input.copyTo(upstream.getOutputStream())
                relayToClient.join(1_000)
            } catch (error: Throwable) {
                if (!server.isClosed) failure.compareAndSet(null, error)
            } finally {
                relay?.close()
                client.close()
            }
        }

        override fun close() {
            server.close()
            synchronized(sockets) { sockets.toList() }.forEach { socket -> socket.close() }
            worker.join(1_000)
        }

        private fun java.io.InputStream.readByte(): Int {
            val value = read()
            check(value >= 0) { "unexpected SOCKS EOF" }
            return value
        }

        private fun java.io.InputStream.readExact(length: Int): ByteArray = ByteArray(length).also { bytes ->
            var offset = 0
            while (offset < bytes.size) {
                val read = read(bytes, offset, bytes.size - offset)
                check(read >= 0) { "unexpected SOCKS EOF" }
                offset += read
            }
        }
    }

    private class TorOffRecoveryRelayServer : AutoCloseable {
        private val server = ServerSocket(0)
        private var first: Socket? = null
        private var second: Socket? = null
        val completedHandshakes = AtomicInteger()
        val subscriptionRequests = AtomicInteger()
        val firstRequest = CompletableDeferred<Unit>()
        val secondRequest = CompletableDeferred<Unit>()
        private val failure = AtomicReference<Throwable?>(null)
        val port = server.localPort
        val url = "ws://127.0.0.1:$port/tor-off-recovery"

        init {
            thread(isDaemon = true, name = "tor-off-recovery-relay") {
                try {
                    first = server.accept().also(::upgrade)
                    completedHandshakes.incrementAndGet()
                    requireRequest(first!!)
                    subscriptionRequests.incrementAndGet()
                    firstRequest.complete(Unit)
                    writeText(first!!, "[\"EVENT\",\"tor-off-recovery\",$torRecoveryEventJson]")

                    second = server.accept().also(::upgrade)
                    completedHandshakes.incrementAndGet()
                    requireRequest(second!!)
                    subscriptionRequests.incrementAndGet()
                    secondRequest.complete(Unit)
                    writeText(second!!, "[\"EVENT\",\"tor-off-recovery\",$directRecoveryEventJson]")
                } catch (error: Throwable) {
                    if (!server.isClosed) failure.compareAndSet(null, error)
                }
            }
        }

        fun diagnostics() = "handshakes=${completedHandshakes.get()}, requests=${subscriptionRequests.get()}, failure=${failure.get()?.javaClass?.simpleName}"

        override fun close() {
            first?.close()
            second?.close()
            server.close()
        }

        private fun upgrade(socket: Socket) {
            val input = socket.getInputStream()
            var key: String? = null
            while (true) {
                val line = readHttpLine(input) ?: error("websocket handshake ended early")
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest("${key ?: error("missing websocket key")}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".encodeToByteArray()),
            )
            socket.getOutputStream().bufferedWriter().apply {
                write("HTTP/1.1 101 Switching Protocols\r\n")
                write("Upgrade: websocket\r\n")
                write("Connection: Upgrade\r\n")
                write("Sec-WebSocket-Accept: $accept\r\n\r\n")
                flush()
            }
        }

        private fun requireRequest(socket: Socket) {
            check(readText(socket).startsWith("[\"REQ\",\"tor-off-recovery\",")) { "expected replayed REQ" }
        }

        private fun readHttpLine(input: java.io.InputStream): String? {
            val bytes = ArrayList<Byte>()
            while (true) {
                val byte = input.read()
                if (byte == -1) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
                if (byte == '\n'.code) return bytes.toByteArray().decodeToString().removeSuffix("\r")
                bytes += byte.toByte()
            }
        }

        private fun readText(socket: Socket): String {
            val input = socket.getInputStream()
            val firstByte = input.read()
            check(firstByte and 0x0f == 1) { "expected text frame" }
            val secondByte = input.read()
            check(secondByte and 0x80 != 0) { "client frame was not masked" }
            var length = secondByte and 0x7f
            if (length == 126) length = (input.read() shl 8) or input.read()
            check(length < 126) { "unexpected frame length" }
            val mask = ByteArray(4).also { input.readNBytes(it, 0, it.size) }
            val payload = ByteArray(length).also { input.readNBytes(it, 0, it.size) }
            payload.indices.forEach { index -> payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
            return payload.decodeToString()
        }

        private fun writeText(socket: Socket, text: String) {
            val payload = text.encodeToByteArray()
            check(payload.size <= 0xffff) { "test payload unexpectedly long" }
            socket.getOutputStream().apply {
                write(0x81)
                if (payload.size < 126) {
                    write(payload.size)
                } else {
                    write(126)
                    write(payload.size ushr 8)
                    write(payload.size and 0xff)
                }
                write(payload)
                flush()
            }
        }
    }

    private class DroppingRelayServer : AutoCloseable {
        private val server = ServerSocket(0)
        private var first: Socket? = null
        private var second: Socket? = null
        val completedHandshakes = AtomicInteger()
        val subscriptionRequests = AtomicInteger()
        val extraConnections = AtomicInteger()
        val firstRequest = CompletableDeferred<Unit>()
        val secondRequest = CompletableDeferred<Unit>()
        val eventWritten = CompletableDeferred<Unit>()
        val url = "ws://127.0.0.1:${server.localPort}/loopback-recovery"

        init {
            thread(isDaemon = true, name = "relay-transport-recovery") {
                try {
                    first = server.accept().also(::upgrade)
                    completedHandshakes.incrementAndGet()
                    requireRequest(first!!)
                    subscriptionRequests.incrementAndGet()
                    firstRequest.complete(Unit)
                    // A bare TCP close makes CIO's reader take its real transport-failure path.
                    first?.close()

                    second = server.accept().also(::upgrade)
                    completedHandshakes.incrementAndGet()
                    requireRequest(second!!)
                    subscriptionRequests.incrementAndGet()
                    secondRequest.complete(Unit)
                    writeText(second!!, "[\"EVENT\",\"loopback-recovery\",${expectedEventJson}]")
                    eventWritten.complete(Unit)

                    while (!server.isClosed) {
                        val extra = server.accept()
                        extraConnections.incrementAndGet()
                        extra.close()
                    }
                } catch (_: Exception) {
                    // close() releases accept; test assertions report any missing phase.
                }
            }
        }

        override fun close() {
            first?.close()
            second?.close()
            server.close()
        }

        fun diagnostics() = "handshakes=${completedHandshakes.get()}, requests=${subscriptionRequests.get()}, extras=${extraConnections.get()}"

        private fun upgrade(socket: Socket) {
            val input = socket.getInputStream()
            var key: String? = null
            while (true) {
                val line = readHttpLine(input) ?: error("websocket handshake ended early")
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                    key = line.substringAfter(':').trim()
                }
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest("${key ?: error("missing websocket key")}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".encodeToByteArray()),
            )
            socket.getOutputStream().bufferedWriter().apply {
                write("HTTP/1.1 101 Switching Protocols\r\n")
                write("Upgrade: websocket\r\n")
                write("Connection: Upgrade\r\n")
                write("Sec-WebSocket-Accept: $accept\r\n\r\n")
                flush()
            }
        }

        /** Do not buffer past CRLF: the next bytes are the first masked WebSocket frame. */
        private fun readHttpLine(input: java.io.InputStream): String? {
            val bytes = ArrayList<Byte>()
            while (true) {
                val byte = input.read()
                if (byte == -1) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
                if (byte == '\n'.code) return bytes.toByteArray().decodeToString().removeSuffix("\r")
                bytes += byte.toByte()
            }
        }

        private fun requireRequest(socket: Socket) {
            val text = readText(socket)
            check(text.startsWith("[\"REQ\",\"loopback-recovery\",")) { "expected replayed REQ, got $text" }
        }

        private fun readText(socket: Socket): String {
            val input = socket.getInputStream()
            val firstByte = input.read()
            check(firstByte and 0x0f == 1) { "expected text frame, opcode=${firstByte and 0x0f}" }
            val secondByte = input.read()
            check(secondByte and 0x80 != 0) { "client frame was not masked" }
            var length = secondByte and 0x7f
            if (length == 126) length = (input.read() shl 8) or input.read()
            check(length < 126) { "unexpected frame length $length" }
            val mask = ByteArray(4).also { input.readNBytes(it, 0, it.size) }
            val payload = ByteArray(length).also { input.readNBytes(it, 0, it.size) }
            payload.indices.forEach { index -> payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
            return payload.decodeToString()
        }

        private fun writeText(socket: Socket, text: String) {
            val payload = text.encodeToByteArray()
            check(payload.size <= 0xffff) { "test payload unexpectedly long" }
            socket.getOutputStream().apply {
                write(0x81)
                if (payload.size < 126) {
                    write(payload.size)
                } else {
                    write(126)
                    write(payload.size ushr 8)
                    write(payload.size and 0xff)
                }
                write(payload)
                flush()
            }
        }
    }

    private companion object {
        val torRecoveryEvent = NostrEvent(
            id = "a".repeat(64),
            pubkey = "p".repeat(64),
            createdAt = 1,
            kind = 1,
            tags = emptyList(),
            content = "tor-route",
        )
        val directRecoveryEvent = NostrEvent(
            id = "b".repeat(64),
            pubkey = "p".repeat(64),
            createdAt = 2,
            kind = 1,
            tags = emptyList(),
            content = "direct-route",
        )
        const val torRecoveryEventJson = "{\"id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"pubkey\":\"pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp\",\"created_at\":1,\"kind\":1,\"tags\":[],\"content\":\"tor-route\"}"
        const val directRecoveryEventJson = "{\"id\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"pubkey\":\"pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp\",\"created_at\":2,\"kind\":1,\"tags\":[],\"content\":\"direct-route\"}"
        val expectedEvent = NostrEvent(
            id = "e".repeat(64),
            pubkey = "p".repeat(64),
            createdAt = 1,
            kind = 1,
            tags = emptyList(),
            content = "recovered-over-loopback",
        )
        const val expectedEventJson = "{\"id\":\"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee\",\"pubkey\":\"pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp\",\"created_at\":1,\"kind\":1,\"tags\":[],\"content\":\"recovered-over-loopback\"}"
    }
}
