package com.bitchat.bluetooth.facade

import com.bitchat.crypto.Cryptography
import com.bitchat.bluetooth.manager.HandshakeSupervisor
import com.bitchat.noise.NoiseConstants
import com.bitchat.noise.NoiseSession
import com.bitchat.noise.NoiseSessionState
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.atomicfu.atomic
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
class NoiseEncryptionFacade(
    private val myPeerID: String,
    private val maxInboundHandshakes: Int = MAX_INBOUND_HANDSHAKES,
    private val maxInboundHandshakesPerLink: Int = MAX_INBOUND_HANDSHAKES_PER_LINK,
    private val maxSessions: Int = MAX_NOISE_SESSIONS,
    private val maxChosenPeers: Int = 2 * maxSessions
) {

    // AtomicFU's non-suspending common lock is deliberately used here instead of Mutex: facade
    // operations do not suspend, and callbacks/network sends happen after the operation returns.
    // A registry entry stays alive while an operation waits for its peer lock, so removing an idle
    // lock cannot split one peer's state across two locks. The locks never nest: registry lookup
    // finishes before the peer lock is acquired, and peer cleanup finishes before registry cleanup.
    // The inbound counter and session order locks are leaf locks: they are taken while holding a
    // peer lock, never the other way round, and never together with the registry lock.
    private val peerLocks = mutableMapOf<String, PeerLock>()
    private val peerLocksRegistry = ReentrantLock()
    private val inboundHandshakeLock = ReentrantLock()
    private var inboundHandshakes = 0
    private val inboundAdmissionsByLink = mutableMapOf<String, ArrayDeque<Long>>()
    private val sessionOrderLock = ReentrantLock()
    private val sessionOrder = LinkedHashMap<String, Unit>()
    // Peers the user chose to write to, oldest choice first. Guarded by sessionOrderLock.
    private val chosenByUser = LinkedHashMap<String, Unit>()
    private val nextSessionToken = atomic(0L)

    private class PeerLock {
        val lock = ReentrantLock()
        var users = 0
        /** Only this peer's lock reads or mutates these slots. */
        var established: NoiseSession? = null
        var establishedBinding: SessionBinding? = null
        var candidate: NoiseSession? = null
        /** Validated predecessor used only to read during recovery. */
        var fallback: NoiseSession? = null
        var fallbackBinding: SessionBinding? = null
        /** When [fallback] began sitting beside an established session, if it does. */
        var fallbackEstablishedAt: Long? = null
        var initiated = false
        var countedInbound = false
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
        val via: DecryptionVia,
        val sessionToken: Long
    )

    /**
     * What goes with a session from the moment it becomes the established one: a [token] that tells it
     * from every other session of this run, and the [link] the last message of its handshake arrived
     * on. One object, moved with the session and dropped with it, and only ever read beside it: what
     * a session may be used for depends on where it was made, and what was said in one session must
     * not be taken for another's. The token is this device's own and is never sent.
     */
    class SessionBinding internal constructor(val token: Long, val link: String)

    private inline fun <T> withPeerLock(peerID: String, block: (PeerLock) -> T): T {
        val peerLock = peerLocksRegistry.withLock {
            peerLocks.getOrPut(peerID) { PeerLock() }.also { it.users += 1 }
        }
        return try {
            peerLock.lock.withLock {
                try {
                    block(peerLock)
                } finally {
                    reconcilePeerBookkeeping(peerID, peerLock)
                }
            }
        } finally {
            peerLocksRegistry.withLock {
                peerLock.users -= 1
                if (peerLock.users == 0 && peerLock.established == null && peerLock.candidate == null && peerLock.fallback == null) {
                    if (peerLocks[peerID] === peerLock) peerLocks.remove(peerID)
                }
            }
        }
    }

    /** Kept generic so every route that drops or promotes a candidate releases its reservation. */
    private fun reconcilePeerBookkeeping(peerID: String, state: PeerLock) {
        val countsInbound = state.candidate != null && !state.initiated && state.established == null
        if (state.countedInbound && !countsInbound) {
            inboundHandshakeLock.withLock {
                inboundHandshakes -= 1
                state.countedInbound = false
            }
        }

        val hasSession = state.established != null || state.fallback != null
        sessionOrderLock.withLock {
            if (hasSession) {
                if (peerID !in sessionOrder) sessionOrder[peerID] = Unit
            } else {
                sessionOrder.remove(peerID)
            }
        }
    }

    /** Reserve before allocating responder state, so a refused opening changes no peer state. */
    private fun reserveInboundHandshake(state: PeerLock, now: Long, link: String): Boolean {
        if (state.countedInbound) return true
        return inboundHandshakeLock.withLock {
            pruneTrackedLinks(now)
            val admissions = inboundAdmissionsByLink[link]
            while (admissions?.isNotEmpty() == true && now - admissions.first() >= INBOUND_HANDSHAKE_WINDOW_MS) {
                admissions.removeFirst()
            }
            if (admissions?.isEmpty() == true) inboundAdmissionsByLink.remove(link)
            val currentAdmissions = inboundAdmissionsByLink[link]
            if (currentAdmissions != null && currentAdmissions.size >= maxInboundHandshakesPerLink) return@withLock false
            if (inboundHandshakes >= maxInboundHandshakes) return@withLock false
            inboundAdmissionsByLink.getOrPut(link) { ArrayDeque() }.addLast(now)
            inboundHandshakes += 1
            state.countedInbound = true
            true
        }
    }

    /** The table is only swept under pressure, so quiet links do not cause work on every opening. */
    private fun pruneTrackedLinks(now: Long) {
        if (inboundAdmissionsByLink.size <= MAX_TRACKED_LINKS) return
        val iterator = inboundAdmissionsByLink.iterator()
        while (iterator.hasNext()) {
            val (_, admissions) = iterator.next()
            if (admissions.isEmpty() || now - admissions.last() >= INBOUND_HANDSHAKE_WINDOW_MS) iterator.remove()
        }
    }

    /**
     * Records that the USER chose to write to [peerID] (a message or file sent, a session asked
     * for). A session with such a peer is pushed out by the session limit only when every other
     * session is with a chosen peer too. Nothing a packet says can put a peer here, which is the
     * point: ids are free to mint and each can earn a session, but none of them can make the user
     * write to it.
     */
    fun markChosenByUser(peerID: String) {
        sessionOrderLock.withLock {
            if (chosenByUser.remove(peerID) == null && chosenByUser.size >= maxChosenPeers) {
                // The set is bounded, so a choice has to go. One with no session goes first: the
                // mark only matters for a session, and a peer the user asked for and never reached
                // must not cost a live conversation its protection. The set holds twice as many
                // ids as there can be sessions, so there is always such a choice unless the user
                // has live sessions with more than maxSessions chosen peers, which cannot be.
                val goes = chosenByUser.keys.firstOrNull { it !in sessionOrder } ?: chosenByUser.keys.first()
                chosenByUser.remove(goes)
            }
            chosenByUser[peerID] = Unit
        }
    }

    internal fun isChosenByUser(peerID: String): Boolean = sessionOrderLock.withLock { peerID in chosenByUser }

    private fun touchSession(peerID: String) {
        sessionOrderLock.withLock {
            if (sessionOrder.remove(peerID) != null) sessionOrder[peerID] = Unit
        }
    }

    internal val inboundHandshakeCount: Int
        get() = inboundHandshakeLock.withLock { inboundHandshakes }

    internal val trackedLinkCount: Int
        get() = inboundHandshakeLock.withLock { inboundAdmissionsByLink.size }

    internal val sessionCount: Int
        get() = sessionOrderLock.withLock { sessionOrder.size }

    fun hasEstablishedSession(peerID: String, now: Long = currentTimeMillis()): Boolean {
        return withPeerLock(peerID) { state ->
            retireExpiredFallback(state, now)
            state.established?.isEstablished() == true
        }
    }

    /**
     * The link the established session's handshake completed on; null when there is no established
     * session. One look under the peer's lock: a session and where it was made are never seen apart.
     */
    fun establishedSessionLink(peerID: String, now: Long = currentTimeMillis()): String? {
        return withPeerLock(peerID) { state ->
            retireExpiredFallback(state, now)
            state.established?.takeIf { it.isEstablished() }?.let { state.establishedBinding?.link }
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
        now: Long,
        link: String
    ): HandshakeResult? {
        val isOpening = message.size == NoiseConstants.XX_MESSAGE_1_SIZE
        val pending = state.candidate

        // A candidate without a live session is the ordinary initial handshake. It must continue
        // to the collision rule below rather than being mistaken for a renegotiation.
        if (state.established?.isEstablished() != true) return null

        if (pending != null && !isOpening) {
            return advanceRenegotiation(peerID, state, pending, message, now, link)
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
        return advanceRenegotiation(peerID, state, session, message, now, link)
    }

    /** Feed [message] to a renegotiation, promoting it if it completes and dropping it if it fails. */
    private fun advanceRenegotiation(
        peerID: String,
        state: PeerLock,
        session: NoiseSession,
        message: ByteArray,
        now: Long,
        link: String
    ): HandshakeResult {
        val response = try {
            session.processHandshakeMessage(message)
        } catch (e: Exception) {
            println("[NoiseEncryptionFacade] Renegotiation with $peerID failed: ${e.message}; the established session stands")
            setCandidate(state, null, destroyPrevious = true)
            return HandshakeResult.Ignored
        }

        if (session.isEstablished()) {
            return validateAndPromote(peerID, state, session, response, now, link)
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
        now: Long = currentTimeMillis(),
        // The link is the address of the connection a packet arrived on, supplied by the
        // platform's GATT layer; it is never taken from a packet field. A relayed opening carries
        // the relay's link, so peers reached through one neighbour share that neighbour's admission.
        link: String = ""
    ): HandshakeResult {
        val result = withPeerLock(peerID) { state ->
            retireExpiredFallback(state, now)
            // The construction is inside the try. Every current actual swallows its own init failure
            // into NoiseSessionState.Failed, but a throw from here would propagate through
            // MessageHandler into the per-peer actor loop in PacketProcessor and kill that coroutine,
            // after which every packet from this peer is swallowed by a channel with no consumer.
            // Nothing about the call site guarantees it cannot throw, so it is covered.
            renegotiation(peerID, state, message, localStaticPrivateKey, localStaticPublicKey, now, link)
                ?.let { return@withPeerLock it }

            val collision = collisionVerdict(peerID, state, message, now)
            when (collision) {
                CollisionVerdict.HOLD -> {
                    println(
                        "[NoiseEncryptionFacade] Handshake collision with $peerID; holding our " +
                            "initiator session and ignoring its message 1"
                    )
                    return@withPeerLock HandshakeResult.Ignored
                }

                CollisionVerdict.YIELD -> {
                    if (!reserveInboundHandshake(state, now, link)) return@withPeerLock HandshakeResult.Ignored
                    println(
                        "[NoiseEncryptionFacade] Handshake collision with $peerID; yielding our " +
                            "initiator session and answering as responder"
                    )
                    setCandidate(state, null, destroyPrevious = true)
                }

                CollisionVerdict.NONE -> Unit
            }

            try {
                val session = state.candidate ?: run {
                    if (!reserveInboundHandshake(state, now, link)) return@withPeerLock HandshakeResult.Ignored
                    NoiseSession(
                        peerID = peerID,
                        isInitiator = false,
                        localStaticPrivateKey = localStaticPrivateKey,
                        localStaticPublicKey = localStaticPublicKey
                    ).also { setCandidate(state, it, initiated = false) }
                }

                val response = session.processHandshakeMessage(message)
                if (session.isEstablished()) {
                    validateAndPromote(peerID, state, session, response, now, link)
                } else {
                    response?.let(HandshakeResult::Response) ?: HandshakeResult.Ignored
                }
            } catch (e: Exception) {
                println("[NoiseEncryptionFacade] Handshake failed for $peerID: ${e.message}")
                setCandidate(state, null, destroyPrevious = true)
                HandshakeResult.Ignored
            }
        }
        if (result is HandshakeResult.Established && !enforceSessionLimit(peerID)) {
            // The handshake completed, but there was no room for its session: every other session
            // is with a peer the user chose to write to and this one is not. Nothing may claim a
            // session exists, and the handshake's last message (if this node owed one) is not sent.
            println("[NoiseEncryptionFacade] No room to keep a session with $peerID; sessions the user chose stay")
            return HandshakeResult.Ignored
        }
        return result
    }

    /**
     * Brings the number of sessions back under the limit after a promotion. Runs outside any peer
     * lock, because it has to take the lock of the peer whose session goes.
     *
     * The peer is picked under the order lock and then picked AGAIN under its own lock, and its
     * session is destroyed while the order lock is still held. [markChosenByUser] takes that same
     * lock, so the user's mark either lands before the second pick (and the peer is kept) or after
     * the session is gone; it cannot land in between. Between the two picks the peer may also have
     * been used, or another promotion may already have made room.
     */
    private fun enforceSessionLimit(promotedPeerID: String): Boolean {
        while (true) {
            // Whether the promoted peer still has its session is read at the end, not tracked
            // here: another promotion's enforcement may be the one that pushed it out.
            val picked = sessionOrderLock.withLock { sessionToPushOut(promotedPeerID) }
                ?: return sessionOrderLock.withLock { promotedPeerID in sessionOrder }
            beforePushingOut?.invoke(picked)
            withPeerLock(picked) { state ->
                sessionOrderLock.withLock {
                    if (sessionToPushOut(promotedPeerID) == picked) {
                        destroySessions(state)
                        sessionOrder.remove(picked)
                    }
                }
            }
        }
    }

    /**
     * Which session goes when there is one too many. A peer the user did not choose goes first,
     * least recently used first. The peer just promoted is the most recently used of them, so it
     * goes only when it is the ONLY one the user did not choose: a newcomer nobody asked for does
     * not displace a conversation the user is having. When every session is with a chosen peer,
     * the least recently used of the others goes and the newcomer (chosen too) stays.
     */
    private fun sessionToPushOut(promotedPeerID: String): String? {
        if (sessionOrder.size <= maxSessions) return null
        return sessionOrder.keys.firstOrNull { it !in chosenByUser }
            ?: sessionOrder.keys.firstOrNull { it != promotedPeerID }
    }

    /** Test hook: runs after a session has been picked to go and before its peer's lock is taken. */
    internal var beforePushingOut: ((String) -> Unit)? = null

    fun encrypt(peerID: String, data: ByteArray, now: Long = currentTimeMillis()): ByteArray? =
        encryptNamingLink(peerID, data, now)?.bytes

    /** What [encryptNamingLink] made, and the binding of the session that made it. */
    class Encrypted(val bytes: ByteArray, val sessionLink: String, val sessionToken: Long)

    /**
     * [encrypt], which also says where the session it used was made: read under the same lock as
     * the session, so the answer is about the very session these bytes belong to, whatever replaces
     * it a moment later.
     */
    fun encryptNamingLink(peerID: String, data: ByteArray, now: Long = currentTimeMillis()): Encrypted? {
        return withPeerLock(peerID) { state ->
            retireExpiredFallback(state, now)
            val binding = state.establishedBinding
            val encrypted = state.established
                ?.takeIf { it.isEstablished() }
                ?.encrypt(data)
            if (encrypted != null) touchSession(peerID)
            encrypted?.let { binding?.let { current -> Encrypted(it, current.link, current.token) } }
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
                state.fallbackBinding = null
                state.fallbackEstablishedAt = null
                touchSession(peerID)
                return@withPeerLock state.establishedBinding?.let {
                    DecryptionResult(establishedPlaintext, DecryptionVia.ESTABLISHED, it.token)
                }
            }
            state.fallback?.takeIf { it.isEstablished() }?.let { session ->
                val fallbackPlaintext = try { session.decrypt(encryptedData) } catch (e: Exception) { null }
                fallbackPlaintext?.let {
                    touchSession(peerID)
                    state.fallbackBinding?.let { binding -> DecryptionResult(it, DecryptionVia.FALLBACK, binding.token) }
                }
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
        state.fallbackBinding = state.establishedBinding
        state.fallbackEstablishedAt = null
        state.established = null
        state.establishedBinding = null
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
            state.establishedBinding = null
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

    /**
     * Drop the in-flight handshake only if this device opened it: for an opening that did not go
     * out. The peer's own opening may have arrived since ours was begun, and when this device gave
     * way to it the handshake in flight is the answer to the peer's, which is not ours to take back.
     */
    fun abandonOwnOpening(peerID: String) {
        withPeerLock(peerID) { state ->
            if (state.initiated) setCandidate(state, null, destroyPrevious = true)
        }
    }

    fun removeSession(peerID: String) {
        withPeerLock(peerID) { state -> destroySessions(state) }
    }

    private fun destroySessions(state: PeerLock) {
        state.established?.destroy()
        state.established = null
        state.establishedBinding = null
        state.fallback?.destroy()
        state.fallback = null
        state.fallbackBinding = null
        state.fallbackEstablishedAt = null
        // A renegotiation only exists to replace the session being removed here, so it goes
        // too -- otherwise it would outlive its purpose and later promote itself over a
        // session the peer has since built by other means.
        setCandidate(state, null, destroyPrevious = true)
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
        now: Long,
        link: String
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
            state.fallbackBinding = state.establishedBinding
        }
        state.established = candidate
        state.establishedBinding = SessionBinding(nextSessionToken.incrementAndGet(), link)
        state.fallbackEstablishedAt = state.fallback?.let { now }
        touchSession(peerID)
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
            state.fallbackBinding = null
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
        /**
         * This global backstop bounds live responder candidates. Reaching all 256 takes seven or
         * more links each sending eight or more openings every three seconds at once; handshakes
         * this node starts are not affected.
         */
        const val MAX_INBOUND_HANDSHAKES = 256

        /**
         * One link that sends eight or more openings every three seconds keeps its own admission
         * full, so other peers whose openings arrive over that link (including peers relayed by
         * that neighbour) wait and retry. Peers on every other link are unaffected.
         */
        const val MAX_INBOUND_HANDSHAKES_PER_LINK = 8

        const val INBOUND_HANDSHAKE_WINDOW_MS = 3_000L

        /**
         * Above this many links in the admission table, links that admitted nothing within the
         * window are dropped from it. A threshold for tidying, not a limit: a link that is inside
         * its window stays, and a link exists only for as long as a connection's address does.
         */
        const val MAX_TRACKED_LINKS = 64

        /**
         * Each session holds cipher state, and ids are free to mint, so their number has to be
         * limited; when it is reached the least recently used session goes. Filling the table takes
         * 256 completed handshakes under 256 different ids, each with its own key, and after that
         * every further one pushes out a session. What that costs the peer pushed out: one new
         * handshake, and the message that reveals the session is gone (it cannot be read) is lost.
         * So sessions with peers the user chose to write to ([markChosenByUser]) go last: a flood
         * of minted ids pushes out one another and peers that have only ever written to us.
         */
        const val MAX_NOISE_SESSIONS = 256

        const val ID_BYTES = 8
        const val ID_HEX_LENGTH = ID_BYTES * 2
        const val FALLBACK_MAX_AGE_MS = 5 * 60 * 1000L
    }
}
