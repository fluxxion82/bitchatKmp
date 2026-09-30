package com.bitchat.client

import com.bitchat.client.model.ClientType
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.tor.TorManager
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteAwareSocksCaptureTest {
    @Test
    fun `HTTP and WebSocket route through SOCKS domains despite proxy exclusion environment`() = runBlocking {
        assertEquals("*", System.getenv("NO_PROXY"), "run this test with NO_PROXY='*'")
        assertEquals("*", System.getenv("no_proxy"), "run this test with no_proxy='*'")

        val directConnections = AtomicInteger()
        ServerSocket(0).use { direct ->
            val directThread = acceptDirectConnections(direct, directConnections)
            SocksCapture().use { socks ->
                val manager = readyTorManager(socks.port)
                val provider = RouteAwareClientProvider(
                    appInformation = AppInformation(Version("test", "test", ""), 1, "test", false),
                    requestedIntent = TorOnIntent,
                    torManager = manager,
                )
                val destination = "localhost:${direct.localPort}"

                runCatching {
                    provider.withRestClient(ClientType.NOSTR, emptyList()) { client ->
                        client.get("http://$destination/http")
                    }
                }
                runCatching {
                    provider.useWebSocketRoute { client, _ ->
                        client.webSocketSession("ws://$destination/websocket")
                    }
                }

                assertTrue(socks.awaitConnects(), "HTTP and WebSocket did not both reach SOCKS")
                assertEquals(listOf("localhost", "localhost"), socks.domainNames())
                assertEquals(listOf(SocksCapture.ATYP_DOMAIN, SocksCapture.ATYP_DOMAIN), socks.addressTypes())
                assertEquals(0, directConnections.get(), "destination received a direct connection")
            }
            directThread.interrupt()
        }
    }

    private fun readyTorManager(port: Int): TorManager {
        val manager = TorManager(Files.createTempDirectory("route-aware-socks").toString())
        val statusField = TorManager::class.java.getDeclaredField("_statusFlow").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val status = statusField.get(manager) as MutableStateFlow<TorStatus>
        TorManager::class.java.getDeclaredField("currentPort").apply {
            isAccessible = true
            setInt(manager, port)
        }
        status.value = TorStatus(mode = TorMode.ON, running = true, state = TorState.RUNNING, socksPort = port, routeGeneration = 1)
        return manager
    }

    private fun acceptDirectConnections(server: ServerSocket, connections: AtomicInteger): Thread = thread(isDaemon = true) {
        while (!server.isClosed) {
            try {
                server.accept().use { connections.incrementAndGet() }
            } catch (_: Exception) {
                return@thread
            }
        }
    }

    private object TorOnIntent : RequestedTorIntent {
        override val current: TorMode = TorMode.ON
        override val updates: StateFlow<TorMode> = MutableStateFlow(TorMode.ON)
    }

    private class SocksCapture : AutoCloseable {
        private val server = ServerSocket(0)
        private val connects = CountDownLatch(2)
        private val types = Collections.synchronizedList(mutableListOf<Int>())
        private val domains = Collections.synchronizedList(mutableListOf<String>())
        private val failure = AtomicReference<Throwable?>(null)
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use(::recordConnect)
                } catch (error: Throwable) {
                    if (!server.isClosed) failure.compareAndSet(null, error)
                    return@thread
                }
            }
        }

        val port: Int get() = server.localPort

        fun awaitConnects(): Boolean {
            val connected = connects.await(10, TimeUnit.SECONDS)
            failure.get()?.let { throw AssertionError("SOCKS capture failed", it) }
            return connected
        }

        fun addressTypes(): List<Int> = synchronized(types) { types.toList() }

        fun domainNames(): List<String> = synchronized(domains) { domains.toList() }

        private fun recordConnect(socket: Socket) {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            assertEquals(5, input.readByte(), "SOCKS version")
            input.readExact(input.readByte())
            output.write(byteArrayOf(5, 0))
            output.flush()

            assertEquals(5, input.readByte(), "CONNECT version")
            assertEquals(1, input.readByte(), "SOCKS command")
            input.readByte() // reserved
            val addressType = input.readByte()
            types += addressType
            when (addressType) {
                ATYP_DOMAIN -> domains += input.readExact(input.readByte()).decodeToString()
                ATYP_IPV4 -> input.readExact(4)
                ATYP_IPV6 -> input.readExact(16)
                else -> error("unknown SOCKS address type $addressType")
            }
            input.readExact(2)
            connects.countDown()
            // Reject after capture: any destination connection is therefore a bypass, not proxy forwarding.
            output.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
        }

        override fun close() {
            server.close()
            worker.join(1_000)
        }

        private fun InputStream.readByte(): Int {
            val value = read()
            check(value >= 0) { "unexpected EOF" }
            return value
        }

        private fun InputStream.readExact(length: Int): ByteArray = ByteArray(length).also { bytes ->
            var offset = 0
            while (offset < bytes.size) {
                val read = read(bytes, offset, bytes.size - offset)
                check(read >= 0) { "unexpected EOF" }
                offset += read
            }
        }

        companion object {
            const val ATYP_DOMAIN = 3
            const val ATYP_IPV4 = 1
            const val ATYP_IPV6 = 4
        }
    }
}
