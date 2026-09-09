package com.bitchat.bluetooth.manager

/**
 * What to do about a peer's Noise handshake when its link is (re)established.
 *
 * The dangerous case is a handshake we did not start. Only the initiator path records a start time,
 * so a responder handshake -- one where the peer sent message 1 and we have answered with message 2
 * and are waiting for message 3 -- looks exactly like a stalled handshake with no start time, and
 * used to be torn down and "restarted". A responder cannot restart anything: discarding it only
 * guarantees that the message 3 already on its way finds nothing to complete, and the peer then has
 * to time out and begin again.
 */
class HandshakeRefreshPolicy(
    private val restartGraceMs: Long = HANDSHAKE_RESTART_GRACE_MS
) {

    enum class Decision {
        /** Leave whatever is in flight alone. */
        LEAVE,

        /** Discard our initiator handshake and start a fresh one. */
        RESTART,

        /** Nothing is in flight and we owe this peer a handshake. */
        INITIATE
    }

    /**
     * @param established whether a usable session already exists
     * @param handshaking whether a handshake is in flight
     * @param ourHandshakeStartedAt when our own initiator handshake went out, or null when the
     *   handshake in flight is not ours to restart
     * @param owed whether a handshake was deferred for want of a link
     */
    fun decide(
        established: Boolean,
        handshaking: Boolean,
        ourHandshakeStartedAt: Long?,
        now: Long,
        owed: Boolean
    ): Decision {
        if (established) return Decision.LEAVE

        if (!handshaking) {
            return if (owed) Decision.INITIATE else Decision.LEAVE
        }

        // Not ours: a responder waiting for message 3. Restarting destroys it for nothing.
        if (ourHandshakeStartedAt == null) return Decision.LEAVE

        // One physical link produces two link-up signals a few hundred milliseconds apart -- the
        // inbound packet that binds the address, then the outbound connection becoming ready.
        // Restarting on the second throws away a handshake that has only just gone out.
        if (now - ourHandshakeStartedAt < restartGraceMs) return Decision.LEAVE

        return Decision.RESTART
    }

    companion object {
        const val HANDSHAKE_RESTART_GRACE_MS = 2_000L
    }
}
