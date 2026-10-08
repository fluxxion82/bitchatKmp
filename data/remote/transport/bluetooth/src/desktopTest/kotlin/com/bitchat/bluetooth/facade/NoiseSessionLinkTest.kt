package com.bitchat.bluetooth.facade

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a session was made (the link its handshake's last message arrived on) is kept with the
 * session and read with it: what a session may be used for depends on it.
 */
class NoiseSessionLinkTest {

    @Test
    fun everySessionThatBecomesTheEstablishedOneHasATokenOfItsOwn() {
        val local = Party("b".repeat(64))
        val remote = Party("c".repeat(64))
        val other = Party("d".repeat(64))
        establish(local, remote, "radio", "radio")
        establish(local, other, "radio", "radio")
        val first = assertNotNull(local.facade.encryptNamingLink(remote.peerID, byteArrayOf(1)))
        val ofAnotherPeer = assertNotNull(local.facade.encryptNamingLink(other.peerID, byteArrayOf(1)))

        // The same peer starts over: another session, another token.
        establish(Party("c".repeat(64)), local, "radio", "bluetooth")
        val second = assertNotNull(local.facade.encryptNamingLink(remote.peerID, byteArrayOf(2)))

        assertEquals(3, setOf(first.sessionToken, ofAnotherPeer.sessionToken, second.sessionToken).size)
        assertEquals("bluetooth", second.sessionLink)
        // And it stays the session's own for as long as the session stands.
        assertEquals(second.sessionToken, assertNotNull(local.facade.encryptNamingLink(remote.peerID, byteArrayOf(3))).sessionToken)
    }

    @Test
    fun aPacketIsReadUnderTheTokenOfTheSessionThatReadIt() {
        val local = Party("d".repeat(64))
        val remote = Party("e".repeat(64))
        establish(local, remote, "radio", "radio")
        val old = assertNotNull(local.facade.encryptNamingLink(remote.peerID, byteArrayOf(1))).sessionToken
        val ofTheOldSession = assertNotNull(remote.facade.encrypt(local.peerID, byteArrayOf(3)))
        val alsoOfTheOldSession = assertNotNull(remote.facade.encrypt(local.peerID, byteArrayOf(5)))

        val restarted = Party("e".repeat(64))
        establish(restarted, local, "radio", "bluetooth")
        val fresh = assertNotNull(local.facade.encryptNamingLink(remote.peerID, byteArrayOf(2))).sessionToken

        // The session that was replaced still reads, under the token it always had...
        val readByTheOld = assertNotNull(local.facade.decrypt(remote.peerID, ofTheOldSession))
        assertEquals(NoiseEncryptionFacade.DecryptionVia.FALLBACK, readByTheOld.via)
        assertEquals(old, readByTheOld.sessionToken)
        // ...and the new one under its own.
        val ofTheNewSession = assertNotNull(restarted.facade.encrypt(local.peerID, byteArrayOf(4)))
        val readByTheNew = assertNotNull(local.facade.decrypt(remote.peerID, ofTheNewSession))
        assertEquals(NoiseEncryptionFacade.DecryptionVia.ESTABLISHED, readByTheNew.via)
        assertEquals(fresh, readByTheNew.sessionToken)
        // The peer was seen using the new session: the old one is gone, and nothing reads under its token.
        assertNull(local.facade.decrypt(remote.peerID, alsoOfTheOldSession))
    }

    @Test
    fun aSessionThatWasGivenUpReadsUnderItsOwnTokenBesideTheOneThatFollows() {
        val local = Party("a".repeat(64))
        val remote = Party("b".repeat(64))
        establish(remote, local, "radio", "radio")
        val token = assertNotNull(local.facade.encryptNamingLink(remote.peerID, byteArrayOf(1))).sessionToken
        val first = assertNotNull(remote.facade.encrypt(local.peerID, byteArrayOf(2)))
        val second = assertNotNull(remote.facade.encrypt(local.peerID, byteArrayOf(3)))

        // Given up: it only reads from now on, under the token it always had.
        local.facade.demote(remote.peerID)
        assertNull(local.facade.establishedSessionLink(remote.peerID))
        assertEquals(token, assertNotNull(local.facade.decrypt(remote.peerID, first)).sessionToken)

        // A new session beside it has a token of its own, and the old one keeps its.
        val restarted = Party("b".repeat(64))
        establish(restarted, local, "radio", "radio")
        val fresh = assertNotNull(local.facade.encryptNamingLink(remote.peerID, byteArrayOf(4))).sessionToken
        assertTrue(fresh != token)
        assertEquals(token, assertNotNull(local.facade.decrypt(remote.peerID, second)).sessionToken)
    }

    private fun establish(initiator: Party, responder: Party, initiatorHears: String, responderHears: String) {
        val message1 = initiator.facade.initiateHandshake(responder.peerID, initiator.privateKey, initiator.publicKey)
        val message2 = response(responder.facade.processHandshake(initiator.peerID, message1, responder.privateKey, responder.publicKey, link = responderHears))
        val message3 = response(initiator.facade.processHandshake(responder.peerID, message2, initiator.privateKey, initiator.publicKey, link = initiatorHears))
        responder.facade.processHandshake(initiator.peerID, message3, responder.privateKey, responder.publicKey, link = responderHears)
    }

    private fun response(result: NoiseEncryptionFacade.HandshakeResult): ByteArray = when (result) {
        is NoiseEncryptionFacade.HandshakeResult.Response -> result.message
        is NoiseEncryptionFacade.HandshakeResult.Established -> result.response ?: error("expected response")
        NoiseEncryptionFacade.HandshakeResult.Ignored,
        NoiseEncryptionFacade.HandshakeResult.RejectedIdentity -> error("expected handshake response")
    }

    private class Party(seed: String) {
        private val crypto = CryptoSigningFacade(seed)
        val peerID = crypto.getIdentityFingerprint()
        val privateKey = crypto.getNoisePrivateKey()
        val publicKey = crypto.getNoisePublicKey()
        val facade = NoiseEncryptionFacade(peerID)
    }
}
