package com.bitchat.viewmodel.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat switches (opening and leaving a DM), applied one at a time in the order they were asked
 * for, each run to its end: a DM switch selects the peer in the chat repository, suspends, and
 * saves the user state later, and cut in between it would leave the peer selected while the state
 * says a public chat (that peer's DMs then count as read). So neither a caller that stops waiting
 * nor the view model's scope going away cancels a switch once it has begun; one asked for after
 * the scope has gone never runs, and its job never completes.
 */
internal class OrderedTransitions(scope: CoroutineScope) {
    private val queue = Channel<Transition>(Channel.UNLIMITED)

    private class Transition(val apply: suspend () -> Unit, val done: CompletableDeferred<Unit>)

    init {
        scope.launch {
            for (transition in queue) {
                try {
                    withContext(NonCancellable) { transition.apply() }
                    transition.done.complete(Unit)
                } catch (e: CancellationException) {
                    transition.done.cancel(e)
                    throw e
                } catch (e: Exception) {
                    transition.done.completeExceptionally(e)
                }
            }
        }
    }

    /**
     * Queues [apply]; returns a job that completes when it has been applied, or completes
     * exceptionally (counted as cancelled) when it failed.
     */
    fun submit(apply: suspend () -> Unit): Job {
        val done = CompletableDeferred<Unit>()
        queue.trySend(Transition(apply, done))
        return done
    }
}
