package com.bitchat.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun randomizeTimestampUpToPast_withinRange() {
        val baseline = Cryptography.randomizeTimestampUpToPast(maxPastSeconds = 0)
        val randomized = Cryptography.randomizeTimestampUpToPast(maxPastSeconds = 60)

        assertTrue(randomized <= baseline)
        assertTrue(randomized >= baseline - 65)
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
    fun hmacSha256_matchesVector() {
        val expectedHex = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
        val key = "key".encodeToByteArray()
        val message = "The quick brown fox jumps over the lazy dog".encodeToByteArray()

        val mac = Cryptography.hmacSha256(key, message)

        assertEquals(expectedHex, mac.toHexString())
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
