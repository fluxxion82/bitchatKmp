package com.bitchat.nostr.model

import com.bitchat.crypto.Cryptography.getDigestHash
import com.bitchat.crypto.Cryptography.isValidPublicKey
import com.bitchat.crypto.Cryptography.schnorrSign
import com.bitchat.crypto.Cryptography.schnorrVerify
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlin.time.Clock

/**
 * Nostr Event structure following NIP-01
 * Compatible with iOS implementation
 */
@Serializable
data class NostrEvent(
    var id: String = "",
    val pubkey: String,
    @SerialName("created_at")
    val createdAt: Int,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    var sig: String? = null
) {
    companion object {
        private val json: Json = Json {
            // defaults are fine; listing these for clarity
            prettyPrint = false
            encodeDefaults = false
            // kotlinx doesn’t escape slashes by default, so no special flag needed
        }

        private val tagsSerializer = ListSerializer(ListSerializer(String.serializer()))

        /**
         * Create from JSON dictionary
         */
        fun fromJson(json: Map<String, Any>): NostrEvent? {
            return try {
                NostrEvent(
                    id = json["id"] as? String ?: "",
                    pubkey = json["pubkey"] as? String ?: return null,
                    createdAt = (json["created_at"] as? Number)?.toInt() ?: return null,
                    kind = (json["kind"] as? Number)?.toInt() ?: return null,
                    tags = (json["tags"] as? List<List<String>>) ?: return null,
                    content = json["content"] as? String ?: return null,
                    sig = json["sig"] as? String?
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Create from JSON string
         */
        fun fromJsonString(jsonString: String): NostrEvent? {
            return try {
                json.decodeFromString(serializer(), jsonString)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Create a new text note event
         */
        fun createTextNote(
            content: String,
            publicKeyHex: String,
            privateKeyHex: String,
            tags: List<List<String>> = emptyList(),
            createdAt: Int = (Clock.System.now().epochSeconds).toInt()
        ): NostrEvent {
            val event = NostrEvent(
                pubkey = publicKeyHex,
                createdAt = createdAt,
                kind = NostrKind.TEXT_NOTE,
                tags = tags,
                content = content
            )
            return event.sign(privateKeyHex)
        }

        /**
         * Create a new metadata event (kind 0)
         */
        fun createMetadata(
            metadata: String,
            publicKeyHex: String,
            privateKeyHex: String,
            createdAt: Int = (Clock.System.now().epochSeconds).toInt()
        ): NostrEvent {
            val event = NostrEvent(
                pubkey = publicKeyHex,
                createdAt = createdAt,
                kind = NostrKind.METADATA,
                tags = emptyList(),
                content = metadata
            )
            return event.sign(privateKeyHex)
        }

        /**
         * Create a channel creation event (kind 40) following NIP-28.
         * @param channelName The name of the channel (e.g., "#general")
         * @param about Optional channel description
         * @param keyCommitment SHA256 hash of derived encryption key (for protected channels)
         * @param publicKeyHex The creator's public key
         * @param privateKeyHex The creator's private key for signing
         */
        fun createChannelCreation(
            channelName: String,
            about: String? = null,
            keyCommitment: String? = null,
            publicKeyHex: String,
            privateKeyHex: String,
            createdAt: Int = (Clock.System.now().epochSeconds).toInt()
        ): NostrEvent {
            // Build metadata JSON content
            val metadataContent = buildString {
                append("{\"name\":\"")
                append(channelName.replace("\"", "\\\""))
                append("\"")
                if (!about.isNullOrBlank()) {
                    append(",\"about\":\"")
                    append(about.replace("\"", "\\\""))
                    append("\"")
                }
                append("}")
            }

            // Build tags - include key commitment if channel is protected
            val tags = mutableListOf<List<String>>()
            if (keyCommitment != null) {
                tags.add(listOf("key-commitment", keyCommitment))
            }

            val event = NostrEvent(
                pubkey = publicKeyHex,
                createdAt = createdAt,
                kind = NostrKind.CHANNEL_CREATE,
                tags = tags,
                content = metadataContent
            )
            return event.sign(privateKeyHex)
        }

        /**
         * Create a channel message event (kind 42) following NIP-28.
         * @param channelEventId The ID of the kind 40 channel creation event (root)
         * @param relayUrl The relay URL where the channel event exists
         * @param content The message content (plaintext or base64-encoded encrypted)
         * @param isEncrypted Whether the content is encrypted
         * @param nickname Optional sender nickname
         * @param publicKeyHex The sender's public key
         * @param privateKeyHex The sender's private key for signing
         */
        fun createChannelMessage(
            channelEventId: String,
            relayUrl: String,
            content: String,
            isEncrypted: Boolean = false,
            nickname: String? = null,
            publicKeyHex: String,
            privateKeyHex: String,
            createdAt: Int = (Clock.System.now().epochSeconds).toInt()
        ): NostrEvent {
            val tags = mutableListOf<List<String>>()

            // Reference to channel creation event (root)
            tags.add(listOf("e", channelEventId, relayUrl, "root"))

            // Nickname tag
            if (!nickname.isNullOrBlank()) {
                tags.add(listOf("n", nickname))
            }

            // Encryption indicator
            if (isEncrypted) {
                tags.add(listOf("encrypted", "aes-gcm"))
            }

            val event = NostrEvent(
                pubkey = publicKeyHex,
                createdAt = createdAt,
                kind = NostrKind.CHANNEL_MESSAGE,
                tags = tags,
                content = content
            )
            return event.sign(privateKeyHex)
        }

        /**
         * Parse channel info from a kind 40 event.
         * @return Parsed channel name and metadata, or null if invalid
         */
        fun parseChannelInfo(event: NostrEvent): ChannelCreateInfo? {
            if (event.kind != NostrKind.CHANNEL_CREATE) return null

            return try {
                // Parse the content as JSON to extract name and about
                val content = event.content
                val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(content)
                val aboutMatch = Regex("\"about\"\\s*:\\s*\"([^\"]+)\"").find(content)

                val channelName = nameMatch?.groupValues?.get(1) ?: return null
                val about = aboutMatch?.groupValues?.get(1)

                val keyCommitment = event.tags.find { it.firstOrNull() == "key-commitment" }?.getOrNull(1)

                ChannelCreateInfo(
                    name = channelName,
                    about = about,
                    creatorPubkey = event.pubkey,
                    keyCommitment = keyCommitment,
                    eventId = event.id,
                    createdAt = event.createdAt
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Data class for parsed channel creation info from kind 40 events
     */
    data class ChannelCreateInfo(
        val name: String,
        val about: String?,
        val creatorPubkey: String,
        val keyCommitment: String?,
        val eventId: String,
        val createdAt: Int
    )

    /**
     * Sign event with secp256k1 private key
     * Returns signed event with id and signature set
     */
    fun sign(privateKeyHex: String): NostrEvent {
        val (eventId, eventIdHash) = calculateEventId()

        // Create signature using secp256k1
        val signature = signHash(eventIdHash, privateKeyHex)

        return this.copy(
            id = eventId,
            sig = signature
        )
    }

    /**
     * Compute event ID (NIP-01) without signing
     */
    fun computeEventIdHex(): String {
        val (eventId, _) = calculateEventId()
        return eventId
    }

    /**
     * Calculate event ID according to NIP-01
     * Returns (hex_id, hash_bytes)
     */
    private fun calculateEventId(): Pair<String, ByteArray> {
        // NIP-01 json
        val serialized = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(pubkey))
            add(JsonPrimitive(createdAt))
            add(JsonPrimitive(kind))
            add(json.encodeToJsonElement(tagsSerializer, tags))
            add(JsonPrimitive(content))
        }

        val jsonString = json.encodeToString(JsonArray.serializer(), serialized)
        val hash = getDigestHash(jsonString.toByteArray(Charsets.UTF_8))
        val hexId = hash.toHexString()

        return Pair(hexId, hash)
    }

    /**
     * Sign hash using BIP-340 Schnorr signatures
     */
    private fun signHash(hash: ByteArray, privateKeyHex: String): String {
        return try {
            // Use the real BIP-340 Schnorr signature from NostrCrypto
            schnorrSign(hash, privateKeyHex)
        } catch (e: Exception) {
            throw RuntimeException("Failed to sign event: ${e.message}", e)
        }
    }

    /**
     * Validate event signature using BIP-340 Schnorr verification
     */
    fun isValidSignature(): Boolean {
        return try {
            val signatureHex = sig ?: return false
            if (id.isEmpty() || pubkey.isEmpty()) return false

            // Recalculate the event ID hash for verification
            val (calculatedId, messageHash) = calculateEventId()

            // Check if the calculated ID matches the stored ID
            if (calculatedId != id) return false

            // Verify the Schnorr signature
            schnorrVerify(messageHash, signatureHex, pubkey)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validate event structure and signature
     */
    fun isValid(): Boolean {
        return try {
            // Basic field validation
            if (pubkey.isEmpty() || content.isEmpty()) return false
            if (createdAt <= 0 || kind < 0) return false
            if (!isValidPublicKey(pubkey)) return false

            // Signature validation
            isValidSignature()
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Nostr event kinds
 */
object NostrKind {
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val DIRECT_MESSAGE = 14     // NIP-17 direct message (unsigned)
    const val FILE_MESSAGE = 15       // NIP-17 file message (unsigned)
    const val SEAL = 13              // NIP-17 sealed event
    const val GIFT_WRAP = 1059       // NIP-17 gift wrap
    const val EPHEMERAL_EVENT = 20000 // For geohash channels

    // NIP-28 Public Channels
    const val CHANNEL_CREATE = 40     // Channel creation
    const val CHANNEL_METADATA = 41   // Channel metadata update
    const val CHANNEL_MESSAGE = 42    // Channel message
    const val CHANNEL_HIDE = 43       // Hide message
    const val CHANNEL_MUTE = 44       // Mute user
}
