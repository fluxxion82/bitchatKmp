package com.bitchat.domain.tor

/** Owns outbound work and publishes route policy only after its old work is gone. */
interface TorRouteLifecycle {
    suspend fun transition(
        waitForDirectRetirements: Boolean = true,
        publishPolicy: suspend () -> Unit,
    )

    suspend fun revokeAndJoin() = transition {}
}
