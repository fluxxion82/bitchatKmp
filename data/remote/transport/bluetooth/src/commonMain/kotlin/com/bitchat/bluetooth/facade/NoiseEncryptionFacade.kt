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
        val session = sessions[peerID] ?: NoiseSession(
            peerID = peerID,
            isInitiator = false,
            localStaticPrivateKey = localStaticPrivateKey,
            localStaticPublicKey = localStaticPublicKey
        ).also { sessions[peerID] = it }

        return try {
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
