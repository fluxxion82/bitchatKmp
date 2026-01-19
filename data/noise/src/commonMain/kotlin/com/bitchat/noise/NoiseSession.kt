package com.bitchat.noise

expect class NoiseSession(
    peerID: String,
    isInitiator: Boolean,
    localStaticPrivateKey: ByteArray,
    localStaticPublicKey: ByteArray
) {
    fun getState(): NoiseSessionState
    fun isEstablished(): Boolean
    fun isHandshaking(): Boolean
    fun getCreationTime(): Long

    fun startHandshake(): ByteArray
    fun processHandshakeMessage(message: ByteArray): ByteArray?

    fun encrypt(data: ByteArray): ByteArray
    fun decrypt(combinedPayload: ByteArray): ByteArray

    fun getRemoteStaticPublicKey(): ByteArray?
    fun getHandshakeHash(): ByteArray?
    fun needsRekey(): Boolean
    fun getSessionStats(): String

    fun reset()
    fun destroy()
}

sealed class NoiseSessionState {
    object Uninitialized : NoiseSessionState()
    object Handshaking : NoiseSessionState()
    object Established : NoiseSessionState()
    data class Failed(val error: Throwable) : NoiseSessionState()

    override fun toString(): String = when (this) {
        is Uninitialized -> "uninitialized"
        is Handshaking -> "handshaking"
        is Established -> "established"
        is Failed -> "failed: ${error.message}"
    }
}

object NoiseConstants {
    const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
    const val NOISE_XX_PATTERN_LENGTH = 3

    // XX Pattern Message Sizes (exactly matching iOS implementation)
    const val XX_MESSAGE_1_SIZE = 32      // -> e (ephemeral key only)
    const val XX_MESSAGE_2_SIZE = 96      // <- e, ee, s, es (32 + 48) + 16 (MAC)
    const val XX_MESSAGE_3_SIZE = 48      // -> s, se (encrypted static key)

    // IMPORTANT: Must match legacy bitchat-android value (256 bytes) for interoperability
    const val MAX_PAYLOAD_SIZE = 256      // Matches AppConstants.MAX_PAYLOAD_SIZE_BYTES
    const val NONCE_SIZE_BYTES = 4
    const val REPLAY_WINDOW_SIZE = 1024
    const val REPLAY_WINDOW_BYTES = REPLAY_WINDOW_SIZE / 8 // 128 bytes

    // Rekey thresholds (same as iOS)
    const val REKEY_TIME_LIMIT_MS = 3600000L // 1 hour
    const val REKEY_MESSAGE_LIMIT_SESSION = 10000L

    const val HIGH_NONCE_WARNING_THRESHOLD = 1000000L
}

sealed class SessionError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object InvalidState : SessionError("Session in invalid state")
    object NotEstablished : SessionError("Session not established")
    object HandshakeFailed : SessionError("Handshake failed")
    object EncryptionFailed : SessionError("Encryption failed")
    object DecryptionFailed : SessionError("Decryption failed")
    class HandshakeInitializationFailed(message: String) : SessionError("Handshake initialization failed: $message")
    class NonceExceeded(message: String) : SessionError(message)
}

object ReplayProtection {
    fun isValidNonce(receivedNonce: Long, highestReceivedNonce: Long, replayWindow: ByteArray): Boolean {
        if (receivedNonce + NoiseConstants.REPLAY_WINDOW_SIZE <= highestReceivedNonce) {
            return false  // Too old, outside window
        }

        if (receivedNonce > highestReceivedNonce) {
            return true  // Always accept newer nonces
        }

        val offset = (highestReceivedNonce - receivedNonce).toInt()
        val byteIndex = offset / 8
        val bitIndex = offset % 8

        return (replayWindow[byteIndex].toInt() and (1 shl bitIndex)) == 0  // Not yet seen
    }

    fun markNonceAsSeen(receivedNonce: Long, highestReceivedNonce: Long, replayWindow: ByteArray): Pair<Long, ByteArray> {
        var newHighestReceivedNonce = highestReceivedNonce
        val newReplayWindow = replayWindow.copyOf()

        if (receivedNonce > highestReceivedNonce) {
            val shift = (receivedNonce - highestReceivedNonce).toInt()

            if (shift >= NoiseConstants.REPLAY_WINDOW_SIZE) {
                // Clear entire window - shift is too large
                newReplayWindow.fill(0)
            } else {
                // Shift window right by `shift` bits
                for (i in (NoiseConstants.REPLAY_WINDOW_BYTES - 1) downTo 0) {
                    val sourceByteIndex = i - shift / 8
                    var newByte = 0

                    if (sourceByteIndex >= 0) {
                        newByte = (newReplayWindow[sourceByteIndex].toInt() and 0xFF) ushr (shift % 8)
                        if (sourceByteIndex > 0 && shift % 8 != 0) {
                            newByte = newByte or ((newReplayWindow[sourceByteIndex - 1].toInt() and 0xFF) shl (8 - shift % 8))
                        }
                    }

                    newReplayWindow[i] = (newByte and 0xFF).toByte()
                }
            }

            newHighestReceivedNonce = receivedNonce
            newReplayWindow[0] = (newReplayWindow[0].toInt() or 1).toByte()  // Mark most recent bit as seen
        } else {
            val offset = (highestReceivedNonce - receivedNonce).toInt()
            val byteIndex = offset / 8
            val bitIndex = offset % 8
            newReplayWindow[byteIndex] = (newReplayWindow[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        }

        return Pair(newHighestReceivedNonce, newReplayWindow)
    }

    fun extractNonceFromCiphertextPayload(combinedPayload: ByteArray): Pair<Long, ByteArray>? {
        if (combinedPayload.size < NoiseConstants.NONCE_SIZE_BYTES) {
            return null
        }

        return try {
            // Extract 4-byte nonce (big-endian)
            var extractedNonce = 0L
            for (i in 0 until NoiseConstants.NONCE_SIZE_BYTES) {
                extractedNonce = (extractedNonce shl 8) or (combinedPayload[i].toLong() and 0xFF)
            }
            // Extract ciphertext (remaining bytes)
            val ciphertext = combinedPayload.copyOfRange(NoiseConstants.NONCE_SIZE_BYTES, combinedPayload.size)
            Pair(extractedNonce, ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    fun nonceToBytes(nonce: Long): ByteArray {
        val bytes = ByteArray(NoiseConstants.NONCE_SIZE_BYTES)
        var value = nonce
        for (i in (NoiseConstants.NONCE_SIZE_BYTES - 1) downTo 0) {
            bytes[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        return bytes
    }
}