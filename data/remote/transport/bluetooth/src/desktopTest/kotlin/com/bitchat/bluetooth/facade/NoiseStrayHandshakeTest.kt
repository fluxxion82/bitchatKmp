package com.bitchat.bluetooth.facade

import com.bitchat.noise.NoiseConstants
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a stray handshake message may do to a session that is already carrying direct messages.
 *
 * Handshakes travel as ordinary broadcast packets with a TTL, so a peer's message 1 reaches this
 * node more than once as a matter of course -- relayed by another node, or echoed back. The device
 * journal shows the consequence, one second after a session was established and working:
 *
 *     [NoiseSession-Linux] Handshake completed with 3b146c2741c9a37e (role: INITIATOR)
 *     ...
 *     [NoiseSession-Linux] Handshake failed with 3b146c2741c9a37e: Invalid state for handshake: established
 *     [NoiseSession-Linux] Session destroyed for 3b146c2741c9a37e
 *     NOISE_HANDSHAKE: Session not yet established with 3b146c2741c9a37e
 *
 * The session was fine. The duplicate could not be fed to it, and the error path threw away the
 * working session because of it -- which is exactly the reported symptom: direct messages work,
 * stop for no reason, and start again some minutes later once a fresh handshake happens to complete.
 *
 * It is also a denial of service: before this, any node that could put a handshake packet on the
 * mesh could strip any two peers of their session at will, repeatedly.
 */
class NoiseStrayHandshakeTest {

    private val pi = "fefe735fc063ff08"
    private val phone = "3b146c2741c9a37e"

    private class Party(val peerID: String) {
        val facade = NoiseEncryptionFacade(peerID)
        val privateKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val publicKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }

    /** Runs XX to completion and returns the two parties, initiator first. */
    private fun established(): Pair<Party, Party> {
        val initiator = Party(pi)
        val responder = Party(phone)

        val message1 = initiator.facade.initiateHandshake(responder.peerID, initiator.privateKey, initiator.publicKey)
        val message2 = responder.facade.processHandshake(initiator.peerID, message1, responder.privateKey, responder.publicKey)
        assertNotNull(message2)
        val message3 = initiator.facade.processHandshake(responder.peerID, message2, initiator.privateKey, initiator.publicKey)
        assertNotNull(message3)
        responder.facade.processHandshake(initiator.peerID, message3, responder.privateKey, responder.publicKey)

        assertTrue(initiator.facade.hasEstablishedSession(responder.peerID))
        assertTrue(responder.facade.hasEstablishedSession(initiator.peerID))
        return initiator to responder
    }

    private fun assertStillTalking(initiator: Party, responder: Party) {
        val plaintext = "the session must survive".encodeToByteArray()
        val sealed = initiator.facade.encrypt(responder.peerID, plaintext)
        assertNotNull(sealed, "an established session should still encrypt")
        assertContentEquals(plaintext, responder.facade.decrypt(initiator.peerID, sealed))
    }

    @Test
    fun aReplayedMessageOneDoesNotDestroyAnEstablishedSession() {
        val (initiator, responder) = established()

        // The same message 1 arriving a second time, which is what a TTL-carrying mesh does.
        val replay = ByteArray(NoiseConstants.XX_MESSAGE_1_SIZE).also { SecureRandom().nextBytes(it) }
        responder.facade.processHandshake(initiator.peerID, replay, responder.privateKey, responder.publicKey)

        assertTrue(
            responder.facade.hasEstablishedSession(initiator.peerID),
            "a duplicate handshake must not take down a working session"
        )
        assertStillTalking(initiator, responder)
    }

    @Test
    fun aStrayHandshakeCannotStripAPeerOfItsSessionRepeatedly() {
        val (initiator, responder) = established()

        repeat(10) {
            val noise = ByteArray(NoiseConstants.XX_MESSAGE_1_SIZE).also { SecureRandom().nextBytes(it) }
            responder.facade.processHandshake(initiator.peerID, noise, responder.privateKey, responder.publicKey)
        }

        assertTrue(responder.facade.hasEstablishedSession(initiator.peerID))
        assertStillTalking(initiator, responder)
    }

    @Test
    fun aPeerThatRestartsCanStillEstablishAFreshSession() {
        val (initiator, responder) = established()

        // The peer lost its state and opens a genuinely new handshake with fresh keys. Ignoring
        // every handshake while established would leave this peer unable to talk to us again, so
        // the new one has to be negotiated -- without the old session going dark while it is.
        val restarted = Party(initiator.peerID)
        val message1 = restarted.facade.initiateHandshake(responder.peerID, restarted.privateKey, restarted.publicKey)
        val message2 = responder.facade.processHandshake(restarted.peerID, message1, responder.privateKey, responder.publicKey)
        assertNotNull(message2, "a restarted peer must be answered")

        // Mid-renegotiation the old session is still the live one and still works.
        assertStillTalking(initiator, responder)

        val message3 = restarted.facade.processHandshake(responder.peerID, message2, restarted.privateKey, restarted.publicKey)
        assertNotNull(message3)
        responder.facade.processHandshake(restarted.peerID, message3, responder.privateKey, responder.publicKey)

        assertTrue(responder.facade.hasEstablishedSession(restarted.peerID))
        assertStillTalking(restarted, responder)
    }
}
