package com.bitchat.bluetooth.facade

import com.bitchat.crypto.Cryptography
import com.bitchat.noise.NoiseConstants
import com.bitchat.noise.NoiseSession
import com.bitchat.noise.NoiseSessionState
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

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

    // AtomicFU's non-suspending common lock is deliberately used here instead of Mutex: facade
    // operations do not suspend, and callbacks/network sends happen after the operation returns.
    // A registry entry stays alive while an operation waits for its peer lock, so removing an idle
    // lock cannot split one peer's state across two locks. The locks never nest: registry lookup
    // finishes before the peer lock is acquired, and peer cleanup finishes before registry cleanup.
    private val peerLocks = mutableMapOf<String, PeerLock>()
    private val peerLocksRegistry = ReentrantLock()

    private class PeerLock {
        val lock = ReentrantLock()
        var users = 0
        /** Only this peer's lock reads or mutates these slots. */
        var established: NoiseSession? = null
        var candidate: NoiseSession? = null
        var initiated = false
    }

    /** The candidate, when it is a handshake in flight with no established session beside it. */
    private fun PeerLock.firstHandshake(): NoiseSession? =
        candidate?.takeIf { established == null && it.isHandshaking() }

    sealed interface HandshakeResult {
        data class Response(val message: ByteArray) : HandshakeResult
        data class Established(val response: ByteArray?) : HandshakeResult
        data object Ignored : HandshakeResult
        data object RejectedIdentity : HandshakeResult
    }

    private inline fun <T> withPeerLock(peerID: String, block: (PeerLock) -> T): T {
        val peerLock = peerLocksRegistry.withLock {
            peerLocks.getOrPut(peerID) { PeerLock() }.also { it.users += 1 }
        }
        return try {
            peerLock.lock.withLock { block(peerLock) }
        } finally {
            peerLocksRegistry.withLock {
                peerLock.users -= 1
                if (peerLock.users == 0 && peerLock.established == null && peerLock.candidate == null) {
                    if (peerLocks[peerID] === peerLock) peerLocks.remove(peerID)
                }
            }
        }
    }

    fun hasEstablishedSession(peerID: String): Boolean {
        return withPeerLock(peerID) { it.established?.isEstablished() == true }
    }

    /**
     * True while the FIRST handshake with [peerID] has been started and has not completed or failed.
     *
     * A renegotiation running beside an established session is deliberately not "handshaking": the
     * callers of this and of [handshakesInFlight] restart or discard what they are told is in
     * flight with [removeSession], which also drops the established session. Anyone in range can
     * open a renegotiation under a peer's id with one unsigned packet, so reporting it here would
     * let that packet end the real session when the deadline passes.
     */
    fun isHandshaking(peerID: String): Boolean {
        return withPeerLock(peerID) { it.firstHandshake() != null }
    }

    /**
     * Every peer whose session is mid-handshake, mapped to the epoch-millis instant the session
     * was created. That instant is when the handshake started, so it is the deadline's origin.
     */
    fun handshakesInFlight(): Map<String, Long> {
        val peerIDs = peerLocksRegistry.withLock { peerLocks.keys.toList() }
        return peerIDs.mapNotNull { peerID ->
            withPeerLock(peerID) { state ->
                state.firstHandshake()?.let { peerID to it.getCreationTime() }
            }
        }.toMap()
    }

    fun initiateHandshake(peerID: String, localStaticPrivateKey: ByteArray, localStaticPublicKey: ByteArray): ByteArray {
        return withPeerLock(peerID) { state ->
            if (state.established?.isEstablished() == true) {
                return@withPeerLock byteArrayOf()
            }
            val existingCandidate = state.candidate
            if (existingCandidate?.isHandshaking() == true) {
                return@withPeerLock byteArrayOf()
            }
            existingCandidate?.destroy()
            val session = NoiseSession(
                peerID = peerID,
                isInitiator = true,
                localStaticPrivateKey = localStaticPrivateKey,
                localStaticPublicKey = localStaticPublicKey
            )
            state.candidate = session
            state.initiated = true
            session.startHandshake()
        }
    }

    /**
     * Handle [message] against a session that is already established, or against a renegotiation
     * already running for [peerID]. Returns null when there is nothing established to protect and
     * the ordinary path should run.
     *
     * The live session is never fed one of these messages and never destroyed by one failing. That
     * is the whole point: an established session is only ever replaced by a completed one.
     */
    private fun renegotiation(
        peerID: String,
        state: PeerLock,
        message: ByteArray,
        localStaticPrivateKey: ByteArray,
        localStaticPublicKey: ByteArray
    ): HandshakeResult? {
        val isOpening = message.size == NoiseConstants.XX_MESSAGE_1_SIZE
        val pending = state.candidate

        // A candidate without a live session is the ordinary initial handshake. It must continue
        // to the collision rule below rather than being mistaken for a renegotiation.
        if (state.established?.isEstablished() != true) return null

        if (pending != null && !isOpening) {
            return advanceRenegotiation(peerID, state, pending, message)
        }

        if (!isOpening) {
            // A stray message 2 or 3 with no renegotiation to belong to. There is nothing to do
            // with it, and feeding it to the live session is what used to destroy the live session.
            println("[NoiseEncryptionFacade] Ignoring a stray handshake message from $peerID; the established session stands")
            return HandshakeResult.Ignored
        }

        // A fresh opening. Any half-finished renegotiation is stale, so it gives way to this one.
        pending?.destroy()
        val session = NoiseSession(
            peerID = peerID,
            isInitiator = false,
            localStaticPrivateKey = localStaticPrivateKey,
            localStaticPublicKey = localStaticPublicKey
        )
        state.candidate = session
        return advanceRenegotiation(peerID, state, session, message)
    }

    /** Feed [message] to a renegotiation, promoting it if it completes and dropping it if it fails. */
    private fun advanceRenegotiation(
        peerID: String,
        state: PeerLock,
        session: NoiseSession,
        message: ByteArray
    ): HandshakeResult {
        val response = try {
            session.processHandshakeMessage(message)
        } catch (e: Exception) {
            println("[NoiseEncryptionFacade] Renegotiation with $peerID failed: ${e.message}; the established session stands")
            state.candidate?.destroy()
            state.candidate = null
            return HandshakeResult.Ignored
        }

        if (session.isEstablished()) {
            return validateAndPromote(peerID, state, session, response)
        }
        return response?.let(HandshakeResult::Response) ?: HandshakeResult.Ignored
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
    private fun collisionVerdict(peerID: String, state: PeerLock, message: ByteArray): CollisionVerdict {
        if (message.size != NoiseConstants.XX_MESSAGE_1_SIZE) return CollisionVerdict.NONE
        val session = state.candidate ?: return CollisionVerdict.NONE
        if (!session.isHandshaking()) return CollisionVerdict.NONE
        if (!state.initiated) return CollisionVerdict.NONE
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
    ): HandshakeResult = withPeerLock(peerID) { state ->
        // The construction is inside the try. Every current actual swallows its own init failure
        // into NoiseSessionState.Failed, but a throw from here would propagate through
        // MessageHandler into the per-peer actor loop in PacketProcessor and kill that coroutine,
        // after which every packet from this peer is swallowed by a channel with no consumer.
        // Nothing about the call site guarantees it cannot throw, so it is covered.
        renegotiation(peerID, state, message, localStaticPrivateKey, localStaticPublicKey)
            ?.let { return@withPeerLock it }

        when (collisionVerdict(peerID, state, message)) {
            CollisionVerdict.HOLD -> {
                println(
                    "[NoiseEncryptionFacade] Handshake collision with $peerID; holding our " +
                        "initiator session and ignoring its message 1"
                )
                return@withPeerLock HandshakeResult.Ignored
            }

            CollisionVerdict.YIELD -> {
                println(
                    "[NoiseEncryptionFacade] Handshake collision with $peerID; yielding our " +
                        "initiator session and answering as responder"
                )
                state.candidate?.destroy()
                state.candidate = null
                state.initiated = false
            }

            CollisionVerdict.NONE -> Unit
        }

        try {
            val session = state.candidate ?: NoiseSession(
                peerID = peerID,
                isInitiator = false,
                localStaticPrivateKey = localStaticPrivateKey,
                localStaticPublicKey = localStaticPublicKey
            ).also { state.candidate = it }

            val response = session.processHandshakeMessage(message)
            if (session.isEstablished()) {
                validateAndPromote(peerID, state, session, response)
            } else {
                response?.let(HandshakeResult::Response) ?: HandshakeResult.Ignored
            }
        } catch (e: Exception) {
            println("[NoiseEncryptionFacade] Handshake failed for $peerID: ${e.message}")
            state.candidate?.destroy()
            state.candidate = null
            state.initiated = false
            HandshakeResult.Ignored
        }
    }

    fun encrypt(peerID: String, data: ByteArray): ByteArray? {
        return withPeerLock(peerID) { state ->
            state.established
                ?.takeIf { it.isEstablished() }
                ?.encrypt(data)
        }
    }

    fun decrypt(peerID: String, encryptedData: ByteArray): ByteArray? {
        return withPeerLock(peerID) { state ->
            val session = state.established ?: return@withPeerLock null
            if (!session.isEstablished()) return@withPeerLock null
            try {
                session.decrypt(encryptedData)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun getSessionState(peerID: String): String {
        return withPeerLock(peerID) { state ->
            val sessionState = state.established?.getState() ?: state.candidate?.getState()
            when (sessionState) {
                NoiseSessionState.Established -> if (state.established != null) "established" else "uninitialized"
                NoiseSessionState.Handshaking -> "handshaking"
                NoiseSessionState.Uninitialized -> "uninitialized"
                is NoiseSessionState.Failed -> "failed"
                else -> "uninitialized"
            }
        }
    }

    fun getRemoteStaticKey(peerID: String): ByteArray? {
        return withPeerLock(peerID) { it.established?.getRemoteStaticPublicKey() }
    }

    fun removeSession(peerID: String) {
        withPeerLock(peerID) { state ->
            state.established?.destroy()
            state.established = null
            // A renegotiation only exists to replace the session being removed here, so it goes
            // too -- otherwise it would outlive its purpose and later promote itself over a
            // session the peer has since built by other means.
            state.candidate?.destroy()
            state.candidate = null
            state.initiated = false
        }
    }

    fun clearAllSessions() {
        val peerIDs = peerLocksRegistry.withLock {
            peerLocks.keys.toList()
        }
        peerIDs.forEach(::removeSession)
    }

    /** Runs under the peer lock, immediately after XX has learned the authenticated static key. */
    private fun validateAndPromote(
        peerID: String,
        state: PeerLock,
        candidate: NoiseSession,
        response: ByteArray?
    ): HandshakeResult {
        val remoteStaticKey = candidate.getRemoteStaticPublicKey()
        val rawID = remoteStaticKey?.hexPrefix()
        val hashID = remoteStaticKey?.let(Cryptography::getDigestHash)?.hexPrefix()
        val claimedID = peerID.lowercase()
        val accepted = peerID.length == ID_HEX_LENGTH && peerID.all { it.isHexDigit() } &&
            (claimedID == rawID || claimedID == hashID)
        if (!accepted) {
            println(
                "[NoiseEncryptionFacade] Rejected Noise identity claimed=$peerID " +
                    "raw=${rawID ?: "unavailable"} sha256=${hashID ?: "unavailable"}"
            )
            state.candidate?.destroy()
            state.candidate = null
            state.initiated = false
            return HandshakeResult.RejectedIdentity
        }

        state.candidate = null
        state.established?.destroy()
        state.established = candidate
        state.initiated = false
        println("[NoiseEncryptionFacade] Noise identity validated for $peerID; session established")
        return HandshakeResult.Established(response)
    }

    private fun ByteArray.hexPrefix(): String = take(ID_BYTES).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private companion object {
        const val ID_BYTES = 8
        const val ID_HEX_LENGTH = ID_BYTES * 2
    }
}
