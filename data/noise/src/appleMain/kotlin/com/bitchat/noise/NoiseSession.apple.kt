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
import kotlin.time.Clock

/**
 * iOS/Native implementation of Noise Protocol
 * Uses noise-c library via cinterop bindings
 *
 * The cinterop is configured in:
 * - build.gradle.kts (cinterop configuration)
 * - src/nativeInterop/cinterop/noise.def (cinterop definition)
 *
 * Generated bindings are available as: import noise.c.*
 * C functions called:
 * - noise_handshakestate_new_by_name() - Create handshake state
 * - noise_handshakestate_write_message() - Generate handshake message
 * - noise_handshakestate_read_message() - Process handshake message
 * - noise_handshakestate_split() - Extract send/recv cipher states
 * - noise_cipherstate_encrypt_and_hash() - Authenticated encryption
 * - noise_cipherstate_decrypt_and_hash() - Authenticated decryption
 * - noise_handshakestate_free() - Clean up handshake state
 */
actual class NoiseSession actual constructor(
    private val peerID: String,
    private val isInitiator: Boolean,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {
    // Session state
    private var state: NoiseSessionState = NoiseSessionState.Uninitialized
    private val creationTime = Clock.System.now().toEpochMilliseconds()

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
    // Store as opaque pointers - the cinterop system handles type casting
    @OptIn(ExperimentalForeignApi::class)
    private var handshakeStatePtr: CPointer<*>? = null

    @OptIn(ExperimentalForeignApi::class)
    private var sendCipherPtr: CPointer<*>? = null

    @OptIn(ExperimentalForeignApi::class)
    private var recvCipherPtr: CPointer<*>? = null

    // CRITICAL FIX: Thread safety for cipher operations
    // The noise-c CipherState objects maintain internal nonce state that can be corrupted
    // by concurrent encrypt/decrypt operations. Multiple threads calling encrypt/decrypt
    // simultaneously will corrupt the nonce counter and produce invalid ciphertexts.
    private val cipherLock = Any()

    init {
        try {
            validateStaticKeys()
            println("[NoiseSession-Native] Created ${if (isInitiator) "initiator" else "responder"} session for $peerID")
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            println("[NoiseSession-Native] Failed to initialize Noise session: ${e.message}")
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

        println("[NoiseSession-Native] Static keys validated - private: ${localStaticPrivateKey.size} bytes, public: ${localStaticPublicKey.size} bytes")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun initializeHandshakeState() {
        println("[NoiseSession-Native] Initializing noise-c handshake state...")

        try {
            // Constants from noise-c
            // NOISE_ROLE_INITIATOR = ('R' << 8) | 1 = 0x5201 = 21001
            // NOISE_ROLE_RESPONDER = ('R' << 8) | 2 = 0x5202 = 21002
            val noiseRole = if (isInitiator) 0x5201 else 0x5202
            val protocolName = "Noise_XX_25519_ChaChaPoly_SHA256"

            memScoped {
                // Allocate a pointer to hold the handshake state pointer
                // The function signature is: int noise_handshakestate_new_by_name(NoiseHandshakeState **state, const char *protocol_name, int role)
                val stateOutParam = allocArray<CPointerVar<NoiseHandshakeState>>(1)

                // Call actual noise_handshakestate_new_by_name from noise-c library
                val result = noise_handshakestate_new_by_name(
                    stateOutParam,
                    protocolName,
                    noiseRole
                )

                // Check for errors (NOISE_ERROR_NONE = 0)
                if (result != 0) {
                    println("[NoiseSession-Native] noise_handshakestate_new_by_name returned error code: $result")
                    throw SessionError.HandshakeFailed
                }

                // Get the actual pointer from the output parameter
                val actualHandshakeStatePtr = stateOutParam[0] ?: throw SessionError.HandshakeFailed

                // Store the pointer for later use - cast to opaque CPointer for storage
                handshakeStatePtr = actualHandshakeStatePtr.reinterpret<COpaque>()

                println("[NoiseSession-Native] ✅ Successfully created noise-c handshake state via noise_handshakestate_new_by_name (role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")

                // Set the local keypair on the handshake state
                // IMPORTANT: Only set the PRIVATE key. The PUBLIC key is derived from it by noise-c.
                // Do NOT try to set the public key separately - that will cause NOISE_ERROR_INVALID_PARAM.
                val localDHState = noise_handshakestate_get_local_keypair_dh(actualHandshakeStatePtr)
                    ?: throw SessionError.HandshakeFailed

                localStaticPrivateKey.usePinned { privKeyPinned ->
                    val setPrivKeyResult = noise_dhstate_set_keypair_private(
                        localDHState,
                        privKeyPinned.addressOf(0).reinterpret(),
                        localStaticPrivateKey.size.toULong()
                    )

                    if (setPrivKeyResult != 0) {
                        println("[NoiseSession-Native] noise_dhstate_set_keypair_private failed with error code: $setPrivKeyResult")
                        throw SessionError.HandshakeFailed
                    }

                    println("[NoiseSession-Native] ✅ Set local private key on handshake state (public key will be derived by noise-c)")
                }

                // Call noise_handshakestate_start() to initialize the handshake state
                val startResult = noise_handshakestate_start(actualHandshakeStatePtr)
                if (startResult != 0) {
                    println("[NoiseSession-Native] noise_handshakestate_start returned error code: $startResult")
                    throw SessionError.HandshakeFailed
                }

                println("[NoiseSession-Native] ✅ Started noise-c handshake state")
            }

        } catch (e: Exception) {
            println("[NoiseSession-Native] ❌ Failed to initialize handshake state: ${e.message}")
            throw e
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun startHandshake(): ByteArray {
        println("[NoiseSession-Native] Starting noise XX handshake with $peerID as INITIATOR")

        if (!isInitiator) {
            throw IllegalStateException("Only initiator can start handshake")
        }

        if (state != NoiseSessionState.Uninitialized) {
            throw IllegalStateException("Handshake already started")
        }

        return try {
            state = NoiseSessionState.Handshaking

            // Create handshake state using noise-c
            initializeHandshakeState()

            // Generate first handshake message using noise_handshakestate_write_message
            val handshakeStatePtr = this.handshakeStatePtr
                ?: throw SessionError.HandshakeFailed

            memScoped {
                // Allocate buffer for XX message 1 output
                val messageBuffer = allocArray<UByteVar>(NoiseConstants.XX_MESSAGE_1_SIZE)

                // Create NoiseBuffer structure for the message output
                val messageNoiseBuffer = alloc<NoiseBuffer>()
                messageNoiseBuffer.data = messageBuffer
                messageNoiseBuffer.size = 0U  // Start at 0, write_message fills it
                messageNoiseBuffer.max_size = NoiseConstants.XX_MESSAGE_1_SIZE.toULong()

                // Call write_message with NULL payload (no payload in XX message 1)
                val result = noise_handshakestate_write_message(
                    handshakeStatePtr.reinterpret(),
                    messageNoiseBuffer.ptr,
                    null  // No payload for message 1
                )

                if (result != 0) {
                    println("[NoiseSession-Native] noise_handshakestate_write_message failed with error code: $result")
                    throw SessionError.HandshakeFailed
                }

                // Copy generated message to Kotlin ByteArray
                val messageLength = messageNoiseBuffer.size.toInt()
                val firstMessage = ByteArray(messageLength)
                for (i in 0 until messageLength) {
                    firstMessage[i] = messageBuffer[i].toByte()
                }

                currentPattern++

                println("[NoiseSession-Native] ✅ noise-c handshake state created, sent XX message 1 to $peerID (${firstMessage.size} bytes) currentPattern: $currentPattern")
                firstMessage
            }

        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            println("[NoiseSession-Native] Failed to start handshake: ${e.message}")
            throw e
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun processHandshakeMessage(message: ByteArray): ByteArray? {
        println("[NoiseSession-Native] Processing handshake message from $peerID (${message.size} bytes)")

        return try {
            // Initialize as responder if receiving first message
            if (state == NoiseSessionState.Uninitialized && !isInitiator) {
                initializeHandshakeState()
                state = NoiseSessionState.Handshaking
                println("[NoiseSession-Native] Initialized as RESPONDER for XX handshake with $peerID")
            }

            if (state != NoiseSessionState.Handshaking) {
                throw IllegalStateException("Invalid state for handshake: $state")
            }

            val handshakeStatePtr = this.handshakeStatePtr
                ?: throw SessionError.HandshakeFailed

            memScoped {
                // Allocate buffers for input/output
                val inputBuffer = allocArray<UByteVar>(message.size)
                message.forEachIndexed { i, byte ->
                    inputBuffer[i] = byte.toUByte()
                }

                // Create NoiseBuffer for input message
                // CRITICAL: max_size must be set - noise-c decrements it during processing
                val messageNoiseBuffer = alloc<NoiseBuffer>()
                messageNoiseBuffer.data = inputBuffer
                messageNoiseBuffer.size = message.size.toULong()
                messageNoiseBuffer.max_size = message.size.toULong()

                // Create NoiseBuffer for output payload (output buffer: size starts at 0)
                val payloadBuffer = allocArray<UByteVar>(NoiseConstants.MAX_PAYLOAD_SIZE)
                val payloadNoiseBuffer = alloc<NoiseBuffer>()
                payloadNoiseBuffer.data = payloadBuffer
                payloadNoiseBuffer.size = 0U  // Output buffer starts at 0, read_message fills it
                payloadNoiseBuffer.max_size = NoiseConstants.MAX_PAYLOAD_SIZE.toULong()

                // Log received message for debugging interop
                println("[NoiseSession-Native] 📥 Received handshake message (${message.size} bytes):")
                println("[NoiseSession-Native]    Hex: ${message.toHexString()}")
                println("[NoiseSession-Native]    e (32): ${message.take(32).toByteArray().toHexString()}")
                if (message.size > 32) {
                    println("[NoiseSession-Native]    encrypted_s (${message.size - 32}): ${message.drop(32).toByteArray().toHexString()}")
                }

                // Read the incoming handshake message
                val result = noise_handshakestate_read_message(
                    handshakeStatePtr.reinterpret(),
                    messageNoiseBuffer.ptr,
                    payloadNoiseBuffer.ptr
                )

                if (result != 0) {
                    // Log the actual error code for debugging
                    val errorName = when (result) {
                        0x4504 -> "MAC_FAILURE"
                        0x450A -> "INVALID_LENGTH"
                        0x450B -> "INVALID_PARAM"
                        0x450C -> "INVALID_STATE"
                        0x4510 -> "INVALID_FORMAT"
                        else -> "UNKNOWN($result / 0x${result.toString(16)})"
                    }
                    println("[NoiseSession-Native] ❌ noise_handshakestate_read_message failed: $errorName")
                    println("[NoiseSession-Native]    Message size: ${message.size}")
                    println("[NoiseSession-Native]    Role: ${if (isInitiator) "INITIATOR" else "RESPONDER"}")
                    println("[NoiseSession-Native]    currentPattern: $currentPattern")
                    throw SessionError.HandshakeFailed
                }

                currentPattern++
                val payloadLength = payloadNoiseBuffer.size.toInt()
                println("[NoiseSession-Native] Read handshake message, payload length: $payloadLength currentPattern: $currentPattern")

                // Check what action the handshake state wants us to take next
                val action = noise_handshakestate_get_action(handshakeStatePtr.reinterpret())
                println("[NoiseSession-Native] Handshake action after processing message: $action")

                // Noise-c action codes: NOISE_ID('A', n) = (0x41 << 8) | n = 0x4100 | n
                val NOISE_ACTION_NONE = 0
                val NOISE_ACTION_WRITE_MESSAGE = 0x4101  // 16641
                val NOISE_ACTION_READ_MESSAGE = 0x4102   // 16642
                val NOISE_ACTION_FAILED = 0x4103         // 16643
                val NOISE_ACTION_SPLIT = 0x4104          // 16644

                when (action) {
                    NOISE_ACTION_WRITE_MESSAGE -> {
                        // Noise library says we need to send a response
                        val responseBuffer = allocArray<UByteVar>(NoiseConstants.XX_MESSAGE_2_SIZE)
                        val responseNoiseBuffer = alloc<NoiseBuffer>()
                        responseNoiseBuffer.data = responseBuffer
                        responseNoiseBuffer.size = 0U  // Output buffer starts at 0, write_message fills it
                        responseNoiseBuffer.max_size = NoiseConstants.XX_MESSAGE_2_SIZE.toULong()

                        val result2 = noise_handshakestate_write_message(
                            handshakeStatePtr.reinterpret(),
                            responseNoiseBuffer.ptr,
                            null
                        )

                        if (result2 != 0) {
                            println("[NoiseSession-Native] ❌ noise_handshakestate_write_message failed: $result2")
                            throw SessionError.HandshakeFailed
                        }

                        currentPattern++
                        val responseLength = responseNoiseBuffer.size.toInt()

                        // Copy response to Kotlin ByteArray
                        val response = ByteArray(responseLength)
                        for (i in 0 until responseLength) {
                            response[i] = responseBuffer[i].toByte()
                        }

                        println("[NoiseSession-Native] ✅ Generated handshake response: ${response.size} bytes, currentPattern: $currentPattern")

                        // Check if we should complete handshake after this
                        val nextAction = noise_handshakestate_get_action(handshakeStatePtr.reinterpret())
                        println("[NoiseSession-Native] Next action after write: $nextAction")
                        if (nextAction == NOISE_ACTION_SPLIT) {
                            completeHandshake()
                        }

                        response
                    }

                    NOISE_ACTION_SPLIT -> {
                        // Handshake complete, split into transport keys
                        completeHandshake()
                        println("[NoiseSession-Native] SPLIT ✅ XX handshake completed with $peerID")
                        null
                    }

                    NOISE_ACTION_FAILED -> {
                        println("[NoiseSession-Native] ❌ Handshake action is FAILED")
                        throw SessionError.HandshakeFailed
                    }

                    NOISE_ACTION_READ_MESSAGE -> {
                        // Noise library expects us to read another message
                        println("[NoiseSession-Native] Handshake waiting for next message from $peerID")
                        null
                    }

                    NOISE_ACTION_NONE -> {
                        println("[NoiseSession-Native] Handshake action: NONE - waiting")
                        null
                    }

                    else -> {
                        println("[NoiseSession-Native] ⚠️ Unknown handshake action: $action (0x${action.toString(16)})")
                        null
                    }
                }
            }

        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            println("[NoiseSession-Native] Handshake failed with $peerID: ${e.message}")
            throw e
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun completeHandshake() {
        if (currentPattern < NOISE_XX_PATTERN_LENGTH) {
            return
        }

        println("[NoiseSession-Native] Completing XX handshake with $peerID")

        try {
            val handshakeStateLocal = handshakeStatePtr
                ?: throw IllegalStateException("Handshake state is null")

            // Split handshake state into send and receive ciphers for transport mode
            // IMPORTANT: We must capture the cipher pointers to use them for actual encryption/decryption
            memScoped {
                // Allocate output parameters for cipher pointers
                val sendCipherOut = alloc<CPointerVar<COpaque>>()
                val receiveCipherOut = alloc<CPointerVar<COpaque>>()

                // Call noise_handshakestate_split to extract transport ciphers
                // This function populates sendCipherOut and receiveCipherOut with cipher pointers
                val splitResult = noise_handshakestate_split(
                    handshakeStateLocal.reinterpret(),
                    sendCipherOut.ptr.reinterpret(),
                    receiveCipherOut.ptr.reinterpret()
                )

                if (splitResult != 0) {
                    println("[NoiseSession-Native] noise_handshakestate_split failed with error code: $splitResult")
                    throw Exception("Failed to split handshake state")
                }

                // CRITICAL: Store the cipher pointers for use in encrypt/decrypt
                // These pointers are owned by the noise-c library
                val sendPtr = sendCipherOut.value
                val recvPtr = receiveCipherOut.value

                if (sendPtr != null) {
                    sendCipherPtr = sendPtr
                    println("[NoiseSession-Native] ✅ Send cipher captured for encryption")
                } else {
                    println("[NoiseSession-Native] Warning: Send cipher pointer is null")
                }

                if (recvPtr != null) {
                    recvCipherPtr = recvPtr
                    println("[NoiseSession-Native] ✅ Receive cipher captured for decryption")
                } else {
                    println("[NoiseSession-Native] Warning: Receive cipher pointer is null")
                }
            }

            println("[NoiseSession-Native] ✅ Split handshake state successfully - transport keys derived")

            // Extract remote public key if available
            // This is useful for peer authentication and verification
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
                        println("[NoiseSession-Native] ✅ Remote static public key extracted (32 bytes)")
                    } else {
                        println("[NoiseSession-Native] Warning: Failed to extract remote public key, error code: $result")
                    }
                }
            }

            // Extract handshake hash for channel binding
            // SHA256 produces 32 bytes. This hash is a commitment to the entire handshake exchange
            // and can be used for optional channel binding or authentication purposes.
            handshakeHash = ByteArray(32)
            handshakeHash!!.usePinned { pinned ->
                val result = noise_handshakestate_get_handshake_hash(
                    handshakeStateLocal.reinterpret(),
                    pinned.addressOf(0).reinterpret(),
                    32UL
                )
                if (result == 0) {
                    println("[NoiseSession-Native] ✅ Extracted handshake hash (32 bytes)")
                } else {
                    println("[NoiseSession-Native] Warning: Failed to extract handshake hash, error code: $result")
                }
            }

            // Clean up handshake state - this frees the C structure and all its resources
            noise_handshakestate_free(handshakeStateLocal.reinterpret())
            handshakeStatePtr = null

            // Reset session counters for transport phase
            // Transport phase starts fresh with counter at 0
            messagesSent = 0
            messagesReceived = 0
            currentPattern = 0

            // Reset replay protection window for transport phase
            // Each transport phase maintains its own nonce window for replay detection
            highestReceivedNonce = 0L
            replayWindow = ByteArray(NoiseConstants.REPLAY_WINDOW_BYTES)

            // Mark session as established - ready for encrypted communication
            state = NoiseSessionState.Established
            println("[NoiseSession-Native] ✅ Handshake completed with $peerID (role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")
            println("[NoiseSession-Native] ✅ Session established and ready for encrypted transport")

        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            println("[NoiseSession-Native] ❌ Failed to complete handshake: ${e.message}")
            throw e
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun encrypt(data: ByteArray): ByteArray {
        // NOTE: cipherLock exists for documentation and potential JVM synchronization
        // Kotlin Native does not support synchronized blocks - single-threaded model ensures safety
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }

        if (messagesSent > UInt.MAX_VALUE.toLong() - 1) {
            throw SessionError.NonceExceeded("Nonce value $messagesSent exceeds 4-byte limit")
        }

        return try {
            // Get send cipher pointer - should have been set by completeHandshake()
            val sendCipherLocal = sendCipherPtr
                ?: throw IllegalStateException("Send cipher not available - handshake not complete")

            val currentNonce = messagesSent
            messagesSent++

            // Set the nonce for this message
            val setNonceResult = noise_cipherstate_set_nonce(
                sendCipherLocal.reinterpret(),
                currentNonce.toULong()
            )

            if (setNonceResult != 0) {
                println("[NoiseSession-Native] Failed to set nonce: error code $setNonceResult")
                throw SessionError.EncryptionFailed
            }

            // Get the MAC length to allocate correct ciphertext buffer size
            val macLength = noise_cipherstate_get_mac_length(sendCipherLocal.reinterpret()).toInt()
            val ciphertextLength = data.size + macLength

            // Allocate buffer for ciphertext (includes MAC)
            val ciphertextBuffer = ByteArray(ciphertextLength)

            // Create NoiseBuffer for the plaintext and ciphertext
            memScoped {
                val noiseBuffer = alloc<NoiseBuffer>()

                // Copy plaintext into buffer
                data.usePinned { dataPinned ->
                    ciphertextBuffer.usePinned { ciphertextPinned ->
                        // Set buffer to point to our plaintext (input)
                        noiseBuffer.data = dataPinned.addressOf(0).reinterpret()
                        noiseBuffer.size = data.size.toULong()
                        noiseBuffer.max_size = ciphertextLength.toULong()

                        // Encrypt the data with associated data = null
                        val encryptResult = noise_cipherstate_encrypt_with_ad(
                            sendCipherLocal.reinterpret(),
                            null,  // No associated data
                            0UL,   // AD length = 0
                            noiseBuffer.ptr
                        )

                        if (encryptResult != 0) {
                            println("[NoiseSession-Native] Encryption failed: error code $encryptResult")
                            throw SessionError.EncryptionFailed
                        }

                        // Copy encrypted result back to our ciphertext array
                        val encryptedSize = noiseBuffer.size.toInt()
                        for (i in 0 until encryptedSize) {
                            ciphertextBuffer[i] = noiseBuffer.data!![i].toInt().toByte()
                        }
                    }
                }
            }

            // Create combined payload: <nonce><ciphertext+MAC>
            val nonceBytes = ReplayProtection.nonceToBytes(currentNonce)
            val combinedPayload = ByteArray(NoiseConstants.NONCE_SIZE_BYTES + ciphertextLength)

            nonceBytes.copyInto(combinedPayload, 0)
            ciphertextBuffer.copyInto(combinedPayload, NoiseConstants.NONCE_SIZE_BYTES)

            if (currentNonce > NoiseConstants.HIGH_NONCE_WARNING_THRESHOLD) {
                println("[NoiseSession-Native] High nonce value detected: $currentNonce - consider rekeying")
            }

            println("[NoiseSession-Native] ✅ NATIVE ENCRYPT: ${data.size} → ${combinedPayload.size} bytes (nonce: $currentNonce, MAC: $macLength) for $peerID (msg #$messagesSent, role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")
            combinedPayload

        } catch (e: Exception) {
            println("[NoiseSession-Native] Real encryption failed - exception: ${e.message}")
            throw SessionError.EncryptionFailed
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun decrypt(combinedPayload: ByteArray): ByteArray {
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }

        return try {
            val nonceAndCiphertext = ReplayProtection.extractNonceFromCiphertextPayload(combinedPayload)
                ?: throw SessionError.DecryptionFailed

            val (extractedNonce, ciphertext) = nonceAndCiphertext

            if (!ReplayProtection.isValidNonce(extractedNonce, highestReceivedNonce, replayWindow)) {
                println("[NoiseSession-Native] Replay attack detected: nonce $extractedNonce rejected for $peerID")
                throw SessionError.DecryptionFailed
            }

            // Get receive cipher pointer - should have been set by completeHandshake()
            val recvCipherLocal = recvCipherPtr
                ?: throw IllegalStateException("Receive cipher not available - handshake not complete")

            // Set the nonce for this message
            val setNonceResult = noise_cipherstate_set_nonce(
                recvCipherLocal.reinterpret(),
                extractedNonce.toULong()
            )

            if (setNonceResult != 0) {
                println("[NoiseSession-Native] Failed to set nonce for decryption: error code $setNonceResult")
                throw SessionError.DecryptionFailed
            }

            // Get the MAC length to know how much to allocate for plaintext
            val macLength = noise_cipherstate_get_mac_length(recvCipherLocal.reinterpret()).toInt()
            val plaintextLength = maxOf(0, ciphertext.size - macLength)

            // Allocate buffer for plaintext (ciphertext size - MAC)
            val plaintextBuffer = ByteArray(plaintextLength)

            // Decrypt the ciphertext
            memScoped {
                val noiseBuffer = alloc<NoiseBuffer>()

                ciphertext.usePinned { ciphertextPinned ->
                    plaintextBuffer.usePinned { plaintextPinned ->
                        // Set buffer to point to our ciphertext (input)
                        noiseBuffer.data = ciphertextPinned.addressOf(0).reinterpret()
                        noiseBuffer.size = ciphertext.size.toULong()
                        noiseBuffer.max_size = ciphertext.size.toULong()

                        // Decrypt the data with associated data = null
                        val decryptResult = noise_cipherstate_decrypt_with_ad(
                            recvCipherLocal.reinterpret(),
                            null,  // No associated data
                            0UL,   // AD length = 0
                            noiseBuffer.ptr
                        )

                        if (decryptResult != 0) {
                            println("[NoiseSession-Native] Decryption failed: error code $decryptResult")
                            throw SessionError.DecryptionFailed
                        }

                        // Copy decrypted result back to our plaintext array
                        val decryptedSize = noiseBuffer.size.toInt()
                        if (decryptedSize > plaintextLength) {
                            println("[NoiseSession-Native] Decrypted size ($decryptedSize) exceeds plaintext buffer ($plaintextLength)")
                            throw SessionError.DecryptionFailed
                        }

                        for (i in 0 until decryptedSize) {
                            plaintextBuffer[i] = noiseBuffer.data!![i].toInt().toByte()
                        }
                    }
                }
            }

            // Mark nonce as seen AFTER successful decryption
            val (newHighestReceivedNonce, newReplayWindow) = ReplayProtection.markNonceAsSeen(
                extractedNonce,
                highestReceivedNonce,
                replayWindow
            )
            highestReceivedNonce = newHighestReceivedNonce
            replayWindow = newReplayWindow

            if (extractedNonce > NoiseConstants.HIGH_NONCE_WARNING_THRESHOLD) {
                println("[NoiseSession-Native] High nonce value detected: $extractedNonce - consider rekeying")
            }

            println("[NoiseSession-Native] ✅ NATIVE DECRYPT: ${combinedPayload.size} → ${plaintextBuffer.size} bytes from $peerID (nonce: $extractedNonce, MAC: $macLength, highest: $highestReceivedNonce, role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")
            plaintextBuffer

        } catch (e: Exception) {
            println("[NoiseSession-Native] Decryption failed - exception: ${e.message}")
            println("[NoiseSession-Native] Session state: $state, highest received nonce: $highestReceivedNonce")
            println("[NoiseSession-Native] Input data size: ${combinedPayload.size} bytes")
            throw SessionError.DecryptionFailed
        }
    }

    actual fun getRemoteStaticPublicKey(): ByteArray? = remoteStaticPublicKey?.copyOf()

    actual fun getHandshakeHash(): ByteArray? = handshakeHash?.copyOf()

    actual fun needsRekey(): Boolean {
        if (!isEstablished()) return false

        val timeLimit = Clock.System.now().toEpochMilliseconds() - creationTime > NoiseConstants.REKEY_TIME_LIMIT_MS
        val messageLimit = (messagesSent + messagesReceived) > NoiseConstants.REKEY_MESSAGE_LIMIT_SESSION

        return timeLimit || messageLimit
    }

    actual fun getSessionStats(): String = buildString {
        appendLine("NoiseSession with $peerID:")
        appendLine("  State: $state")
        appendLine("  Role: ${if (isInitiator) "initiator" else "responder"}")
        appendLine("  Messages sent: $messagesSent")
        appendLine("  Messages received: $messagesReceived")
        appendLine("  Session age: ${(Clock.System.now().toEpochMilliseconds() - creationTime) / 1000}s")
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
            println("[NoiseSession-Native] Failed to reset session: ${e.message}")
        }
    }

    actual fun destroy() {
        try {
            // TODO: Destroy noise-c objects properly
            remoteStaticPublicKey?.fill(0)
            handshakeHash?.fill(0)

            remoteStaticPublicKey = null
            handshakeHash = null

            if (state !is NoiseSessionState.Failed) {
                state = NoiseSessionState.Failed(Exception("Session destroyed"))
            }

            println("[NoiseSession-Native] Session destroyed for $peerID")

        } catch (e: Exception) {
            println("[NoiseSession-Native] Error during session cleanup: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NoiseSession"
    }
}