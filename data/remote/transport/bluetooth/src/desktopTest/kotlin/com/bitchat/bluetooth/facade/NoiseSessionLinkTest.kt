package com.bitchat.bluetooth.facade

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Where a session was made (the link its handshake's last message arrived on) is kept with the
 * session and read with it: what a session may be used for depends on it.
 */
class NoiseSessionLinkTest {

    @Test
    fun withoutAnEstablishedSessionThereIsNoLink() {
        val local = Party("1".repeat(64))
        val remote = Party("2".repeat(64))
        assertNull(local.facade.establishedSessionLink(remote.peerID))

        // Nor while a handshake is only in flight.
        local.facade.initiateHandshake(remote.peerID, local.privateKey, local.publicKey)
        assertNull(local.facade.establishedSessionLink(remote.peerID))
        assertNull(local.facade.encryptNamingLink(remote.peerID, byteArrayOf(1)))
    }

    @Test
    fun aSessionIsKnownByTheLinkItsHandshakeCompletedOnOnEitherSide() {
        val initiator = Party("3".repeat(64))
        val responder = Party("4".repeat(64))
        // The initiator's session stands when message 2 arrives, the responder's when message 3 does.
        establish(initiator, responder, initiatorHears = "first", responderHears = "second")

        assertEquals("first", initiator.facade.establishedSessionLink(responder.peerID))
        assertEquals("second", responder.facade.establishedSessionLink(initiator.peerID))
    }

    @Test
    fun aSessionMadeAgainIsKnownByTheLinkOfTheNewHandshakeAtOnce() {
        val local = Party("5".repeat(64))
        val remote = Party("6".repeat(64))
        establish(remote, local, initiatorHears = "radio", responderHears = "radio")
        assertEquals("radio", local.facade.establishedSessionLink(remote.peerID))

        // The peer starts over and this time its messages arrive on another link. Until the last of
        // them the standing session is the old one, with its link; with the last, the new one and its.
        val restarted = Party("6".repeat(64))
        val message1 = restarted.facade.initiateHandshake(local.peerID, restarted.privateKey, restarted.publicKey)
        val message2 = response(local.facade.processHandshake(remote.peerID, message1, local.privateKey, local.publicKey, link = "bluetooth"))
        assertEquals("radio", local.facade.establishedSessionLink(remote.peerID))
        val message3 = response(restarted.facade.processHandshake(local.peerID, message2, restarted.privateKey, restarted.publicKey, link = "bluetooth"))
        local.facade.processHandshake(remote.peerID, message3, local.privateKey, local.publicKey, link = "bluetooth")

        assertEquals("bluetooth", local.facade.establishedSessionLink(remote.peerID))
    }

    @Test
    fun encryptionSaysWhereTheSessionItUsedWasMade() {
        val local = Party("7".repeat(64))
        val remote = Party("8".repeat(64))
        establish(local, remote, initiatorHears = "radio", responderHears = "bluetooth")

        val fromLocal = assertNotNull(local.facade.encryptNamingLink(remote.peerID, "one".encodeToByteArray()))
        assertEquals("radio", fromLocal.sessionLink)
        assertContentEquals("one".encodeToByteArray(), assertNotNull(remote.facade.decrypt(local.peerID, fromLocal.bytes)).plaintext)
        val fromRemote = assertNotNull(remote.facade.encryptNamingLink(local.peerID, "two".encodeToByteArray()))
        assertEquals("bluetooth", fromRemote.sessionLink)
        // The plain form is the same encryption without the word.
        assertNotNull(local.facade.decrypt(remote.peerID, assertNotNull(remote.facade.encrypt(local.peerID, "three".encodeToByteArray()))))
    }

    @Test
    fun aSessionThatWasGivenUpHasNoLinkAndNeitherHasItsFallback() {
        val local = Party("9".repeat(64))
        val remote = Party("a".repeat(64))
        establish(remote, local, initiatorHears = "radio", responderHears = "radio")

        local.facade.demote(remote.peerID)

        assertNull(local.facade.establishedSessionLink(remote.peerID))
        assertNull(local.facade.encryptNamingLink(remote.peerID, byteArrayOf(1)))
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
