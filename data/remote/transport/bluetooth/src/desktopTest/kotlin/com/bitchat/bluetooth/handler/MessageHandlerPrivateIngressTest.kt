package com.bitchat.bluetooth.handler

import com.bitchat.api.dto.mapper.toWireFormat
import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.manager.PeerManager
import com.bitchat.bluetooth.manager.SecurityManager
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.MessageType
import com.bitchat.bluetooth.protocol.SpecialRecipients
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageHandlerPrivateIngressTest {

    @Test
    fun addressedPlaintextPacketsDoNotReachAnyPrivateIngressCallback() = runTest {
        val fixture = Fixture()
        try {
            fixture.handler.handlePacket(
                packet(MessageType.MESSAGE, fixture, "plaintext message".encodeToByteArray()),
                fixture.sender.peerID
            )
            // A well-formed file, so only the address check can be what stops it.
            fixture.handler.handlePacket(
                packet(MessageType.FILE_TRANSFER, fixture, file.toWireFormat()!!),
                fixture.sender.peerID
            )
            fixture.handler.handlePacket(
                packet(MessageType.FRAGMENT, fixture, "plaintext fragment".encodeToByteArray()),
                fixture.sender.peerID
            )

            assertTrue(fixture.delegate.authenticatedMessages.isEmpty())
            assertTrue(fixture.delegate.authenticatedFiles.isEmpty())
            assertTrue(fixture.delegate.deliveryAcks.isEmpty())
            assertTrue(fixture.delegate.readReceipts.isEmpty())
            assertTrue(fixture.delegate.publicMessages.isEmpty())
            assertTrue(fixture.delegate.publicFiles.isEmpty())
            assertTrue(fixture.delegate.fragments.isEmpty())
        } finally {
            fixture.securityManager.shutdown()
        }
    }

    @Test
    fun broadcastMessageStillReachesThePublicCallback() = runTest {
        val fixture = Fixture()
        try {
            fixture.handler.handlePacket(
                packet(
                    type = MessageType.MESSAGE,
                    fixture = fixture,
                    payload = "public message".encodeToByteArray(),
                    recipientID = SpecialRecipients.BROADCAST
                ),
                fixture.sender.peerID
            )

            assertEquals(listOf(fixture.sender.peerID to "public message"), fixture.delegate.publicMessages)
        } finally {
            fixture.securityManager.shutdown()
        }
    }

    @Test
    fun encryptedPrivateMessageFromAnEstablishedSessionReachesAuthenticatedCallbackOnce() = runTest {
        val fixture = Fixture()
        try {
            fixture.establishSession()
            val privatePayload = PrivateMessagePacket(
                messageID = "private-message-id",
                content = "authenticated message"
            ).encode()!!
            val encrypted = fixture.sender.noise.encrypt(
                fixture.receiver.peerID,
                NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, privatePayload).encode()
            )!!

            fixture.handler.handlePacket(
                packet(MessageType.NOISE_ENCRYPTED, fixture, encrypted),
                fixture.sender.peerID
            )

            assertEquals(
                listOf(AuthenticatedMessage(fixture.sender.peerID, "private-message-id", "authenticated message")),
                fixture.delegate.authenticatedMessages
            )
        } finally {
            fixture.securityManager.shutdown()
        }
    }

    @Test
    fun broadcastFileStillReachesThePublicFileCallback() = runTest {
        val fixture = Fixture()
        try {
            fixture.handler.handlePacket(
                packet(MessageType.FILE_TRANSFER, fixture, file.toWireFormat()!!, recipientID = SpecialRecipients.BROADCAST),
                fixture.sender.peerID
            )

            assertEquals(listOf(fixture.sender.peerID to file), fixture.delegate.publicFiles)
            assertTrue(fixture.delegate.authenticatedFiles.isEmpty())
        } finally {
            fixture.securityManager.shutdown()
        }
    }

    @Test
    fun encryptedFileFromAnEstablishedSessionReachesTheAuthenticatedFileCallback() = runTest {
        val fixture = Fixture()
        try {
            fixture.establishSession()
            val encrypted = fixture.sender.noise.encrypt(
                fixture.receiver.peerID,
                NoisePayload(NoisePayloadType.FILE_TRANSFER, file.toWireFormat()!!).encode()
            )!!

            fixture.handler.handlePacket(packet(MessageType.NOISE_ENCRYPTED, fixture, encrypted), fixture.sender.peerID)

            assertEquals(listOf(fixture.sender.peerID to file), fixture.delegate.authenticatedFiles)
            assertTrue(fixture.delegate.publicFiles.isEmpty())
        } finally {
            fixture.securityManager.shutdown()
        }
    }

    @Test
    fun receiptsFromAnEstablishedSessionReachTheirOwnCallbacks() = runTest {
        val fixture = Fixture()
        try {
            fixture.establishSession()
            for ((type, id) in listOf(NoisePayloadType.DELIVERED to "delivered-id", NoisePayloadType.READ_RECEIPT to "read-id")) {
                val encrypted = fixture.sender.noise.encrypt(
                    fixture.receiver.peerID,
                    NoisePayload(type, id.encodeToByteArray()).encode()
                )!!
                fixture.handler.handlePacket(packet(MessageType.NOISE_ENCRYPTED, fixture, encrypted), fixture.sender.peerID)
            }

            assertEquals(listOf(fixture.sender.peerID to "delivered-id"), fixture.delegate.deliveryAcks)
            assertEquals(listOf(fixture.sender.peerID to "read-id"), fixture.delegate.readReceipts)
            assertTrue(fixture.delegate.authenticatedMessages.isEmpty())
        } finally {
            fixture.securityManager.shutdown()
        }
    }

    @Test
    fun undecryptablePayloadClaimingToBeFromAPeerReachesNoCallback() = runTest {
        val fixture = Fixture()
        try {
            fixture.establishSession()
            // Not produced by the session: what anyone in range can send under the sender's id.
            fixture.handler.handlePacket(
                packet(MessageType.NOISE_ENCRYPTED, fixture, ByteArray(48) { 7 }),
                fixture.sender.peerID
            )

            assertTrue(fixture.delegate.authenticatedMessages.isEmpty())
            assertTrue(fixture.delegate.authenticatedFiles.isEmpty())
            assertTrue(fixture.delegate.deliveryAcks.isEmpty())
            assertTrue(fixture.delegate.readReceipts.isEmpty())
            assertTrue(fixture.delegate.publicMessages.isEmpty())
        } finally {
            fixture.securityManager.shutdown()
        }
    }

    private val file = BitchatFilePacket(
        fileName = "note.txt",
        fileSize = 5,
        mimeType = "text/plain",
        content = "hello".encodeToByteArray(),
    )

    private fun packet(
        type: MessageType,
        fixture: Fixture,
        payload: ByteArray,
        recipientID: ByteArray = fixture.receiver.peerID.hexToBytes()
    ) = BitchatPacket(
        type = type.value,
        senderID = fixture.sender.peerID.hexToBytes(),
        recipientID = recipientID,
        timestamp = 1u,
        payload = payload,
        ttl = 1u
    )

    private class Fixture {
        val sender = Party("1".repeat(64))
        val receiver = Party("2".repeat(64))
        val securityManager = SecurityManager(receiver.noise, receiver.crypto, receiver.peerID)
        val handler = MessageHandler(receiver.peerID, securityManager, PeerManager(), receiver.crypto)
        val delegate = RecordingDelegate()

        init {
            handler.delegate = delegate
        }

        fun establishSession() {
            val message1 = sender.noise.initiateHandshake(
                receiver.peerID,
                sender.crypto.getNoisePrivateKey(),
                sender.crypto.getNoisePublicKey()
            )
            val message2 = receiver.noise.processHandshake(
                sender.peerID,
                message1,
                receiver.crypto.getNoisePrivateKey(),
                receiver.crypto.getNoisePublicKey()
            )!!
            val message3 = sender.noise.processHandshake(
                receiver.peerID,
                message2,
                sender.crypto.getNoisePrivateKey(),
                sender.crypto.getNoisePublicKey()
            )!!
            receiver.noise.processHandshake(
                sender.peerID,
                message3,
                receiver.crypto.getNoisePrivateKey(),
                receiver.crypto.getNoisePublicKey()
            )
        }
    }

    private class Party(seed: String) {
        val crypto = CryptoSigningFacade(seed)
        val peerID = crypto.getIdentityFingerprint()
        val noise = NoiseEncryptionFacade(peerID)
    }

    private class RecordingDelegate : MessageHandlerDelegate {
        val publicMessages = mutableListOf<Pair<String, String>>()
        val authenticatedMessages = mutableListOf<AuthenticatedMessage>()
        val authenticatedFiles = mutableListOf<Pair<String, BitchatFilePacket>>()
        val deliveryAcks = mutableListOf<Pair<String, String>>()
        val readReceipts = mutableListOf<Pair<String, String>>()
        val publicFiles = mutableListOf<Pair<String, BitchatFilePacket>>()
        val fragments = mutableListOf<String>()

        override fun onPeerAnnounced(peerID: String, nickname: String) = Unit

        override fun onMessageReceived(peerID: String, message: String) {
            publicMessages += peerID to message
        }

        override fun onAuthenticatedPrivateMessage(peerID: String, messageId: String, content: String) {
            authenticatedMessages += AuthenticatedMessage(peerID, messageId, content)
        }

        override fun onAuthenticatedPrivateFile(peerID: String, file: BitchatFilePacket) {
            authenticatedFiles += peerID to file
        }

        override fun onAuthenticatedDelivered(peerID: String, messageId: String) {
            deliveryAcks += peerID to messageId
        }

        override fun onAuthenticatedRead(peerID: String, messageId: String) {
            readReceipts += peerID to messageId
        }

        override fun onHandshakeReceived(peerID: String) = Unit
        override fun onHandshakeResponse(peerID: String, responsePacket: ByteArray) = Unit
        override fun onSessionEstablished(peerID: String) = Unit
        override fun onSessionUnusable(peerID: String) = Unit
        override fun onPeerLeft(peerID: String) = Unit
        override fun onFragmentReceived(peerID: String) {
            fragments += peerID
        }

        override fun onPublicFileReceived(peerID: String, filePacket: BitchatFilePacket) {
            publicFiles += peerID to filePacket
        }
    }

    private data class AuthenticatedMessage(val peerID: String, val messageId: String, val content: String)

    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
