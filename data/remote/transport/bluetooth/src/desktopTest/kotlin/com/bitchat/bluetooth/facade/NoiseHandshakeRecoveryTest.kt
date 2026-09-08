package com.bitchat.bluetooth.facade

import com.bitchat.bluetooth.manager.HandshakeSupervisor
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The recovery contract between [NoiseEncryptionFacade] and [HandshakeSupervisor], driven with
 * real Noise sessions rather than mocks: the sessions are what carry the creation time the
 * deadline is measured from, and mocking them would only assert that the mock was configured.
 */
class NoiseHandshakeRecoveryTest {

    private val random = SecureRandom()

    private fun key(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    private class Party(val peerID: String) {
        val facade = NoiseEncryptionFacade()
        val privateKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val publicKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }

    @Test
    fun aHandshakeWithNoResponseIsAbandonedAfterTheDeadlineAndCanBeReInitiated() {
        val supervisor = HandshakeSupervisor()
        val facade = NoiseEncryptionFacade()
        val peerID = "269e37bb6be7caf9"
        val privateKey = key()
        val publicKey = key()

        val first = facade.initiateHandshake(peerID, privateKey, publicKey)
        assertEquals(32, first.size, "XX message 1 should have gone out")
        assertTrue(facade.isHandshaking(peerID))

        // The peer never answers. Every further attempt is a no-op, which is what used to wedge
        // the session for the life of the process.
        assertTrue(facade.initiateHandshake(peerID, privateKey, publicKey).isEmpty())
        assertEquals("handshaking", facade.getSessionState(peerID))

        val inFlight = facade.handshakesInFlight()
        assertEquals(setOf(peerID), inFlight.keys)
        val startedAt = inFlight.getValue(peerID)

        assertFalse(supervisor.isExpired(startedAt, startedAt + HandshakeSupervisor.HANDSHAKE_TIMEOUT_MS - 1))
        assertTrue(supervisor.isExpired(startedAt, startedAt + HandshakeSupervisor.HANDSHAKE_TIMEOUT_MS))

        // What the sweeper does once the deadline has passed.
        facade.removeSession(peerID)

        assertFalse(facade.isHandshaking(peerID))
        assertEquals("uninitialized", facade.getSessionState(peerID))
        assertTrue(facade.handshakesInFlight().isEmpty())

        val retry = facade.initiateHandshake(peerID, privateKey, publicKey)
        assertEquals(32, retry.size, "the abandoned handshake must be re-initiable")
        assertFalse(retry.contentEquals(first), "the retry should carry a fresh ephemeral key")
        assertTrue(facade.isHandshaking(peerID))
    }

    @Test
    fun aHandshakeThatCompletesNormallyIsUntouchedByTheDeadline() {
        val supervisor = HandshakeSupervisor()
        val alice = Party("fefe735fc063ff08")
        val bob = Party("269e37bb6be7caf9")

        val message1 = alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey)
        assertEquals(32, message1.size)

        val message2 = bob.facade.processHandshake(alice.peerID, message1, bob.privateKey, bob.publicKey)
        assertNotNull(message2, "responder should answer XX message 1")

        val message3 = alice.facade.processHandshake(bob.peerID, message2, alice.privateKey, alice.publicKey)
        assertNotNull(message3, "initiator should answer XX message 2")

        bob.facade.processHandshake(alice.peerID, message3, bob.privateKey, bob.publicKey)

        assertTrue(alice.facade.hasEstablishedSession(bob.peerID))
        assertTrue(bob.facade.hasEstablishedSession(alice.peerID))

        // Nothing is in flight, so no deadline applies however far the clock is wound forward.
        assertTrue(alice.facade.handshakesInFlight().isEmpty())
        assertTrue(bob.facade.handshakesInFlight().isEmpty())
        assertFalse(alice.facade.isHandshaking(bob.peerID))
        assertFalse(supervisor.isExpired(startedAt = 0L, now = 0L))

        // And the established session still works after the point a stalled one would have died.
        val plaintext = "hey".encodeToByteArray()
        val ciphertext = assertNotNull(alice.facade.encrypt(bob.peerID, plaintext))
        assertContentEquals(plaintext, bob.facade.decrypt(alice.peerID, ciphertext))
    }

    @Test
    fun aHandshakeThatIsStillInsideTheDeadlineIsNotAbandoned() {
        val supervisor = HandshakeSupervisor()
        val facade = NoiseEncryptionFacade()
        val peerID = "9343bbdb113d0118"

        facade.initiateHandshake(peerID, key(), key())
        val startedAt = facade.handshakesInFlight().getValue(peerID)

        val stillInFlight = facade.handshakesInFlight().filterValues { started ->
            supervisor.isExpired(started, startedAt + 1_000L)
        }

        assertTrue(stillInFlight.isEmpty(), "a one-second-old handshake must not be swept")
        assertTrue(facade.isHandshaking(peerID))
    }

    @Test
    fun aFailedHandshakeLeavesNoSessionBehindAndNeverThrows() {
        val facade = NoiseEncryptionFacade()
        val peerID = "deadbeefdeadbeef"

        // All-zero static keys fail validation, so the session can never handshake. The facade must
        // absorb that: a throw here would propagate into the per-peer actor loop in PacketProcessor
        // and kill it, silently swallowing every later packet from this peer.
        val response = facade.processHandshake(
            peerID = peerID,
            message = ByteArray(32),
            localStaticPrivateKey = ByteArray(32),
            localStaticPublicKey = ByteArray(32)
        )

        assertEquals(null, response)
        assertEquals("uninitialized", facade.getSessionState(peerID))
        assertTrue(facade.handshakesInFlight().isEmpty())
    }
}
