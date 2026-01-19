package com.bitchat.crypto

expect object Cryptography {
    fun generateKeyPair(): Pair<String, String>
    fun derivePublicKey(privateKeyHex: String): String
    fun generateEd25519KeyPair(): Pair<String, String>
    fun deriveEd25519PublicKey(privateKeyHex: String): String
    fun ed25519Sign(message: ByteArray, privateKeyHex: String): ByteArray
    fun ed25519Verify(message: ByteArray, signature: ByteArray, publicKeyHex: String): Boolean
    fun deriveNIP44Key(sharedSecret: ByteArray): ByteArray
    fun encryptNIP44(plaintext: String, recipientPublicKeyHex: String, senderPrivateKeyHex: String): String
    fun decryptNIP44(ciphertext: String, senderPublicKeyHex: String, recipientPrivateKeyHex: String): String
    fun randomizeTimestampUpToPast(maxPastSeconds: Int = 172800): Int
    fun isValidPrivateKey(privateKeyHex: String): Boolean
    fun isValidPublicKey(publicKeyHex: String): Boolean
    fun getDigestHash(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray

    fun schnorrSign(messageHash: ByteArray, privateKeyHex: String): String
    fun schnorrVerify(messageHash: ByteArray, signatureHex: String, publicKeyHex: String): Boolean

    fun createAESSecretKey(password: String, salt: ByteArray): ByteArray
    fun encryptAESGCM(plaintext: String, key: ByteArray): ByteArray
    fun decryptAESGCM(encryptedData: ByteArray, key: ByteArray): String?

    fun deriveX25519PublicKey(privateKeyHex: String): String
}
