@file:OptIn(ExperimentalStdlibApi::class)

package com.bitchat.noise

import kotlinx.cinterop.COpaque
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import noise.c.NoiseBuffer
import noise.c.NoiseHandshakeState
import noise.c.noise_cipherstate_decrypt_with_ad
import noise.c.noise_cipherstate_encrypt_with_ad
import noise.c.noise_cipherstate_get_mac_length
import noise.c.noise_cipherstate_set_nonce
import noise.c.noise_dhstate_get_public_key
import noise.c.noise_dhstate_set_keypair_private
import noise.c.noise_handshakestate_free
import noise.c.noise_handshakestate_get_action
import noise.c.noise_handshakestate_get_handshake_hash
import noise.c.noise_handshakestate_get_local_keypair_dh
import noise.c.noise_handshakestate_get_remote_public_key_dh
import noise.c.noise_handshakestate_new_by_name
import noise.c.noise_handshakestate_read_message
import noise.c.noise_handshakestate_split
import noise.c.noise_handshakestate_start
import noise.c.noise_handshakestate_write_message
import platform.posix.time

/**
 * Linux ARM64 implementation of Noise Protocol using noise-c via cinterop.
 *
 * This implementation mirrors the Apple implementation exactly, using the same
 * noise-c library bindings for full Noise XX handshake and transport encryption.
 */
@OptIn(ExperimentalForeignApi::class)
actual class NoiseSession actual constructor(
    private val peerID: String,
    private val isInitiator: Boolean,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {
    // Session state
    private var state: NoiseSessionState = NoiseSessionState.Uninitialized
    private val creationTime = time(null) * 1000L // Convert to milliseconds

    // Session counters
    private var messagesSent = 0L
    private var messagesReceived = 0L

    // Handshake pattern tracking (XX pattern has 3 messages)
    private var currentPattern = 0
    private val NOISE_XX_PATTERN_LENGTH = 3

    // Sliding window replay protection
    private var highestReceivedNonce = 0L
    private var replayWindow = ByteArray(NoiseConstants.REPLAY_WINDOW_BYTES)

    // Remote peer information
    private var remoteStaticPublicKey: ByteArray? = null
    private var handshakeHash: ByteArray? = null

    // noise-c library state (C pointers)
    private var handshakeStatePtr: CPointer<*>? = null
    private var sendCipherPtr: CPointer<*>? = null
    private var recvCipherPtr: CPointer<*>? = null

    init {
        try {
            validateStaticKeys()
            println("[NoiseSession-Linux] Created ${if (isInitiator) "initiator" else "responder"} session for $peerID")
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            println("[NoiseSession-Linux] Failed to initialize Noise session: ${e.message}")
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
        println("[NoiseSession-Linux] Static keys validated - private: ${localStaticPrivateKey.size} bytes, public: ${localStaticPublicKey.size} bytes")
    }

    private fun initializeHandshakeState() {
        println("[NoiseSession-Linux] Initializing noise-c handshake state...")

        try {
            // Constants from noise-c
            val noiseRole = if (isInitiator) 0x5201 else 0x5202
            val protocolName = "Noise_XX_25519_ChaChaPoly_SHA256"

            memScoped {
                val stateOutParam = allocArray<CPointerVar<NoiseHandshakeState>>(1)

                val result = noise_handshakestate_new_by_name(
                    stateOutParam,
                    protocolName,
                    noiseRole
                )

                if (result != 0) {
                    println("[NoiseSession-Linux] noise_handshakestate_new_by_name returned error code: $result")
                    throw SessionError.HandshakeFailed
                }

                val actualHandshakeStatePtr = stateOutParam[0] ?: throw SessionError.HandshakeFailed
                handshakeStatePtr = actualHandshakeStatePtr.reinterpret<COpaque>()

                println("[NoiseSession-Linux] Successfully created noise-c handshake state (role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")

                val localDHState = noise_handshakestate_get_local_keypair_dh(actualHandshakeStatePtr)
                    ?: throw SessionError.HandshakeFailed

                localStaticPrivateKey.usePinned { privKeyPinned ->
                    val setPrivKeyResult = noise_dhstate_set_keypair_private(
                        localDHState,
                        privKeyPinned.addressOf(0).reinterpret(),
                        localStaticPrivateKey.size.toULong()
                    )

                    if (setPrivKeyResult != 0) {
                        println("[NoiseSession-Linux] noise_dhstate_set_keypair_private failed with error code: $setPrivKeyResult")
                        throw SessionError.HandshakeFailed
                    }

                    println("[NoiseSession-Linux] Set local private key on handshake state")
                }

                val startResult = noise_handshakestate_start(actualHandshakeStatePtr)
                if (startResult != 0) {
                    println("[NoiseSession-Linux] noise_handshakestate_start returned error code: $startResult")
                    throw SessionError.HandshakeFailed
                }

                println("[NoiseSession-Linux] Started noise-c handshake state")
            }

        } catch (e: Exception) {
            println("[NoiseSession-Linux] Failed to initialize handshake state: ${e.message}")
            throw e
        }
    }

    actual fun startHandshake(): ByteArray {
        println("[NoiseSession-Linux] Starting noise XX handshake with $peerID as INITIATOR")

        if (!isInitiator) {
            throw IllegalStateException("Only initiator can start handshake")
        }

        if (state != NoiseSessionState.Uninitialized) {
            throw IllegalStateException("Handshake already started")
        }

        return try {
            state = NoiseSessionState.Handshaking
            initializeHandshakeState()

            val handshakeStatePtr = this.handshakeStatePtr
                ?: throw SessionError.HandshakeFailed

            memScoped {
                val messageBuffer = allocArray<UByteVar>(NoiseConstants.XX_MESSAGE_1_SIZE)

                val messageNoiseBuffer = alloc<NoiseBuffer>()
                messageNoiseBuffer.data = messageBuffer
                messageNoiseBuffer.size = 0U
                messageNoiseBuffer.max_size = NoiseConstants.XX_MESSAGE_1_SIZE.toULong()

                val result = noise_handshakestate_write_message(
                    handshakeStatePtr.reinterpret(),
                    messageNoiseBuffer.ptr,
                    null
                )

                if (result != 0) {
                    println("[NoiseSession-Linux] noise_handshakestate_write_message failed with error code: $result")
                    throw SessionError.HandshakeFailed
                }

                val messageLength = messageNoiseBuffer.size.toInt()
                val firstMessage = ByteArray(messageLength)
                for (i in 0 until messageLength) {
                    firstMessage[i] = messageBuffer[i].toByte()
                }

                currentPattern++

                println("[NoiseSession-Linux] Sent XX message 1 to $peerID (${firstMessage.size} bytes)")
                firstMessage
            }

        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            println("[NoiseSession-Linux] Failed to start handshake: ${e.message}")
            throw e
        }
    }

    actual fun processHandshakeMessage(message: ByteArray): ByteArray? {
        println("[NoiseSession-Linux] Processing handshake message from $peerID (${message.size} bytes)")

        return try {
            if (state == NoiseSessionState.Uninitialized && !isInitiator) {
                initializeHandshakeState()
                state = NoiseSessionState.Handshaking
                println("[NoiseSession-Linux] Initialized as RESPONDER for XX handshake with $peerID")
            }

            if (state != NoiseSessionState.Handshaking) {
                throw IllegalStateException("Invalid state for handshake: $state")
            }

            val handshakeStatePtr = this.handshakeStatePtr
                ?: throw SessionError.HandshakeFailed

            memScoped {
                val inputBuffer = allocArray<UByteVar>(message.size)
                message.forEachIndexed { i, byte ->
                    inputBuffer[i] = byte.toUByte()
                }

                val messageNoiseBuffer = alloc<NoiseBuffer>()
                messageNoiseBuffer.data = inputBuffer
                messageNoiseBuffer.size = message.size.toULong()
                messageNoiseBuffer.max_size = message.size.toULong()

                val payloadBuffer = allocArray<UByteVar>(NoiseConstants.MAX_PAYLOAD_SIZE)
                val payloadNoiseBuffer = alloc<NoiseBuffer>()
                payloadNoiseBuffer.data = payloadBuffer
                payloadNoiseBuffer.size = 0U
                payloadNoiseBuffer.max_size = NoiseConstants.MAX_PAYLOAD_SIZE.toULong()

                val result = noise_handshakestate_read_message(
                    handshakeStatePtr.reinterpret(),
                    messageNoiseBuffer.ptr,
                    payloadNoiseBuffer.ptr
                )

                if (result != 0) {
                    val errorName = when (result) {
                        0x4504 -> "MAC_FAILURE"
                        0x450A -> "INVALID_LENGTH"
                        0x450B -> "INVALID_PARAM"
                        0x450C -> "INVALID_STATE"
                        0x4510 -> "INVALID_FORMAT"
                        else -> "UNKNOWN($result / 0x${result.toString(16)})"
                    }
                    println("[NoiseSession-Linux] noise_handshakestate_read_message failed: $errorName")
                    throw SessionError.HandshakeFailed
                }

                currentPattern++
                val payloadLength = payloadNoiseBuffer.size.toInt()
                println("[NoiseSession-Linux] Read handshake message, payload length: $payloadLength")

                val action = noise_handshakestate_get_action(handshakeStatePtr.reinterpret())

                val NOISE_ACTION_NONE = 0
                val NOISE_ACTION_WRITE_MESSAGE = 0x4101
                val NOISE_ACTION_READ_MESSAGE = 0x4102
                val NOISE_ACTION_FAILED = 0x4103
                val NOISE_ACTION_SPLIT = 0x4104

                when (action) {
                    NOISE_ACTION_WRITE_MESSAGE -> {
                        val responseBuffer = allocArray<UByteVar>(NoiseConstants.XX_MESSAGE_2_SIZE)
                        val responseNoiseBuffer = alloc<NoiseBuffer>()
                        responseNoiseBuffer.data = responseBuffer
                        responseNoiseBuffer.size = 0U
                        responseNoiseBuffer.max_size = NoiseConstants.XX_MESSAGE_2_SIZE.toULong()

                        val result2 = noise_handshakestate_write_message(
                            handshakeStatePtr.reinterpret(),
                            responseNoiseBuffer.ptr,
                            null
                        )

                        if (result2 != 0) {
                            println("[NoiseSession-Linux] noise_handshakestate_write_message failed: $result2")
                            throw SessionError.HandshakeFailed
                        }

                        currentPattern++
                        val responseLength = responseNoiseBuffer.size.toInt()

                        val response = ByteArray(responseLength)
                        for (i in 0 until responseLength) {
                            response[i] = responseBuffer[i].toByte()
                        }

                        println("[NoiseSession-Linux] Generated handshake response: ${response.size} bytes")

                        val nextAction = noise_handshakestate_get_action(handshakeStatePtr.reinterpret())
                        if (nextAction == NOISE_ACTION_SPLIT) {
                            completeHandshake()
                        }

                        response
                    }

                    NOISE_ACTION_SPLIT -> {
                        completeHandshake()
                        println("[NoiseSession-Linux] XX handshake completed with $peerID")
                        null
                    }

                    NOISE_ACTION_FAILED -> {
                        println("[NoiseSession-Linux] Handshake action is FAILED")
                        throw SessionError.HandshakeFailed
                    }

                    NOISE_ACTION_READ_MESSAGE -> {
                        println("[NoiseSession-Linux] Handshake waiting for next message from $peerID")
                        null
                    }

                    NOISE_ACTION_NONE -> {
                        println("[NoiseSession-Linux] Handshake action: NONE - waiting")
                        null
                    }

                    else -> {
                        println("[NoiseSession-Linux] Unknown handshake action: $action")
                        null
                    }
                }
            }

        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            println("[NoiseSession-Linux] Handshake failed with $peerID: ${e.message}")
            throw e
        }
    }

    private fun completeHandshake() {
        if (currentPattern < NOISE_XX_PATTERN_LENGTH) {
            return
        }

        println("[NoiseSession-Linux] Completing XX handshake with $peerID")

        try {
            val handshakeStateLocal = handshakeStatePtr
                ?: throw IllegalStateException("Handshake state is null")

            memScoped {
                val sendCipherOut = alloc<CPointerVar<COpaque>>()
                val receiveCipherOut = alloc<CPointerVar<COpaque>>()

                val splitResult = noise_handshakestate_split(
                    handshakeStateLocal.reinterpret(),
                    sendCipherOut.ptr.reinterpret(),
                    receiveCipherOut.ptr.reinterpret()
                )

                if (splitResult != 0) {
                    println("[NoiseSession-Linux] noise_handshakestate_split failed with error code: $splitResult")
                    throw Exception("Failed to split handshake state")
                }

                val sendPtr = sendCipherOut.value
                val recvPtr = receiveCipherOut.value

                if (sendPtr != null) {
                    sendCipherPtr = sendPtr
                    println("[NoiseSession-Linux] Send cipher captured for encryption")
                }

                if (recvPtr != null) {
                    recvCipherPtr = recvPtr
                    println("[NoiseSession-Linux] Receive cipher captured for decryption")
                }
            }

            println("[NoiseSession-Linux] Split handshake state successfully - transport keys derived")

            val remoteDHPtr = noise_handshakestate_get_remote_public_key_dh(
                handshakeStateLocal.reinterpret()
            )
            if (remoteDHPtr != null) {
                remoteStaticPublicKey = ByteArray(32)
                remoteStaticPublicKey!!.usePinned { pinned ->
                    val result = noise_dhstate_get_public_key(
                        remoteDHPtr,
                        pinned.addressOf(0).reinterpret(),
                        32UL
                    )
                    if (result == 0) {
                        println("[NoiseSession-Linux] Remote static public key extracted (32 bytes)")
                    }
                }
            }

            handshakeHash = ByteArray(32)
            handshakeHash!!.usePinned { pinned ->
                val result = noise_handshakestate_get_handshake_hash(
                    handshakeStateLocal.reinterpret(),
                    pinned.addressOf(0).reinterpret(),
                    32UL
                )
                if (result == 0) {
                    println("[NoiseSession-Linux] Extracted handshake hash (32 bytes)")
                }
            }

            noise_handshakestate_free(handshakeStateLocal.reinterpret())
            handshakeStatePtr = null

            messagesSent = 0
            messagesReceived = 0
            currentPattern = 0

            highestReceivedNonce = 0L
            replayWindow = ByteArray(NoiseConstants.REPLAY_WINDOW_BYTES)

            state = NoiseSessionState.Established
            println("[NoiseSession-Linux] Handshake completed with $peerID (role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")

        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            println("[NoiseSession-Linux] Failed to complete handshake: ${e.message}")
            throw e
        }
    }

    actual fun encrypt(data: ByteArray): ByteArray {
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }

        if (messagesSent > UInt.MAX_VALUE.toLong() - 1) {
            throw SessionError.NonceExceeded("Nonce value $messagesSent exceeds 4-byte limit")
        }

        return try {
            val sendCipherLocal = sendCipherPtr
                ?: throw IllegalStateException("Send cipher not available - handshake not complete")

            val currentNonce = messagesSent
            messagesSent++

            val setNonceResult = noise_cipherstate_set_nonce(
                sendCipherLocal.reinterpret(),
                currentNonce.toULong()
            )

            if (setNonceResult != 0) {
                println("[NoiseSession-Linux] Failed to set nonce: error code $setNonceResult")
                throw SessionError.EncryptionFailed
            }

            val macLength = noise_cipherstate_get_mac_length(sendCipherLocal.reinterpret()).toInt()
            val ciphertextLength = data.size + macLength

            val ciphertextBuffer = ByteArray(ciphertextLength)

            memScoped {
                val noiseBuffer = alloc<NoiseBuffer>()

                data.usePinned { dataPinned ->
                    ciphertextBuffer.usePinned { ciphertextPinned ->
                        noiseBuffer.data = dataPinned.addressOf(0).reinterpret()
                        noiseBuffer.size = data.size.toULong()
                        noiseBuffer.max_size = ciphertextLength.toULong()

                        val encryptResult = noise_cipherstate_encrypt_with_ad(
                            sendCipherLocal.reinterpret(),
                            null,
                            0UL,
                            noiseBuffer.ptr
                        )

                        if (encryptResult != 0) {
                            println("[NoiseSession-Linux] Encryption failed: error code $encryptResult")
                            throw SessionError.EncryptionFailed
                        }

                        val encryptedSize = noiseBuffer.size.toInt()
                        for (i in 0 until encryptedSize) {
                            ciphertextBuffer[i] = noiseBuffer.data!![i].toInt().toByte()
                        }
                    }
                }
            }

            val nonceBytes = ReplayProtection.nonceToBytes(currentNonce)
            val combinedPayload = ByteArray(NoiseConstants.NONCE_SIZE_BYTES + ciphertextLength)

            nonceBytes.copyInto(combinedPayload, 0)
            ciphertextBuffer.copyInto(combinedPayload, NoiseConstants.NONCE_SIZE_BYTES)

            println("[NoiseSession-Linux] ENCRYPT: ${data.size} -> ${combinedPayload.size} bytes (nonce: $currentNonce)")
            combinedPayload

        } catch (e: Exception) {
            println("[NoiseSession-Linux] Encryption failed: ${e.message}")
            throw SessionError.EncryptionFailed
        }
    }

    actual fun decrypt(combinedPayload: ByteArray): ByteArray {
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }

        return try {
            val nonceAndCiphertext = ReplayProtection.extractNonceFromCiphertextPayload(combinedPayload)
                ?: throw SessionError.DecryptionFailed

            val (extractedNonce, ciphertext) = nonceAndCiphertext

            if (!ReplayProtection.isValidNonce(extractedNonce, highestReceivedNonce, replayWindow)) {
                println("[NoiseSession-Linux] Replay attack detected: nonce $extractedNonce rejected")
                throw SessionError.DecryptionFailed
            }

            val recvCipherLocal = recvCipherPtr
                ?: throw IllegalStateException("Receive cipher not available - handshake not complete")

            val setNonceResult = noise_cipherstate_set_nonce(
                recvCipherLocal.reinterpret(),
                extractedNonce.toULong()
            )

            if (setNonceResult != 0) {
                println("[NoiseSession-Linux] Failed to set nonce for decryption: error code $setNonceResult")
                throw SessionError.DecryptionFailed
            }

            val macLength = noise_cipherstate_get_mac_length(recvCipherLocal.reinterpret()).toInt()
            val plaintextLength = maxOf(0, ciphertext.size - macLength)

            val plaintextBuffer = ByteArray(plaintextLength)

            memScoped {
                val noiseBuffer = alloc<NoiseBuffer>()

                ciphertext.usePinned { ciphertextPinned ->
                    plaintextBuffer.usePinned { plaintextPinned ->
                        noiseBuffer.data = ciphertextPinned.addressOf(0).reinterpret()
                        noiseBuffer.size = ciphertext.size.toULong()
                        noiseBuffer.max_size = ciphertext.size.toULong()

                        val decryptResult = noise_cipherstate_decrypt_with_ad(
                            recvCipherLocal.reinterpret(),
                            null,
                            0UL,
                            noiseBuffer.ptr
                        )

                        if (decryptResult != 0) {
                            println("[NoiseSession-Linux] Decryption failed: error code $decryptResult")
                            throw SessionError.DecryptionFailed
                        }

                        val decryptedSize = noiseBuffer.size.toInt()
                        if (decryptedSize > plaintextLength) {
                            throw SessionError.DecryptionFailed
                        }

                        for (i in 0 until decryptedSize) {
                            plaintextBuffer[i] = noiseBuffer.data!![i].toInt().toByte()
                        }
                    }
                }
            }

            val (newHighestReceivedNonce, newReplayWindow) = ReplayProtection.markNonceAsSeen(
                extractedNonce,
                highestReceivedNonce,
                replayWindow
            )
            highestReceivedNonce = newHighestReceivedNonce
            replayWindow = newReplayWindow

            println("[NoiseSession-Linux] DECRYPT: ${combinedPayload.size} -> ${plaintextBuffer.size} bytes (nonce: $extractedNonce)")
            plaintextBuffer

        } catch (e: Exception) {
            println("[NoiseSession-Linux] Decryption failed: ${e.message}")
            throw SessionError.DecryptionFailed
        }
    }

    actual fun getRemoteStaticPublicKey(): ByteArray? = remoteStaticPublicKey?.copyOf()

    actual fun getHandshakeHash(): ByteArray? = handshakeHash?.copyOf()

    actual fun needsRekey(): Boolean {
        if (!isEstablished()) return false

        val currentTimeMs = time(null) * 1000L
        val timeLimit = currentTimeMs - creationTime > NoiseConstants.REKEY_TIME_LIMIT_MS
        val messageLimit = (messagesSent + messagesReceived) > NoiseConstants.REKEY_MESSAGE_LIMIT_SESSION

        return timeLimit || messageLimit
    }

    actual fun getSessionStats(): String = buildString {
        appendLine("NoiseSession with $peerID:")
        appendLine("  State: $state")
        appendLine("  Role: ${if (isInitiator) "initiator" else "responder"}")
        appendLine("  Messages sent: $messagesSent")
        appendLine("  Messages received: $messagesReceived")
        val currentTimeMs = time(null) * 1000L
        appendLine("  Session age: ${(currentTimeMs - creationTime) / 1000}s")
        appendLine("  Needs rekey: ${needsRekey()}")
        appendLine("  Has remote key: ${remoteStaticPublicKey != null}")
    }

    actual fun reset() {
        try {
            destroy()
            state = NoiseSessionState.Uninitialized
            messagesSent = 0
            messagesReceived = 0
            highestReceivedNonce = 0L
            replayWindow = ByteArray(NoiseConstants.REPLAY_WINDOW_BYTES)
            remoteStaticPublicKey = null
            handshakeHash = null
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            println("[NoiseSession-Linux] Failed to reset session: ${e.message}")
        }
    }

    actual fun destroy() {
        try {
            remoteStaticPublicKey?.fill(0)
            handshakeHash?.fill(0)

            remoteStaticPublicKey = null
            handshakeHash = null

            if (state !is NoiseSessionState.Failed) {
                state = NoiseSessionState.Failed(Exception("Session destroyed"))
            }

            println("[NoiseSession-Linux] Session destroyed for $peerID")

        } catch (e: Exception) {
            println("[NoiseSession-Linux] Error during session cleanup: ${e.message}")
        }
    }
}
