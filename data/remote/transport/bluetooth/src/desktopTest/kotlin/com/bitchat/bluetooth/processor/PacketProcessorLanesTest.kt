package com.bitchat.bluetooth.processor

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.handler.MessageHandler
import com.bitchat.bluetooth.handler.MessageHandlerDelegate
import com.bitchat.bluetooth.handler.PrivateReceipt
import com.bitchat.bluetooth.manager.PeerManager
import com.bitchat.bluetooth.manager.SecurityManager
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.MESH_LANE_CAPACITY
import com.bitchat.bluetooth.protocol.MESH_PACKET_LANES
import com.bitchat.bluetooth.protocol.MessageType
import com.bitchat.bluetooth.protocol.SpecialRecipients
import com.bitchat.domain.chat.model.BitchatFilePacket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketProcessorLanesTest {
    @Test
    fun onePeersPacketsAreHandledInArrivalOrder() {
        fixture(laneCount = 2, laneCapacity = 400).use { fixture ->
            val peer = "sender"
            repeat(300) { number -> assertTrue(fixture.processor.processPacket(packet(number, "m$number"), peer)) }
            awaitUntil { fixture.delegate.arrived.count == 0L }
            assertEquals((0 until 300).map { "m$it" }, fixture.delegate.messages.map { it.second })
        }
    }

    @Test
    fun aFullLaneDropsTheNewestPacketAndOtherLanesKeepWorking() {
        fixture(laneCount = 2, laneCapacity = 3).use { fixture ->
            val a = "a"
            val b = (1..100).map { "peer-$it" }.first { fixture.processor.laneOf(it) != fixture.processor.laneOf(a) }
            fixture.delegate.holdFirst = CountDownLatch(1)
            assertTrue(fixture.processor.processPacket(packet(0, "held"), a))
            awaitUntil { fixture.delegate.firstEntered.count == 0L }
            repeat(3) { assertTrue(fixture.processor.processPacket(packet(it + 1, "a$it"), a)) }
            repeat(5) { assertFalse(fixture.processor.processPacket(packet(it + 10, "drop$it"), a)) }
            assertTrue(fixture.processor.processPacket(packet(100, "other"), b))
            awaitUntil { fixture.delegate.otherArrived.count == 0L }
            fixture.delegate.holdFirst!!.countDown()
            awaitUntil { fixture.delegate.arrived.count == 0L }
            assertEquals(listOf("held", "a0", "a1", "a2"), fixture.delegate.messages.filter { it.first == a }.map { it.second })
        }
    }

    @Test
    fun byteBudgetBoundsWhatIsQueuedAndIsGivenBack() {
        fixture(laneCount = 1, laneCapacity = 5, maxQueuedBytesPerLane = 250).use { fixture ->
            fixture.delegate.holdFirst = CountDownLatch(1)
            assertTrue(fixture.processor.processPacket(packet(0, "x".repeat(50)), "a"))
            awaitUntil { fixture.delegate.firstEntered.count == 0L }
            assertTrue(fixture.processor.processPacket(packet(1, "y".repeat(50)), "a"))
            assertFalse(fixture.processor.processPacket(packet(2, "z".repeat(50)), "a"))
            fixture.delegate.holdFirst!!.countDown()
            awaitUntil { fixture.delegate.arrived.count == 0L }
            awaitUntil { fixture.processor.queuedBytes == 0 }
            assertTrue(fixture.processor.processPacket(packet(3, "new"), "a"))
        }
    }

    @Test
    fun oneLanesByteBudgetDoesNotRefuseAnotherLanesPacket() {
        fixture(laneCount = 2, laneCapacity = 2, maxQueuedBytesPerLane = 200).use { fixture ->
            val a = "a"
            val b = (1..100).map { "peer-$it" }.first { fixture.processor.laneOf(it) != fixture.processor.laneOf(a) }
            fixture.delegate.holdFirst = CountDownLatch(1)

            assertTrue(fixture.processor.processPacket(packet(0, "x".repeat(36)), a))
            awaitUntil { fixture.delegate.firstEntered.count == 0L }
            assertTrue(fixture.processor.processPacket(packet(1, "y".repeat(36)), a))
            assertFalse(fixture.processor.processPacket(packet(2, "z".repeat(36)), a))
            assertTrue(fixture.processor.processPacket(packet(3, "other".padEnd(36, 'o')), b))
            awaitUntil { fixture.delegate.messages.any { it.first == b } }

            fixture.delegate.holdFirst!!.countDown()
        }
    }

    @Test
    fun shuttingDownGivesBackWhatWasStillWaiting() {
        val fixture = fixture(laneCount = 1, laneCapacity = 5)
        fixture.delegate.holdFirst = CountDownLatch(1)
        assertTrue(fixture.processor.processPacket(packet(0, "held"), "a"))
        awaitUntil { fixture.delegate.firstEntered.count == 0L }
        repeat(2) { assertTrue(fixture.processor.processPacket(packet(it + 1, "waiting$it"), "a")) }

        fixture.close()
        fixture.delegate.holdFirst!!.countDown()

        // The two that never reached the handler, and the one that was inside it.
        awaitUntil { fixture.processor.queuedBytes == 0 }
    }

    @Test
    fun aPacketWhoseHandlingThrowsDoesNotStopTheLane() {
        fixture(laneCount = 1, laneCapacity = 2).use { fixture ->
            fixture.delegate.throwFirst = true
            assertTrue(fixture.processor.processPacket(packet(0, "bad"), "a"))
            assertTrue(fixture.processor.processPacket(packet(1, "good"), "a"))
            awaitUntil { fixture.delegate.arrived.count == 0L }
            assertEquals(listOf("good"), fixture.delegate.messages.map { it.second })
            awaitUntil { fixture.processor.queuedBytes == 0 }
        }
    }

    @Test
    fun tenThousandClaimedSendersNeedNoPerSenderState() {
        // Before lanes this allocated 10,000 unlimited channels and 10,000 consumer coroutines,
        // none of them ever released. With the lanes a real processor has, every packet is either
        // handled or counted as dropped, and nothing is left reserved.
        fixture(laneCount = MESH_PACKET_LANES, laneCapacity = MESH_LANE_CAPACITY).use { fixture ->
            val accepted = (0 until 10_000).count { fixture.processor.processPacket(packet(it, "m$it"), "claimed-$it") }
            awaitUntil { fixture.delegate.messages.size == accepted }
            assertEquals(10_000L, accepted + fixture.processor.droppedPacketCount)
            awaitUntil { fixture.processor.queuedBytes == 0 }
        }
    }

    @Test
    fun handshakeLinkReachesTheFacadeAdmissionLimit() {
        handshakeFixture().use { fixture ->
            val first = fixture.openingPacket("1".repeat(64), 1uL)
            val second = fixture.openingPacket("2".repeat(64), 2uL)
            fixture.processor.processPacket(first.packet, first.peerID, "same-link")
            fixture.processor.processPacket(second.packet, second.peerID, "same-link")

            awaitUntil { fixture.delegate.handshakeResponses.size == 1 }
            // Packet processing is asynchronous; give a refused opening enough time to reach the
            // handler before deciding it did not produce a second response.
            Thread.sleep(400)
            assertEquals(1, fixture.delegate.handshakeResponses.size)
        }

        handshakeFixture().use { fixture ->
            val first = fixture.openingPacket("3".repeat(64), 1uL)
            val second = fixture.openingPacket("4".repeat(64), 2uL)
            fixture.processor.processPacket(first.packet, first.peerID, "first-link")
            fixture.processor.processPacket(second.packet, second.peerID, "second-link")

            awaitUntil { fixture.delegate.handshakeResponses.size == 2 }
        }
    }

    private fun fixture(laneCount: Int, laneCapacity: Int, maxQueuedBytesPerLane: Int = 100_000): Fixture =
        Fixture(laneCount, laneCapacity, maxQueuedBytesPerLane)

    private fun handshakeFixture(): HandshakeFixture = HandshakeFixture()

    private fun packet(number: Int, content: String) = BitchatPacket(
        type = MessageType.MESSAGE.value,
        senderID = byteArrayOf(1), recipientID = SpecialRecipients.BROADCAST,
        timestamp = number.toULong(), payload = content.encodeToByteArray(), ttl = 1u
    )

    private class Fixture(lanes: Int, capacity: Int, bytes: Int) : AutoCloseable {
        val delegate = Delegate()
        private val crypto = CryptoSigningFacade("2".repeat(64))
        private val security = SecurityManager(NoiseEncryptionFacade(crypto.getIdentityFingerprint()), crypto, crypto.getIdentityFingerprint())
        private val handler = MessageHandler(crypto.getIdentityFingerprint(), security, PeerManager(), crypto).also { it.delegate = delegate }
        val processor = PacketProcessor(crypto.getIdentityFingerprint(), security, handler, lanes, capacity, bytes)
        override fun close() { processor.shutdown(); security.shutdown() }
    }

    private class HandshakeFixture : AutoCloseable {
        private val localCrypto = CryptoSigningFacade("a".repeat(64))
        private val localID = localCrypto.getIdentityFingerprint()
        private val facade = NoiseEncryptionFacade(localID, maxInboundHandshakesPerLink = 1)
        private val security = SecurityManager(facade, localCrypto, localID)
        val delegate = Delegate()
        private val handler = MessageHandler(localID, security, PeerManager(), localCrypto).also { it.delegate = delegate }
        val processor = PacketProcessor(localID, security, handler, laneCount = 1, laneCapacity = 4)

        fun openingPacket(seed: String, timestamp: ULong): Opening {
            val remoteCrypto = CryptoSigningFacade(seed)
            val remoteID = remoteCrypto.getIdentityFingerprint()
            val remoteFacade = NoiseEncryptionFacade(remoteID)
            return Opening(
                remoteID,
                BitchatPacket(
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = BitchatPacket.hexStringToByteArray(remoteID),
                    recipientID = BitchatPacket.hexStringToByteArray(localID),
                    timestamp = timestamp,
                    payload = remoteFacade.initiateHandshake(
                        localID,
                        remoteCrypto.getNoisePrivateKey(),
                        remoteCrypto.getNoisePublicKey()
                    ),
                    ttl = 1u
                )
            )
        }

        override fun close() { processor.shutdown(); security.shutdown() }
    }

    private data class Opening(val peerID: String, val packet: BitchatPacket)

    private class Delegate : MessageHandlerDelegate {
        val messages = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val firstEntered = CountDownLatch(1)
        val otherArrived = CountDownLatch(1)
        val arrived = CountDownLatch(1)
        val handshakeResponses = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        /** When set, the first packet to arrive waits inside the handler until it is released. */
        var holdFirst: CountDownLatch? = null
        private val held = java.util.concurrent.atomic.AtomicBoolean(false)
        var throwFirst = false
        override fun onMessageReceived(peerID: String, message: String) {
            if (throwFirst) { throwFirst = false; throw IllegalStateException("test") }
            holdFirst?.let { hold ->
                if (held.compareAndSet(false, true)) { firstEntered.countDown(); awaitUntil { hold.count == 0L } }
            }
            messages += peerID to message
            if (message == "other") otherArrived.countDown()
            if (message == "m299" || message == "a2" || message == "y".repeat(50) || message == "good") arrived.countDown()
        }
        override fun onPeerAnnounced(peerID: String, nickname: String) = Unit
        override fun onAuthenticatedPrivateMessage(peerID: String, messageId: String, content: String, receipt: PrivateReceipt) = Unit
        override fun onAuthenticatedDeliveredNumbers(peerID: String, numbers: List<Long>, sessionToken: Long) = Unit
        override fun onAuthenticatedPrivateFile(peerID: String, file: BitchatFilePacket) = Unit
        override fun onAuthenticatedDelivered(peerID: String, messageId: String) = Unit
        override fun onAuthenticatedRead(peerID: String, messageId: String) = Unit
        override fun onHandshakeReceived(peerID: String) = Unit
        override fun onHandshakeResponse(peerID: String, responsePacket: ByteArray, link: String, final: Boolean) { handshakeResponses += responsePacket }
        override fun onSessionEstablished(peerID: String) = Unit
        override fun onSessionUnusable(peerID: String) = Unit
        override fun onSessionNotShared(peerID: String) = Unit
        override fun onPeerLeft(peerID: String) = Unit
        override fun onFragmentReceived(peerID: String) = Unit
        override fun onPublicFileReceived(peerID: String, filePacket: BitchatFilePacket) = Unit
    }
}

private fun awaitUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition()) {
        assertTrue(System.nanoTime() < deadline, "condition not reached within 10 s")
        Thread.sleep(5)
    }
}
