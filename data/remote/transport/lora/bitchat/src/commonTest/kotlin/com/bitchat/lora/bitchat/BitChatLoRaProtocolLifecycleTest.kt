package com.bitchat.lora.bitchat

import com.bitchat.lora.bitchat.protocol.LoRaAssembler
import com.bitchat.lora.bitchat.protocol.LoRaFragmenter
import com.bitchat.lora.bitchat.protocol.LoRaFrame
import com.bitchat.lora.bitchat.protocol.MeshPacketFrame
import com.bitchat.lora.bitchat.protocol.HeartbeatPayload
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.LoRaEvent
import com.bitchat.transport.RadioPurpose
import com.bitchat.transport.RadioSendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.bitchat.lora.radio.airtimeMs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BitChatLoRaProtocolLifecycleTest {
    private class Radio(private val clock: () -> Long = { 0 }) : BitChatRadio {
        override val events = MutableSharedFlow<LoRaEvent>(extraBufferCapacity = 8)
        override var isReady = false
        var configureSucceeds = true
        var starts = 0
        var shutdowns = 0
        val sent = mutableListOf<ByteArray>()
        val sentAt = mutableListOf<Long>()
        var stopGate: CompletableDeferred<Unit>? = null
        override fun configure(config: LoRaConfig): Boolean {
            isReady = configureSucceeds
            return configureSucceeds
        }
        override fun startReceiving() { starts++ }
        override fun send(data: ByteArray): Boolean { sent += data; sentAt += clock(); return isReady }
        override suspend fun shutdown() { stopGate?.await(); isReady = false; shutdowns++ }
    }

    @Test
    fun meshRadioLinkHearsPeerIdsWithoutCaseAndFramesAUsersOpening() = protocolTest {
        val radio = Radio { testScheduler.currentTime }
        val protocol = protocol(radio, testScheduler)
        assertTrue(protocol.start(LoRaConfig.US_915))
        val peer = "AbCdEf0123456789"
        radio.events.emit(LoRaEvent.PacketReceived(
            LoRaFrame(9u, 0u, 1u, LoRaFrame.FLAG_HEARTBEAT, HeartbeatPayload(peer, "Remote").toBytes()).toBytes(), -50, 5f
        ))
        runCurrent()

        val link = protocol.meshPacketLink
        assertTrue(link.hears(peer.lowercase()))
        assertFalse(link.hears("0000000000000000"))
        val packet = byteArrayOf(1, 2, 3)
        val sending = async { link.send(packet, peer, RadioPurpose.HandshakeOpening(byUser = true)) }
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(RadioSendResult.SENT, sending.await())
        assertContentEquals(packet, radio.sent.mapNotNull(MeshPacketFrame::decode).single())
        assertEquals(1, (link as BitChatMeshRadioLink).keptHolds())

        // Stopped: the radio fails at once (nobody is to wait for time on air that will not come),
        // and nothing is kept for a handshake whose hold went with the transmitter.
        protocol.stop()
        assertEquals(RadioSendResult.FAILED, link.send(packet, peer, RadioPurpose.PrivateMessage))
        assertEquals(0, link.keptHolds())
    }

    @Test
    fun restartRestoresOneHeartbeatAndMessageCollector() = protocolTest {
        val radio = Radio { testScheduler.currentTime }
        val protocol = protocol(radio, testScheduler)
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
    fun failedInitializationCanBeRetriedAndStopAwaitsRadioCleanup() = protocolTest {
        val radio = Radio().apply { configureSucceeds = false }
        val protocol = protocol(radio, testScheduler)
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
    fun packetFramesReachOnlyTheMeshPacketFlowWhileFramesAndHeartbeatsKeepTheirPaths() = protocolTest {
        val radio = Radio()
        val protocol = protocol(radio, testScheduler)
        val messages = mutableListOf<ByteArray>()
        val meshPackets = mutableListOf<ByteArray>()
        backgroundScope.launch { protocol.incomingMessages.collect { messages += it } }
        backgroundScope.launch { protocol.incomingMeshPackets.collect { meshPackets += it } }
        assertTrue(protocol.start(LoRaConfig.US_915))
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

    @Test fun heartbeatsWaitForTheirOwnAirtimeThenSixtySeconds() = protocolTest {
        val radio = Radio { testScheduler.currentTime }
        val protocol = protocol(radio, testScheduler)
        protocol.deviceId = "0123456789abcdef"
        protocol.nickname = "€".repeat(50)
        assertTrue(protocol.start(LoRaConfig.US_915))
        advanceTimeBy(10 * 60_000 + 20_000)
        runCurrent()
        // Off the air after its airtime and the millisecond the clock may be behind by.
        val onAir = LoRaConfig.US_915.airtimeMs(radio.sent.first().size) + 1
        assertEquals((0 until 10).map { 1_000L + it * (60_000L + onAir) }, radio.sentAt.take(10))
        assertTrue(radio.sent.all { it.size <= 38 })
        protocol.stop()
    }

    @Test fun aHeartbeatCarriesTheStartOfALongNicknameInWholeCharactersAndItsOwnMessageId() = protocolTest {
        val radio = Radio { testScheduler.currentTime }
        val protocol = protocol(radio, testScheduler)
        protocol.deviceId = "0123456789abcdef"
        protocol.nickname = "€".repeat(50)
        assertTrue(protocol.start(LoRaConfig.US_915))
        advanceTimeBy(2 * 60_000 + 20_000)
        runCurrent()
        val frames = radio.sent.map { assertNotNull(LoRaFrame.fromBytes(it)) }
        assertTrue(frames.size >= 2)
        for (frame in frames) {
            assertEquals(LoRaFrame.FLAG_HEARTBEAT, frame.flags)
            assertEquals("€".repeat(8), assertNotNull(HeartbeatPayload.fromBytes(frame.payload)).nickname)
        }
        // Numbered by the fragmenter like every other message, as before: never one fixed id.
        assertEquals(frames.size, frames.map { it.messageId }.distinct().size)
        protocol.stop()
    }

    @Test fun aHeartbeatRefusedAfterARestartIsAskedForAgainUntilTheMinuteIsOver() = protocolTest {
        val radio = Radio { testScheduler.currentTime }
        val protocol = protocol(radio, testScheduler)
        protocol.deviceId = "0123456789abcdef"
        assertTrue(protocol.start(LoRaConfig.US_915))
        advanceTimeBy(5_500)
        assertEquals(listOf(1_000L), radio.sentAt)
        val leftTheAirAt = 1_000L + LoRaConfig.US_915.airtimeMs(radio.sent.single().size) + 1
        protocol.stop()
        assertTrue(protocol.start(LoRaConfig.US_915))
        advanceTimeBy(70_000)
        runCurrent()
        // Asked for at 6.5 s and every 5 s after: sent the first time its ledger is free again, 60 s
        // after the one before the restart left the air, and not once before.
        val retries = generateSequence(6_500L) { it + BitChatLoRaProtocol.HEARTBEAT_RETRY_MS }
        assertEquals(listOf(1_000L, 61_500L), radio.sentAt)
        assertEquals(61_500L, retries.first { it >= leftTheAirAt + 60_000 })
        protocol.stop()
    }

    @Test fun fourFullPublicFramesAreRefusedAsOneBatchWhileThreeAreSent() = protocolTest {
        val radio = Radio()
        val protocol = protocol(radio, testScheduler)
        assertTrue(protocol.start(LoRaConfig.US_915))
        assertFalse(protocol.send(ByteArray(232 * 4)))
        assertTrue(radio.sent.isEmpty())
        assertTrue(protocol.send(ByteArray(232 * 3)))
        assertEquals(3, radio.sent.size)
        protocol.stop()
    }

    private val built = mutableListOf<BitChatLoRaProtocol>()

    /**
     * Runs [test] and then stops every protocol it built, whatever happened: left running after a
     * failed assertion, the heartbeat loop keeps the test's scheduler busy for ever and the run hangs
     * instead of failing.
     */
    private fun protocolTest(test: suspend TestScope.() -> Unit) = runTest {
        try {
            test()
        } finally {
            built.forEach { it.stop() }
        }
    }

    private fun protocol(radio: Radio, scheduler: TestCoroutineScheduler) =
        BitChatLoRaProtocol(
            radio, LoRaFragmenter(), LoRaAssembler(),
            dispatcher = StandardTestDispatcher(scheduler),
            clockMillis = { scheduler.currentTime },
            jitter = { it.first }
        ).also { built += it }
}
