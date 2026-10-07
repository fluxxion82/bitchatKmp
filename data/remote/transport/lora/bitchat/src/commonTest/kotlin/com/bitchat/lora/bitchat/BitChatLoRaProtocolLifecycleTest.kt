package com.bitchat.lora.bitchat

import com.bitchat.lora.bitchat.protocol.LoRaAssembler
import com.bitchat.lora.bitchat.protocol.LoRaFragmenter
import com.bitchat.lora.bitchat.protocol.LoRaFrame
import com.bitchat.lora.bitchat.protocol.MeshPacketFrame
import com.bitchat.lora.bitchat.protocol.HeartbeatPayload
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.LoRaEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BitChatLoRaProtocolLifecycleTest {
    private class Radio : BitChatRadio {
        override val events = MutableSharedFlow<LoRaEvent>(extraBufferCapacity = 8)
        override var isReady = false
        var configureSucceeds = true
        var starts = 0
        var shutdowns = 0
        val sent = mutableListOf<ByteArray>()
        var stopGate: CompletableDeferred<Unit>? = null
        override fun configure(config: LoRaConfig): Boolean {
            isReady = configureSucceeds
            return configureSucceeds
        }
        override fun startReceiving() { starts++ }
        override fun send(data: ByteArray): Boolean { sent += data; return isReady }
        override suspend fun shutdown() { stopGate?.await(); isReady = false; shutdowns++ }
    }

    @Test
    fun restartRestoresOneHeartbeatAndMessageCollector() = runTest {
        val radio = Radio()
        val protocol = BitChatLoRaProtocol(radio, LoRaFragmenter(), LoRaAssembler(),
            dispatcher = StandardTestDispatcher(testScheduler))
        protocol.deviceId = "0123456789abcdef"
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { protocol.incomingMessages.collect { received += it } }
        assertTrue(protocol.start(LoRaConfig.US_915))
        runCurrent()
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(1, radio.sent.size)
        protocol.stop()
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, radio.sent.size)
        assertTrue(protocol.start(LoRaConfig.US_915))
        runCurrent()
        val payload = byteArrayOf(1, 2, 3)
        val frame = LoRaFrame(123u, 0u, 1u, LoRaFrame.FLAG_NONE, payload)
        radio.events.emit(LoRaEvent.PacketReceived(frame.toBytes(), -50, 5f))
        runCurrent()
        assertEquals(1, received.size)
        assertContentEquals(payload, received.single())
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(2, radio.sent.size)
        assertEquals(2, radio.starts)
        protocol.stop()
    }

    @Test
    fun failedInitializationCanBeRetriedAndStopAwaitsRadioCleanup() = runTest {
        val radio = Radio().apply { configureSucceeds = false }
        val protocol = BitChatLoRaProtocol(radio, LoRaFragmenter(), LoRaAssembler(),
            dispatcher = StandardTestDispatcher(testScheduler))
        assertFalse(protocol.start(LoRaConfig.US_915))
        assertEquals(0, radio.starts)
        radio.configureSucceeds = true
        assertTrue(protocol.start(LoRaConfig.US_915))
        radio.stopGate = CompletableDeferred()
        val stopping = async { protocol.stop() }
        runCurrent()
        assertFalse(stopping.isCompleted)
        radio.stopGate!!.complete(Unit)
        stopping.await()
        assertFalse(protocol.isReady)
        assertTrue(protocol.peers.value.isEmpty())
        protocol.stop()
        assertFalse(protocol.isReady)
    }

    @Test
    fun packetFramesReachOnlyTheMeshPacketFlowWhileFramesAndHeartbeatsKeepTheirPaths() = runTest {
        val radio = Radio()
        val protocol = BitChatLoRaProtocol(radio, LoRaFragmenter(), LoRaAssembler(),
            dispatcher = StandardTestDispatcher(testScheduler))
        val messages = mutableListOf<ByteArray>()
        val meshPackets = mutableListOf<ByteArray>()
        backgroundScope.launch { protocol.incomingMessages.collect { messages += it } }
        backgroundScope.launch { protocol.incomingMeshPackets.collect { meshPackets += it } }
        assertTrue(protocol.start(LoRaConfig.US_915))
        // Stopped whatever happens: left running after a failed assertion, the heartbeat loop keeps the
        // test's scheduler busy for ever and the run hangs instead of failing.
        try {
            runCurrent()

            val meshPacket = byteArrayOf(9, 8, 7)
            radio.events.emit(LoRaEvent.PacketReceived(requireNotNull(MeshPacketFrame.encode(7u, meshPacket)), -50, 5f))
            runCurrent()
            assertContentEquals(meshPacket, meshPackets.single())
            assertTrue(messages.isEmpty())

            val message = byteArrayOf(1, 2, 3)
            radio.events.emit(LoRaEvent.PacketReceived(LoRaFrame(8u, 0u, 1u, LoRaFrame.FLAG_NONE, message).toBytes(), -50, 5f))
            runCurrent()
            assertContentEquals(message, messages.single())
            assertEquals(1, meshPackets.size)

            val heartbeat = HeartbeatPayload("fedcba9876543210", "Remote")
            radio.events.emit(LoRaEvent.PacketReceived(
                LoRaFrame(9u, 0u, 1u, LoRaFrame.FLAG_HEARTBEAT, heartbeat.toBytes()).toBytes(), -50, 5f
            ))
            runCurrent()
            assertEquals("fedcba9876543210", protocol.peers.value.single().deviceId)
            assertEquals(1, messages.size)
            assertEquals(1, meshPackets.size)
        } finally {
            protocol.stop()
        }
    }
}
