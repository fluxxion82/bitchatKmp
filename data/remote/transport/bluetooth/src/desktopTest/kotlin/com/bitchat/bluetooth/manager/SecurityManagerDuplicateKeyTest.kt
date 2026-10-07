package com.bitchat.bluetooth.manager

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityManagerDuplicateKeyTest {
    @Test
    fun nonAnnouncementsWithTheSameHeaderAndDifferentPayloadsAreBothAcceptedInEitherOrder() {
        listOf(
            byteArrayOf(1) to byteArrayOf(2),
            byteArrayOf(2) to byteArrayOf(1)
        ).forEach { (firstPayload, secondPayload) ->
            manager().use { fixture ->
                assertTrue(fixture.manager.validatePacket(packet(payload = firstPayload), PEER_ID))
                assertTrue(fixture.manager.validatePacket(packet(payload = secondPayload), PEER_ID))
            }
        }
    }

    @Test
    fun nonAnnouncementWithTheSamePayloadIsADuplicateWhenOnlyTtlDiffers() {
        manager().use { fixture ->
            val original = packet(payload = byteArrayOf(1))
            val relayed = original.copy(ttl = 0u)

            assertTrue(fixture.manager.validatePacket(original, PEER_ID))
            assertFalse(fixture.manager.validatePacket(original, PEER_ID))
            assertFalse(fixture.manager.validatePacket(relayed, PEER_ID))
        }
    }

    @Test
    fun nonAnnouncementPayloadsWithDifferentHeadersAreNotDuplicates() {
        manager().use { fixture ->
            val payload = byteArrayOf(1)

            assertTrue(fixture.manager.validatePacket(packet(payload = payload), PEER_ID))
            assertTrue(fixture.manager.validatePacket(packet(timestamp = 2u, payload = payload), PEER_ID))
            assertTrue(fixture.manager.validatePacket(packet(type = MessageType.FILE_TRANSFER, payload = payload), PEER_ID))
            assertTrue(fixture.manager.validatePacket(packet(payload = payload), ANOTHER_PEER_ID))
        }
    }

    @Test
    fun forgettingOnePayloadDoesNotForgetAnotherWithTheSameHeader() {
        manager().use { fixture ->
            val forged = packet(payload = byteArrayOf(1))
            val genuine = packet(payload = byteArrayOf(2))

            assertTrue(fixture.manager.validatePacket(forged, PEER_ID))
            assertTrue(fixture.manager.validatePacket(genuine, PEER_ID))
            fixture.manager.forgetPacket(genuine, PEER_ID)

            assertTrue(fixture.manager.validatePacket(genuine, PEER_ID))
            assertFalse(fixture.manager.validatePacket(forged, PEER_ID))
        }
    }

    @Test
    fun anAnnouncementWithAnotherPayloadDoesNotTakeTheRecordOfTheGenuineOne() {
        // The same key for every type: an announcement forged under a peer's id and timestamp must not
        // keep that peer's own announcement out for the length of the announcement window.
        manager().use { fixture ->
            val genuine = packet(type = MessageType.ANNOUNCE, payload = byteArrayOf(1))
            val forged = packet(type = MessageType.ANNOUNCE, payload = byteArrayOf(2))

            assertTrue(fixture.manager.validatePacket(forged, PEER_ID))
            assertTrue(fixture.manager.validatePacket(genuine, PEER_ID))
            assertFalse(fixture.manager.validatePacket(genuine, PEER_ID))
            assertFalse(fixture.manager.validatePacket(genuine.copy(ttl = 0u), PEER_ID))
        }
    }

    @Test
    fun aCopyAddressedToSomeoneElseDoesNotTakeTheRecordOfTheGenuinePacket() {
        // Same sender, timestamp, type and payload; only the recipient differs. Such a copy is not for
        // this device and is ignored, and the packet that IS for it must still get through afterwards.
        val mine = byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1)
        val someoneElse = byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2)
        listOf<Pair<ByteArray?, ByteArray?>>(someoneElse to mine, null to mine, mine to null, mine to someoneElse).forEach { (first, second) ->
            manager().use { fixture ->
                assertTrue(fixture.manager.validatePacket(packet(payload = byteArrayOf(1)).copy(recipientID = first), PEER_ID))
                assertTrue(fixture.manager.validatePacket(packet(payload = byteArrayOf(1)).copy(recipientID = second), PEER_ID))
                assertFalse(fixture.manager.validatePacket(packet(payload = byteArrayOf(1)).copy(recipientID = second), PEER_ID))
            }
        }
    }

    private fun manager(): Fixture {
        val crypto = CryptoSigningFacade("2".repeat(64))
        return Fixture(SecurityManager(NoiseEncryptionFacade(crypto.getIdentityFingerprint()), crypto, crypto.getIdentityFingerprint()))
    }

    private fun packet(
        type: MessageType = MessageType.NOISE_ENCRYPTED,
        timestamp: ULong = 1u,
        payload: ByteArray
    ) = BitchatPacket(
        type = type.value,
        senderID = byteArrayOf(1),
        timestamp = timestamp,
        payload = payload,
        ttl = 1u
    )

    private class Fixture(val manager: SecurityManager) : AutoCloseable {
        override fun close() = manager.shutdown()
    }

    private companion object {
        const val PEER_ID = "peer"
        const val ANOTHER_PEER_ID = "other-peer"
    }
}
