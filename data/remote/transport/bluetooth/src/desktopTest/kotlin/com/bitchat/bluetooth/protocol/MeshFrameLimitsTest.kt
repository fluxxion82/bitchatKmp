package com.bitchat.bluetooth.protocol

import com.bitchat.api.dto.mapper.toWireFormat
import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two limits must agree: the largest file this app sends ([BitchatFilePacket.MAX_CONTENT_BYTES]) has
 * to arrive as a frame no receiver refuses ([MAX_MESH_FRAME_BYTES]). These build the frame the way
 * `BluetoothMeshService` does for a public and for a private file, so the constants cannot drift apart.
 */
class MeshFrameLimitsTest {

    // Bytes that do not compress, so nothing here gets smaller on the way.
    private val largestFile = BitchatFilePacket(
        fileName = "x".repeat(100) + ".jpeg",
        fileSize = BitchatFilePacket.MAX_CONTENT_BYTES.toLong(),
        mimeType = "application/octet-stream",
        content = Random(1).nextBytes(BitchatFilePacket.MAX_CONTENT_BYTES),
    )

    @Test
    fun theLargestPublicFileFitsInOneFrame() {
        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = ByteArray(8),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = 0u,
            payload = assertNotNull(largestFile.toWireFormat()),
            signature = ByteArray(64),
            ttl = 7u,
        )

        val frame = assertNotNull(packet.toBinaryData())
        assertTrue(frame.size <= MAX_MESH_FRAME_BYTES, "a public file of the largest size is ${frame.size} bytes on the wire")
    }

    @Test
    fun theLargestPrivateFileFitsInOneFrame() {
        val (sender, recipient) = establishedSession()
        val sealed = sender.facade.encrypt(
            recipient.peerID,
            NoisePayload(NoisePayloadType.FILE_TRANSFER, assertNotNull(largestFile.toWireFormat())).encode(),
        )
        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = BitchatPacket.hexStringToByteArray(sender.peerID),
            recipientID = BitchatPacket.hexStringToByteArray(recipient.peerID),
            timestamp = 0u,
            payload = assertNotNull(sealed, "the session encrypts a file of the largest size"),
            signature = ByteArray(64),
            ttl = 7u,
        )

        val frame = assertNotNull(packet.toBinaryData())
        assertTrue(frame.size <= MAX_MESH_FRAME_BYTES, "a private file of the largest size is ${frame.size} bytes on the wire")
    }

    private class Party(seed: String) {
        private val crypto = CryptoSigningFacade(seed)
        val peerID = crypto.getIdentityFingerprint()
        val facade = NoiseEncryptionFacade(peerID)
        val privateKey = crypto.getNoisePrivateKey()
        val publicKey = crypto.getNoisePublicKey()
    }

    /** Runs the handshake to completion; the sender is its initiator. */
    private fun establishedSession(): Pair<Party, Party> {
        val initiator = Party("1".repeat(64))
        val responder = Party("2".repeat(64))
        val message1 = initiator.facade.initiateHandshake(responder.peerID, initiator.privateKey, initiator.publicKey)
        val message2 = reply(responder.facade.processHandshake(initiator.peerID, message1, responder.privateKey, responder.publicKey))
        val message3 = reply(initiator.facade.processHandshake(responder.peerID, message2, initiator.privateKey, initiator.publicKey))
        responder.facade.processHandshake(initiator.peerID, message3, responder.privateKey, responder.publicKey)
        check(initiator.facade.hasEstablishedSession(responder.peerID)) { "no session" }
        return initiator to responder
    }

    private fun reply(result: NoiseEncryptionFacade.HandshakeResult): ByteArray =
        (result as? NoiseEncryptionFacade.HandshakeResult.Response)?.message
            ?: assertNotNull((result as? NoiseEncryptionFacade.HandshakeResult.Established)?.response, "expected a handshake reply, got $result")
}
