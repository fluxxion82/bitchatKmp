package com.bitchat.bluetooth.manager

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.MessageType
import com.bitchat.bluetooth.protocol.MAX_PROCESSED_MESSAGE_IDS
import com.bitchat.bluetooth.protocol.MAX_RECENT_ANNOUNCEMENTS
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Manages security aspects of the mesh network including duplicate detection,
 * replay attack protection, and key exchange handling
 * Extracted from Android SecurityManager for multiplatform use
 */
class SecurityManager(
    private val noiseEncryption: NoiseEncryptionFacade,
    private val cryptoSigning: CryptoSigningFacade,
    private val myPeerID: String,
    private val clock: Clock = Clock.System
) {
    companion object {
        private const val TAG = "SecurityManager"
        private val MESSAGE_TIMEOUT = 5.minutes // 5 minutes (same as iOS)
        private const val ANNOUNCEMENT_DEDUP_WINDOW_MS = 60_000L  // 1 minute window for announcement dedup
    }

    // Convert peer ID hex string to bytes for comparison
    private val myPeerIDBytes: ByteArray = hexToBytes(myPeerID)

    // Security tracking
    private val processedMessages = RecentKeys(MAX_PROCESSED_MESSAGE_IDS, MESSAGE_TIMEOUT.inWholeMilliseconds)

    // Announcement deduplication tracking
    private val recentAnnouncements = RecentKeys(MAX_RECENT_ANNOUNCEMENTS, ANNOUNCEMENT_DEDUP_WINDOW_MS)

    // Delegate for callbacks
    var delegate: SecurityManagerDelegate? = null

    internal val processedMessageCount: Int get() = processedMessages.size
    internal val recentAnnouncementCount: Int get() = recentAnnouncements.size

    /**
     * Validate packet security (timestamp, replay attacks, duplicates, signatures)
     */
    fun validatePacket(packet: BitchatPacket, peerID: String): Boolean {
        // Skip validation for our own packets
        if (peerID == myPeerID) {
            return false
        }

        // Get message type
        val messageType = MessageType.fromValue(packet.type)

        // Duplicate detection
        val messageID = generateMessageID(packet, peerID)

        if (messageType == MessageType.ANNOUNCE) {
            // Deduplicate ANNOUNCE packets within time window
            if (!recentAnnouncements.firstSighting(messageID, clock.now().toEpochMilliseconds())) {
                // Duplicate within window - ignore silently
                return false
            }

        } else {
            // Non-ANNOUNCE: strict deduplication (existing logic)
            if (!processedMessages.firstSighting(messageID, clock.now().toEpochMilliseconds())) {
                return false // Duplicate
            }
        }

        // Signature verification (if present)
        if (packet.signature != null) {
            verifyPacketSignature(packet, peerID)
        }

        return true
    }

    /**
     * Generate unique message ID for duplicate detection
     */
    private fun generateMessageID(packet: BitchatPacket, peerID: String): String {
        return "${peerID}_${packet.timestamp}_${packet.type}"
    }

    /** Releases a duplicate record when a refused early payload could later become readable. */
    fun forgetPacket(packet: BitchatPacket, peerID: String) {
        val messageID = generateMessageID(packet, peerID)
        if (MessageType.fromValue(packet.type) == MessageType.ANNOUNCE) {
            recentAnnouncements.forget(messageID)
        } else {
            processedMessages.forget(messageID)
        }
    }

    /**
     * Verify packet signature
     */
    private fun verifyPacketSignature(packet: BitchatPacket, peerID: String) {
        val signature = packet.signature ?: return
        val packetDataForSigning = packet.toBinaryDataForSigning() ?: return

        // For now, just log - actual verification will depend on having peer's public key
        // In full implementation, would look up peer's signing public key and verify
        delegate?.onSignatureVerificationAttempted(peerID, signature)
    }

    /**
     * Encrypt data for peer using Noise protocol
     */
    fun encryptForPeer(peerID: String, data: ByteArray): ByteArray? {
        return noiseEncryption.encrypt(peerID, data)
    }

    /**
     * Decrypt data from peer using Noise protocol
     */
    fun decryptFromPeer(peerID: String, encryptedData: ByteArray): NoiseEncryptionFacade.DecryptionResult? {
        return noiseEncryption.decrypt(peerID, encryptedData)
    }

    /**
     * Check if we have an established Noise session with peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return noiseEncryption.hasEstablishedSession(peerID)
    }

    /** True while the first handshake with [peerID] is in flight (see the facade). */
    fun isHandshaking(peerID: String): Boolean {
        return noiseEncryption.isHandshaking(peerID)
    }

    fun hasCandidate(peerID: String): Boolean = noiseEncryption.hasCandidate(peerID)

    fun hasValidatedSession(peerID: String): Boolean = noiseEncryption.hasValidatedSession(peerID)

    /**
     * Handle Noise handshake packet
     */
    suspend fun handleNoiseHandshake(
        packet: BitchatPacket,
        peerID: String,
        localPrivateKey: ByteArray,
        localPublicKey: ByteArray
    ): NoiseEncryptionFacade.HandshakeResult {
        // Skip handshakes not addressed to us
        if (packet.recipientID != null && !packet.recipientID.contentEquals(myPeerIDBytes)) {
            return NoiseEncryptionFacade.HandshakeResult.Ignored
        }

        // Skip our own handshake messages
        if (peerID == myPeerID) return NoiseEncryptionFacade.HandshakeResult.Ignored

        return noiseEncryption.processHandshake(peerID, packet.payload, localPrivateKey, localPublicKey)
    }

    /**
     * Get remote static public key for a peer (after handshake completes)
     */
    fun getRemoteStaticKey(peerID: String): ByteArray? {
        return noiseEncryption.getRemoteStaticKey(peerID)
    }

    /**
     * Convert hex string to bytes
     */
    /**
     * Whether [packet] is addressed to this node, or to everyone.
     *
     * Handshake handling has always applied this; encrypted payloads did not, which is how a direct
     * message this node was only relaying ended up being fed to our own session with its sender.
     */
    fun isAddressedToUs(packet: BitchatPacket): Boolean {
        val recipient = packet.recipientID ?: return true
        return recipient.contentEquals(myPeerIDBytes)
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    /**
     * Clear all security data
     */
    fun clearAll() {
        processedMessages.clear()
        recentAnnouncements.clear()
        noiseEncryption.clearAllSessions()
    }

    /**
     * Shutdown manager
     */
    fun shutdown() {
        clearAll()
    }
}

/**
 * Delegate interface for security manager callbacks
 */
interface SecurityManagerDelegate {
    fun onSignatureVerificationAttempted(peerID: String, signature: ByteArray)
    fun onNoiseHandshakeCompleted(peerID: String)
}
