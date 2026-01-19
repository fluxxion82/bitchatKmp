@file:OptIn(ExperimentalStdlibApi::class)

package com.bitchat.noise

import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.HandshakeState
import java.util.logging.Logger

actual class NoiseSession actual constructor(
    private val peerID: String,
    private val isInitiator: Boolean,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {
    private val logger = Logger.getLogger(TAG)

    // Noise Protocol objects
    private var handshakeState: HandshakeState? = null
    private var sendCipher: CipherState? = null
    private var receiveCipher: CipherState? = null

    // Session state
    private var state: NoiseSessionState = NoiseSessionState.Uninitialized
    private val creationTime = System.currentTimeMillis()

    // Session counters
    private var currentPattern = 0
    private var messagesSent = 0L
    private var messagesReceived = 0L

    // Sliding window replay protection
    private var highestReceivedNonce = 0L
    private var replayWindow = ByteArray(NoiseConstants.REPLAY_WINDOW_BYTES)

    // CRITICAL FIX: Enhanced thread safety for cipher operations
    // The noise-java CipherState objects are NOT thread-safe. Multiple concurrent
    // encrypt/decrypt operations can corrupt the internal nonce state.
    private val cipherLock = Any()

    // Remote peer information
    private var remoteStaticPublicKey: ByteArray? = null
    private var handshakeHash: ByteArray? = null

    init {
        try {
            validateStaticKeys()
            logger.info("Created ${if (isInitiator) "initiator" else "responder"} session for $peerID")
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            logger.severe("Failed to initialize Noise session: ${e.message}")
        }
    }

    actual fun getState(): NoiseSessionState = state

    actual fun isEstablished(): Boolean = state is NoiseSessionState.Established

    actual fun isHandshaking(): Boolean = state is NoiseSessionState.Handshaking

    actual fun getCreationTime(): Long = creationTime

    private fun validateStaticKeys() {
        if (localStaticPrivateKey.size != 32) {
            throw IllegalArgumentException("Local static private key must be 32 bytes, got ${localStaticPrivateKey.size}")
        }
        if (localStaticPublicKey.size != 32) {
            throw IllegalArgumentException("Local static public key must be 32 bytes, got ${localStaticPublicKey.size}")
        }

        if (localStaticPrivateKey.all { it == 0.toByte() }) {
            throw IllegalArgumentException("Local static private key cannot be all zeros")
        }
        if (localStaticPublicKey.all { it == 0.toByte() }) {
            throw IllegalArgumentException("Local static public key cannot be all zeros")
        }

        logger.fine("Static keys validated successfully - private: ${localStaticPrivateKey.size} bytes, public: ${localStaticPublicKey.size} bytes")
    }

    private fun initializeNoiseHandshake(role: Int) {
        try {
            logger.fine("Creating HandshakeState with role: ${if (role == HandshakeState.INITIATOR) "INITIATOR" else "RESPONDER"}")

            handshakeState = HandshakeState(NoiseConstants.PROTOCOL_NAME, role)
            logger.fine("HandshakeState created successfully")

            // Set the persistent identity keys
            if (handshakeState?.needsLocalKeyPair() == true) {
                logger.fine("Local static key pair is required for XX pattern")

                val localKeyPair = handshakeState?.localKeyPair
                if (localKeyPair != null) {
                    // Set the persistent identity keys
                    localKeyPair.setPrivateKey(localStaticPrivateKey, 0)

                    if (!localKeyPair.hasPrivateKey() || !localKeyPair.hasPublicKey()) {
                        throw IllegalStateException("Failed to set static identity keys")
                    }

                    logger.fine("✓ Successfully set persistent static identity keys")
                    logger.fine("Private key length: ${localKeyPair.privateKeyLength}")
                    logger.fine("Public key length: ${localKeyPair.publicKeyLength}")

                } else {
                    throw IllegalStateException("HandshakeState returned null for local key pair")
                }

            } else {
                logger.fine("Local static key pair not needed for this handshake pattern/role")
            }

            handshakeState?.start()
            logger.fine("Handshake state started successfully with persistent identity keys")

        } catch (e: Exception) {
            logger.severe("Exception during handshake initialization: ${e.message}")
            throw e
        }
    }

    @Synchronized
    actual fun startHandshake(): ByteArray {
        logger.fine("Starting noise XX handshake with $peerID as INITIATOR")

        if (!isInitiator) {
            throw IllegalStateException("Only initiator can start handshake")
        }

        if (state != NoiseSessionState.Uninitialized) {
            throw IllegalStateException("Handshake already started")
        }

        return try {
            // Initialize handshake as initiator
            initializeNoiseHandshake(HandshakeState.INITIATOR)
            state = NoiseSessionState.Handshaking

            val messageBuffer = ByteArray(NoiseConstants.XX_MESSAGE_1_SIZE)
            val handshakeStateLocal = handshakeState ?: throw IllegalStateException("Handshake state is null")
            val messageLength = handshakeStateLocal.writeMessage(messageBuffer, 0, null, 0, 0)
            currentPattern++
            val firstMessage = messageBuffer.copyOf(messageLength)

            // Validate message size matches XX pattern expectations
            if (firstMessage.size != NoiseConstants.XX_MESSAGE_1_SIZE) {
                logger.warning("Warning: XX message 1 size ${firstMessage.size} != expected ${NoiseConstants.XX_MESSAGE_1_SIZE}")
            }

            logger.fine("Sending XX handshake message 1 to $peerID (${firstMessage.size} bytes) currentPattern: $currentPattern")
            firstMessage

        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            logger.severe("Failed to start handshake: ${e.message}")
            throw e
        }
    }

    @Synchronized
    actual fun processHandshakeMessage(message: ByteArray): ByteArray? {
        logger.info("🔍 KMP NOISE: Processing handshake message from $peerID")
        logger.info("  Message size: ${message.size} bytes")
        logger.info("  Current pattern: $currentPattern")
        logger.info("  Role: ${if (isInitiator) "INITIATOR" else "RESPONDER"}")
        logger.info("  State: $state")

        return try {
            // Initialize as responder if receiving first message
            if (!isInitiator && state == NoiseSessionState.Handshaking && message.size == NoiseConstants.XX_MESSAGE_1_SIZE) {
                logger.warning("⚠️ KMP NOISE: Incoming message 1 while already handshaking with $peerID (pattern=$currentPattern) - resetting session")
                reset()  // bring session back to uninitialized so we can restart the handshake cleanly
                logger.info("Session reset complete; will reprocess handshake from $peerID")
            }

            if (state == NoiseSessionState.Uninitialized && !isInitiator) {
                initializeNoiseHandshake(HandshakeState.RESPONDER)
                state = NoiseSessionState.Handshaking
                logger.info("Initialized as RESPONDER for XX handshake with $peerID")
            }

            if (state != NoiseSessionState.Handshaking) {
                throw IllegalStateException("Invalid state for handshake: $state")
            }

            val handshakeStateLocal = handshakeState ?: throw IllegalStateException("Handshake state is null")

            // Let the noise-java library validate message sizes and handle the flow
            val payloadBufferSize = NoiseConstants.XX_MESSAGE_2_SIZE + NoiseConstants.MAX_PAYLOAD_SIZE
            val payloadBuffer = ByteArray(payloadBufferSize)

            logger.info("  Allocated payload buffer: $payloadBufferSize bytes (XX_MSG_2=${NoiseConstants.XX_MESSAGE_2_SIZE} + MAX_PAYLOAD=${NoiseConstants.MAX_PAYLOAD_SIZE})")
            logger.info("  About to call readMessage with message.size=${message.size}, payloadBuffer.size=${payloadBuffer.size}")

            // Log received message hex dump for debugging interop
            logger.info("📥 JVM received handshake message (${message.size} bytes):")
            logger.info("   Hex: ${message.toHexString()}")
            logger.info("   e (32): ${message.take(32).toByteArray().toHexString()}")
            if (message.size > 32) {
                logger.info("   encrypted_s (${message.size - 32}): ${message.drop(32).toByteArray().toHexString()}")
            }

            // Read the incoming message - the library will handle validation
            val payloadLength = handshakeStateLocal.readMessage(message, 0, message.size, payloadBuffer, 0)
            currentPattern++
            logger.fine("Read handshake message, payload length: $payloadLength currentPattern: $currentPattern")

            // Check what action the handshake state wants us to take next
            val action = handshakeStateLocal.getAction()
            logger.fine("Handshake action after processing message: $action")

            return when (action) {
                HandshakeState.WRITE_MESSAGE -> {
                    // Noise library says we need to send a response
                    val responseBuffer = ByteArray(NoiseConstants.XX_MESSAGE_2_SIZE + NoiseConstants.MAX_PAYLOAD_SIZE)
                    val responseLength = handshakeStateLocal.writeMessage(responseBuffer, 0, null, 0, 0)
                    currentPattern++
                    val response = responseBuffer.copyOf(responseLength)

                    // Log hex dump for debugging interop
                    logger.info("📤 JVM sending handshake message 2 (${response.size} bytes):")
                    logger.info("   Hex: ${response.toHexString()}")
                    logger.info("   e (32): ${response.take(32).toByteArray().toHexString()}")
                    logger.info("   encrypted_s (${response.size - 32}): ${response.drop(32).toByteArray().toHexString()}")

                    logger.fine("Generated handshake response: ${response.size} bytes, action still: ${handshakeStateLocal.getAction()} currentPattern: $currentPattern")

                    // Check if we should complete handshake after this
                    val nextAction = handshakeStateLocal.getAction()
                    if (nextAction == HandshakeState.SPLIT) {
                        completeHandshake()
                    }

                    response
                }

                HandshakeState.SPLIT -> {
                    // Handshake complete, split into transport keys
                    completeHandshake()
                    logger.fine("SPLIT ✅ XX handshake completed with $peerID")
                    null
                }

                HandshakeState.FAILED -> {
                    throw Exception("Handshake failed - noise-java reported FAILED state")
                }

                HandshakeState.READ_MESSAGE -> {
                    // noise-java library expects us to read another message
                    logger.fine("Handshake waiting for next message from $peerID")
                    null
                }

                else -> {
                    logger.fine("Handshake action: $action - no immediate action needed")
                    null
                }
            }

        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            logger.severe("Handshake failed with $peerID: ${e.message}")
            throw e
        }
    }

    private fun completeHandshake() {
        if (currentPattern < 3) {
            return
        }

        logger.fine("Completing XX handshake with $peerID")

        try {
            val handshakeStateLocal = handshakeState ?: throw IllegalStateException("Handshake state is null")

            // Split handshake state into transport ciphers
            val cipherPair = handshakeStateLocal.split()

            sendCipher = cipherPair.getSender()
            receiveCipher = cipherPair.getReceiver()

            // Extract remote static key if available
            if (handshakeStateLocal.hasRemotePublicKey()) {
                val remoteDH = handshakeStateLocal.getRemotePublicKey()
                if (remoteDH != null) {
                    remoteStaticPublicKey = ByteArray(32)
                    remoteDH.getPublicKey(remoteStaticPublicKey!!, 0)
                    logger.fine("Remote static public key: ${remoteStaticPublicKey!!.joinToString("") { "%02x".format(it) }}")
                }
            }

            // Extract handshake hash for channel binding
            handshakeHash = handshakeStateLocal.getHandshakeHash()

            // Clean up handshake state
            handshakeStateLocal.destroy()
            handshakeState = null

            messagesSent = 0
            messagesReceived = 0
            currentPattern = 0

            // Reset sliding window replay protection for new transport phase
            highestReceivedNonce = 0L
            replayWindow = ByteArray(NoiseConstants.REPLAY_WINDOW_BYTES)

            state = NoiseSessionState.Established
            logger.fine("Handshake completed with $peerID as isInitiator: $isInitiator - transport keys derived")
            logger.fine("✅ XX handshake completed with $peerID")

        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            logger.severe("Failed to complete handshake: ${e.message}")
            throw e
        }
    }

    actual fun encrypt(data: ByteArray): ByteArray {
        // Pre-check state without holding cipher lock
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }

        // Critical section: Use dedicated cipher lock to protect CipherState nonce corruption
        synchronized(cipherLock) {
            // Double-check state inside lock
            if (!isEstablished()) {
                throw IllegalStateException("Session not established during cipher operation")
            }

            if (sendCipher == null) {
                throw IllegalStateException("Send cipher not available")
            }

            if (messagesSent > UInt.MAX_VALUE.toLong() - 1) {
                throw SessionError.NonceExceeded("Nonce value $messagesSent exceeds 4-byte limit")
            }

            return try {
                // Encrypt the data first
                val ciphertext = ByteArray(data.size + sendCipher!!.getMACLength())
                sendCipher!!.setNonce(messagesSent)
                val ciphertextLength = sendCipher!!.encryptWithAd(null, data, 0, ciphertext, 0, data.size)

                // Get the current nonce before incrementing
                val currentNonce = messagesSent
                messagesSent++

                // Create combined payload: <nonce><ciphertext> (4 bytes for nonce)
                val nonceBytes = ReplayProtection.nonceToBytes(currentNonce)
                val combinedPayload = ByteArray(NoiseConstants.NONCE_SIZE_BYTES + ciphertextLength)

                // Copy nonce (first 4 bytes)
                System.arraycopy(nonceBytes, 0, combinedPayload, 0, NoiseConstants.NONCE_SIZE_BYTES)

                // Copy ciphertext (remaining bytes)
                System.arraycopy(ciphertext, 0, combinedPayload, NoiseConstants.NONCE_SIZE_BYTES, ciphertextLength)

                // Log high nonce values that might indicate issues
                if (currentNonce > NoiseConstants.HIGH_NONCE_WARNING_THRESHOLD) {
                    logger.warning("High nonce value detected: $currentNonce - consider rekeying")
                }

                logger.fine("✅ JVM ENCRYPT: ${data.size} → ${combinedPayload.size} bytes (nonce: $currentNonce, ciphertextLength+TAG: ${ciphertextLength}) for $peerID (msg #$messagesSent, role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")
                combinedPayload

            } catch (e: Exception) {
                logger.severe("Real encryption failed - exception: ${e.message}")

                // ENHANCED: Log cipher state for debugging
                if (sendCipher != null) {
                    logger.severe("Send cipher state: ${sendCipher!!.javaClass.simpleName}")
                }

                throw SessionError.EncryptionFailed
            }
        }
    }

    actual fun decrypt(combinedPayload: ByteArray): ByteArray {
        // Pre-check state without holding cipher lock
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }

        // Critical section: Use dedicated cipher lock to protect CipherState nonce corruption
        synchronized(cipherLock) {
            // Double-check state inside lock
            if (!isEstablished()) {
                throw IllegalStateException("Session not established during cipher operation")
            }

            if (receiveCipher == null) {
                throw IllegalStateException("Receive cipher not available")
            }

            return try {
                // Extract nonce and ciphertext from combined payload
                val nonceAndCiphertext = ReplayProtection.extractNonceFromCiphertextPayload(combinedPayload)
                if (nonceAndCiphertext == null) {
                    logger.severe("Failed to extract nonce from payload for $peerID")
                    throw SessionError.DecryptionFailed
                }

                val (extractedNonce, ciphertext) = nonceAndCiphertext

                // Validate nonce with sliding window replay protection
                if (!ReplayProtection.isValidNonce(extractedNonce, highestReceivedNonce, replayWindow)) {
                    logger.warning("Replay attack detected: nonce $extractedNonce rejected for $peerID")
                    throw SessionError.DecryptionFailed
                }

                // Use the extracted nonce for decryption
                val plaintext = ByteArray(ciphertext.size)

                receiveCipher!!.setNonce(extractedNonce)
                val plaintextLength = receiveCipher!!.decryptWithAd(null, ciphertext, 0, plaintext, 0, ciphertext.size)

                // Mark nonce as seen after successful decryption
                val (newHighestReceivedNonce, newReplayWindow) = ReplayProtection.markNonceAsSeen(
                    extractedNonce,
                    highestReceivedNonce,
                    replayWindow
                )
                highestReceivedNonce = newHighestReceivedNonce
                replayWindow = newReplayWindow

                // Log high nonce values that might indicate issues
                if (extractedNonce > NoiseConstants.HIGH_NONCE_WARNING_THRESHOLD) {
                    logger.warning("High nonce value detected: $extractedNonce - consider rekeying")
                }

                val result = plaintext.copyOf(plaintextLength)
                logger.fine("✅ JVM DECRYPT: ${combinedPayload.size} → ${result.size} bytes from $peerID (nonce: $extractedNonce, highest: $highestReceivedNonce, role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")
                result

            } catch (e: Exception) {
                logger.severe("Decryption failed - exception: ${e.message}")
                logger.severe("Session state: $state, highest received nonce: $highestReceivedNonce")
                logger.severe("Input data size: ${combinedPayload.size} bytes")

                // ENHANCED: Log cipher state and session details for debugging
                if (receiveCipher != null) {
                    logger.severe("Receive cipher state: ${receiveCipher!!.javaClass.simpleName}")
                }

                throw SessionError.DecryptionFailed
            }
        }
    }

    actual fun getRemoteStaticPublicKey(): ByteArray? = remoteStaticPublicKey?.clone()

    actual fun getHandshakeHash(): ByteArray? = handshakeHash?.clone()

    actual fun needsRekey(): Boolean {
        if (!isEstablished()) return false

        val timeLimit = System.currentTimeMillis() - creationTime > NoiseConstants.REKEY_TIME_LIMIT_MS
        val messageLimit = (messagesSent + messagesReceived) > NoiseConstants.REKEY_MESSAGE_LIMIT_SESSION

        return timeLimit || messageLimit
    }

    actual fun getSessionStats(): String = buildString {
        appendLine("NoiseSession with $peerID:")
        appendLine("  State: $state")
        appendLine("  Role: ${if (isInitiator) "initiator" else "responder"}")
        appendLine("  Messages sent: $messagesSent")
        appendLine("  Messages received: $messagesReceived")
        appendLine("  Session age: ${(System.currentTimeMillis() - creationTime) / 1000}s")
        appendLine("  Needs rekey: ${needsRekey()}")
        appendLine("  Has remote key: ${remoteStaticPublicKey != null}")
    }

    @Synchronized
    actual fun reset() {
        try {
            handshakeState?.destroy()
            handshakeState = null
            sendCipher = null
            receiveCipher = null
            state = NoiseSessionState.Uninitialized
            messagesSent = 0
            messagesReceived = 0
            currentPattern = 0
            highestReceivedNonce = 0L
            replayWindow = ByteArray(NoiseConstants.REPLAY_WINDOW_BYTES)
            remoteStaticPublicKey = null
            handshakeHash = null
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            logger.severe("Failed to reset session: ${e.message}")
        }
    }

    @Synchronized
    actual fun destroy() {
        try {
            handshakeState?.destroy()
            handshakeState = null
            // TODO: Destroy noise-java objects properly
            sendCipher = null
            receiveCipher = null
            remoteStaticPublicKey?.fill(0)
            handshakeHash?.fill(0)

            remoteStaticPublicKey = null
            handshakeHash = null

            if (state !is NoiseSessionState.Failed) {
                state = NoiseSessionState.Failed(Exception("Session destroyed"))
            }

            logger.fine("Session destroyed for $peerID")

        } catch (e: Exception) {
            logger.warning("Error during session cleanup: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NoiseSession"
    }
}
