package com.bitchat.bluetooth.facade

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * peer IDs used here are the real ones, so the direction of the tie is the one that was observed.
 */
class NoiseHandshakeCollisionTest {

    private val pi = "fefe735fc063ff08"
    private val phone = "269e37bb6be7caf9"

    private class Party(val peerID: String) {
        val facade = NoiseEncryptionFacade(peerID)
        val privateKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val publicKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }

    @Test
    fun bothSidesInitiatingStillCompletesAHandshake() {
        val local = Party(pi)
        val remote = Party(phone)

        val ourMessage1 = local.facade.initiateHandshake(remote.peerID, local.privateKey, local.publicKey)
        val theirMessage1 = remote.facade.initiateHandshake(local.peerID, remote.privateKey, remote.publicKey)
        assertEquals(32, ourMessage1.size)
        assertEquals(32, theirMessage1.size)

        // The lower peer ID holds: it keeps its own initiator handshake and ignores ours.
        val ignored = remote.facade.processHandshake(
            local.peerID, ourMessage1, remote.privateKey, remote.publicKey
        )
        assertNull(ignored, "the peer that holds must not answer the message 1 it collided with")
        assertTrue(remote.facade.isHandshaking(local.peerID))

        // The higher peer ID yields: it throws away its own attempt and answers as responder.
        val message2 = local.facade.processHandshake(
            remote.peerID, theirMessage1, local.privateKey, local.publicKey
        )
        assertNotNull(message2, "the peer that yields must answer as responder")
        assertEquals(96, message2.size, "XX message 2")

        val message3 = assertNotNull(
            remote.facade.processHandshake(local.peerID, message2, remote.privateKey, remote.publicKey)
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
        assertContentEquals(plaintext, remote.facade.decrypt(local.peerID, ciphertext))
    }

    @Test
    fun theTieBreaksTheSameWayOnBothSides() {
        // If both yielded there would be two responders and no message 1 left; if neither did, the
        // deadlock the journal recorded. Exactly one side must yield, whichever way round the two
        // facades are created.
        val lower = Party(phone)
        val higher = Party(pi)

        val fromLower = lower.facade.initiateHandshake(higher.peerID, lower.privateKey, lower.publicKey)
        val fromHigher = higher.facade.initiateHandshake(lower.peerID, higher.privateKey, higher.publicKey)

        val higherAnswer = higher.facade.processHandshake(
            lower.peerID, fromLower, higher.privateKey, higher.publicKey
        )
        val lowerAnswer = lower.facade.processHandshake(
            higher.peerID, fromHigher, lower.privateKey, lower.publicKey
        )

        assertNotNull(higherAnswer, "the larger peer ID yields")
        assertNull(lowerAnswer, "the smaller peer ID holds")
    }

    @Test
    fun anOrdinaryOpeningIsNotTreatedAsACollision() {
        val local = Party(pi)
        val remote = Party(phone)

        // No handshake of ours is in flight, so message 1 is answered whatever the peer IDs are --
        // including from a peer whose ID would have made us hold in a collision.
        val message1 = remote.facade.initiateHandshake(local.peerID, remote.privateKey, remote.publicKey)
        val response = local.facade.processHandshake(
            remote.peerID, message1, local.privateKey, local.publicKey
        )

        assertNotNull(response)
        assertEquals(96, response.size)
    }

    @Test
    fun aMessageTwoIsNeverMistakenForACollision() {
        val local = Party(pi)
        val remote = Party(phone)

        val message1 = local.facade.initiateHandshake(remote.peerID, local.privateKey, local.publicKey)
        val message2 = assertNotNull(
            remote.facade.processHandshake(local.peerID, message1, remote.privateKey, remote.publicKey)
        )

        // Our own handshake is in flight and we are the higher peer ID, so a size-blind rule would
        // yield here and destroy the session the reply belongs to.
        val message3 = local.facade.processHandshake(
            remote.peerID, message2, local.privateKey, local.publicKey
        )

        assertNotNull(message3)
        assertEquals(64, message3.size)
    }

    @Test
    fun anAbandonedInitiationNoLongerCausesAHold() {
        val local = Party(pi)
        val remote = Party(phone)

        local.facade.initiateHandshake(remote.peerID, local.privateKey, local.publicKey)

        // The sweeper gave up on our handshake. The peer's message 1 that arrives afterwards is an
        // ordinary opening rather than a collision, so it is answered outright instead of going
        // through the tie-break against a session that no longer exists.
        local.facade.removeSession(remote.peerID)

        val theirMessage1 = remote.facade.initiateHandshake(local.peerID, remote.privateKey, remote.publicKey)
        val response = local.facade.processHandshake(
            remote.peerID, theirMessage1, local.privateKey, local.publicKey
        )

        assertNotNull(response)
        assertEquals(96, response.size)
    }
}
