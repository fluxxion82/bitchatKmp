package com.bitchat.bluetooth.facade

import com.bitchat.crypto.Cryptography
import com.bitchat.bluetooth.manager.HandshakeSupervisor
import com.bitchat.noise.NoiseConstants
import com.bitchat.noise.NoiseSession
import com.bitchat.noise.NoiseSessionState
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlin.time.Clock

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
        /** Validated predecessor used only to read during recovery. */
        var fallback: NoiseSession? = null
        /** When [fallback] began sitting beside an established session, if it does. */
        var fallbackEstablishedAt: Long? = null
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

    enum class DecryptionVia {
        ESTABLISHED,
        FALLBACK
    }

    data class DecryptionResult(
        val plaintext: ByteArray,
        val via: DecryptionVia
    )

    private inline fun <T> withPeerLock(peerID: String, block: (PeerLock) -> T): T {
        val peerLock = peerLocksRegistry.withLock {
            peerLocks.getOrPut(peerID) { PeerLock() }.also { it.users += 1 }
        }
        return try {
            peerLock.lock.withLock { block(peerLock) }
        } finally {
            peerLocksRegistry.withLock {
                peerLock.users -= 1
                if (peerLock.users == 0 && peerLock.established == null && peerLock.candidate == null && peerLock.fallback == null) {
                    if (peerLocks[peerID] === peerLock) peerLocks.remove(peerID)
                }
            }
        }
    }

    fun hasEstablishedSession(peerID: String, now: Long = currentTimeMillis()): Boolean {
        return withPeerLock(peerID) { state ->
            retireExpiredFallback(state, now)
            state.established?.isEstablished() == true
        }
    }

    /**
     * True while the FIRST handshake with [peerID] has been started and has not completed or failed.
     *
     * A renegotiation running beside an established session is deliberately not "handshaking": the
     * callers of this and of [handshakesInFlight] abandon only the candidate they are told is in
     * flight. Anyone in range can
     * open a renegotiation under a peer's id with one unsigned packet, so reporting it here would
     * let that packet end the real session when the deadline passes.
     */
    fun isHandshaking(peerID: String): Boolean {
        return withPeerLock(peerID) { state ->
            retireExpiredFallback(state, currentTimeMillis())
            state.firstHandshake() != null
        }
    }

    /** Unlike [isHandshaking], this also reports a renegotiation beside a live session. */
    fun hasCandidate(peerID: String): Boolean = withPeerLock(peerID) { state ->
        retireExpiredFallback(state, currentTimeMillis())
        state.candidate != null
    }

    /** A validated established or fallback session can authenticate a peer's traffic. */
    fun hasValidatedSession(peerID: String): Boolean = withPeerLock(peerID) { state ->
        retireExpiredFallback(state, currentTimeMillis())
        state.established?.isEstablished() == true || state.fallback?.isEstablished() == true
    }

    /**
     * Every peer whose session is mid-handshake, mapped to the epoch-millis instant the session
     * was created. That instant is when the handshake started, so it is the deadline's origin.
     */
    fun handshakesInFlight(): Map<String, Long> {
        val peerIDs = peerLocksRegistry.withLock { peerLocks.keys.toList() }
        return peerIDs.mapNotNull { peerID ->
            withPeerLock(peerID) { state ->
                retireExpiredFallback(state, currentTimeMillis())
                state.firstHandshake()?.let { peerID to it.getCreationTime() }
            }
        }.toMap()
    }

    fun initiateHandshake(peerID: String, localStaticPrivateKey: ByteArray, localStaticPublicKey: ByteArray): ByteArray {
        return withPeerLock(peerID) { state ->
            retireExpiredFallback(state, currentTimeMillis())
            if (state.established?.isEstablished() == true) {
                return@withPeerLock byteArrayOf()
            }
            val existingCandidate = state.candidate
            if (existingCandidate?.isHandshaking() == true) {
                return@withPeerLock byteArrayOf()
            }
            val session = NoiseSession(
                peerID = peerID,
                isInitiator = true,
                localStaticPrivateKey = localStaticPrivateKey,
                localStaticPublicKey = localStaticPublicKey
            )
            setCandidate(state, session, initiated = true, destroyPrevious = true)
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
        localStaticPublicKey: ByteArray,
        now: Long
    ): HandshakeResult? {
        val isOpening = message.size == NoiseConstants.XX_MESSAGE_1_SIZE
        val pending = state.candidate

        // A candidate without a live session is the ordinary initial handshake. It must continue
        // to the collision rule below rather than being mistaken for a renegotiation.
        if (state.established?.isEstablished() != true) return null

        if (pending != null && !isOpening) {
            return advanceRenegotiation(peerID, state, pending, message, now)
        }

        if (!isOpening) {
            // A stray message 2 or 3 with no renegotiation to belong to. There is nothing to do
            // with it, and feeding it to the live session is what used to destroy the live session.
            println("[NoiseEncryptionFacade] Ignoring a stray handshake message from $peerID; the established session stands")
            return HandshakeResult.Ignored
        }

        // A fresh opening. Any half-finished renegotiation is stale, so it gives way to this one.
        val session = NoiseSession(
            peerID = peerID,
            isInitiator = false,
            localStaticPrivateKey = localStaticPrivateKey,
            localStaticPublicKey = localStaticPublicKey
        )
        setCandidate(state, session, initiated = false, destroyPrevious = true)
        return advanceRenegotiation(peerID, state, session, message, now)
    }

    /** Feed [message] to a renegotiation, promoting it if it completes and dropping it if it fails. */
    private fun advanceRenegotiation(
        peerID: String,
        state: PeerLock,
        session: NoiseSession,
        message: ByteArray,
        now: Long
    ): HandshakeResult {
        val response = try {
            session.processHandshakeMessage(message)
        } catch (e: Exception) {
            println("[NoiseEncryptionFacade] Renegotiation with $peerID failed: ${e.message}; the established session stands")
            setCandidate(state, null, destroyPrevious = true)
            return HandshakeResult.Ignored
        }

        if (session.isEstablished()) {
            return validateAndPromote(peerID, state, session, response, now)
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
    private fun collisionVerdict(peerID: String, state: PeerLock, message: ByteArray, now: Long): CollisionVerdict {
        if (message.size != NoiseConstants.XX_MESSAGE_1_SIZE) return CollisionVerdict.NONE
        val session = state.candidate ?: return CollisionVerdict.NONE
        if (!session.isHandshaking()) return CollisionVerdict.NONE
        if (!state.initiated) return CollisionVerdict.NONE
        if (now - session.getCreationTime() >= HandshakeSupervisor.HANDSHAKE_TIMEOUT_MS) {
            return CollisionVerdict.YIELD
        }
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
        localStaticPublicKey: ByteArray,
        now: Long = currentTimeMillis()
    ): HandshakeResult = withPeerLock(peerID) { state ->
        retireExpiredFallback(state, now)
        // The construction is inside the try. Every current actual swallows its own init failure
        // into NoiseSessionState.Failed, but a throw from here would propagate through
        // MessageHandler into the per-peer actor loop in PacketProcessor and kill that coroutine,
        // after which every packet from this peer is swallowed by a channel with no consumer.
        // Nothing about the call site guarantees it cannot throw, so it is covered.
        renegotiation(peerID, state, message, localStaticPrivateKey, localStaticPublicKey, now)
            ?.let { return@withPeerLock it }

        when (collisionVerdict(peerID, state, message, now)) {
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
                setCandidate(state, null, destroyPrevious = true)
            }

            CollisionVerdict.NONE -> Unit
        }

        try {
            val session = state.candidate ?: NoiseSession(
                peerID = peerID,
                isInitiator = false,
                localStaticPrivateKey = localStaticPrivateKey,
                localStaticPublicKey = localStaticPublicKey
            ).also { setCandidate(state, it, initiated = false) }

            val response = session.processHandshakeMessage(message)
            if (session.isEstablished()) {
                validateAndPromote(peerID, state, session, response, now)
            } else {
                response?.let(HandshakeResult::Response) ?: HandshakeResult.Ignored
            }
        } catch (e: Exception) {
            println("[NoiseEncryptionFacade] Handshake failed for $peerID: ${e.message}")
            setCandidate(state, null, destroyPrevious = true)
            HandshakeResult.Ignored
        }
    }

    fun encrypt(peerID: String, data: ByteArray, now: Long = currentTimeMillis()): ByteArray? {
        return withPeerLock(peerID) { state ->
            retireExpiredFallback(state, now)
            state.established
                ?.takeIf { it.isEstablished() }
                ?.encrypt(data)
        }
    }

    /** The fallback is decrypt-only; outgoing traffic always requires [PeerLock.established]. */
    fun decrypt(peerID: String, encryptedData: ByteArray, now: Long = currentTimeMillis()): DecryptionResult? {
        return withPeerLock(peerID) { state ->
            retireExpiredFallback(state, now)
            val established = state.established?.takeIf { it.isEstablished() }
            val establishedPlaintext = established?.let { session ->
                try {
                    session.decrypt(encryptedData)
                } catch (e: Exception) {
                    null
                }
            }
            if (establishedPlaintext != null) {
                // Successfully reading the new traffic confirms the peer has switched.
                state.fallback?.destroy()
                state.fallback = null
                state.fallbackEstablishedAt = null
                return@withPeerLock DecryptionResult(establishedPlaintext, DecryptionVia.ESTABLISHED)
            }
            state.fallback?.takeIf { it.isEstablished() }?.let { session ->
                val fallbackPlaintext = try { session.decrypt(encryptedData) } catch (e: Exception) { null }
                fallbackPlaintext?.let { DecryptionResult(it, DecryptionVia.FALLBACK) }
            }
        }
    }

    fun getSessionState(peerID: String): String {
        return withPeerLock(peerID) { state ->
            retireExpiredFallback(state, currentTimeMillis())
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
        return withPeerLock(peerID) { state ->
            retireExpiredFallback(state, currentTimeMillis())
            state.established?.getRemoteStaticPublicKey()
        }
    }

    /**
     * Move the healthy slot out of lifecycle accounting while preserving a decrypt-only fallback.
     *
     * A candidate that was running beside it goes too, as it did when the session was removed
     * outright: it belongs to an exchange the caller has just declared failed, and left in place it
     * would make [initiateHandshake] refuse the replacement until the sweeper got round to it.
     */
    fun demote(peerID: String) {
        withPeerLock(peerID) { state -> demote(state) }
    }

    /** Runs under the peer lock. The candidate goes whether or not there is a session to demote. */
    private fun demote(state: PeerLock) {
        setCandidate(state, null, destroyPrevious = true)
        val established = state.established ?: return
        state.fallback?.destroy()
        state.fallback = established
        state.fallbackEstablishedAt = null
        state.established = null
    }

    /**
     * Give lifecycle ownership back to the peer's proven predecessor after it decrypts beside a
     * newer established session. A sole established session is deliberately never discarded here.
     */
    fun discardEstablished(peerID: String) {
        withPeerLock(peerID) { state ->
            if (state.fallback == null) {
                // The evidence was a decrypt by a predecessor that has since been retired, so there
                // is nothing to hand the peer back to. What remains true is that the established
                // session is not working: treat it as any other condemned session, so the caller's
                // handshake can start instead of being refused by a session nobody shares.
                demote(state)
                return@withPeerLock
            }
            state.established?.destroy()
            state.established = null
            state.fallbackEstablishedAt = null
            // As in demote: nothing left over may block the handshake the caller starts next.
            setCandidate(state, null, destroyPrevious = true)
        }
    }

    /** Drop only an in-flight handshake; traffic slots intentionally survive. */
    fun abandonHandshake(peerID: String) {
        withPeerLock(peerID) { state ->
            setCandidate(state, null, destroyPrevious = true)
        }
    }

    fun removeSession(peerID: String) {
        withPeerLock(peerID) { state ->
            state.established?.destroy()
            state.established = null
            state.fallback?.destroy()
            state.fallback = null
            state.fallbackEstablishedAt = null
            // A renegotiation only exists to replace the session being removed here, so it goes
            // too -- otherwise it would outlive its purpose and later promote itself over a
            // session the peer has since built by other means.
            setCandidate(state, null, destroyPrevious = true)
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
        response: ByteArray?,
        now: Long
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
            setCandidate(state, null, destroyPrevious = true)
            return HandshakeResult.RejectedIdentity
        }

        setCandidate(state, null)
        val previousEstablished = state.established
        if (previousEstablished != null) {
            state.fallback?.destroy()
            state.fallback = previousEstablished
        }
        state.established = candidate
        state.fallbackEstablishedAt = state.fallback?.let { now }
        println("[NoiseEncryptionFacade] Noise identity validated for $peerID; session established")
        return HandshakeResult.Established(response)
    }

    /** Candidate ownership and role are inseparable: no destroyed candidate leaves a stale role. */
    private fun setCandidate(
        state: PeerLock,
        candidate: NoiseSession?,
        initiated: Boolean = false,
        destroyPrevious: Boolean = false
    ) {
        if (destroyPrevious && state.candidate !== candidate) state.candidate?.destroy()
        state.candidate = candidate
        state.initiated = candidate != null && initiated
    }

    private fun retireExpiredFallback(state: PeerLock, now: Long) {
        val since = state.fallbackEstablishedAt ?: return
        if (state.established != null && now - since >= FALLBACK_MAX_AGE_MS) {
            state.fallback?.destroy()
            state.fallback = null
            state.fallbackEstablishedAt = null
        }
    }

    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private fun ByteArray.hexPrefix(): String = take(ID_BYTES).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    companion object {
        const val ID_BYTES = 8
        const val ID_HEX_LENGTH = ID_BYTES * 2
        const val FALLBACK_MAX_AGE_MS = 5 * 60 * 1000L
    }
}
