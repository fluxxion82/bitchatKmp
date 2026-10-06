package com.bitchat.bluetooth.facade

import com.bitchat.crypto.Cryptography
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoiseIdentityGateTest {

    @Test
    fun rawPrefixIdsEstablishAndCarryTrafficInBothDirections() {
        val alice = Party("1".repeat(64), Identity.RAW)
        val bob = Party(
            "2".repeat(64),
            Identity.RAW,
            CryptoSigningFacade("2".repeat(64)).getIdentityFingerprint().uppercase()
        )

        establish(alice, bob)

        assertTraffic(alice, bob, "alice to bob")
        assertTraffic(bob, alice, "bob to alice")
    }

    @Test
    fun sha256PrefixIdsEstablishAndCarryTrafficInBothDirections() {
        val alice = Party("3".repeat(64), Identity.SHA256)
        val bob = Party("4".repeat(64), Identity.SHA256)

        establish(alice, bob)

        assertTraffic(alice, bob, "alice to bob")
        assertTraffic(bob, alice, "bob to alice")
    }

    @Test
    fun impostorRenegotiationCannotReplaceAnEstablishedResponderSession() {
        val honest = Party("5".repeat(64), Identity.RAW)
        val victim = Party("6".repeat(64), Identity.RAW)
        establish(honest, victim)
        assertTrue(victim.facade.hasEstablishedSession(honest.peerID))
        val oldRemoteKey = assertNotNull(victim.facade.getRemoteStaticKey(honest.peerID))

        // The attacker claims honest's ID but has a different Noise static key.
        val attacker = Party("7".repeat(64), Identity.RAW, claimedPeerID = honest.peerID)
        val message1 = attacker.facade.initiateHandshake(victim.peerID, attacker.privateKey, attacker.publicKey)
        val message2 = response(victim.facade.processHandshake(honest.peerID, message1, victim.privateKey, victim.publicKey))
        val message3 = response(attacker.facade.processHandshake(victim.peerID, message2, attacker.privateKey, attacker.publicKey))

        assertEquals(
            NoiseEncryptionFacade.HandshakeResult.RejectedIdentity,
            victim.facade.processHandshake(honest.peerID, message3, victim.privateKey, victim.publicKey)
        )
        assertTrue(victim.facade.hasEstablishedSession(honest.peerID))
        assertEquals("established", victim.facade.getSessionState(honest.peerID))
        assertContentEquals(oldRemoteKey, victim.facade.getRemoteStaticKey(honest.peerID))

        // These packets are produced only after the impostor completed its XX transcript.
        assertTraffic(honest, victim, "old session forward")
        assertTraffic(victim, honest, "old session reverse")
    }

    @Test
    fun anOpeningUnderAnEstablishedPeersIdIsNotAHandshakeInFlight() {
        val honest = Party("5".repeat(64), Identity.RAW)
        val victim = Party("6".repeat(64), Identity.RAW)
        establish(honest, victim)

        // One unsigned 32-byte packet under honest's id: a renegotiation candidate now exists.
        val attacker = Party("7".repeat(64), Identity.RAW, claimedPeerID = honest.peerID)
        val message1 = attacker.facade.initiateHandshake(victim.peerID, attacker.privateKey, attacker.publicKey)
        response(victim.facade.processHandshake(honest.peerID, message1, victim.privateKey, victim.publicKey))

        // The sweeper discards what these report with removeSession, which also drops the
        // established session: a renegotiation must not show up here, however long it stalls.
        assertFalse(victim.facade.isHandshaking(honest.peerID))
        assertFalse(honest.peerID in victim.facade.handshakesInFlight())
        assertEquals("established", victim.facade.getSessionState(honest.peerID))
        assertTraffic(honest, victim, "still the real session")
    }

    @Test
    fun impostorWithoutAPreviousSessionNeverBecomesUsable() {
        val victim = Party("8".repeat(64), Identity.RAW)
        val claimed = Party("9".repeat(64), Identity.RAW)
        val attacker = Party("a".repeat(64), Identity.RAW, claimedPeerID = claimed.peerID)

        val message1 = attacker.facade.initiateHandshake(victim.peerID, attacker.privateKey, attacker.publicKey)
        val message2 = response(victim.facade.processHandshake(claimed.peerID, message1, victim.privateKey, victim.publicKey))
        val message3 = response(attacker.facade.processHandshake(victim.peerID, message2, attacker.privateKey, attacker.publicKey))

        assertEquals(
            NoiseEncryptionFacade.HandshakeResult.RejectedIdentity,
            victim.facade.processHandshake(claimed.peerID, message3, victim.privateKey, victim.publicKey)
        )
        assertFalse(victim.facade.hasEstablishedSession(claimed.peerID))
        assertEquals("uninitialized", victim.facade.getSessionState(claimed.peerID))
        assertNull(victim.facade.getRemoteStaticKey(claimed.peerID))
        assertFalse(claimed.peerID in victim.facade.handshakesInFlight())
        assertNull(victim.facade.encrypt(claimed.peerID, "no candidate traffic".encodeToByteArray()))
        assertNull(victim.facade.decrypt(claimed.peerID, byteArrayOf(0, 0, 0, 0)))
    }

    @Test
    fun initiatorRejectsAnImpostorBeforeProducingMessageThree() {
        val victim = Party("b".repeat(64), Identity.RAW)
        val claimed = Party("c".repeat(64), Identity.RAW)
        val attacker = Party("d".repeat(64), Identity.RAW, claimedPeerID = claimed.peerID)

        val message1 = victim.facade.initiateHandshake(claimed.peerID, victim.privateKey, victim.publicKey)
        val message2 = response(attacker.facade.processHandshake(victim.peerID, message1, attacker.privateKey, attacker.publicKey))

        assertEquals(
            NoiseEncryptionFacade.HandshakeResult.RejectedIdentity,
            victim.facade.processHandshake(claimed.peerID, message2, victim.privateKey, victim.publicKey)
        )
        assertFalse(victim.facade.hasEstablishedSession(claimed.peerID))
        assertEquals("uninitialized", victim.facade.getSessionState(claimed.peerID))
    }

    @Test
    fun concurrentEncryptionAndRenegotiationUseOnlyEstablishedSessionsAndDistinctNonces() {
        val alice = Party("e".repeat(64), Identity.RAW)
        val bob = Party("f".repeat(64), Identity.RAW)
        establish(alice, bob)

        val restartedAlice = Party("e".repeat(64), Identity.RAW)
        val message1 = restartedAlice.facade.initiateHandshake(bob.peerID, restartedAlice.privateKey, restartedAlice.publicKey)
        val message2 = response(bob.facade.processHandshake(alice.peerID, message1, bob.privateKey, bob.publicKey))

        val workers = Executors.newFixedThreadPool(8)
        try {
            val ready = CountDownLatch(7)
            val go = CountDownLatch(1)
            val delivered = CountDownLatch(6)
            val received = ConcurrentLinkedQueue<String>()
            val encryptions = (0 until 6).map { index ->
                workers.submit(Callable {
                    ready.countDown()
                    assertTrue(go.await(5, TimeUnit.SECONDS))
                    val plaintext = "message-$index".encodeToByteArray()
                    val ciphertext = assertNotNull(bob.facade.encrypt(alice.peerID, plaintext))
                    assertContentEquals(plaintext, assertNotNull(alice.facade.decrypt(bob.peerID, ciphertext)).plaintext)
                    received += plaintext.decodeToString()
                    delivered.countDown()
                    ciphertext
                })
            }
            val completion = workers.submit(Callable {
                ready.countDown()
                assertTrue(go.await(5, TimeUnit.SECONDS))
                // Let every concurrent send traverse the old established session while the
                // replacement is only a candidate, then complete promotion immediately after.
                assertTrue(delivered.await(5, TimeUnit.SECONDS))
                val message3 = response(restartedAlice.facade.processHandshake(bob.peerID, message2, restartedAlice.privateKey, restartedAlice.publicKey))
                bob.facade.processHandshake(alice.peerID, message3, bob.privateKey, bob.publicKey)
            })

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            val ciphertexts = encryptions.map { it.get(5, TimeUnit.SECONDS) }
            completion.get(5, TimeUnit.SECONDS)

            // The first four bytes are the explicit transport nonce. A facade lock must serialize
            // the native send counter as well as the cipher operation.
            assertEquals(ciphertexts.size, ciphertexts.map(::nonce).toSet().size)
            assertEquals((0 until 6).map { "message-$it" }.toSet(), received.toSet())
            assertTrue(bob.facade.hasEstablishedSession(alice.peerID))
            assertTrue(restartedAlice.facade.hasEstablishedSession(bob.peerID))
        } finally {
            workers.shutdownNow()
        }
    }

    private fun establish(initiator: Party, responder: Party) {
        val message1 = initiator.facade.initiateHandshake(responder.peerID, initiator.privateKey, initiator.publicKey)
        val message2 = response(responder.facade.processHandshake(initiator.peerID, message1, responder.privateKey, responder.publicKey))
        val message3 = response(initiator.facade.processHandshake(responder.peerID, message2, initiator.privateKey, initiator.publicKey))
        assertEquals(
            NoiseEncryptionFacade.HandshakeResult.Established(null),
            responder.facade.processHandshake(initiator.peerID, message3, responder.privateKey, responder.publicKey)
        )
        assertTrue(initiator.facade.hasEstablishedSession(responder.peerID))
        assertTrue(responder.facade.hasEstablishedSession(initiator.peerID))
    }

    private fun assertTraffic(sender: Party, receiver: Party, text: String) {
        val plaintext = text.encodeToByteArray()
        val ciphertext = assertNotNull(sender.facade.encrypt(receiver.peerID, plaintext))
        assertContentEquals(plaintext, assertNotNull(receiver.facade.decrypt(sender.peerID, ciphertext)).plaintext)
    }

    private fun response(result: NoiseEncryptionFacade.HandshakeResult): ByteArray = when (result) {
        is NoiseEncryptionFacade.HandshakeResult.Response -> result.message
        is NoiseEncryptionFacade.HandshakeResult.Established -> assertNotNull(result.response)
        NoiseEncryptionFacade.HandshakeResult.Ignored,
        NoiseEncryptionFacade.HandshakeResult.RejectedIdentity -> error("expected handshake response, got $result")
    }

    private fun nonce(ciphertext: ByteArray): Int = ciphertext.take(4).fold(0) { value, byte ->
        (value shl 8) or (byte.toInt() and 0xff)
    }

    private enum class Identity { RAW, SHA256 }

    private class Party(seed: String, identity: Identity, claimedPeerID: String? = null) {
        private val crypto = CryptoSigningFacade(seed)
        val privateKey = crypto.getNoisePrivateKey()
        val publicKey = crypto.getNoisePublicKey()
        val peerID = claimedPeerID ?: when (identity) {
            Identity.RAW -> crypto.getIdentityFingerprint()
            Identity.SHA256 -> Cryptography.getDigestHash(publicKey).hexPrefix()
        }
        val facade = NoiseEncryptionFacade(peerID)
    }
}

// File level: the nested Party class derives ids with it and cannot see the test class's members.
private fun ByteArray.hexPrefix(): String = take(8).joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
