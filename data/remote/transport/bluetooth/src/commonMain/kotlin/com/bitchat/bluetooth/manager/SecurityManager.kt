package com.bitchat.bluetooth.manager

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val myPeerID: String
) {
    companion object {
        private const val TAG = "SecurityManager"
        private val MESSAGE_TIMEOUT = 5.minutes // 5 minutes (same as iOS)
        private const val CLEANUP_INTERVAL_MS = 300_000L // 5 minutes
        private const val MAX_PROCESSED_MESSAGES = 5000
        private const val ANNOUNCEMENT_DEDUP_WINDOW_MS = 60_000L  // 1 minute window for announcement dedup
    }

    // Convert peer ID hex string to bytes for comparison
    private val myPeerIDBytes: ByteArray = hexToBytes(myPeerID)

    // Security tracking
    private val processedMessages = mutableSetOf<String>()
    private val messageTimestamps = mutableMapOf<String, Long>()

    // Announcement deduplication tracking
    private val recentAnnouncements = mutableSetOf<String>()
    private val announcementTimestamps = mutableMapOf<String, Long>()

    // Delegate for callbacks
    var delegate: SecurityManagerDelegate? = null

    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        startPeriodicCleanup()
    }

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
            val now = Clock.System.now().toEpochMilliseconds()
            val lastAnnouncementTime = announcementTimestamps[messageID]

            if (lastAnnouncementTime != null && (now - lastAnnouncementTime) < ANNOUNCEMENT_DEDUP_WINDOW_MS) {
                // Duplicate within window - ignore silently
                return false
            }

            // Update announcement tracking
            recentAnnouncements.add(messageID)
            announcementTimestamps[messageID] = now

            // Cleanup if too many announcements tracked
            if (recentAnnouncements.size > 1000) {
                cleanupOldAnnouncements()
            }

        } else {
            // Non-ANNOUNCE: strict deduplication (existing logic)
            if (processedMessages.contains(messageID)) {
                return false // Duplicate
            }
            // Add to processed messages
            processedMessages.add(messageID)
            messageTimestamps[messageID] = Clock.System.now().toEpochMilliseconds()

            // Enforce size limit
            if (processedMessages.size > MAX_PROCESSED_MESSAGES) {
                cleanupOldMessages()
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
    fun decryptFromPeer(peerID: String, encryptedData: ByteArray): ByteArray? {
        return noiseEncryption.decrypt(peerID, encryptedData)
    }

    /**
     * Check if we have an established Noise session with peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return noiseEncryption.hasEstablishedSession(peerID)
    }

    /**
     * Handle Noise handshake packet
     */
    suspend fun handleNoiseHandshake(
        packet: BitchatPacket,
        peerID: String,
        localPrivateKey: ByteArray,
        localPublicKey: ByteArray
    ): ByteArray? {
        // Skip handshakes not addressed to us
        if (packet.recipientID != null && !packet.recipientID.contentEquals(myPeerIDBytes)) {
            return null
        }

        // Skip our own handshake messages
        if (peerID == myPeerID) return null

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
    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    /**
     * Periodic cleanup of old message IDs
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupOldMessages()
            }
        }
    }

    /**
     * Clean up old message timestamps
     */
    private fun cleanupOldMessages() {
        val now = Clock.System.now().toEpochMilliseconds()
        val timeoutMs = MESSAGE_TIMEOUT.inWholeMilliseconds

        val staleMessages = messageTimestamps.filter { (_, timestamp) ->
            (now - timestamp) > timeoutMs
        }.keys.toList()

        staleMessages.forEach { messageID ->
            processedMessages.remove(messageID)
            messageTimestamps.remove(messageID)
        }
    }

    /**
     * Clean up old announcements beyond timeout window
     */
    private fun cleanupOldAnnouncements() {
        val now = Clock.System.now().toEpochMilliseconds()
        val cutoffTime = now - MESSAGE_TIMEOUT.inWholeMilliseconds

        val oldAnnouncements = announcementTimestamps.filter { it.value < cutoffTime }.keys
        oldAnnouncements.forEach { announcementID ->
            recentAnnouncements.remove(announcementID)
            announcementTimestamps.remove(announcementID)
        }
    }

    /**
     * Clear all security data
     */
    fun clearAll() {
        processedMessages.clear()
        messageTimestamps.clear()
        recentAnnouncements.clear()
        announcementTimestamps.clear()
        noiseEncryption.clearAllSessions()
    }

    /**
     * Shutdown manager
     */
    fun shutdown() {
        managerScope.cancel()
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
