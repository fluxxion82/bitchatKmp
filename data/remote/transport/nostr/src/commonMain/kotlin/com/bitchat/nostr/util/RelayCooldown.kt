package com.bitchat.nostr.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Lets something be done for a relay at most once per [interval], without forgetting a request that
 * comes too soon: that one is told how long to wait. While one is waiting, further requests for the
 * same relay are answered "already waiting", so a relay cannot queue up more than one.
 *
 * There is one entry per relay URL, and those come from this app's own relay list.
 */
internal class RelayCooldown(
    private val interval: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class State(var waiting: Boolean = false, var lastStarted: TimeMark? = null)

    private val lock = SynchronizedObject()
    private val states = mutableMapOf<String, State>()

    /** How long to wait before acting for [relayUrl]; null when an earlier request is still waiting to act. */
    fun request(relayUrl: String): Duration? = synchronized(lock) {
        val state = states.getOrPut(relayUrl) { State() }
        if (state.waiting) return@synchronized null
        state.waiting = true
        val sinceLast = state.lastStarted?.elapsedNow()
        if (sinceLast == null || sinceLast >= interval) Duration.ZERO else interval - sinceLast
    }

    /** The wait is over and the action for [relayUrl] starts now. */
    fun started(relayUrl: String) = synchronized(lock) {
        states.getOrPut(relayUrl) { State() }.let { state ->
            state.waiting = false
            state.lastStarted = timeSource.markNow()
        }
    }
}
