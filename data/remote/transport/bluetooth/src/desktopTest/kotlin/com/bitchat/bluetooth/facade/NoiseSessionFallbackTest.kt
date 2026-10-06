package com.bitchat.bluetooth.facade

import com.bitchat.bluetooth.manager.HandshakeSupervisor
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the two traffic slots with real Noise sessions, not mocked session state. */
class NoiseSessionFallbackTest {

    @Test
    fun demotionKeepsFallbackReadableButMakesOutgoingEncryptionUnavailable() {
        val alice = FallbackParty("1".repeat(64))
        val bob = FallbackParty("2".repeat(64))
        establish(alice, bob)
        val oldCiphertext = assertNotNull(bob.facade.encrypt(alice.peerID, "fallback input".encodeToByteArray()))

        assertTrue(alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey).isEmpty())
        alice.facade.demote(bob.peerID)

        assertFalse(alice.facade.hasEstablishedSession(bob.peerID))
        assertFalse(alice.facade.isHandshaking(bob.peerID))
        assertNull(alice.facade.encrypt(bob.peerID, "must queue".encodeToByteArray()))
        assertDecryptedBy(
            alice.facade.decrypt(bob.peerID, oldCiphertext),
            NoiseEncryptionFacade.DecryptionVia.FALLBACK,
            "fallback input"
        )
        assertEquals(32, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey).size)
    }

    @Test
    fun demotedPeerRekeysAgainstPeerThatStillHasItsOldSession() {
        val alice = FallbackParty("3".repeat(64))
        val bob = FallbackParty("4".repeat(64))
        establish(alice, bob)

        alice.facade.demote(bob.peerID)
        completeHandshake(alice, bob, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey))

        assertTrue(alice.facade.hasEstablishedSession(bob.peerID))
        assertTrue(bob.facade.hasEstablishedSession(alice.peerID))
        assertTraffic(alice, bob, "new session forward")
        assertTraffic(bob, alice, "new session reverse")
    }

    @Test
    fun lostMessageThreeRemainsRecoverableThroughFallback() {
        val alice = FallbackParty("5".repeat(64))
        val bob = FallbackParty("6".repeat(64))
        establish(alice, bob)
        val oldToAlice = assertNotNull(bob.facade.encrypt(alice.peerID, "old".encodeToByteArray()))

        alice.facade.demote(bob.peerID)
        val message1 = alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey)
        val message2 = response(bob.facade.processHandshake(alice.peerID, message1, bob.privateKey, bob.publicKey))
        val lostMessage3 = response(alice.facade.processHandshake(bob.peerID, message2, alice.privateKey, alice.publicKey))
        assertEquals(64, lostMessage3.size)
        assertDecryptedBy(alice.facade.decrypt(bob.peerID, oldToAlice), NoiseEncryptionFacade.DecryptionVia.FALLBACK, "old")
        assertNull(bob.facade.decrypt(alice.peerID, assertNotNull(alice.facade.encrypt(bob.peerID, "new".encodeToByteArray()))))

        // Bob still holds the responder candidate whose message 3 never came. Condemning his
        // session clears it, so the replacement handshake can start at once and not at the sweep.
        bob.facade.demote(alice.peerID)
        val opener = bob.facade.initiateHandshake(alice.peerID, bob.privateKey, bob.publicKey)
        assertEquals(32, opener.size)
        completeHandshake(bob, alice, opener)
        assertTraffic(alice, bob, "recovered forward")
        assertTraffic(bob, alice, "recovered reverse")
    }

    @Test
    fun discardingAnUnsharedSessionAlsoClearsACandidateSoTheReplacementCanStart() {
        val alice = FallbackParty("5".repeat(64))
        val bob = FallbackParty("6".repeat(64))
        establish(alice, bob)
        alice.facade.demote(bob.peerID)
        completeHandshake(alice, bob, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey))
        // Alice: established (new) + fallback (old). An opening under Bob's id leaves a responder
        // candidate beside them; anyone in range can send one.
        val stranger = FallbackParty("9".repeat(64))
        val opening = stranger.facade.initiateHandshake(alice.peerID, stranger.privateKey, stranger.publicKey)
        response(alice.facade.processHandshake(bob.peerID, opening, alice.privateKey, alice.publicKey))

        alice.facade.discardEstablished(bob.peerID)

        assertFalse(alice.facade.hasEstablishedSession(bob.peerID))
        assertEquals(32, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey).size)
    }

    @Test
    fun condemningAPeerWithNoSessionStillClearsACandidateSoAHandshakeCanStart() {
        val alice = FallbackParty("5".repeat(64))
        val bob = FallbackParty("6".repeat(64))
        // No session with Bob at all, only a responder candidate left by an opening under his id.
        val stranger = FallbackParty("9".repeat(64))
        val opening = stranger.facade.initiateHandshake(alice.peerID, stranger.privateKey, stranger.publicKey)
        response(alice.facade.processHandshake(bob.peerID, opening, alice.privateKey, alice.publicKey))
        assertTrue(alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey).isEmpty())

        alice.facade.demote(bob.peerID)

        assertEquals(32, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey).size)
    }

    @Test
    fun discardingWhenThePredecessorIsAlreadyGoneDemotesInsteadOfDoingNothing() {
        val alice = FallbackParty("5".repeat(64))
        val bob = FallbackParty("6".repeat(64))
        establish(alice, bob)
        val underTheOnlySession = assertNotNull(bob.facade.encrypt(alice.peerID, "still readable".encodeToByteArray()))

        // "Not shared" evidence can outlive the fallback it came from (retired at five minutes).
        alice.facade.discardEstablished(bob.peerID)

        // The session is condemned like any other: out of the lifecycle's way, still readable.
        assertFalse(alice.facade.hasEstablishedSession(bob.peerID))
        assertEquals(32, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey).size)
        assertDecryptedBy(alice.facade.decrypt(bob.peerID, underTheOnlySession), NoiseEncryptionFacade.DecryptionVia.FALLBACK, "still readable")
    }

    @Test
    fun removingASessionAlsoRemovesItsFallback() {
        val alice = FallbackParty("5".repeat(64))
        val bob = FallbackParty("6".repeat(64))
        establish(alice, bob)
        val old = assertNotNull(bob.facade.encrypt(alice.peerID, "old".encodeToByteArray()))
        alice.facade.demote(bob.peerID)

        alice.facade.removeSession(bob.peerID)

        assertNull(alice.facade.decrypt(bob.peerID, old))
    }

    @Test
    fun aPeerThatAnswersARekeyStillReadsWhatWasSentUnderTheSessionItReplaced() {
        val alice = FallbackParty("5".repeat(64))
        val bob = FallbackParty("6".repeat(64))
        establish(alice, bob)

        // Alice rekeys. What she sends before her side has switched is under the old session and
        // can reach Bob after he has already promoted the new one.
        val sentUnderTheOldSession = assertNotNull(alice.facade.encrypt(bob.peerID, "in flight".encodeToByteArray()))
        alice.facade.demote(bob.peerID)
        completeHandshake(alice, bob, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey))
        assertTrue(bob.facade.hasEstablishedSession(alice.peerID))

        // Bob had a healthy session and replaced it by promotion: the one he replaced must still decrypt.
        assertDecryptedBy(
            bob.facade.decrypt(alice.peerID, sentUnderTheOldSession),
            NoiseEncryptionFacade.DecryptionVia.FALLBACK,
            "in flight"
        )
        assertTraffic(alice, bob, "and the new session works")
    }

    @Test
    fun fallbackRetiresOnNewTrafficOrWhenItIsTooOldBesideEstablished() {
        val alice = FallbackParty("7".repeat(64))
        val bob = FallbackParty("8".repeat(64))
        establish(alice, bob)
        val old = assertNotNull(bob.facade.encrypt(alice.peerID, "old".encodeToByteArray()))
        alice.facade.demote(bob.peerID)
        completeHandshake(alice, bob, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey))

        assertTraffic(bob, alice, "new traffic retires fallback")
        assertNull(alice.facade.decrypt(bob.peerID, old))

        // A fresh rekey gives Alice another fallback. Drive expiry with the facade clock seam.
        val beforeAge = assertNotNull(bob.facade.encrypt(alice.peerID, "before age".encodeToByteArray()))
        alice.facade.demote(bob.peerID)
        completeHandshake(alice, bob, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey))
        val started = Clock.System.now().toEpochMilliseconds()
        alice.facade.hasEstablishedSession(bob.peerID, started + NoiseEncryptionFacade.FALLBACK_MAX_AGE_MS + 100)
        assertNull(alice.facade.decrypt(bob.peerID, beforeAge, started + NoiseEncryptionFacade.FALLBACK_MAX_AGE_MS + 100))
    }

    @Test
    fun abandoningAHandshakeNeverDestroysTheReadableFallback() {
        val alice = FallbackParty("9".repeat(64))
        val bob = FallbackParty("a".repeat(64))
        establish(alice, bob)
        val oldCiphertext = assertNotNull(bob.facade.encrypt(alice.peerID, "fallback survives".encodeToByteArray()))
        alice.facade.demote(bob.peerID)
        alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey)
        alice.facade.abandonHandshake(bob.peerID)
        assertFalse(alice.facade.isHandshaking(bob.peerID))
        assertDecryptedBy(
            alice.facade.decrypt(bob.peerID, oldCiphertext),
            NoiseEncryptionFacade.DecryptionVia.FALLBACK,
            "fallback survives"
        )
    }

    @Test
    fun impostorCannotReplaceFallbackDuringOwedHandshake() {
        val honest = FallbackParty("b".repeat(64))
        val victim = FallbackParty("c".repeat(64))
        val impostor = FallbackParty("d".repeat(64))
        establish(honest, victim)
        val honestCiphertext = assertNotNull(honest.facade.encrypt(victim.peerID, "honest input".encodeToByteArray()))
        victim.facade.demote(honest.peerID)
        val message1 = victim.facade.initiateHandshake(honest.peerID, victim.privateKey, victim.publicKey)
        val impostorMessage2 = response(impostor.facade.processHandshake(victim.peerID, message1, impostor.privateKey, impostor.publicKey))

        assertEquals(NoiseEncryptionFacade.HandshakeResult.RejectedIdentity, victim.facade.processHandshake(
            honest.peerID, impostorMessage2, victim.privateKey, victim.publicKey
        ))
        assertFalse(victim.facade.hasEstablishedSession(honest.peerID))
        assertDecryptedBy(
            victim.facade.decrypt(honest.peerID, honestCiphertext),
            NoiseEncryptionFacade.DecryptionVia.FALLBACK,
            "honest input"
        )
    }

    @Test
    fun demotionAndPromotionKeepOnlyTheMostRecentFallback() {
        val alice = FallbackParty("e".repeat(64))
        val bob = FallbackParty("f".repeat(64))
        establish(alice, bob)
        val oldest = assertNotNull(bob.facade.encrypt(alice.peerID, "oldest".encodeToByteArray()))
        alice.facade.demote(bob.peerID)
        alice.facade.demote(bob.peerID)
        completeHandshake(alice, bob, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey))
        val recent = assertNotNull(bob.facade.encrypt(alice.peerID, "recent".encodeToByteArray()))
        alice.facade.demote(bob.peerID)
        completeHandshake(alice, bob, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey))

        assertNull(alice.facade.decrypt(bob.peerID, oldest))
        assertDecryptedBy(alice.facade.decrypt(bob.peerID, recent), NoiseEncryptionFacade.DecryptionVia.FALLBACK, "recent")
    }

    @Test
    fun failedInitiatorDoesNotLeaveItsRoleAndExpiredInitiatorYields() {
        val first = FallbackParty("0".repeat(64))
        val second = FallbackParty("f".repeat(64))
        val (smaller, larger) = if (first.peerID < second.peerID) first to second else second to first
        val now = Clock.System.now().toEpochMilliseconds()
        smaller.facade.initiateHandshake(larger.peerID, smaller.privateKey, smaller.publicKey)
        assertEquals(NoiseEncryptionFacade.HandshakeResult.Ignored, smaller.facade.processHandshake(
            larger.peerID, byteArrayOf(0), smaller.privateKey, smaller.publicKey, now + 100
        ))
        val opening = larger.facade.initiateHandshake(smaller.peerID, larger.privateKey, larger.publicKey)
        assertTrue(smaller.facade.processHandshake(larger.peerID, opening, smaller.privateKey, smaller.publicKey, now + 200)
            is NoiseEncryptionFacade.HandshakeResult.Response)

        val freshFirst = FallbackParty("1".repeat(64))
        val freshSecond = FallbackParty("2".repeat(64))
        val (expiredSmaller, expiredLarger) = if (freshFirst.peerID < freshSecond.peerID) freshFirst to freshSecond else freshSecond to freshFirst
        expiredSmaller.facade.initiateHandshake(expiredLarger.peerID, expiredSmaller.privateKey, expiredSmaller.publicKey)
        val opener = expiredLarger.facade.initiateHandshake(expiredSmaller.peerID, expiredLarger.privateKey, expiredLarger.publicKey)
        assertTrue(expiredSmaller.facade.processHandshake(
            expiredLarger.peerID, opener, expiredSmaller.privateKey, expiredSmaller.publicKey,
            now + HandshakeSupervisor.HANDSHAKE_TIMEOUT_MS + 1_000
        ) is NoiseEncryptionFacade.HandshakeResult.Response)
    }

    @Test
    fun decryptReportsEstablishedForNormalTraffic() {
        val alice = FallbackParty("3".repeat(64))
        val bob = FallbackParty("4".repeat(64))
        establish(alice, bob)

        assertDecryptedBy(
            bob.facade.decrypt(alice.peerID, assertNotNull(alice.facade.encrypt(bob.peerID, "normal".encodeToByteArray()))),
            NoiseEncryptionFacade.DecryptionVia.ESTABLISHED,
            "normal"
        )
    }

    @Test
    fun discardEstablishedKeepsFallbackReadableAndAllowsAFirstHandshake() {
        val alice = FallbackParty("5".repeat(64))
        val bob = FallbackParty("6".repeat(64))
        establish(alice, bob)
        val oldCiphertext = assertNotNull(bob.facade.encrypt(alice.peerID, "old".encodeToByteArray()))
        alice.facade.demote(bob.peerID)
        completeHandshake(alice, bob, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey))

        alice.facade.discardEstablished(bob.peerID)

        assertFalse(alice.facade.hasEstablishedSession(bob.peerID))
        assertDecryptedBy(alice.facade.decrypt(bob.peerID, oldCiphertext), NoiseEncryptionFacade.DecryptionVia.FALLBACK, "old")
        assertEquals(32, alice.facade.initiateHandshake(bob.peerID, alice.privateKey, alice.publicKey).size)
    }

}

private class FallbackParty(seed: String) {
    private val crypto = CryptoSigningFacade(seed)
    val peerID = crypto.getIdentityFingerprint()
    val facade = NoiseEncryptionFacade(peerID)
    val privateKey = crypto.getNoisePrivateKey()
    val publicKey = crypto.getNoisePublicKey()
}

private fun establish(initiator: FallbackParty, responder: FallbackParty) =
    completeHandshake(initiator, responder, initiator.facade.initiateHandshake(responder.peerID, initiator.privateKey, initiator.publicKey))

private fun completeHandshake(initiator: FallbackParty, responder: FallbackParty, message1: ByteArray) {
    val message2 = response(responder.facade.processHandshake(initiator.peerID, message1, responder.privateKey, responder.publicKey))
    val message3 = response(initiator.facade.processHandshake(responder.peerID, message2, initiator.privateKey, initiator.publicKey))
    assertEquals(NoiseEncryptionFacade.HandshakeResult.Established(null), responder.facade.processHandshake(
        initiator.peerID, message3, responder.privateKey, responder.publicKey
    ))
}

private fun assertTraffic(sender: FallbackParty, receiver: FallbackParty, text: String) {
    val plaintext = text.encodeToByteArray()
    assertDecryptedBy(
        receiver.facade.decrypt(sender.peerID, assertNotNull(sender.facade.encrypt(receiver.peerID, plaintext))),
        NoiseEncryptionFacade.DecryptionVia.ESTABLISHED,
        text
    )
}

private fun assertDecryptedBy(
    result: NoiseEncryptionFacade.DecryptionResult?,
    via: NoiseEncryptionFacade.DecryptionVia,
    text: String
) {
    val decrypted = assertNotNull(result)
    assertEquals(via, decrypted.via)
    assertContentEquals(text.encodeToByteArray(), decrypted.plaintext)
}

private fun response(result: NoiseEncryptionFacade.HandshakeResult): ByteArray = when (result) {
    is NoiseEncryptionFacade.HandshakeResult.Response -> result.message
    is NoiseEncryptionFacade.HandshakeResult.Established -> assertNotNull(result.response)
    NoiseEncryptionFacade.HandshakeResult.Ignored, NoiseEncryptionFacade.HandshakeResult.RejectedIdentity -> error("expected handshake response, got $result")
}
