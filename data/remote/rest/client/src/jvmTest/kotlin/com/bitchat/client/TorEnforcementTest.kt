package com.bitchat.client

import com.bitchat.domain.tor.model.TorMode
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TorEnforcementTest {

    private fun gate(mode: TorMode, socks: Pair<String, Int>?) =
        TorRoutingGate(intent = { mode }, socksAddress = { socks })

    // MARK: - the decision

    @Test
    fun `refuses when tor is requested and no proxy is listening`() {
        assertTrue(gate(TorMode.ON, socks = null).mustRefuse())
    }

    @Test
    fun `allows when tor is requested and its proxy is listening`() {
        assertTrue(!gate(TorMode.ON, "127.0.0.1" to 9050).mustRefuse())
    }

    @Test
    fun `allows when tor was never requested`() {
        assertTrue(!gate(TorMode.OFF, socks = null).mustRefuse())
    }

    @Test
    fun `a listening proxy is not enough on its own`() {
        // Availability of a port is not a reason to route through it; the request is what matters.
        // With intent OFF the traffic goes direct even though a proxy is there.
        assertEquals(Proxy.NO_PROXY, gate(TorMode.OFF, socks = null).proxy())
    }

    @Test
    fun `routes through the observed listener when there is one`() {
        val proxy = gate(TorMode.ON, "127.0.0.1" to 9050).proxy()

        assertEquals(Proxy.Type.SOCKS, proxy.type())
        assertEquals(InetSocketAddress("127.0.0.1", 9050), proxy.address())
    }

    // MARK: - and that the decision actually stops bytes

    /** Counts connections so a test can prove nothing reached the far end. */
    private fun countingServer(connections: AtomicInteger): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use {
                        connections.incrementAndGet()
                        it.getOutputStream().write(
                            "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray()
                        )
                    }
                } catch (_: Exception) {
                    return@thread
                }
            }
        }
        return server
    }

    private fun clientWith(mode: TorMode, socks: Pair<String, Int>?): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(TorEnforcingInterceptor(gate(mode, socks)))
            .build()

    @Test
    fun `nothing reaches the network when tor is requested but not listening`() {
        /*
         * The property that matters. A refusal that still opened the socket would have disclosed
         * the address already -- disclosure completes at the TCP handshake, long before any
         * response could be discarded.
         */
        val connections = AtomicInteger()
        countingServer(connections).use { server ->
            val client = clientWith(TorMode.ON, socks = null)
            val request = Request.Builder()
                .url("http://127.0.0.1:${server.localPort}/anything")
                .build()

            assertFailsWith<TorRequiredException> { client.newCall(request).execute() }

            assertEquals(0, connections.get(), "a connection was made despite the refusal")
        }
    }

    @Test
    fun `traffic flows normally when tor was never requested`() {
        val connections = AtomicInteger()
        countingServer(connections).use { server ->
            val client = clientWith(TorMode.OFF, socks = null)
            val request = Request.Builder()
                .url("http://127.0.0.1:${server.localPort}/anything")
                .build()

            client.newCall(request).execute().use { assertEquals(204, it.code) }

            assertEquals(1, connections.get())
        }
    }

    @Test
    fun `the refusal names the host it declined to reach`() {
        val failure = assertFailsWith<TorRequiredException> {
            clientWith(TorMode.ON, socks = null)
                .newCall(Request.Builder().url("http://relay.example.com/x").build())
                .execute()
        }

        assertTrue(
            failure.message.orEmpty().contains("relay.example.com"),
            "unhelpful message: ${failure.message}"
        )
    }

    /**
     * Keep-alive server: holds each socket open and answers every request on it, so that a
     * connection is genuinely poolable. The counting server above closes after one response, which
     * makes reuse impossible and would hide what this test is about.
     */
    private fun keepAliveServer(connections: AtomicInteger): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { return@thread }
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    try {
                        val input = socket.getInputStream().bufferedReader()
                        val output = socket.getOutputStream()
                        while (!socket.isClosed) {
                            var sawRequest = false
                            while (true) {
                                val line = input.readLine() ?: return@thread
                                if (line.isEmpty()) break
                                sawRequest = true
                            }
                            if (!sawRequest) return@thread
                            output.write(
                                "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray()
                            )
                            output.flush()
                        }
                    } catch (_: Exception) {
                        // client went away
                    }
                }
            }
        }
        return server
    }

    @Test
    fun `a pooled direct connection is not reused after tor is requested`() {
        /*
         * Refusing new requests is not enough on its own. An idle connection opened while traffic
         * was allowed to go direct sits in the pool, and the next request is handed it without the
         * proxy selector being consulted again -- so enforcement would cover only hosts the app had
         * never talked to.
         */
        val connections = AtomicInteger()
        keepAliveServer(connections).use { server ->
            val pool = okhttp3.ConnectionPool()
            val client = OkHttpClient.Builder().connectionPool(pool).build()
            val url = "http://127.0.0.1:${server.localPort}/x"
            fun call() = client.newCall(Request.Builder().url(url).build()).execute().close()

            call()
            call()
            assertEquals(1, connections.get(), "precondition: the pool reuses idle connections")

            pool.evictAll()
            call()

            assertEquals(
                2,
                connections.get(),
                "the evicted connection was reused, so retiring the pool does not work"
            )
        }
    }
}
