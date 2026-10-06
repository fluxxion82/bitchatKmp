package com.bitchat.bluetooth.facade

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What happens when both peers start a Noise handshake at the same moment.
 *
 * This is not hypothetical. The device journal shows the embedded node and an Android phone both
 * sending XX message 1 within a second of a link coming up, each then feeding the other's 32-byte
 * message 1 into an initiator state machine that wanted a 96-byte message 2:
 *
 *     [NoiseSession-Linux] Processing handshake message from 269e37bb6be7caf9 (32 bytes)
 *     [NoiseSession-Linux] noise_handshakestate_read_message failed: INVALID_LENGTH
 *
 * The phone was already applying the tie-break the upstream client implements and waiting for us to
 * give way; because we never did, no direct message between the two could ever be encrypted. The
 * peers here use IDs derived from their real Noise static keys, while [orderedParties] keeps the
 * observed larger-ID-yields direction deterministic.
 */
class NoiseHandshakeCollisionTest {

    private class Party(seed: String) {
        private val crypto = CryptoSigningFacade(seed)
        val peerID = crypto.getIdentityFingerprint()
        val facade = NoiseEncryptionFacade(peerID)
        val privateKey = crypto.getNoisePrivateKey()
        val publicKey = crypto.getNoisePublicKey()
    }

    @Test
    fun bothSidesInitiatingStillCompletesAHandshake() {
        val (local, remote) = orderedParties("1".repeat(64), "2".repeat(64))

        val ourMessage1 = local.facade.initiateHandshake(remote.peerID, local.privateKey, local.publicKey)
        val theirMessage1 = remote.facade.initiateHandshake(local.peerID, remote.privateKey, remote.publicKey)
        assertEquals(32, ourMessage1.size)
        assertEquals(32, theirMessage1.size)

        // The lower peer ID holds: it keeps its own initiator handshake and ignores ours.
        val ignored = remote.facade.processHandshake(
            local.peerID, ourMessage1, remote.privateKey, remote.publicKey
        )
        assertEquals(NoiseEncryptionFacade.HandshakeResult.Ignored, ignored, "the peer that holds must not answer the message 1 it collided with")
        assertTrue(remote.facade.isHandshaking(local.peerID))

        // The higher peer ID yields: it throws away its own attempt and answers as responder.
        val message2 = response(local.facade.processHandshake(
            remote.peerID, theirMessage1, local.privateKey, local.publicKey
        ))
        assertEquals(96, message2.size, "XX message 2")

        val message3 = assertNotNull(
            response(remote.facade.processHandshake(local.peerID, message2, remote.privateKey, remote.publicKey))
        )
        // 64 on the wire: the 48-byte XX message 3 plus its 16-byte tag, as the device journal
        // records it. What the tie-break depends on is only that it is not the 32 bytes of a
        // message 1, so it can never be mistaken for a second peer opening a handshake.
        assertEquals(64, message3.size, "XX message 3")

        local.facade.processHandshake(remote.peerID, message3, local.privateKey, local.publicKey)

        assertTrue(local.facade.hasEstablishedSession(remote.peerID))
        assertTrue(remote.facade.hasEstablishedSession(local.peerID))

        val plaintext = "hey".encodeToByteArray()
        val ciphertext = assertNotNull(local.facade.encrypt(remote.peerID, plaintext))
        assertContentEquals(plaintext, assertNotNull(remote.facade.decrypt(local.peerID, ciphertext)).plaintext)
    }

    @Test
    fun theTieBreaksTheSameWayOnBothSides() {
        // If both yielded there would be two responders and no message 1 left; if neither did, the
        // deadlock the journal recorded. Exactly one side must yield, whichever way round the two
        // facades are created.
        val (higher, lower) = orderedParties("3".repeat(64), "4".repeat(64))

        val fromLower = lower.facade.initiateHandshake(higher.peerID, lower.privateKey, lower.publicKey)
        val fromHigher = higher.facade.initiateHandshake(lower.peerID, higher.privateKey, higher.publicKey)

        val higherAnswer = higher.facade.processHandshake(
            lower.peerID, fromLower, higher.privateKey, higher.publicKey
        )
        val lowerAnswer = lower.facade.processHandshake(
            higher.peerID, fromHigher, lower.privateKey, lower.publicKey
        )

        assertTrue(higherAnswer is NoiseEncryptionFacade.HandshakeResult.Response, "the larger peer ID yields")
        assertEquals(NoiseEncryptionFacade.HandshakeResult.Ignored, lowerAnswer, "the smaller peer ID holds")
    }

    @Test
    fun anOrdinaryOpeningIsNotTreatedAsACollision() {
        val local = Party("5".repeat(64))
        val remote = Party("6".repeat(64))

        // No handshake of ours is in flight, so message 1 is answered whatever the peer IDs are --
        // including from a peer whose ID would have made us hold in a collision.
        val message1 = remote.facade.initiateHandshake(local.peerID, remote.privateKey, remote.publicKey)
        val response = response(local.facade.processHandshake(
            remote.peerID, message1, local.privateKey, local.publicKey
        ))
        assertEquals(96, response.size)
    }

    @Test
    fun aMessageTwoIsNeverMistakenForACollision() {
        val local = Party("7".repeat(64))
        val remote = Party("8".repeat(64))

        val message1 = local.facade.initiateHandshake(remote.peerID, local.privateKey, local.publicKey)
        val message2 = response(remote.facade.processHandshake(local.peerID, message1, remote.privateKey, remote.publicKey))

        // Our own handshake is in flight and we are the higher peer ID, so a size-blind rule would
        // yield here and destroy the session the reply belongs to.
        val message3 = response(local.facade.processHandshake(
            remote.peerID, message2, local.privateKey, local.publicKey
        ))
        assertEquals(64, message3.size)
    }

    @Test
    fun anAbandonedInitiationNoLongerCausesAHold() {
        val local = Party("9".repeat(64))
        val remote = Party("a".repeat(64))

        local.facade.initiateHandshake(remote.peerID, local.privateKey, local.publicKey)

        // The sweeper gave up on our handshake. The peer's message 1 that arrives afterwards is an
        // ordinary opening rather than a collision, so it is answered outright instead of going
        // through the tie-break against a session that no longer exists.
        local.facade.removeSession(remote.peerID)

        val theirMessage1 = remote.facade.initiateHandshake(local.peerID, remote.privateKey, remote.publicKey)
        val response = response(local.facade.processHandshake(
            remote.peerID, theirMessage1, local.privateKey, local.publicKey
        ))
        assertEquals(96, response.size)
    }

    private fun response(result: NoiseEncryptionFacade.HandshakeResult): ByteArray = when (result) {
        is NoiseEncryptionFacade.HandshakeResult.Response -> result.message
        is NoiseEncryptionFacade.HandshakeResult.Established -> assertNotNull(result.response)
        NoiseEncryptionFacade.HandshakeResult.Ignored,
        NoiseEncryptionFacade.HandshakeResult.RejectedIdentity -> error("expected handshake response, got $result")
    }

    /** Returns the larger-ID peer first because that peer must yield. */
    private fun orderedParties(firstSeed: String, secondSeed: String): Pair<Party, Party> {
        val first = Party(firstSeed)
        val second = Party(secondSeed)
        return if (first.peerID > second.peerID) first to second else second to first
    }
}
