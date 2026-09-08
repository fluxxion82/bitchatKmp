package com.bitchat.bluetooth.facade

import com.bitchat.noise.NoiseSession
import com.bitchat.noise.NoiseSessionState

/**
 * Facade for Noise Protocol encryption/decryption
 * Wraps the noise module API for bluetooth mesh needs
 */
class NoiseEncryptionFacade {
    // Session storage: peerID -> NoiseSession
    private val sessions = mutableMapOf<String, NoiseSession>()

    fun hasEstablishedSession(peerID: String): Boolean {
        return sessions[peerID]?.isEstablished() == true
    }

    /** True while a handshake with [peerID] has been started but has not completed or failed. */
    fun isHandshaking(peerID: String): Boolean {
        return sessions[peerID]?.isHandshaking() == true
    }

    /**
     * Every peer whose session is mid-handshake, mapped to the epoch-millis instant the session
     * was created. That instant is when the handshake started, so it is the deadline's origin.
     */
    fun handshakesInFlight(): Map<String, Long> {
        return sessions
            .filterValues { it.isHandshaking() }
            .mapValues { (_, session) -> session.getCreationTime() }
    }

    fun initiateHandshake(peerID: String, localStaticPrivateKey: ByteArray, localStaticPublicKey: ByteArray): ByteArray {
        // Check if session already exists and is handshaking or established
        val existingSession = sessions[peerID]
        if (existingSession != null) {
            if (existingSession.isEstablished()) {
                // Session already established, no need to re-handshake
                return byteArrayOf()  // Return empty, no handshake needed
            }
            if (existingSession.isHandshaking()) {
                // Handshake in progress, don't recreate session!
                // Return empty to avoid sending duplicate handshake message
                return byteArrayOf()
            }
        }

        // Only create new session if none exists or previous failed
        val session = NoiseSession(
            peerID = peerID,
            isInitiator = true,
            localStaticPrivateKey = localStaticPrivateKey,
            localStaticPublicKey = localStaticPublicKey
        )
        sessions[peerID] = session
        return session.startHandshake()
    }

    fun processHandshake(
        peerID: String,
        message: ByteArray,
        localStaticPrivateKey: ByteArray,
        localStaticPublicKey: ByteArray
    ): ByteArray? {
        // The construction is inside the try. Every current actual swallows its own init failure
        // into NoiseSessionState.Failed, but a throw from here would propagate through
        // MessageHandler into the per-peer actor loop in PacketProcessor and kill that coroutine,
        // after which every packet from this peer is swallowed by a channel with no consumer.
        // Nothing about the call site guarantees it cannot throw, so it is covered.
        return try {
            val session = sessions[peerID] ?: NoiseSession(
                peerID = peerID,
                isInitiator = false,
                localStaticPrivateKey = localStaticPrivateKey,
                localStaticPublicKey = localStaticPublicKey
            ).also { sessions[peerID] = it }

            session.processHandshakeMessage(message)
        } catch (e: Exception) {
            println("[NoiseEncryptionFacade] Handshake failed for $peerID: ${e.message}")
            sessions.remove(peerID)?.destroy()
            null
        }
    }

    fun encrypt(peerID: String, data: ByteArray): ByteArray? {
        val session = sessions[peerID] ?: return null
        return if (session.isEstablished()) {
            session.encrypt(data)
        } else {
            null
        }
    }

    fun decrypt(peerID: String, encryptedData: ByteArray): ByteArray? {
        val session = sessions[peerID] ?: return null
        return if (session.isEstablished()) {
            try {
                session.decrypt(encryptedData)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    fun getSessionState(peerID: String): String {
        val state = sessions[peerID]?.getState()
        return when (state) {
            NoiseSessionState.Established -> "established"
            NoiseSessionState.Handshaking -> "handshaking"
            NoiseSessionState.Uninitialized -> "uninitialized"
            is NoiseSessionState.Failed -> "failed"
            else -> "uninitialized"
        }
    }

    fun getRemoteStaticKey(peerID: String): ByteArray? {
        return sessions[peerID]?.getRemoteStaticPublicKey()
    }

    fun removeSession(peerID: String) {
        sessions.remove(peerID)?.destroy()
    }

    fun clearAllSessions() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
    }
}
