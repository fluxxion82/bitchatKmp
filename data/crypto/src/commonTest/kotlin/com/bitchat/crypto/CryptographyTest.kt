package com.bitchat.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CryptographyTest {
    @Test
    fun generateKeyPair_returnsValidKeys() {
        val (privateKeyHex, publicKeyHex) = Cryptography.generateKeyPair()

        assertEquals(64, privateKeyHex.length)
        assertEquals(64, publicKeyHex.length)
        assertTrue(Cryptography.isValidPrivateKey(privateKeyHex))
        assertTrue(Cryptography.isValidPublicKey(publicKeyHex))
    }

    @Test
    fun derivePublicKey_matchesKnownVector() {
        val privateKeyHex = "0000000000000000000000000000000000000000000000000000000000000001"
        val expectedPublicKeyHex =
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

        val derived = Cryptography.derivePublicKey(privateKeyHex)

        assertEquals(expectedPublicKeyHex, derived)
    }

    @Test
    fun deriveNIP44Key_matchesHKDFVector() {
        val sharedSecret = ByteArray(32) { it.toByte() }
        val expectedHex = "06df0a78a2319320fa904694a17faa7e98d594dc3b027422428134afe063482c"

        val derived = Cryptography.deriveNIP44Key(sharedSecret)

        assertEquals(expectedHex, derived.toHexString())
    }

    @Test
    fun encryptDecryptNIP44_roundTrip() {
        val senderPrivateKeyHex = "0000000000000000000000000000000000000000000000000000000000000001"
        val recipientPrivateKeyHex = "0000000000000000000000000000000000000000000000000000000000000002"
        val senderPublicKeyHex = Cryptography.derivePublicKey(senderPrivateKeyHex)
        val recipientPublicKeyHex = Cryptography.derivePublicKey(recipientPrivateKeyHex)
        val plaintext = "hello nip44"

        val ciphertext = Cryptography.encryptNIP44(
            plaintext = plaintext,
            recipientPublicKeyHex = recipientPublicKeyHex,
            senderPrivateKeyHex = senderPrivateKeyHex
        )

        assertTrue(ciphertext.startsWith("v2:"))

        val decrypted = Cryptography.decryptNIP44(
            ciphertext = ciphertext,
            senderPublicKeyHex = senderPublicKeyHex,
            recipientPrivateKeyHex = recipientPrivateKeyHex
        )

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun encryptDecryptNIP44_oneCharacterPlaintextRoundTrip() {
        val senderPrivateKeyHex = "0000000000000000000000000000000000000000000000000000000000000001"
        val recipientPrivateKeyHex = "0000000000000000000000000000000000000000000000000000000000000002"
        val senderPublicKeyHex = Cryptography.derivePublicKey(senderPrivateKeyHex)
        val recipientPublicKeyHex = Cryptography.derivePublicKey(recipientPrivateKeyHex)
        val plaintext = "x"

        val ciphertext = Cryptography.encryptNIP44(
            plaintext = plaintext,
            recipientPublicKeyHex = recipientPublicKeyHex,
            senderPrivateKeyHex = senderPrivateKeyHex
        )
        val decrypted = Cryptography.decryptNIP44(
            ciphertext = ciphertext,
            senderPublicKeyHex = senderPublicKeyHex,
            recipientPrivateKeyHex = recipientPrivateKeyHex
        )

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun randomizeTimestampUpToPast_withinRange() {
        val baseline = Cryptography.randomizeTimestampUpToPast(maxPastSeconds = 0)
        val randomized = Cryptography.randomizeTimestampUpToPast(maxPastSeconds = 60)

        assertTrue(randomized <= baseline)
        assertTrue(randomized >= baseline - 65)
    }

    @Test
    fun secureRandomBytes_returnsExactlyTheRequestedLength() {
        for (size in listOf(0, 1, 12, 32, 1000)) {
            assertEquals(size, Cryptography.secureRandomBytes(size).size)
        }
    }

    @Test
    fun secureRandomBytes_rejectsNegativeSize() {
        assertFailsWith<IllegalArgumentException> { Cryptography.secureRandomBytes(-1) }
    }

    @Test
    fun secureRandomBytes_fillsTheBuffer() {
        // Catches a buffer handed back without being filled, on every platform including the
        // native ones. It cannot tell a weak generator from a strong one - WeakRandomnessGuardTest
        // and CsprngProvenanceTest do that - and is deliberately no stronger than this: a false
        // failure needs two identical or all-zero 256-bit draws.
        val first = Cryptography.secureRandomBytes(32)
        val second = Cryptography.secureRandomBytes(32)

        assertFalse(first.all { it == 0.toByte() })
        assertFalse(second.all { it == 0.toByte() })
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun isValidPrivateKey_rejectsInvalidValues() {
        val zeroKey = "00".repeat(32)
        val tooLong = "00".repeat(33)

        assertFalse(Cryptography.isValidPrivateKey(zeroKey))
        assertFalse(Cryptography.isValidPrivateKey(tooLong))
    }

    @Test
    fun isValidPublicKey_acceptsKnownVector() {
        val publicKeyHex =
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        val tooShort = "00".repeat(31)

        assertTrue(Cryptography.isValidPublicKey(publicKeyHex))
        assertFalse(Cryptography.isValidPublicKey(tooShort))
    }

    @Test
    fun getDigestHash_matchesVector() {
        val expectedHex = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

        val digest = Cryptography.getDigestHash("hello".encodeToByteArray())

        assertEquals(expectedHex, digest.toHexString())
    }

    @Test
    fun getDigestHash_emptyInput_matchesVector() {
        val expectedHex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        val digest = Cryptography.getDigestHash(ByteArray(0))

        assertEquals(expectedHex, digest.toHexString())
    }

    @Test
    fun hmacSha256_matchesVector() {
        val expectedHex = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
        val key = "key".encodeToByteArray()
        val message = "The quick brown fox jumps over the lazy dog".encodeToByteArray()

        val mac = Cryptography.hmacSha256(key, message)

        assertEquals(expectedHex, mac.toHexString())
    }

    @Test
    fun hmacSha256_emptyKeyAndMessage_matchesVector() {
        val expectedHex = "b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad"

        val mac = Cryptography.hmacSha256(ByteArray(0), ByteArray(0))

        assertEquals(expectedHex, mac.toHexString())
    }

    @Test
    fun hmacSha256_emptyMessage_matchesVector() {
        val expectedHex = "5d5d139563c95b5967b9bd9a8c9b233a9dedb45072794cd232dc1b74832607d0"

        val mac = Cryptography.hmacSha256("key".encodeToByteArray(), ByteArray(0))

        assertEquals(expectedHex, mac.toHexString())
    }

    @Test
    fun ed25519SignAndVerify_emptyMessageRoundTrip() {
        val privateKeyHex = "0000000000000000000000000000000000000000000000000000000000000001"
        val publicKeyHex = Cryptography.deriveEd25519PublicKey(privateKeyHex)
        val message = ByteArray(0)

        val signature = Cryptography.ed25519Sign(message, privateKeyHex)

        assertTrue(Cryptography.ed25519Verify(message, signature, publicKeyHex))
    }

    @Test
    fun schnorrSignAndVerify_roundTrip() {
        val privateKeyHex = "0000000000000000000000000000000000000000000000000000000000000003"
        val publicKeyHex = Cryptography.derivePublicKey(privateKeyHex)
        val messageHash = Cryptography.getDigestHash("message".encodeToByteArray())

        val signatureHex = Cryptography.schnorrSign(messageHash, privateKeyHex)

        assertEquals(128, signatureHex.length)
        assertTrue(Cryptography.schnorrVerify(messageHash, signatureHex, publicKeyHex))
    }

    @Test
    fun createAESSecretKey_matchesVector() {
        val expectedHex = "0394a2ede332c9a13eb82e9b24631604c31df978b4e2f0fbd2c549944f9d79a5"
        val key = Cryptography.createAESSecretKey("password", "salt".encodeToByteArray())

        assertEquals(expectedHex, key.toHexString())
    }

    @Test
    fun encryptDecryptAESGCM_roundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "hello aes gcm"

        val encrypted = Cryptography.encryptAESGCM(plaintext, key)
        val decrypted = Cryptography.decryptAESGCM(encrypted, key)

        assertNotNull(decrypted)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun encryptDecryptAESGCM_emptyPlaintextRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val plaintext = ""

        val encrypted = Cryptography.encryptAESGCM(plaintext, key)
        val decrypted = Cryptography.decryptAESGCM(encrypted, key)

        assertNotNull(decrypted)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun secureRandomBytes_returnsRequestedSizeAndDiffersAcrossCalls() {
        val draws = List(16) { Cryptography.secureRandomBytes(32) }

        draws.forEach { assertEquals(32, it.size) }
        // 16 draws of 256 bits collide with probability ~2^-249. A repeat means a fixed or
        // reseeded generator, not bad luck.
        assertEquals(draws.size, draws.map { it.toHexString() }.toSet().size)
    }

    @Test
    fun secureRandomBytes_zeroSizeIsEmpty() {
        assertEquals(0, Cryptography.secureRandomBytes(0).size)
    }

    @Test
    fun secureRandomBytes_negativeSizeIsRejected() {
        assertFailsWith<IllegalArgumentException> { Cryptography.secureRandomBytes(-1) }
    }

    private fun ByteArray.toHexString(): String {
        val hexChars = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (b in this) {
            val i = b.toInt() and 0xff
            out.append(hexChars[i ushr 4])
            out.append(hexChars[i and 0x0f])
        }
        return out.toString()
    }
}
