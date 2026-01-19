package com.bitchat.nostr.model

import com.bitchat.crypto.Cryptography.derivePublicKey
import com.bitchat.crypto.Cryptography.generateKeyPair
import com.bitchat.crypto.Cryptography.getDigestHash
import com.bitchat.crypto.Cryptography.isValidPrivateKey
import com.bitchat.nostr.Bech32
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlin.time.Clock

/**
 * Manages Nostr identity (secp256k1 keypair) for NIP-17 private messaging
 * Compatible with iOS implementation
 */
data class NostrIdentity(
    val privateKeyHex: String,
    val publicKeyHex: String,
    val npub: String,
    val createdAt: Long
) {

    companion object {
        private const val TAG = "NostrIdentity"

        /**
         * Generate a new Nostr identity
         */
        fun generate(): NostrIdentity {
            val (privateKeyHex, publicKeyHex) = generateKeyPair()
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArray())

            // Log.d(TAG, "Generated new Nostr identity: npub=$npub")

            return NostrIdentity(
                privateKeyHex = privateKeyHex,
                publicKeyHex = publicKeyHex,
                npub = npub,
                createdAt = Clock.System.now().toEpochMilliseconds()
            )
        }

        /**
         * Create from existing private key
         */
        fun fromPrivateKey(privateKeyHex: String): NostrIdentity {
            require(isValidPrivateKey(privateKeyHex)) {
                "Invalid private key"
            }

            val publicKeyHex = derivePublicKey(privateKeyHex)
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArray())

            return NostrIdentity(
                privateKeyHex = privateKeyHex,
                publicKeyHex = publicKeyHex,
                npub = npub,
                createdAt = Clock.System.now().toEpochMilliseconds()
            )
        }

        /**
         * Create from a deterministic seed (for demo purposes)
         */
        fun fromSeed(seed: String): NostrIdentity {
            // Hash the seed to create a private key
            val seedBytes = seed.toByteArray(Charsets.UTF_8)
            val privateKeyBytes = getDigestHash(seedBytes)
            val privateKeyHex = privateKeyBytes.toHexString()

            return fromPrivateKey(privateKeyHex)
        }
    }

    /**
     * Sign a Nostr event
     */
    fun signEvent(event: NostrEvent): NostrEvent {
        return event.sign(privateKeyHex)
    }

    /**
     * Get short display format
     */
    fun getShortNpub(): String {
        return if (npub.length > 16) {
            "${npub.take(8)}...${npub.takeLast(8)}"
        } else {
            npub
        }
    }
}
