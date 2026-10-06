package com.bitchat.bluetooth.facade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NoiseInboundHandshakeLimitTest {

    @Test
    fun responderCandidatesStopAtTheGlobalInboundLimitAndResumeAfterOneIsAbandoned() {
        val local = InboundParty("1".repeat(64), maxInboundHandshakes = 2)
        val first = InboundParty("2".repeat(64))
        val second = InboundParty("3".repeat(64))
        val refused = InboundParty("4".repeat(64))

        opening(first, local)
        opening(second, local)
        assertEquals(2, local.facade.inboundHandshakeCount)

        // One opening, offered twice: a second initiateHandshake on the same facade returns nothing
        // while the first is still in flight.
        val refusedOpening = refused.openingFor(local)
        assertEquals(
            NoiseEncryptionFacade.HandshakeResult.Ignored,
            local.facade.processHandshake(refused.peerID, refusedOpening, local.privateKey, local.publicKey)
        )
        assertFalse(local.facade.hasCandidate(refused.peerID))
        assertEquals(2, local.facade.inboundHandshakeCount)

        local.facade.abandonHandshake(first.peerID)
        assertEquals(1, local.facade.inboundHandshakeCount)
        assertIs<NoiseEncryptionFacade.HandshakeResult.Response>(
            local.facade.processHandshake(refused.peerID, refusedOpening, local.privateKey, local.publicKey)
        )
        assertEquals(2, local.facade.inboundHandshakeCount)
    }

    @Test
    fun responderAdmissionsAreLimitedPerLinkButResumeAfterTheWindow() {
        val local = InboundParty("a".repeat(64), maxInboundHandshakes = 10, maxInboundHandshakesPerLink = 2)
        val first = InboundParty("b".repeat(64))
        val second = InboundParty("c".repeat(64))
        val refused = InboundParty("d".repeat(64))
        val otherLink = InboundParty("e".repeat(64))
        val afterWindow = InboundParty("f".repeat(64))
        val now = 10_000L

        opening(first, local, now, "a")
        opening(second, local, now, "a")
        assertEquals(
            NoiseEncryptionFacade.HandshakeResult.Ignored,
            local.facade.processHandshake(
                refused.peerID, refused.openingFor(local), local.privateKey, local.publicKey, now = now, link = "a"
            )
        )
        assertFalse(local.facade.hasCandidate(refused.peerID))
        assertEquals(2, local.facade.inboundHandshakeCount)

        opening(otherLink, local, now, "b")
        opening(afterWindow, local, now + 3_000L, "a")
        assertEquals(4, local.facade.inboundHandshakeCount)
    }

    @Test
    fun globalInboundLimitStillRefusesWhenLinksArePlentiful() {
        val local = InboundParty("1".repeat(64), maxInboundHandshakes = 2, maxInboundHandshakesPerLink = 100)
        val first = InboundParty("2".repeat(64))
        val second = InboundParty("3".repeat(64))
        val refused = InboundParty("4".repeat(64))

        opening(first, local, 0L, "a")
        opening(second, local, 0L, "b")
        assertEquals(
            NoiseEncryptionFacade.HandshakeResult.Ignored,
            local.facade.processHandshake(
                refused.peerID, refused.openingFor(local), local.privateKey, local.publicKey, now = 0L, link = "c"
            )
        )
        assertFalse(local.facade.hasCandidate(refused.peerID))
        assertEquals(2, local.facade.inboundHandshakeCount)
    }

    @Test
    fun staleLinkAdmissionRowsArePrunedWhenTheTableExceedsItsBound() {
        val local = InboundParty("5".repeat(64), maxInboundHandshakesPerLink = 8)
        repeat(100) { index ->
            // Every byte of the seed differs between peers: key clamping ignores some bits of the
            // first and last byte, and seeds that differ only there are the same identity.
            val remote = InboundParty((index + 16).toString(16).padStart(2, '0').repeat(32))
            opening(remote, local, 0L, "link-$index")
        }
        assertEquals(100, local.facade.trackedLinkCount)

        // A seed outside the range used above, or this would be one of those peers again.
        val later = InboundParty("f".repeat(64))
        opening(later, local, 3_001L, "later")

        assertEquals(1, local.facade.trackedLinkCount)
    }

    @Test
    fun anOpeningRefusedByTheTotalLimitDoesNotUseUpItsLinksAdmission() {
        // Two alive in total, three per link. The third opening is refused by the total, on a link
        // that has admitted nothing: when a place frees, that link must still have all three.
        val local = InboundParty("5".repeat(64), maxInboundHandshakes = 2, maxInboundHandshakesPerLink = 3)
        val first = InboundParty("11".repeat(32))
        val second = InboundParty("22".repeat(32))
        opening(first, local, 0L, "a")
        opening(second, local, 0L, "a")

        val waiting = listOf("33", "44", "77", "88").map { InboundParty(it.repeat(32)) }
        val openings = waiting.map { it.openingFor(local) }
        repeat(3) { attempt ->
            assertEquals(
                NoiseEncryptionFacade.HandshakeResult.Ignored,
                local.facade.processHandshake(
                    waiting[attempt].peerID, openings[attempt], local.privateKey, local.publicKey, now = 1L, link = "b"
                )
            )
        }

        local.facade.abandonHandshake(first.peerID)
        local.facade.abandonHandshake(second.peerID)
        // Had the three refusals been charged to link "b", it would be full now.
        assertIs<NoiseEncryptionFacade.HandshakeResult.Response>(
            local.facade.processHandshake(waiting[3].peerID, openings[3], local.privateKey, local.publicKey, now = 2L, link = "b")
        )
    }

    @Test
    fun promotionReleasesAnInboundPlace() {
        val local = InboundParty("5".repeat(64), maxInboundHandshakes = 1)
        val first = InboundParty("6".repeat(64))
        val second = InboundParty("7".repeat(64))

        val message1 = first.openingFor(local)
        val message2 = response(local.facade.processHandshake(first.peerID, message1, local.privateKey, local.publicKey))
        val message3 = response(first.facade.processHandshake(local.peerID, message2, first.privateKey, first.publicKey))
        assertEquals(
            NoiseEncryptionFacade.HandshakeResult.Established(null),
            local.facade.processHandshake(first.peerID, message3, local.privateKey, local.publicKey)
        )

        assertEquals(0, local.facade.inboundHandshakeCount)
        assertIs<NoiseEncryptionFacade.HandshakeResult.Response>(
            local.facade.processHandshake(second.peerID, second.openingFor(local), local.privateKey, local.publicKey)
        )
    }

    @Test
    fun ourInitiatorHandshakeIsNotSubjectToTheInboundLimit() {
        val local = InboundParty("8".repeat(64), maxInboundHandshakes = 1)
        val inbound = InboundParty("9".repeat(64))
        val outbound = InboundParty("a".repeat(64))

        opening(inbound, local)
        assertEquals(1, local.facade.inboundHandshakeCount)

        assertEquals(32, local.facade.initiateHandshake(outbound.peerID, local.privateKey, local.publicKey).size)
        assertTrue(local.facade.isHandshaking(outbound.peerID))
        assertEquals(1, local.facade.inboundHandshakeCount)
    }

    @Test
    fun establishedPeerCanRenegotiateAtTheInboundLimitWithoutTakingAPlace() {
        val local = InboundParty("b".repeat(64), maxInboundHandshakes = 1)
        val established = InboundParty("c".repeat(64))
        val filler = InboundParty("d".repeat(64))
        establish(established, local)
        opening(filler, local)
        assertEquals(1, local.facade.inboundHandshakeCount)

        val restarted = InboundParty("c".repeat(64))
        assertIs<NoiseEncryptionFacade.HandshakeResult.Response>(
            local.facade.processHandshake(
                established.peerID,
                restarted.openingFor(local),
                local.privateKey,
                local.publicKey
            )
        )
        assertEquals(1, local.facade.inboundHandshakeCount)
    }

    @Test
    fun yieldedCollisionDoesNotDestroyOurCandidateWhenTheInboundLimitIsFull() {
        val first = InboundParty("e".repeat(64))
        val second = InboundParty("f".repeat(64))
        val (remote, localBase) = if (first.peerID < second.peerID) first to second else second to first
        val local = InboundParty("f".repeat(64), maxInboundHandshakes = 10, maxInboundHandshakesPerLink = 1)
        // Recreate the larger identity with the limit while preserving the collision order.
        val collisionLocal = if (local.peerID == localBase.peerID) local else InboundParty(
            if (localBase === first) "e".repeat(64) else "f".repeat(64),
            maxInboundHandshakes = 10,
            maxInboundHandshakesPerLink = 1
        )
        val filler = InboundParty("0".repeat(64))
        opening(filler, collisionLocal, 0L, "full")
        collisionLocal.facade.initiateHandshake(remote.peerID, collisionLocal.privateKey, collisionLocal.publicKey)

        assertEquals(
            NoiseEncryptionFacade.HandshakeResult.Ignored,
            collisionLocal.facade.processHandshake(
                remote.peerID,
                remote.openingFor(collisionLocal),
                collisionLocal.privateKey,
                collisionLocal.publicKey,
                now = 0L,
                link = "full"
            )
        )
        assertTrue(collisionLocal.facade.isHandshaking(remote.peerID))
        assertEquals(1, collisionLocal.facade.inboundHandshakeCount)
    }

    private fun opening(remote: InboundParty, local: InboundParty, now: Long = 0L, link: String = "") {
        assertIs<NoiseEncryptionFacade.HandshakeResult.Response>(
            local.facade.processHandshake(remote.peerID, remote.openingFor(local), local.privateKey, local.publicKey, now, link)
        )
    }

    private fun establish(initiator: InboundParty, responder: InboundParty) {
        val message1 = initiator.openingFor(responder)
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

    private class InboundParty(
        seed: String,
        maxInboundHandshakes: Int = NoiseEncryptionFacade.MAX_INBOUND_HANDSHAKES,
        maxInboundHandshakesPerLink: Int = NoiseEncryptionFacade.MAX_INBOUND_HANDSHAKES_PER_LINK
    ) {
        private val crypto = CryptoSigningFacade(seed)
        val peerID = crypto.getIdentityFingerprint()
        val privateKey = crypto.getNoisePrivateKey()
        val publicKey = crypto.getNoisePublicKey()
        val facade = NoiseEncryptionFacade(
            peerID,
            maxInboundHandshakes = maxInboundHandshakes,
            maxInboundHandshakesPerLink = maxInboundHandshakesPerLink
        )

        fun openingFor(remote: InboundParty): ByteArray =
            facade.initiateHandshake(remote.peerID, privateKey, publicKey)
    }
}
