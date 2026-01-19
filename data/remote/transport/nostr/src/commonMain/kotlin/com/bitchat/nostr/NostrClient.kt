package com.bitchat.nostr

import com.bitchat.crypto.Cryptography
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import com.bitchat.nostr.util.toLittleEndianBytes
import com.bitchat.transport.TransportIdentityProvider
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

private const val NOSTR_PRIVATE_KEY = "nostr_private_key"
private const val DEVICE_SEED_KEY = "nostr_device_seed"

/**
 * NIP-17 Protocol Implementation for Private Direct Messages
 * Compatible with iOS implementation
 */
class NostrClient(
    private val nostrPreferences: NostrPreferences,
    private val identityProvider: TransportIdentityProvider,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val geohashIdentityCache = mutableMapOf<String, NostrIdentity>()

    // Track which message ID is currently being mined
    val currentlyMiningMessageId = MutableStateFlow<String?>(null)

    /**
     * Create NIP-17 private message gift-wrap (receiver copy only per iOS)
     * Returns a single gift-wrapped event ready for relay broadcast
     */
    fun createPrivateMessage(
        content: String,
        recipientPubkey: String,
        senderIdentity: NostrIdentity
    ): List<NostrEvent> {
        // // Log.d(TAG, "Creating private message for recipient: ${recipientPubkey.take(16)}...")

        // 1. Create the rumor (unsigned kind 14) with p-tag
        val rumorBase = NostrEvent(
            pubkey = senderIdentity.publicKeyHex,
            createdAt = (Clock.System.now().epochSeconds).toInt(),
            kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(listOf("p", recipientPubkey)),
            content = content
        )
        val rumorId = rumorBase.computeEventIdHex()
        val rumor = rumorBase.copy(id = rumorId)

        // 2. Seal the rumor (kind 13) signed by sender, timestamp randomized up to 2 days
        val sealedEvent = createSeal(
            rumor = rumor,
            recipientPubkey = recipientPubkey,
            senderPrivateKey = senderIdentity.privateKeyHex,
            senderPublicKey = senderIdentity.publicKeyHex
        )

        // 3. Gift wrap to recipient (kind 1059)
        val giftWrapToRecipient = createGiftWrap(
            seal = sealedEvent,
            recipientPubkey = recipientPubkey
        )
        // Log.d(TAG, "Created gift wrap: toRecipient=${giftWrapToRecipient.id.take(16)}...")
        return listOf(giftWrapToRecipient)
    }

    /**
     * Decrypt a received NIP-17 message
     * Returns (content, senderPubkey, timestamp) or null if decryption fails
     */
    fun decryptPrivateMessage(
        giftWrap: NostrEvent,
        recipientIdentity: NostrIdentity
    ): Triple<String, String, Int>? {
        // Log.v(TAG, "Starting decryption of gift wrap: ${giftWrap.id.take(16)}...")

        return try {
            // SECURITY: Validate event before attempting decryption
            // 1. Verify this is a gift wrap event (kind 1059)
            if (giftWrap.kind != NostrKind.GIFT_WRAP) {
                // Log.w(TAG, "❌ Rejected event: not a gift wrap (kind=${giftWrap.kind}, expected ${NostrKind.GIFT_WRAP})")
                return null
            }

            // 2. Verify event signature is valid
            if (!giftWrap.isValidSignature()) {
                // Log.w(TAG, "❌ Rejected event: invalid signature")
                return null
            }

            // 3. Verify timestamp is recent (prevent replay attacks)
            // NIP-17 randomizes timestamps up to 2 days in the past, so allow 48h + 15min buffer
            val maxAgeSeconds = 48 * 3600 + 15 * 60 // 48 hours + 15 minutes
            val currentTime = Clock.System.now().epochSeconds.toInt()
            val age = currentTime - giftWrap.createdAt

            if (age > maxAgeSeconds) {
                // Log.w(TAG, "❌ Rejected event: too old (age=${age}s, max=${maxAgeSeconds}s)")
                return null
            }

            // 4. Unwrap the gift wrap
            val seal = unwrapGiftWrap(giftWrap, recipientIdentity.privateKeyHex)
                ?: run {
                    // Log.w(TAG, "❌ Failed to unwrap gift wrap")
                    return null
                }

            // Log.v(TAG, "Successfully unwrapped gift wrap from: ${seal.pubkey.take(16)}...")

            // 2. Open the seal
            val rumor = openSeal(seal, recipientIdentity.privateKeyHex)
                ?: run {
                    // Log.w(TAG, "❌ Failed to open seal")
                    return null
                }

            // Log.v(TAG, "Successfully opened seal")

            Triple(rumor.content, rumor.pubkey, rumor.createdAt)
        } catch (e: Exception) {
            // Log.w(TAG, "Failed to decrypt private message: ${e.message}")
            null
        }
    }

    /**
     * Create a geohash-scoped text note (kind 1) with optional nickname
     * This creates a persistent text note that can be retrieved later
     */
    suspend fun createGeohashTextNote(
        content: String,
        geohash: String,
        senderIdentity: NostrIdentity,
        nickname: String? = null
    ): NostrEvent = withContext(Dispatchers.Default) {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("g", geohash))

        if (!nickname.isNullOrEmpty()) {
            tags.add(listOf("n", nickname))
        }

        val event = NostrEvent(
            pubkey = senderIdentity.publicKeyHex,
            createdAt = (Clock.System.now().epochSeconds).toInt(),
            kind = NostrKind.TEXT_NOTE,
            tags = tags,
            content = content
        )

        return@withContext senderIdentity.signEvent(event)
    }

    /**
     * Create a geohash-scoped ephemeral public message (kind 20000)
     * Includes Proof of Work mining if enabled in settings
     */
    suspend fun createEphemeralGeohashEvent(
        content: String,
        geohash: String,
        senderIdentity: NostrIdentity,
        nickname: String? = null,
        teleported: Boolean = false,
        tempId: String = ""
    ): NostrEvent {
        try {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("📝 NostrClient.createEphemeralGeohashEvent STARTED")
            println("   Geohash: $geohash")
            println("   Nickname: ${nickname ?: "none"}")
            println("   Teleported: $teleported")
            println("   Content length: ${content.length}")
            println("   Content preview: ${content.take(50)}${if (content.length > 50) "..." else ""}")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            val tags = mutableListOf<List<String>>()
            tags.add(listOf("g", geohash))

            if (!nickname.isNullOrEmpty()) {
                tags.add(listOf("n", nickname))
            }

            if (teleported) {
                // Use tag consistent with event handlers ("t","teleport")
                tags.add(listOf("t", "teleport"))
            }

            println("📋 NostrClient: Event tags constructed:")
            tags.forEachIndexed { index, tag ->
                println("   ${index + 1}. [${tag.joinToString(", ")}]")
            }

            var event = NostrEvent(
                pubkey = senderIdentity.publicKeyHex,
                createdAt = (Clock.System.now().epochSeconds).toInt(),
                kind = NostrKind.EPHEMERAL_EVENT,
                tags = tags,
                content = content
            )

            println("📄 NostrClient: Event structure BEFORE signing:")
            println("   Kind: ${event.kind} (should be 20000 for ephemeral)")
            println("   Pubkey: ${senderIdentity.publicKeyHex.take(16)}... (${senderIdentity.publicKeyHex.length} chars)")
            println("   Created at: ${event.createdAt}")
            println("   Tags count: ${event.tags.size}")

            val powDifficulty = nostrPreferences.getPowDifficulty()
            val powEnabled = nostrPreferences.getPowEnabled()
            println("⛏️  NostrClient: PoW settings - enabled=$powEnabled, difficulty=$powDifficulty")

            if (powEnabled && powDifficulty > 0) {
                try {
                    // Use tempId if provided, otherwise event ID
                    val trackingId = tempId.ifEmpty { event.id }
                    currentlyMiningMessageId.value = trackingId
                    nostrPreferences.setIsMining(true)
                    println("⛏️  NostrClient: Starting PoW mining for ID: $trackingId")

                    val minedEvent = NostrProofOfWork.mineEvent(
                        event = event,
                        targetDifficulty = powDifficulty,
                        maxIterations = 2_000_000
                    )

                    if (minedEvent != null) {
                        event = minedEvent
                        val actualDifficulty = NostrProofOfWork.calculateDifficulty(event.id)
                        println("✅ NostrClient: PoW mining successful - target=$powDifficulty, actual=$actualDifficulty")
                    } else {
                        println("⚠️  NostrClient: PoW mining failed, proceeding without PoW")
                    }
                } finally {
                    // Minimum animation time
                    delay(500)
                    currentlyMiningMessageId.value = null
                    nostrPreferences.setIsMining(false)
                    println("⛏️  NostrClient: PoW mining completed/stopped")
                }
            }

            println("🔏 NostrClient: Signing event...")
            val signedEvent = senderIdentity.signEvent(event)

            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("📋 NostrClient: FINAL EVENT DETAILS (Legacy Compatibility Check)")
            println("   Event ID: ${signedEvent.id}")
            println("   Event ID length: ${signedEvent.id.length} chars")
            println("   Kind: ${signedEvent.kind} (must be 20000 for ephemeral)")
            println("   Pubkey: ${signedEvent.pubkey.take(16)}... (${signedEvent.pubkey.length} chars)")
            println("   Signature: ${signedEvent.sig?.take(16) ?: "UNSIGNED"}... (${signedEvent.sig?.length ?: 0} chars)")
            println("   Created at: ${signedEvent.createdAt}")
            println("   Tags:")
            signedEvent.tags.forEachIndexed { index, tag ->
                println("     ${index + 1}. ${tag.joinToString(" | ")}")
            }
            println("   'g' tag value: ${signedEvent.tags.find { it.firstOrNull() == "g" }?.getOrNull(1) ?: "MISSING"}")
            println("   'n' tag value: ${signedEvent.tags.find { it.firstOrNull() == "n" }?.getOrNull(1) ?: "none"}")
            println("   Content length: ${signedEvent.content.length}")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("✅ NostrClient.createEphemeralGeohashEvent COMPLETED")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            return signedEvent
        } catch (e: Exception) {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("❌ NostrClient.createEphemeralGeohashEvent FAILED")
            println("   Geohash: $geohash")
            println("   Error: ${e.message}")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            e.printStackTrace()
            throw e
        }
    }

    // MARK: - Private Methods

    private fun createSeal(
        rumor: NostrEvent,
        recipientPubkey: String,
        senderPrivateKey: String,
        senderPublicKey: String
    ): NostrEvent {
        val rumorJSON = json.encodeToString(rumor) //gson.toJson(rumor)

        val encrypted = Cryptography.encryptNIP44(
            plaintext = rumorJSON,
            recipientPublicKeyHex = recipientPubkey,
            senderPrivateKeyHex = senderPrivateKey
        )

        val seal = NostrEvent(
            pubkey = senderPublicKey,
            createdAt = Cryptography.randomizeTimestampUpToPast(),
            kind = NostrKind.SEAL,
            tags = emptyList(),
            content = encrypted
        )

        // Sign with the ephemeral key
        return seal.sign(senderPrivateKey)
    }

    private fun createGiftWrap(
        seal: NostrEvent,
        recipientPubkey: String
    ): NostrEvent {
        val sealJSON = json.encodeToString(seal)

        // Create new ephemeral key for gift wrap
        val (wrapPrivateKey, wrapPublicKey) = Cryptography.generateKeyPair()
        // Log.v(TAG, "Creating gift wrap with ephemeral key")

        // Encrypt the seal with the new ephemeral key
        val encrypted = Cryptography.encryptNIP44(
            plaintext = sealJSON,
            recipientPublicKeyHex = recipientPubkey,
            senderPrivateKeyHex = wrapPrivateKey
        )

        val giftWrap = NostrEvent(
            pubkey = wrapPublicKey,
            createdAt = Cryptography.randomizeTimestampUpToPast(),
            kind = NostrKind.GIFT_WRAP,
            tags = listOf(listOf("p", recipientPubkey)), // Tag recipient
            content = encrypted
        )

        // Sign with the gift wrap ephemeral key
        return giftWrap.sign(wrapPrivateKey)
    }

    private fun unwrapGiftWrap(
        giftWrap: NostrEvent,
        recipientPrivateKey: String
    ): NostrEvent? {
        // Log.d(TAG, "Unwrapping gift wrap; content prefix='${giftWrap.content.take(3)}' length=${giftWrap.content.length}")

        return try {
            val decrypted = Cryptography.decryptNIP44(
                ciphertext = giftWrap.content,
                senderPublicKeyHex = giftWrap.pubkey,
                recipientPrivateKeyHex = recipientPrivateKey
            )

//            val jsonElement = JsonParser.parseString(decrypted)
//            if (!jsonElement.isJsonObject) {
//                // Log.w(TAG, "Decrypted gift wrap is not a JSON object")
//                return null
//            }
//
//            val jsonObject = jsonElement.asJsonObject
//            val seal = NostrEvent(
//                id = jsonObject.get("id")?.asString ?: "",
//                pubkey = jsonObject.get("pubkey")?.asString ?: "",
//                createdAt = jsonObject.get("created_at")?.asInt ?: 0,
//                kind = jsonObject.get("kind")?.asInt ?: 0,
//                tags = parseTagsFromJson(jsonObject.get("tags")?.asJsonArray) ?: emptyList(),
//                content = jsonObject.get("content")?.asString ?: "",
//                sig = jsonObject.get("sig")?.asString
//            )

            val seal = json.decodeFromString<NostrEvent>(decrypted)
            // Log.v(TAG, "Unwrapped seal with kind: ${seal.kind}")
            seal
        } catch (e: Exception) {
            // Log.w(TAG, "Failed to unwrap gift wrap: ${e.message}")
            null
        }
    }

    private fun openSeal(
        seal: NostrEvent,
        recipientPrivateKey: String
    ): NostrEvent? {
        return try {
            val decrypted = Cryptography.decryptNIP44(
                ciphertext = seal.content,
                senderPublicKeyHex = seal.pubkey,
                recipientPrivateKeyHex = recipientPrivateKey
            )

//            val jsonElement = JsonParser.parseString(decrypted)
//            if (!jsonElement.isJsonObject) {
//                // Log.w(TAG, "Decrypted seal is not a JSON object")
//                return null
//            }
//
//            val jsonObject = jsonElement.asJsonObject
//            NostrEvent(
//                id = jsonObject.get("id")?.asString ?: "",
//                pubkey = jsonObject.get("pubkey")?.asString ?: "",
//                createdAt = jsonObject.get("created_at")?.asInt ?: 0,
//                kind = jsonObject.get("kind")?.asInt ?: 0,
//                tags = parseTagsFromJson(jsonObject.get("tags")?.asJsonArray) ?: emptyList(),
//                content = jsonObject.get("content")?.asString ?: "",
//                sig = jsonObject.get("sig")?.asString
//            )
            val seal = json.decodeFromString<NostrEvent>(decrypted)
            seal
        } catch (e: Exception) {
            // Log.w(TAG, "Failed to open seal: ${e.message}")
            null
        }
    }

    /**
     * Get or create the current Nostr identity
     */
    fun getCurrentNostrIdentity(): NostrIdentity? {
        // Try to load existing Nostr private key
        val existingKey = identityProvider.loadKey(NOSTR_PRIVATE_KEY) // loadNostrPrivateKey(stateManager)
        if (existingKey != null) {
            return try {
                NostrIdentity.fromPrivateKey(existingKey)
            } catch (e: Exception) {
                // Log.e(TAG, "Failed to create identity from stored key: ${e.message}")
                null
            }
        }

        // Generate new identity
        val newIdentity = NostrIdentity.generate()
        identityProvider.saveKey(key = NOSTR_PRIVATE_KEY, value = newIdentity.privateKeyHex)
        // saveNostrPrivateKey(stateManager, newIdentity.privateKeyHex)

        // Log.i(TAG, "Created new Nostr identity: ${newIdentity.getShortNpub()}")
        return newIdentity
    }

    /**
     * Derive a deterministic, unlinkable Nostr identity for a given geohash
     * Uses HMAC-SHA256(deviceSeed, geohash) as private key material with fallback rehashing
     * if the candidate is not a valid secp256k1 private key.
     *
     * Direct port from iOS implementation for 100% compatibility
     * OPTIMIZED: Cached for UI responsiveness
     */
    fun deriveIdentity(forGeohash: String): NostrIdentity {
        // Check cache first for immediate response
        geohashIdentityCache[forGeohash]?.let { cachedIdentity ->
            println("NostrClient: Using cached geohash identity for $forGeohash")
            return cachedIdentity
        }

        println("NostrClient: Deriving new identity for geohash $forGeohash")

        val seed = getOrCreateDeviceSeed()

        val geohashBytes = forGeohash.toByteArray()

        // Try a few iterations to ensure a valid key can be formed (exactly like iOS)
        for (i in 0 until 10) {
            val candidateKey = candidateKey(seed, geohashBytes, i.toUInt())
            val candidateKeyHex = candidateKey.toHexString()

            if (Cryptography.isValidPrivateKey(candidateKeyHex)) {
                val identity = NostrIdentity.fromPrivateKey(candidateKeyHex)

                // Cache the result for future UI responsiveness
                geohashIdentityCache[forGeohash] = identity

                println("NostrClient: Derived geohash identity for $forGeohash (iteration $i), pubkey=${identity.publicKeyHex.take(16)}...")
                return identity
            }
        }

        // As a final fallback, hash the seed+msg and try again (exactly like iOS)
        val combined = seed + geohashBytes
        val fallbackKey = Cryptography.getDigestHash(combined) // digest.digest(combined)

        val fallbackIdentity = NostrIdentity.fromPrivateKey(fallbackKey.toHexString())

        // Cache the fallback result too
        geohashIdentityCache[forGeohash] = fallbackIdentity

        println("NostrClient: Used fallback identity derivation for $forGeohash, pubkey=${fallbackIdentity.publicKeyHex.take(16)}...")
        return fallbackIdentity
    }

    /**
     * Generate candidate key for a specific iteration (matches iOS implementation)
     */
    private fun candidateKey(seed: ByteArray, message: ByteArray, iteration: UInt): ByteArray {
        val input = message + iteration.toLittleEndianBytes()
        return Cryptography.hmacSha256(seed, input)
    }

    /**
     * Clear all Nostr identity data
     */
    fun clearAllAssociations() {
        // Clear cache first
        geohashIdentityCache.clear()

        // Clear Nostr private key using public methods instead of reflection
        try {
            identityProvider.removeKeys(NOSTR_PRIVATE_KEY, DEVICE_SEED_KEY)
            // Log.i(TAG, "Cleared all Nostr identity data and cache")
        } catch (e: Exception) {
            // Log.e(TAG, "Failed to clear Nostr data: ${e.message}")
        }
    }

    private fun getOrCreateDeviceSeed(): ByteArray {
        try {
            // Use public methods instead of reflection to access the encrypted preferences
            val existingSeed = identityProvider.loadKey(DEVICE_SEED_KEY)
            if (existingSeed != null) {

                return Base64.decode(existingSeed) //android.util.Base64.decode(existingSeed, android.util.Base64.DEFAULT)
            }

            // Generate new seed
            val seed = ByteArray(32)
            Random.nextBytes(seed)
            //SecureRandom().nextBytes(seed)

            val seedBase64 = Base64.encode(seed) // android.util.Base64.encodeToString(seed, android.util.Base64.DEFAULT)
            identityProvider.saveKey(DEVICE_SEED_KEY, seedBase64)

            // Log.d(TAG, "Generated new device seed for geohash identity derivation")
            return seed
        } catch (e: Exception) {
            // Log.e(TAG, "Failed to get/create device seed: ${e.message}")
            throw e
        }
    }
}
