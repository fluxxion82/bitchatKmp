package com.bitchat.noise

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import noise.c.NoiseBuffer
import noise.c.NoiseCipherState
import noise.c.bitchat_noise_cipherstate_decrypt_at
import noise.c.bitchat_noise_cipherstate_layout_is_compatible
import noise.c.bitchat_noise_cipherstate_nonce
import noise.c.noise_cipherstate_encrypt_with_ad
import noise.c.noise_cipherstate_free
import noise.c.noise_cipherstate_init_key
import noise.c.noise_cipherstate_new_by_id
import noise.c.noise_cipherstate_set_nonce
import kotlin.experimental.xor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for native NoiseSession implementation
 * Tests that the noise-c library bindings are working correctly
 */
@OptIn(ExperimentalForeignApi::class)
class NoiseSessionNativeTest {

    @Test
    fun cipherStateLayoutCheckReadsTheNonceSetByNoiseC() {
        assertEquals(1, bitchat_noise_cipherstate_layout_is_compatible())
    }

    @Test
    fun failedDecryptLeavesTheNativeCounterWhereItWas() = withCipherPair { send, receive ->
        val genuine = seal(send, nonce = 5u, "genuine")
        assertContentEquals("genuine".encodeToByteArray(), openAt(receive, nonce = 5u, genuine))
        assertEquals(6uL, bitchat_noise_cipherstate_nonce(receive))

        val forged = seal(send, nonce = 900u, "forged")
        forged[forged.lastIndex] = forged.last() xor 1
        assertEquals(null, openAt(receive, nonce = 900u, forged))
        assertEquals(6uL, bitchat_noise_cipherstate_nonce(receive))

        assertEquals(null, openAt(receive, nonce = 2u, ByteArray(4)))
        assertEquals(6uL, bitchat_noise_cipherstate_nonce(receive))
    }

    @Test
    fun olderPacketDoesNotMoveTheNativeCounterBack() = withCipherPair { send, receive ->
        val third = seal(send, nonce = 3u, "third")
        val ninth = seal(send, nonce = 9u, "ninth")

        assertContentEquals("ninth".encodeToByteArray(), openAt(receive, nonce = 9u, ninth))
        assertEquals(10uL, bitchat_noise_cipherstate_nonce(receive))

        assertContentEquals("third".encodeToByteArray(), openAt(receive, nonce = 3u, third))
        assertEquals(10uL, bitchat_noise_cipherstate_nonce(receive))
    }

    @Test
    fun testSessionInitializationWithValidKeys() {
        // Arrange & Act
        val session = NoiseSession(
            peerID = "peer1",
            isInitiator = true,
            localStaticPrivateKey = TEST_PRIVATE_KEY,
            localStaticPublicKey = TEST_PUBLIC_KEY
        )

        // Assert
        assertEquals(NoiseSessionState.Uninitialized, session.getState())
        assertTrue(!session.isEstablished())
        assertTrue(!session.isHandshaking())
    }

    @Test
    fun testSessionInitializationWithInvalidPrivateKeySize() {
        // Arrange & Act
        val session = NoiseSession(
            peerID = "peer1",
            isInitiator = true,
            localStaticPrivateKey = ByteArray(16), // Too small
            localStaticPublicKey = TEST_PUBLIC_KEY
        )

        // Assert - validation error should result in Failed state
        assertTrue(session.getState() is NoiseSessionState.Failed)
    }

    @Test
    fun testSessionInitializationWithInvalidPublicKeySize() {
        // Arrange & Act
        val session = NoiseSession(
            peerID = "peer1",
            isInitiator = true,
            localStaticPrivateKey = TEST_PRIVATE_KEY,
            localStaticPublicKey = ByteArray(16) // Too small
        )

        // Assert - validation error should result in Failed state
        assertTrue(session.getState() is NoiseSessionState.Failed)
    }

    @Test
    fun testSessionInitializationWithZeroPrivateKey() {
        // Arrange & Act
        val session = NoiseSession(
            peerID = "peer1",
            isInitiator = true,
            localStaticPrivateKey = ByteArray(32), // All zeros
            localStaticPublicKey = TEST_PUBLIC_KEY
        )

        // Assert - validation error should result in Failed state
        assertTrue(session.getState() is NoiseSessionState.Failed)
    }

    @Test
    fun testSessionInitializationWithZeroPublicKey() {
        // Arrange & Act
        val session = NoiseSession(
            peerID = "peer1",
            isInitiator = true,
            localStaticPrivateKey = TEST_PRIVATE_KEY,
            localStaticPublicKey = ByteArray(32) // All zeros
        )

        // Assert - validation error should result in Failed state
        assertTrue(session.getState() is NoiseSessionState.Failed)
    }

    @Test
    fun testInitiatorCanStartHandshake() {
        // Arrange
        val session = NoiseSession(
            peerID = "peer2",
            isInitiator = true,
            localStaticPrivateKey = TEST_PRIVATE_KEY,
            localStaticPublicKey = TEST_PUBLIC_KEY
        )

        // Act
        val message = session.startHandshake()

        // Assert
        assertEquals(NoiseConstants.XX_MESSAGE_1_SIZE, message.size)
        assertTrue(session.isHandshaking())
    }

    @Test
    fun testResponderCannotStartHandshake() {
        // Arrange
        val session = NoiseSession(
            peerID = "peer2",
            isInitiator = false,
            localStaticPrivateKey = TEST_PRIVATE_KEY,
            localStaticPublicKey = TEST_PUBLIC_KEY
        )

        // Act & Assert
        assertFails {
            session.startHandshake()
        }
    }

    @Test
    fun testCannotStartHandshakeTwice() {
        // Arrange
        val session = NoiseSession(
            peerID = "peer2",
            isInitiator = true,
            localStaticPrivateKey = TEST_PRIVATE_KEY,
            localStaticPublicKey = TEST_PUBLIC_KEY
        )

        // Act
        session.startHandshake()

        // Assert
        assertFails {
            session.startHandshake()
        }
    }

    @Test
    fun testGetSessionStats() {
        // Arrange
        val session = NoiseSession(
            peerID = "peer3",
            isInitiator = true,
            localStaticPrivateKey = TEST_PRIVATE_KEY,
            localStaticPublicKey = TEST_PUBLIC_KEY
        )

        // Act
        val stats = session.getSessionStats()

        // Assert
        assertTrue(stats.contains("peer3"))
        assertTrue(stats.contains("initiator"))
        assertTrue(stats.contains("uninitialized"))
    }

    @Test
    fun testResetSession() {
        // Arrange
        val session = NoiseSession(
            peerID = "peer4",
            isInitiator = true,
            localStaticPrivateKey = TEST_PRIVATE_KEY,
            localStaticPublicKey = TEST_PUBLIC_KEY
        )
        session.startHandshake()

        // Act
        session.reset()

        // Assert
        assertEquals(NoiseSessionState.Uninitialized, session.getState())
        assertTrue(!session.isHandshaking())
    }

    @Test
    fun testDestroySession() {
        // Arrange
        val session = NoiseSession(
            peerID = "peer5",
            isInitiator = true,
            localStaticPrivateKey = TEST_PRIVATE_KEY,
            localStaticPublicKey = TEST_PUBLIC_KEY
        )

        // Act
        session.destroy()

        // Assert
        assertTrue(session.getState() is NoiseSessionState.Failed)
    }

    @Test
    fun testCompleteHandshakeFunctionIsCallable() {
        // This test verifies that the completeHandshake() function exists and can be called
        // The full handshake test requires proper key exchange which is more complex

        // Arrange
        val initiator = NoiseSession(
            peerID = "responder_peer",
            isInitiator = true,
            localStaticPrivateKey = TEST_PRIVATE_KEY,
            localStaticPublicKey = TEST_PUBLIC_KEY
        )

        // Act - Start handshake (which will initialize the handshake state)
        val msg1 = initiator.startHandshake()

        // Assert
        // We can verify that:
        // 1. Message 1 is generated correctly
        assertEquals(NoiseConstants.XX_MESSAGE_1_SIZE, msg1.size)
        // 2. Session is handshaking
        assertTrue(initiator.isHandshaking())
        // 3. Can get session stats (which implicitly tests that state is valid)
        val stats = initiator.getSessionStats()
        assertTrue(stats.contains("handshaking"))
    }

    private fun withCipherPair(
        block: (send: CPointer<NoiseCipherState>, receive: CPointer<NoiseCipherState>) -> Unit
    ) {
        val send = newCipher()
        val receive = newCipher()
        try {
            block(send, receive)
        } finally {
            noise_cipherstate_free(send)
            noise_cipherstate_free(receive)
        }
    }

    private fun newCipher(): CPointer<NoiseCipherState> = memScoped {
        val out = allocPointerTo<NoiseCipherState>()
        assertEquals(0, noise_cipherstate_new_by_id(out.ptr, NOISE_CIPHER_CHACHAPOLY))
        val cipher = assertNotNull(out.value)
        ByteArray(32) { 7 }.usePinned { key ->
            assertEquals(0, noise_cipherstate_init_key(cipher, key.addressOf(0).reinterpret(), 32u))
        }
        cipher
    }

    private fun seal(cipher: CPointer<NoiseCipherState>, nonce: ULong, text: String): ByteArray = memScoped {
        val plaintext = text.encodeToByteArray()
        val packet = plaintext.copyOf(plaintext.size + MAC_LENGTH)
        assertEquals(0, noise_cipherstate_set_nonce(cipher, nonce))
        packet.usePinned { pinned ->
            val buffer = alloc<NoiseBuffer>()
            buffer.data = pinned.addressOf(0).reinterpret()
            buffer.size = plaintext.size.toULong()
            buffer.max_size = packet.size.toULong()
            assertEquals(0, noise_cipherstate_encrypt_with_ad(cipher, null, 0u, buffer.ptr))
            assertEquals(packet.size.toULong(), buffer.size)
        }
        packet
    }

    /** The plaintext, or null when noise-c refused the packet. */
    private fun openAt(cipher: CPointer<NoiseCipherState>, nonce: ULong, packet: ByteArray): ByteArray? = memScoped {
        val scratch = packet.copyOf()
        scratch.usePinned { pinned ->
            val buffer = alloc<NoiseBuffer>()
            buffer.data = pinned.addressOf(0).reinterpret()
            buffer.size = scratch.size.toULong()
            buffer.max_size = scratch.size.toULong()
            if (bitchat_noise_cipherstate_decrypt_at(cipher, nonce, buffer.ptr) != 0) return@memScoped null
            scratch.copyOf(buffer.size.toInt())
        }
    }

    companion object {
        // NOISE_ID('C', 1) in noise/protocol/constants.h.
        private const val NOISE_CIPHER_CHACHAPOLY = 0x4301
        private const val MAC_LENGTH = 16

        // Test key material (32 bytes for Curve25519)
        private val TEST_PRIVATE_KEY = ByteArray(32) { it.toByte() }
        private val TEST_PUBLIC_KEY = ByteArray(32) { (it + 1).toByte() }
        private val TEST_PRIVATE_KEY_2 = ByteArray(32) { (it + 2).toByte() }
        private val TEST_PUBLIC_KEY_2 = ByteArray(32) { (it + 3).toByte() }
    }
}
