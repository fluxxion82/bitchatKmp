package com.bitchat.bluetooth.manager

/**
 * Peers this node owes a handshake to: one could not be sent, or is being attempted, and it is
 * started again when a link to the peer comes up rather than on a timer.
 *
 * Two kinds are kept apart. A handshake the USER asked for (a private message was sent to that
 * peer) and one this node started by itself (a recovery after unreadable packets, a retry by the
 * sweeper). The second kind is triggered by packets nothing has authenticated, so it has its own
 * room: however many of those there are, they push out only one another, never a peer the user
 * asked for. Each kind keeps at most [capacity] peers and its oldest entry goes first.
 *
 * Pure bookkeeping; callers serialise access.
 */
class OwedHandshakes(private val capacity: Int = HandshakeSupervisor.MAX_OWED_HANDSHAKES) {

    private val forUser = linkedSetOf<String>()
    private val automatic = linkedSetOf<String>()

    val userCount: Int get() = forUser.size
    val automaticCount: Int get() = automatic.size

    operator fun contains(peerID: String): Boolean = peerID in forUser || peerID in automatic

    fun isForUser(peerID: String): Boolean = peerID in forUser

    /** The user asked for a session with [peerID]. An automatic entry for it becomes the user's. */
    fun rememberForUser(peerID: String) {
        automatic.remove(peerID)
        add(forUser, peerID)
    }

    /** This node started a handshake with [peerID] by itself. Never downgrades a user's entry. */
    fun rememberAutomatic(peerID: String) {
        if (peerID in forUser) return
        add(automatic, peerID)
    }

    fun remove(peerID: String) {
        forUser.remove(peerID)
        automatic.remove(peerID)
    }

    /** An entry already present keeps its place in the queue. */
    private fun add(set: LinkedHashSet<String>, peerID: String) {
        if (peerID in set) return
        if (set.size >= capacity) set.firstOrNull()?.let(set::remove)
        set.add(peerID)
    }
}
