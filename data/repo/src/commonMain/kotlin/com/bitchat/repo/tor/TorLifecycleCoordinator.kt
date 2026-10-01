package com.bitchat.repo.tor

import com.bitchat.domain.app.AppForegroundState
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

/** The two blocking manager calls owned by [TorLifecycleCoordinator] on Apple. */
interface TorLifecycleManager {
    suspend fun start()
    suspend fun stop()
}

/** Apple-only lifecycle authority, exposed so common callers can delegate to it when bound. */
interface TorLifecycleControl {
    suspend fun initialize()
    suspend fun reconcile()
}

/**
 * Serializes Apple Tor lifecycle decisions from the user's requested mode and process foreground
 * state. The foreground holder remains a conflated state flow for state readers, and also queues
 * every published edge before this actor performs a potentially blocking manager operation.
 * Therefore a background event received during bootstrap cannot be overwritten by a later
 * foreground event.
 */
class TorLifecycleCoordinator(
    private val manager: TorLifecycleManager,
    private val requestedIntent: RequestedTorIntent,
    private val foregroundState: AppForegroundState,
    private val scope: CoroutineScope,
) : TorLifecycleControl {
    private sealed interface Signal {
        data class Foreground(val value: Boolean) : Signal
        data class Requested(val value: TorMode) : Signal
        data class Snapshot(val foreground: Boolean, val requested: TorMode, val done: CompletableDeferred<Unit>) : Signal
    }

    private val signals = Channel<Signal>(Channel.UNLIMITED)
    private val initialization = Mutex()
    private var initialized = false

    override suspend fun initialize() {
        initialization.withLock {
            if (initialized) return
            initialized = true
            foregroundState.listen { signals.trySend(Signal.Foreground(it)) }
            scope.launch {
                requestedIntent.updates.collect { signals.send(Signal.Requested(it)) }
            }
            scope.launch { runStateMachine() }
        }
    }

    override suspend fun reconcile() {
        initialize()
        val done = CompletableDeferred<Unit>()
        signals.send(Signal.Snapshot(foregroundState.foreground.value, requestedIntent.current, done))
        done.await()
    }

    private suspend fun runStateMachine() {
        var foreground = foregroundState.foreground.value
        var requested = requestedIntent.current
        var managerStarted = false

        for (signal in signals) {
            when (signal) {
                is Signal.Foreground -> foreground = signal.value
                is Signal.Requested -> requested = signal.value
                is Signal.Snapshot -> {
                    foreground = signal.foreground
                    requested = signal.requested
                }
            }

            // A foreground edge can wait behind a blocking background stop. Intent updates are
            // conflated, so check the live value before that edge starts another generation.
            // Do not use this to replace queued edges: a background edge must still stop Tor.
            if (foreground && !managerStarted) {
                requested = requestedIntent.current
            }
            val shouldRun = foreground && requested == TorMode.ON
            if (shouldRun && !managerStarted) {
                // Mark before the call: signals that arrive while start blocks are reconciled only
                // after it returns, and see this generation as already owned.
                managerStarted = true
                manager.start()
            } else if (!shouldRun && managerStarted) {
                // Clear before stop for the symmetric reason. A queued foreground starts exactly
                // one new generation after the worker has finished stopping the old one.
                managerStarted = false
                manager.stop()
            }

            if (signal is Signal.Snapshot) signal.done.complete(Unit)
        }
    }
}
