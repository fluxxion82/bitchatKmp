package com.bitchat.bluetooth.facade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NoiseSessionLimitTest {

    @Test
    fun leastRecentlyUsedSessionIsEvictedAfterTheLimitIsExceeded() {
        val local = SessionParty("1".repeat(64), maxSessions = 2)
        val first = SessionParty("2".repeat(64))
        val second = SessionParty("3".repeat(64))
        val third = SessionParty("4".repeat(64))

        establish(first, local)
        establish(second, local)
        establish(third, local)

        assertEquals(2, local.facade.sessionCount)
        assertFalse(local.facade.hasEstablishedSession(first.peerID))
        assertFalse(local.facade.hasValidatedSession(first.peerID))
        assertTrue(local.facade.hasEstablishedSession(second.peerID))
        assertTrue(local.facade.hasEstablishedSession(third.peerID))
    }

    @Test
    fun successfulDecryptMakesASessionMostRecentlyUsed() {
        val local = SessionParty("5".repeat(64), maxSessions = 2)
        val first = SessionParty("6".repeat(64))
        val second = SessionParty("7".repeat(64))
        val third = SessionParty("8".repeat(64))
        establish(first, local)
        establish(second, local)

        val ciphertext = assertNotNull(first.facade.encrypt(local.peerID, "refresh".encodeToByteArray()))
        assertNotNull(local.facade.decrypt(first.peerID, ciphertext))
        establish(third, local)

        assertTrue(local.facade.hasEstablishedSession(first.peerID))
        assertFalse(local.facade.hasEstablishedSession(second.peerID))
        assertTrue(local.facade.hasEstablishedSession(third.peerID))
    }

    @Test
    fun newlyPromotedSessionIsNeverEvictedEvenAtOneSession() {
        val local = SessionParty("9".repeat(64), maxSessions = 1)
        val first = SessionParty("a".repeat(64))
        val second = SessionParty("b".repeat(64))

        establish(first, local)
        establish(second, local)

        assertEquals(1, local.facade.sessionCount)
        assertFalse(local.facade.hasEstablishedSession(first.peerID))
        assertTrue(local.facade.hasEstablishedSession(second.peerID))
    }

    @Test
    fun aSessionWithAPeerTheUserChoseOutlastsNewerSessionsNobodyChose() {
        val local = SessionParty("1".repeat(64), maxSessions = 2)
        val chosen = SessionParty("2".repeat(64))
        val stranger = SessionParty("3".repeat(64))
        val minted = SessionParty("4".repeat(64))
        val another = SessionParty("5".repeat(64))

        establish(chosen, local)
        local.facade.markChosenByUser(chosen.peerID)
        establish(stranger, local)
        // The chosen session is the least recently used from here on, and still not the one that goes.
        establish(minted, local)
        assertTrue(local.facade.hasEstablishedSession(chosen.peerID))
        assertFalse(local.facade.hasEstablishedSession(stranger.peerID))

        establish(another, local)
        assertTrue(local.facade.hasEstablishedSession(chosen.peerID))
        assertFalse(local.facade.hasEstablishedSession(minted.peerID))
        assertTrue(local.facade.hasEstablishedSession(another.peerID))
        assertEquals(2, local.facade.sessionCount)
    }

    @Test
    fun whenEverySessionIsWithAChosenPeerTheLeastRecentlyUsedOfThemGoes() {
        val local = SessionParty("6".repeat(64), maxSessions = 2)
        val first = SessionParty("7".repeat(64))
        val second = SessionParty("8".repeat(64))
        val third = SessionParty("9".repeat(64))
        listOf(first, second, third).forEach { local.facade.markChosenByUser(it.peerID) }

        establish(first, local)
        establish(second, local)
        establish(third, local)

        assertFalse(local.facade.hasEstablishedSession(first.peerID))
        assertTrue(local.facade.hasEstablishedSession(second.peerID))
        assertTrue(local.facade.hasEstablishedSession(third.peerID))
    }

    @Test
    fun aSessionPickedToGoIsLookedAtAgainBeforeItIsDestroyed() {
        val local = SessionParty("a".repeat(64), maxSessions = 2)
        val first = SessionParty("b".repeat(64))
        val second = SessionParty("c".repeat(64))
        val third = SessionParty("d".repeat(64))
        establish(first, local)
        establish(second, local)

        // Between being picked and being destroyed, the user writes to the picked peer.
        val picked = mutableListOf<String>()
        local.facade.beforePushingOut = { peerID ->
            picked += peerID
            if (picked.size == 1) local.facade.markChosenByUser(peerID)
        }
        establish(third, local)

        assertEquals(listOf(first.peerID, second.peerID), picked)
        assertTrue(local.facade.hasEstablishedSession(first.peerID))
        assertFalse(local.facade.hasEstablishedSession(second.peerID))
        assertTrue(local.facade.hasEstablishedSession(third.peerID))
        assertEquals(2, local.facade.sessionCount)
    }

    @Test
    fun choicesWithNoSessionAreForgottenBeforeAChoiceThatProtectsOne() {
        // Room for two sessions and (by default) four choices.
        val local = SessionParty("1".repeat(64), maxSessions = 2)
        val kept = SessionParty("2".repeat(64))
        val stranger = SessionParty("3".repeat(64))
        val minted = SessionParty("4".repeat(64))
        establish(kept, local)
        local.facade.markChosenByUser(kept.peerID)
        establish(stranger, local)

        // The user asks for many peers that are never reached. "kept" is the oldest choice by far.
        repeat(20) { local.facade.markChosenByUser("unreachable-$it") }
        assertTrue(local.facade.isChosenByUser(kept.peerID))

        establish(minted, local)
        assertTrue(local.facade.hasEstablishedSession(kept.peerID))
        assertFalse(local.facade.hasEstablishedSession(stranger.peerID))
    }

    @Test
    fun aNewcomerNobodyChoseIsTheOneRefusedWhenEveryOtherSessionIsChosen() {
        val local = SessionParty("1".repeat(64), maxSessions = 2)
        val first = SessionParty("2".repeat(64))
        val second = SessionParty("3".repeat(64))
        val newcomer = SessionParty("4".repeat(64))
        establish(first, local)
        establish(second, local)
        local.facade.markChosenByUser(first.peerID)
        local.facade.markChosenByUser(second.peerID)

        val message1 = newcomer.facade.initiateHandshake(local.peerID, newcomer.privateKey, newcomer.publicKey)
        val message2 = response(local.facade.processHandshake(newcomer.peerID, message1, local.privateKey, local.publicKey))
        val message3 = response(newcomer.facade.processHandshake(local.peerID, message2, newcomer.privateKey, newcomer.publicKey))
        val result = local.facade.processHandshake(newcomer.peerID, message3, local.privateKey, local.publicKey)

        // The handshake completed and still nothing says a session exists.
        assertEquals(NoiseEncryptionFacade.HandshakeResult.Ignored, result)
        assertFalse(local.facade.hasValidatedSession(newcomer.peerID))
        assertFalse(local.facade.hasCandidate(newcomer.peerID))
        assertTrue(local.facade.hasEstablishedSession(first.peerID))
        assertTrue(local.facade.hasEstablishedSession(second.peerID))
        assertEquals(2, local.facade.sessionCount)
    }

    @Test
    fun removingASessionFreesItsSlot() {
        val local = SessionParty("c".repeat(64), maxSessions = 1)
        val remote = SessionParty("d".repeat(64))
        establish(remote, local)

        local.facade.removeSession(remote.peerID)

        assertEquals(0, local.facade.sessionCount)
    }

    @Test
    fun renegotiationWithAFallbackStillCountsAsOneSession() {
        val local = SessionParty("e".repeat(64), maxSessions = 2)
        val remote = SessionParty("f".repeat(64))
        establish(remote, local)

        val restarted = SessionParty("f".repeat(64))
        establish(restarted, local)

        assertTrue(local.facade.hasEstablishedSession(remote.peerID))
        assertEquals(1, local.facade.sessionCount)
    }

    private fun establish(initiator: SessionParty, responder: SessionParty) {
        val message1 = initiator.facade.initiateHandshake(responder.peerID, initiator.privateKey, initiator.publicKey)
        val message2 = response(responder.facade.processHandshake(initiator.peerID, message1, responder.privateKey, responder.publicKey))
        val message3 = response(initiator.facade.processHandshake(responder.peerID, message2, initiator.privateKey, initiator.publicKey))
        responder.facade.processHandshake(initiator.peerID, message3, responder.privateKey, responder.publicKey)
    }

    private fun response(result: NoiseEncryptionFacade.HandshakeResult): ByteArray = when (result) {
        is NoiseEncryptionFacade.HandshakeResult.Response -> result.message
        is NoiseEncryptionFacade.HandshakeResult.Established -> result.response ?: error("expected response")
        NoiseEncryptionFacade.HandshakeResult.Ignored,
        NoiseEncryptionFacade.HandshakeResult.RejectedIdentity -> error("expected handshake response")
    }

    private class SessionParty(seed: String, maxSessions: Int = NoiseEncryptionFacade.MAX_NOISE_SESSIONS) {
        private val crypto = CryptoSigningFacade(seed)
        val peerID = crypto.getIdentityFingerprint()
        val privateKey = crypto.getNoisePrivateKey()
        val publicKey = crypto.getNoisePublicKey()
        val facade = NoiseEncryptionFacade(peerID, maxSessions = maxSessions)
    }
}
