package com.bitchat.tui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Applies requested values one at a time, in order, skipping any that a newer request has already
 * replaced: the latest request always wins, and two applications never overlap. For a desired
 * state (the geohashes to sample) set from several places over time (each visit to a screen, and
 * its end), so that an old "stop" can never run after, or during, a newer "start".
 */
class LatestOnly<T>(scope: CoroutineScope, private val apply: suspend (T) -> Unit) {
    private val requests = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch { for (value in requests) apply(value) }
    }

    fun request(value: T) {
        requests.trySend(value)
    }
}

/**
 * The coroutines that calls into a scope we do not own (a view model's) started, so they can be
 * awaited before that scope is cancelled: a selection made as a screen closes must finish, not be
 * cut off when the screen's view model is cleared. [scopeJob] is that scope's job.
 */
class LaunchedWork(private val scopeJob: () -> Job) {
    private val pending = ArrayList<Job>()

    /** Runs [call] (on the thread that owns this tracker), remembering the coroutines it launched. */
    fun track(call: () -> Unit) {
        val parent = scopeJob()
        val before = parent.children.toSet()
        call()
        pending.removeAll { it.isCompleted }
        pending += parent.children.filterNot { it in before }
    }

    /** Waits for everything tracked so far. */
    suspend fun awaitAll() {
        val jobs = pending.toList()
        pending.clear()
        jobs.joinAll()
    }
}
