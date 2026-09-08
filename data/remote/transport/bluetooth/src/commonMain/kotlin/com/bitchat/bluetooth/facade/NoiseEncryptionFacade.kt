package com.bitchat.bluetooth.facade

import com.bitchat.noise.NoiseConstants
import com.bitchat.noise.NoiseSession
import com.bitchat.noise.NoiseSessionState

/**
 * Facade for Noise Protocol encryption/decryption
 * Wraps the noise module API for bluetooth mesh needs
 *
 * [myPeerID] is not decoration: it settles handshake collisions. Two peers that decide to talk at
 * the same moment both start an XX handshake as initiator, and each then receives the other's
 * 32-byte message 1 into a state machine that is waiting for a 96-byte message 2. Exactly one side
 * has to give up its own attempt and answer, and both sides have to agree which — see
 * [processHandshake].
 */
class NoiseEncryptionFacade(private val myPeerID: String) {
    // Session storage: peerID -> NoiseSession
    private val sessions = mutableMapOf<String, NoiseSession>()

    // Peers whose current session we created ourselves, as the XX initiator. The role is not
    // readable back off NoiseSession, and it is what decides whether an incoming message 1 is a
    // collision or an ordinary opening.
    private val initiatedPeers = mutableSetOf<String>()

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
        initiatedPeers.add(peerID)
        return session.startHandshake()
    }

    /**
     * Whether an incoming handshake [message] from [peerID] collides with a handshake we started,
     * and if so whether this node is the one that must give way.
     *
     * A collision is an XX message 1 arriving while our own initiator handshake is still in flight.
     * The tie is broken on peer ID, and it has to break the same way on both sides or they either
     * both yield or neither does: the peer with the larger ID yields and becomes the responder.
     * This is the rule the upstream Android client already applies
     * (`NoiseSessionManager.processHandshakeMessageWithResult`), so a KMP node that does not
     * implement it simply never completes a handshake against an upstream peer that initiated at
     * the same time — which is what the device journal shows, our message 1 ignored by the phone
     * and the phone's message 1 failing here with INVALID_LENGTH.
     */
    private fun collisionVerdict(peerID: String, message: ByteArray): CollisionVerdict {
        if (message.size != NoiseConstants.XX_MESSAGE_1_SIZE) return CollisionVerdict.NONE
        val session = sessions[peerID] ?: return CollisionVerdict.NONE
        if (!session.isHandshaking()) return CollisionVerdict.NONE
        if (peerID !in initiatedPeers) return CollisionVerdict.NONE
        return if (myPeerID > peerID) CollisionVerdict.YIELD else CollisionVerdict.HOLD
    }

    private enum class CollisionVerdict {
        /** No handshake of ours is in the way. */
        NONE,

        /** Both sides initiated and we are the one that gives way. */
        YIELD,

        /** Both sides initiated and the remote gives way; ignore its message 1. */
        HOLD
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
        when (collisionVerdict(peerID, message)) {
            CollisionVerdict.HOLD -> {
                println(
                    "[NoiseEncryptionFacade] Handshake collision with $peerID; holding our " +
                        "initiator session and ignoring its message 1"
                )
                return null
            }

            CollisionVerdict.YIELD -> {
                println(
                    "[NoiseEncryptionFacade] Handshake collision with $peerID; yielding our " +
                        "initiator session and answering as responder"
                )
                sessions.remove(peerID)?.destroy()
                initiatedPeers.remove(peerID)
            }

            CollisionVerdict.NONE -> Unit
        }

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
            initiatedPeers.remove(peerID)
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
        initiatedPeers.remove(peerID)
    }

    fun clearAllSessions() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        initiatedPeers.clear()
    }
}
