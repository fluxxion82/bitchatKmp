package com.bitchat.bluetooth.handler

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.manager.PeerManager
import com.bitchat.bluetooth.manager.SecurityManager
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.MAX_PENDING_PAYLOADS_PER_PEER
import com.bitchat.bluetooth.protocol.MessageType
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageHandlerPendingBoundsTest {
    @Test
    fun threeEarlyMessagesBeforeTheLastHandshakeMessageAreAllDelivered() = runTest {
        Fixture().use { fixture ->
            val message1 = fixture.remoteNoise.initiateHandshake(fixture.localID, fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey())
            fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_HANDSHAKE, message1), fixture.remoteID)
            val message3 = (fixture.remoteNoise.processHandshake(
                fixture.localID, fixture.delegate.response!!, fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey()
            ) as NoiseEncryptionFacade.HandshakeResult.Established).response!!
            val encrypted = listOf("first", "second", "third").mapIndexed { index, content ->
                fixture.remoteNoise.encrypt(
                    fixture.localID,
                    NoisePayload(
                        NoisePayloadType.PRIVATE_MESSAGE,
                        PrivateMessagePacket("id-$index", content).encode()!!
                    ).encode()
                )!!
            }

            encrypted.forEach { fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_ENCRYPTED, it), fixture.remoteID) }
            assertEquals(3, fixture.handler.pendingEncryptedPayloadCount)
            assertEquals(emptyList(), fixture.delegate.privateMessages)
            assertEquals(emptyList(), fixture.delegate.unusable)

            fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_HANDSHAKE, message3), fixture.remoteID)
            assertEquals(listOf("first", "second", "third"), fixture.delegate.privateMessages)
            assertEquals(emptyList(), fixture.delegate.unusable)
            assertEquals(0, fixture.handler.pendingEncryptedPayloadCount)
        }
    }

    @Test
    fun refusedEarlyPayloadIsNotLeftAsADuplicateAfterTheHandshake() = runTest {
        Fixture(PendingEncryptedPayloads(maxBytesPerPeer = 1)).use { fixture ->
            suspend fun receive(packet: BitchatPacket) {
                assertTrue(fixture.security.validatePacket(packet, fixture.remoteID))
                fixture.handler.handlePacket(packet, fixture.remoteID)
            }

            val message1 = fixture.remoteNoise.initiateHandshake(
                fixture.localID, fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey()
            )
            receive(fixture.packet(MessageType.NOISE_HANDSHAKE, message1, 1u))
            val message3 = (fixture.remoteNoise.processHandshake(
                fixture.localID, fixture.delegate.response!!,
                fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey()
            ) as NoiseEncryptionFacade.HandshakeResult.Established).response!!
            val encrypted = fixture.packet(
                MessageType.NOISE_ENCRYPTED,
                fixture.remoteNoise.encrypt(
                    fixture.localID,
                    NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, PrivateMessagePacket("id", "early").encode()!!).encode()
                )!!,
                2u
            )

            receive(encrypted)
            assertEquals(0, fixture.handler.pendingEncryptedPayloadCount)
            receive(fixture.packet(MessageType.NOISE_HANDSHAKE, message3, 3u))
            receive(encrypted)

            assertEquals(listOf("early"), fixture.delegate.privateMessages)
        }
    }

    @Test
    fun anEarlyMessageUnderARenegotiatedSessionWaitsToo() = runTest {
        Fixture().use { fixture ->
            // A first session, completed on both sides.
            val first1 = fixture.remoteNoise.initiateHandshake(fixture.localID, fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey())
            fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_HANDSHAKE, first1), fixture.remoteID)
            val first3 = (fixture.remoteNoise.processHandshake(
                fixture.localID, fixture.delegate.response!!, fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey()
            ) as NoiseEncryptionFacade.HandshakeResult.Established).response!!
            fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_HANDSHAKE, first3), fixture.remoteID)

            // The peer restarts and handshakes again. Here that handshake runs beside the session
            // still held, which is exactly the candidate isHandshaking() does not report.
            fixture.remoteNoise.removeSession(fixture.localID)
            val second1 = fixture.remoteNoise.initiateHandshake(fixture.localID, fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey())
            fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_HANDSHAKE, second1), fixture.remoteID)
            val second3 = (fixture.remoteNoise.processHandshake(
                fixture.localID, fixture.delegate.response!!, fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey()
            ) as NoiseEncryptionFacade.HandshakeResult.Established).response!!
            val encrypted = fixture.remoteNoise.encrypt(fixture.localID, NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, PrivateMessagePacket("id", "after restart").encode()!!).encode())!!

            fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_ENCRYPTED, encrypted), fixture.remoteID)
            assertEquals(1, fixture.handler.pendingEncryptedPayloadCount)
            assertEquals(emptyList(), fixture.delegate.privateMessages)

            fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_HANDSHAKE, second3), fixture.remoteID)
            assertEquals(listOf("after restart"), fixture.delegate.privateMessages)
            assertEquals(0, fixture.handler.pendingEncryptedPayloadCount)
        }
    }

    @Test
    fun payloadsWithoutCandidatesAreNotRetainedAndStillCountForRecovery() = runTest {
        Fixture().use { fixture ->
            fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_ENCRYPTED, ByteArray(48)), "invented")
            repeat(2) { fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_ENCRYPTED, ByteArray(48)), "invented") }
            assertEquals(0, fixture.handler.pendingEncryptedPayloadCount)
            assertEquals(listOf("invented"), fixture.delegate.unusable)
            repeat(1_000) { fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_ENCRYPTED, ByteArray(48)), "other-$it") }
            assertEquals(0, fixture.handler.pendingEncryptedPayloadCount)
        }
    }

    @Test
    fun anEarlyPayloadTakesAPlaceOnTheLinkThatDeliveredIt() = runTest {
        // One place per link. Two peers, each with a handshake open, send an early payload.
        suspend fun keptWhenSecondPeerUses(secondLink: String): Int {
            Fixture(PendingEncryptedPayloads(maxPeersPerLink = 1)).use { fixture ->
                val others = listOf("3a", "4b").map { CryptoSigningFacade(it.repeat(32)) }
                others.forEachIndexed { index, crypto ->
                    val id = crypto.getIdentityFingerprint()
                    val opening = NoiseEncryptionFacade(id).initiateHandshake(
                        fixture.localID, crypto.getNoisePrivateKey(), crypto.getNoisePublicKey()
                    )
                    fixture.handler.handlePacket(
                        fixture.packet(MessageType.NOISE_HANDSHAKE, opening, (10 + index).toULong()), id, link = "open-$index"
                    )
                    fixture.handler.handlePacket(
                        fixture.packet(MessageType.NOISE_ENCRYPTED, ByteArray(48) { 1 }, (20 + index).toULong()),
                        id,
                        link = if (index == 0) "first" else secondLink
                    )
                }
                return fixture.handler.pendingEncryptedPayloadCount
            }
        }

        assertEquals(1, keptWhenSecondPeerUses("first"))
        assertEquals(2, keptWhenSecondPeerUses("second"))
    }

    @Test
    fun aKeptPayloadThatStaysUnreadableIsChargedToTheLinkItArrivedOn() = runTest {
        Fixture().use { fixture ->
            val message1 = fixture.remoteNoise.initiateHandshake(fixture.localID, fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey())
            fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_HANDSHAKE, message1, 1u), fixture.remoteID, link = "honest")
            val message3 = (fixture.remoteNoise.processHandshake(
                fixture.localID, fixture.delegate.response!!, fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey()
            ) as NoiseEncryptionFacade.HandshakeResult.Established).response!!

            // Someone else, on another link, sends three unreadable packets under the peer's id.
            // They are kept, and tried when the handshake completes over the honest link.
            repeat(3) {
                fixture.handler.handlePacket(
                    fixture.packet(MessageType.NOISE_ENCRYPTED, ByteArray(48) { 5 }, (10 + it).toULong()), fixture.remoteID, link = "other"
                )
            }
            assertEquals(3, fixture.handler.pendingEncryptedPayloadCount)
            fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_HANDSHAKE, message3, 2u), fixture.remoteID, link = "honest")

            // Three failures still ask for a recovery, as they always have. What it costs is taken
            // from the link the junk came in on, not from the one that carried the handshake.
            assertEquals(listOf(fixture.remoteID), fixture.delegate.unusable)
            assertEquals(1, fixture.handler.sessionFailureTracker.recoveriesChargedTo("other"))
            assertEquals(0, fixture.handler.sessionFailureTracker.recoveriesChargedTo("honest"))
        }
    }

    @Test
    fun unreadablePayloadRecoveryIsLimitedByTheLinkThatDeliveredIt() = runTest {
        Fixture().use { fixture ->
            repeat(9) { index ->
                fixture.handler.handlePacket(
                    fixture.packet(MessageType.NOISE_ENCRYPTED, ByteArray(48), index.toULong()),
                    "invented-$index",
                    link = "first"
                )
            }
            assertEquals((0 until 8).map { "invented-$it" }, fixture.delegate.unusable)

            fixture.handler.handlePacket(
                fixture.packet(MessageType.NOISE_ENCRYPTED, ByteArray(48), 9u),
                "other-link",
                link = "second"
            )
            assertEquals((0 until 8).map { "invented-$it" } + "other-link", fixture.delegate.unusable)
        }
    }

    @Test
    fun noMoreThanThePerPeerLimitIsKeptDuringAHandshake() = runTest {
        Fixture().use { fixture ->
            val message1 = fixture.remoteNoise.initiateHandshake(fixture.localID, fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey())
            fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_HANDSHAKE, message1), fixture.remoteID)
            repeat(MAX_PENDING_PAYLOADS_PER_PEER + 3) {
                fixture.handler.handlePacket(fixture.packet(MessageType.NOISE_ENCRYPTED, ByteArray(48) { it.toByte() }), fixture.remoteID)
            }
            assertEquals(MAX_PENDING_PAYLOADS_PER_PEER, fixture.handler.pendingEncryptedPayloadCount)
        }
    }

    private class Fixture(pendingEncryptedPayloads: PendingEncryptedPayloads = PendingEncryptedPayloads()) : AutoCloseable {
        val localCrypto = CryptoSigningFacade("2".repeat(64))
        val remoteCrypto = CryptoSigningFacade("1".repeat(64))
        val localID = localCrypto.getIdentityFingerprint()
        val remoteID = remoteCrypto.getIdentityFingerprint()
        val localNoise = NoiseEncryptionFacade(localID)
        val remoteNoise = NoiseEncryptionFacade(remoteID)
        val security = SecurityManager(localNoise, localCrypto, localID)
        val handler = MessageHandler(localID, security, PeerManager(), localCrypto, pendingEncryptedPayloads = pendingEncryptedPayloads)
        val delegate = Delegate()

        init { handler.delegate = delegate }
        fun packet(type: MessageType, payload: ByteArray, timestamp: ULong = 1u) = BitchatPacket(
            type = type.value, senderID = byteArrayOf(1), recipientID = localID.hexToBytes(),
            timestamp = timestamp, payload = payload, ttl = 1u
        )
        override fun close() = security.shutdown()
    }

    private class Delegate : MessageHandlerDelegate {
        var response: ByteArray? = null
        val privateMessages = mutableListOf<String>()
        val unusable = mutableListOf<String>()
        override fun onHandshakeResponse(peerID: String, responsePacket: ByteArray) { response = responsePacket }
        override fun onAuthenticatedPrivateMessage(peerID: String, messageId: String, content: String) { privateMessages += content }
        override fun onSessionUnusable(peerID: String) { unusable += peerID }
        override fun onPeerAnnounced(peerID: String, nickname: String) = Unit
        override fun onMessageReceived(peerID: String, message: String) = Unit
        override fun onAuthenticatedPrivateFile(peerID: String, file: BitchatFilePacket) = Unit
        override fun onAuthenticatedDelivered(peerID: String, messageId: String) = Unit
        override fun onAuthenticatedRead(peerID: String, messageId: String) = Unit
        override fun onHandshakeReceived(peerID: String) = Unit
        override fun onSessionEstablished(peerID: String) = Unit
        override fun onSessionNotShared(peerID: String) = Unit
        override fun onPeerLeft(peerID: String) = Unit
        override fun onFragmentReceived(peerID: String) = Unit
        override fun onPublicFileReceived(peerID: String, filePacket: BitchatFilePacket) = Unit
    }
}

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
