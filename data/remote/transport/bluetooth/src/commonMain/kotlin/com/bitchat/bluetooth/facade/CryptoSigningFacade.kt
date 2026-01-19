package com.bitchat.bluetooth.facade

import com.bitchat.crypto.Cryptography

/**
 * Facade for cryptographic signing operations
 * Wraps the crypto module API for bluetooth mesh needs
 */
class CryptoSigningFacade(
    private val privateKeyHex: String
) {
    // Ed25519 keys for signing
    private val ed25519PublicKeyHex: String = Cryptography.deriveEd25519PublicKey(privateKeyHex)

    // X25519 keys for Noise protocol - derived from the same private seed
    private val x25519PublicKeyHex: String = Cryptography.deriveX25519PublicKey(privateKeyHex)

    fun getIdentityFingerprint(): String {
        // Return first 16 hex chars of X25519 public key as peer ID
        // This ensures the peer ID matches the Noise handshake fingerprint
        return x25519PublicKeyHex.take(16)
    }

    /**
     * Get Noise protocol public key (X25519/Curve25519)
     * Returns the X25519 public key that will be used in Noise handshake
     */
    fun getNoisePublicKey(): ByteArray {
        return hexToBytes(x25519PublicKeyHex)
    }

    /**
     * Get Noise protocol private key (X25519/Curve25519)
     * Returns the clamped private key for Noise handshake
     */
    fun getNoisePrivateKey(): ByteArray {
        val privateKeyBytes = hexToBytes(privateKeyHex)
        // Clamp according to X25519 spec
        privateKeyBytes[0] = (privateKeyBytes[0].toInt() and 248).toByte()
        privateKeyBytes[31] = (privateKeyBytes[31].toInt() and 127).toByte()
        privateKeyBytes[31] = (privateKeyBytes[31].toInt() or 64).toByte()
        return privateKeyBytes
    }

    /**
     * Get signing public key (Ed25519)
     */
    fun getSigningPublicKey(): ByteArray {
        return hexToBytes(ed25519PublicKeyHex)
    }

    fun signPacket(packetData: ByteArray): ByteArray {
        return Cryptography.ed25519Sign(packetData, privateKeyHex)
    }

    fun verifySignature(packetData: ByteArray, signature: ByteArray, signerPublicKey: ByteArray): Boolean {
        return Cryptography.ed25519Verify(packetData, signature, bytesToHex(signerPublicKey))
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            value.toString(16).padStart(2, '0')
        }
    }
}
