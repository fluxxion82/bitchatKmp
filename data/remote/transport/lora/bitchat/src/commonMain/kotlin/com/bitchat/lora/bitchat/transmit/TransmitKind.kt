package com.bitchat.lora.bitchat.transmit

internal enum class Ledger { LOCAL_ORIGIN, REMOTE_SOLICITED, BACKGROUND }

/** Whose packet caused a response transmission. */
internal sealed interface Cause {
    data object Unauthenticated : Cause
    data class Validated(val peerID: String) : Cause
}

/** The transmission's budget, ordering and collision-avoidance delay. */
internal enum class TransmitKind(val ledger: Ledger, val priority: Int, val jitterMs: LongRange) {
    LOCAL_HANDSHAKE_FINAL(Ledger.LOCAL_ORIGIN, 0, 0L..1_000L),
    LOCAL_HANDSHAKE_ANSWER(Ledger.LOCAL_ORIGIN, 0, 0L..250L),
    PRIVATE_MESSAGE(Ledger.LOCAL_ORIGIN, 1, 250L..1_250L),
    LOCAL_HANDSHAKE_OPENING(Ledger.LOCAL_ORIGIN, 2, 0L..1_000L),
    PUBLIC_MESSAGE(Ledger.LOCAL_ORIGIN, 3, 0L..0L),
    DELIVERY_ACK(Ledger.REMOTE_SOLICITED, 0, 250L..1_250L),
    HANDSHAKE_ANSWER(Ledger.REMOTE_SOLICITED, 1, 0L..250L),
    RECOVERY_OPENING(Ledger.REMOTE_SOLICITED, 2, 0L..1_000L),
    CACHED_RESEND(Ledger.REMOTE_SOLICITED, 3, 0L..250L),
    HEARTBEAT(Ledger.BACKGROUND, 0, 0L..1_000L),
}
