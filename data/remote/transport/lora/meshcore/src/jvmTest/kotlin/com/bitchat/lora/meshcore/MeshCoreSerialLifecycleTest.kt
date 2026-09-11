package com.bitchat.lora.meshcore

import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshCoreSerialLifecycleTest {
    @Test
    fun reconnectWaitsForPreviousReaderAndKeepsNewSocketUsable() = runBlocking {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            val serial = MeshCoreSerial().apply {
                host = server.inetAddress.hostAddress
                port = server.localPort
            }
            try {
                val disconnected = CompletableDeferred<Unit>()
                serial.onDisconnect = { disconnected.complete(Unit) }
                assertTrue(serial.open())
                server.accept().close()
                withTimeout(3000) { disconnected.await() }
                assertTrue(serial.reconnect())
                server.accept().use { peer ->
                    val incoming = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(3000) { serial.incoming.first() }
                    }
                    peer.getOutputStream().write(byteArrayOf('>'.code.toByte(), 3, 0, 5, 6, 7))
                    peer.getOutputStream().flush()
                    assertContentEquals(byteArrayOf(5, 6, 7), incoming.await())
                    assertTrue(serial.isConnected)
                    serial.shutdown()
                    assertFalse(serial.isConnected)
                    serial.shutdown()
                }
            } finally {
                serial.shutdown()
            }
        }
    }

    @Test
    fun explicitShutdownThenStartCreatesAnotherReader() = runBlocking {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            val serial = MeshCoreSerial().apply {
                host = server.inetAddress.hostAddress
                port = server.localPort
            }
            repeat(2) { index ->
                assertTrue(serial.open())
                server.accept().use { peer ->
                    val incoming = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(3000) { serial.incoming.first() }
                    }
                    peer.getOutputStream().write(byteArrayOf('>'.code.toByte(), 1, 0, index.toByte()))
                    peer.getOutputStream().flush()
                    assertContentEquals(byteArrayOf(index.toByte()), incoming.await())
                    serial.shutdown()
                    assertFalse(serial.isConnected)
                }
            }
        }
    }
}
