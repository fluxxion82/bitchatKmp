package com.bitchat.client

import com.bitchat.client.model.ClientType
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.callContext
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DirectHttpGetTest {
    private class Intent(var mode: TorMode) : RequestedTorIntent {
        override val current get() = mode
        override val updates: StateFlow<TorMode> get() = MutableStateFlow(mode)
    }

    private val appInformation = AppInformation(Version("test", "test", ""), 1, "test", false)

    private fun provider(intent: RequestedTorIntent, readyTimeout: kotlin.time.Duration = 1.seconds) =
        RouteAwareClientProvider(
            appInformation = appInformation,
            requestedIntent = intent,
            torRouteSource = null,
            readyTimeout = readyTimeout,
        )

    private fun provider(intent: RequestedTorIntent, engine: () -> HttpClientEngineFactory<*>) =
        RouteAwareClientProvider(
            appInformation = appInformation,
            requestedIntent = intent,
            torRouteSource = null,
            engineFactory = { engine() },
        )

    /** Holds its request until cancelled and its sockets until [mayRelease]. */
    @OptIn(InternalAPI::class)
    private class HeldEngine(dispatcher: CoroutineDispatcher? = null) :
        HttpClientEngineBase("held"), RouteTeardownAwaiter {
        override val config = HttpClientEngineConfig().apply { this.dispatcher = dispatcher }
        val requested = CompletableDeferred<Unit>()
        val mayRelease = CompletableDeferred<Unit>()

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            requested.complete(Unit)
            awaitCancellation()
        }

        override suspend fun awaitRouteTeardown() = mayRelease.await()
    }

    private class Fixed(private val engine: HeldEngine) : HttpClientEngineFactory<HttpClientEngineConfig> {
        override fun create(block: HttpClientEngineConfig.() -> Unit) = engine
    }

    /** Answers every request with [status] and [body]. */
    @OptIn(InternalAPI::class)
    private class Answering(
        private val status: HttpStatusCode,
        private val body: ByteReadChannel,
    ) : HttpClientEngineFactory<HttpClientEngineConfig> {
        override fun create(block: HttpClientEngineConfig.() -> Unit) = object : HttpClientEngineBase("answering") {
            override val config = HttpClientEngineConfig()

            override suspend fun execute(data: HttpRequestData) = HttpResponseData(
                status, GMTDate(), Headers.Empty, HttpProtocolVersion.HTTP_1_1, body, callContext(),
            )
        }
    }

    @Test
    fun `off returns a two hundred body`() = runBlocking {
        LoopbackServer.responding(200, "here").use { server ->
            val result = DirectHttpGet(provider(Intent(TorMode.OFF))).get(server.url, admit = { true })

            assertEquals("here", result)
            assertTrue(server.awaitConnection())
            assertEquals("GET / HTTP/1.1", server.requestLine)
        }
    }

    @Test
    fun `on returns without waiting for a Tor route or connecting`() = runBlocking {
        LoopbackServer.holding().use { server ->
            val result = withTimeout(10.seconds) {
                DirectHttpGet(provider(Intent(TorMode.ON), readyTimeout = 1.hours)).get(server.url, admit = { true })
            }

            assertNull(result)
            assertTrue(server.awaitNoConnection())
        }
    }

    @Test
    fun `a refused request does not connect`() = runBlocking {
        LoopbackServer.holding().use { server ->
            assertNull(DirectHttpGet(provider(Intent(TorMode.OFF))).get(server.url) { false })
            assertTrue(server.awaitNoConnection())
        }
    }

    @Test
    fun `no engine is built while Tor is requested`() = runBlocking {
        val built = AtomicInteger()
        val provider = provider(Intent(TorMode.ON)) {
            built.incrementAndGet()
            OkHttp
        }

        assertNull(provider.withDirectClient { "used" })

        assertEquals(0, built.get())
    }

    @Test
    fun `a switch to Tor is not published until the engine has released its sockets`() = runBlocking {
        val intent = Intent(TorMode.OFF)
        val engine = HeldEngine()
        val provider = provider(intent) { Fixed(engine) }
        val published = AtomicBoolean()
        val result = async(Dispatchers.Default) { DirectHttpGet(provider).get("http://held.invalid/") { true } }
        engine.requested.await()

        val switching = launch(Dispatchers.Default) {
            provider.transition {
                published.set(true)
                intent.mode = TorMode.ON
            }
        }
        delay(300)
        assertFalse(published.get())

        engine.mayRelease.complete(Unit)
        switching.join()

        assertTrue(published.get())
        assertNull(result.await())
    }

    @Test
    fun `an answer may be sixteen kibibytes and no longer`() = runBlocking {
        val direct = DirectHttpGet(provider(Intent(TorMode.OFF)))
        val longest = "x".repeat(16 * 1024)
        LoopbackServer.responding(200, longest).use { server ->
            assertEquals(longest, direct.get(server.url) { true })
        }
        LoopbackServer.responding(200, longest + "x").use { server ->
            assertNull(direct.get(server.url) { true })
        }
    }

    @Test
    fun `a request is given up after ten seconds`() = runTest {
        val engine = HeldEngine(StandardTestDispatcher(testScheduler)).apply { mayRelease.complete(Unit) }
        val provider = provider(Intent(TorMode.OFF)) { Fixed(engine) }

        assertNull(DirectHttpGet(provider).get("http://held.invalid/") { true })

        assertEquals(10_000, currentTime)
    }

    @Test
    fun `a body closed with a failure is not read as its end`() = runBlocking {
        val broken = ByteChannel(autoFlush = true).apply {
            writeFully("""{"latitude":1.5,"longitude":2.5}""".encodeToByteArray())
            close(IllegalStateException("the transfer broke"))
        }
        assertTrue(runCatching { broken.readAtMost(64) }.isFailure)

        val whole = ByteChannel(autoFlush = true).apply {
            writeFully("""{"latitude":1.5}""".encodeToByteArray())
            close(null)
        }
        assertEquals("""{"latitude":1.5}""", whole.readAtMost(64))
    }

    @Test
    fun `a refused status is answered without reading its body`() = runBlocking {
        val unreadable = ByteChannel().apply { cancel(IllegalStateException("the body was read")) }
        val provider = provider(Intent(TorMode.OFF)) { Answering(HttpStatusCode.NotFound, unreadable) }

        assertNull(DirectHttpGet(provider).get("http://answering.invalid/") { true })
    }

    @Test
    fun `a direct client that waited out a switch to Tor is not used`() = runBlocking {
        val intent = Intent(TorMode.OFF)
        val provider = provider(intent)
        val publishing = CompletableDeferred<Unit>()
        val mayPublish = CompletableDeferred<Unit>()
        val switching = launch(Dispatchers.Default) {
            provider.transition {
                publishing.complete(Unit)
                mayPublish.await()
                intent.mode = TorMode.ON
            }
        }
        publishing.await()
        var used = false
        val asked = CompletableDeferred<Unit>()
        val waiting = async(Dispatchers.Default) {
            asked.complete(Unit)
            provider.withDirectClient { used = true }
        }
        asked.await()
        // Admission is closed until the switch has published; give the call time to reach it.
        delay(200)

        mayPublish.complete(Unit)
        switching.join()

        assertNull(waiting.await())
        assertFalse(used)
    }

    @Test
    fun `non two hundred responses and redirects are no answer`() = runBlocking {
        LoopbackServer.responding(404, "missing").use { missing ->
            assertNull(DirectHttpGet(provider(Intent(TorMode.OFF))).get(missing.url) { true })
        }
        LoopbackServer.responding(500, "broken").use { broken ->
            assertNull(DirectHttpGet(provider(Intent(TorMode.OFF))).get(broken.url) { true })
        }
        LoopbackServer.holding().use { redirectedTo ->
            LoopbackServer.responding(302, "", "Location: ${redirectedTo.url}\r\n").use { redirect ->
                assertNull(DirectHttpGet(provider(Intent(TorMode.OFF))).get(redirect.url) { true })
                assertTrue(redirectedTo.awaitNoConnection())
            }
        }
    }

    @Test
    fun `body limits and timeout are no answer`() = runBlocking {
        val direct = DirectHttpGet(provider(Intent(TorMode.OFF)), maxBytes = 4)
        LoopbackServer.responding(200, "12345").use { tooLarge ->
            assertNull(direct.get(tooLarge.url) { true })
        }
        LoopbackServer.responding(200, "1234").use { exact ->
            assertEquals("1234", direct.get(exact.url) { true })
        }
        LoopbackServer.holding().use { slow ->
            assertNull(DirectHttpGet(provider(Intent(TorMode.OFF)), timeout = 300.milliseconds).get(slow.url) { true })
            assertTrue(slow.awaitConnection())
        }
    }

    @Test
    fun `an oversized unbounded body returns before the server closes`() = runBlocking {
        val direct = DirectHttpGet(
            routes = provider(Intent(TorMode.OFF)),
            timeout = 30.seconds,
            maxBytes = 4,
        )
        LoopbackServer.respondingWithoutLengthThenHolding("12345").use { server ->
            assertNull(withTimeout(10.seconds) { direct.get(server.url) { true } })
            assertTrue(server.awaitConnection())
        }
    }

    @Test
    fun `transition cancels a direct request before publishing policy`() = runBlocking {
        val intent = Intent(TorMode.OFF)
        val provider = provider(intent)
        val order = Collections.synchronizedList(mutableListOf<String>())
        LoopbackServer.holding().use { server ->
            val result = async(Dispatchers.Default) {
                val value = DirectHttpGet(provider).get(server.url) { true }
                order += "returned"
                value
            }
            assertTrue(server.awaitConnection())

            provider.transition {
                order += "published"
                intent.mode = TorMode.ON
            }

            assertNull(result.await())
            assertEquals(setOf("returned", "published"), order.toSet())
            assertNull(DirectHttpGet(provider).get(server.url) { true })
        }
    }

    @Test
    fun `caller cancellation still cancels its job and leaves no tracked work`() = runBlocking {
        val provider = provider(Intent(TorMode.OFF))
        LoopbackServer.holding().use { server ->
            var afterGet = false
            val caller = launch(Dispatchers.Default) {
                DirectHttpGet(provider).get(server.url) { true }
                afterGet = true
            }
            assertTrue(server.awaitConnection())

            caller.cancelAndJoin()

            assertTrue(caller.isCancelled)
            assertFalse(afterGet)
            provider.revokeAndJoin()
        }
    }

    @Test
    fun `rest clients retain direct off behavior`() = runBlocking {
        val result = provider(Intent(TorMode.OFF)).withRestClient(ClientType.NOSTR, emptyList()) { "ok" }
        assertEquals("ok", result)
    }

    private class LoopbackServer private constructor(private val reply: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0).apply { soTimeout = 500 }
        private val accepted = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        private val connections = AtomicInteger()

        @Volatile
        var requestLine: String? = null
            private set
        private val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    connections.incrementAndGet()
                    accepted.countDown()
                    requestLine = socket.inputStream.readHeaders()
                    reply(socket)
                }
            } catch (_: SocketException) {
            } catch (_: SocketTimeoutException) {
            } finally {
                finished.countDown()
            }
        }

        val url get() = "http://127.0.0.1:${server.localPort}/"

        fun awaitConnection(): Boolean = accepted.await(10, TimeUnit.SECONDS)

        fun awaitNoConnection(): Boolean = finished.await(2, TimeUnit.SECONDS) && connections.get() == 0

        override fun close() {
            server.close()
            worker.join(1_000)
        }

        companion object {
            fun responding(status: Int, body: String, headers: String = ""): LoopbackServer = LoopbackServer { socket ->
                val bytes = body.encodeToByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\n$headers\r\n".encodeToByteArray())
                    write(bytes)
                    flush()
                }
            }

            fun holding(): LoopbackServer = LoopbackServer { socket ->
                try {
                    while (socket.inputStream.read() != -1) {
                    }
                } catch (_: Exception) {
                }
            }

            fun respondingWithoutLengthThenHolding(body: String): LoopbackServer = LoopbackServer { socket ->
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 Test\r\n\r\n".encodeToByteArray())
                    write(body.encodeToByteArray())
                    flush()
                }
                try {
                    while (socket.inputStream.read() != -1) {
                    }
                } catch (_: Exception) {
                }
            }

            /** Reads to the end of the request's headers and gives its first line. */
            private fun InputStream.readHeaders(): String {
                val head = StringBuilder()
                var window = 0
                while (window != 0x0d0a0d0a) {
                    val byte = read()
                    if (byte < 0) break
                    head.append(byte.toChar())
                    window = (window shl 8) or byte
                }
                return head.lineSequence().first()
            }
        }
    }
}
