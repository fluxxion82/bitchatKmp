package com.bitchat.noise

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.random.Random

class NoiseSessionTransactionalReceiveTest {

    @Test
    fun forgedHighNonceAndForgedTagDoNotPreventLaterMessages() {
        assertForgedPacketDoesNotAdvanceReceiveState { packet ->
            packet.copyOf().also { ReplayProtection.nonceToBytes(500).copyInto(it, 0) }
        }
        assertForgedPacketDoesNotAdvanceReceiveState { packet ->
            packet.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        }
    }

    @Test
    fun forgedNonceBeyondReplayWindowDoesNotPreventLaterMessages() {
        val (sender, receiver) = establishedSessions()
        val first = sender.encrypt("first".encodeToByteArray())
        val forged = first.copyOf().also {
            ReplayProtection.nonceToBytes(100_000).copyInto(it, 0)
        }

        assertFails { receiver.decrypt(forged) }
        assertContentEquals("first".encodeToByteArray(), receiver.decrypt(first))

        val second = sender.encrypt("second".encodeToByteArray())
        assertContentEquals("second".encodeToByteArray(), receiver.decrypt(second))
    }

    @Test
    fun validPacketsWithinTheReplayWindowDecryptOutOfOrder() {
        val (sender, receiver) = establishedSessions()
        val first = sender.encrypt("first".encodeToByteArray())
        val second = sender.encrypt("second".encodeToByteArray())
        val third = sender.encrypt("third".encodeToByteArray())

        assertContentEquals("third".encodeToByteArray(), receiver.decrypt(third))
        assertContentEquals("first".encodeToByteArray(), receiver.decrypt(first))
        assertContentEquals("second".encodeToByteArray(), receiver.decrypt(second))
    }

    @Test
    fun replayDoesNotPreventTheNextPacket() {
        val (sender, receiver) = establishedSessions()
        val first = sender.encrypt("first".encodeToByteArray())
        val second = sender.encrypt("second".encodeToByteArray())

        assertContentEquals("first".encodeToByteArray(), receiver.decrypt(first))
        assertFails { receiver.decrypt(first) }
        assertContentEquals("second".encodeToByteArray(), receiver.decrypt(second))
    }

    @Test
    fun forgedPacketsInterleavedWithGenuinePacketsDoNotAdvanceReceiveState() {
        val (sender, receiver) = establishedSessions()
        val random = Random(0)

        repeat(200) { index ->
            val plaintext = "message-$index".encodeToByteArray()
            val genuine = sender.encrypt(plaintext)
            val forged = genuine.copyOf().also {
                ReplayProtection.nonceToBytes(10_000L + random.nextInt(1_000_000)).copyInto(it, 0)
            }

            assertFails { receiver.decrypt(forged) }
            assertContentEquals(plaintext, receiver.decrypt(genuine))
        }
    }

    private fun assertForgedPacketDoesNotAdvanceReceiveState(
        forge: (ByteArray) -> ByteArray
    ) {
        val (sender, receiver) = establishedSessions()
        val first = sender.encrypt("first".encodeToByteArray())

        assertFails { receiver.decrypt(forge(first)) }
        assertContentEquals("first".encodeToByteArray(), receiver.decrypt(first))

        val second = sender.encrypt("second".encodeToByteArray())
        assertContentEquals("second".encodeToByteArray(), receiver.decrypt(second))
    }

    private fun establishedSessions(): Pair<NoiseSession, NoiseSession> {
        val initiator = NoiseSession(
            peerID = "responder",
            isInitiator = true,
            localStaticPrivateKey = INITIATOR_PRIVATE_KEY,
            localStaticPublicKey = INITIATOR_PUBLIC_KEY
        )
        val responder = NoiseSession(
            peerID = "initiator",
            isInitiator = false,
            localStaticPrivateKey = RESPONDER_PRIVATE_KEY,
            localStaticPublicKey = RESPONDER_PUBLIC_KEY
        )

        val message1 = initiator.startHandshake()
        val message2 = requireNotNull(responder.processHandshakeMessage(message1))
        val message3 = requireNotNull(initiator.processHandshakeMessage(message2))
        responder.processHandshakeMessage(message3)

        assertTrue(initiator.isEstablished())
        assertTrue(responder.isEstablished())
        return initiator to responder
    }

    private companion object {
        val INITIATOR_PRIVATE_KEY = ByteArray(32) { (it + 1).toByte() }
        val INITIATOR_PUBLIC_KEY = ByteArray(32) { (it + 2).toByte() }
        val RESPONDER_PRIVATE_KEY = ByteArray(32) { (it + 3).toByte() }
        val RESPONDER_PUBLIC_KEY = ByteArray(32) { (it + 4).toByte() }
    }
}
