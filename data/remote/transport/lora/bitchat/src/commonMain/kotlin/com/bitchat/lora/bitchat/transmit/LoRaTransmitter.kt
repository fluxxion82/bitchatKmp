package com.bitchat.lora.bitchat.transmit

import com.bitchat.lora.bitchat.BitChatRadio
import com.bitchat.lora.radio.LoRaConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one way to the radio: a single worker takes frames from the [TransmitQueue] one at a time, so
 * two frames are never on the air together and none goes out without its time on air accounted for.
 */
internal class LoRaTransmitter(
    private val radio: BitChatRadio,
    private val clockMillis: () -> Long,
    private val jitter: (LongRange) -> Long,
) {
    enum class Outcome { SENT, FAILED, EXPIRED, CANCELLED }

    class Ticket internal constructor(private val completion: CompletableDeferred<Outcome>) {
        suspend fun await(): Outcome = completion.await()
    }

    // Everything below is read and written under the mutex, which is never held across a call to
    // the radio or a wait.
    private val mutex = Mutex()
    private val queue = TransmitQueue()
    private val tickets = mutableMapOf<TransmitQueue.Item, CompletableDeferred<Outcome>>()
    private var worker: Job? = null
    private var accepting = false

    private val wake = Channel<Unit>(Channel.CONFLATED)

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun start(config: LoRaConfig, scope: CoroutineScope) {
        while (true) {
            val finishing = mutex.withLock {
                val previous = worker
                if (previous == null || previous.isCompleted) {
                    queue.configure(config)
                    accepting = true
                    // Atomic: its body starts even in a scope that is already cancelled, so the worker's
                    // own cleanup runs however it ends and no ticket is left without an answer.
                    worker = scope.launch(start = CoroutineStart.ATOMIC) { work() }
                    return
                }
                check(!previous.isActive) { "Transmitter is already started" }
                previous
            }
            // Cancelled, and still seeing its last frame off the air.
            finishing.join()
        }
    }

    /**
     * Nothing more is sent once this returns; what was waiting is cancelled, a frame already handed
     * to the radio is waited for. The time already spent stays spent.
     */
    suspend fun stop() = withContext(NonCancellable) {
        val running = mutex.withLock {
            accepting = false
            worker
        }
        // The worker cleans up after itself as it ends, whoever ended it.
        running?.cancelAndJoin()
    }

    /**
     * Asks for [frames] to be sent, all or none. Null when they were refused: no time on air or no
     * place left for this [kind] (or this [cause]), or the transmitter is stopped. One delay is drawn
     * for the whole list, so the fragments of a message stay together. A frame not handed to the
     * radio within [lifetimeMillis] is dropped: that is decided at the worker's last look at the
     * clock before it calls the radio.
     */
    suspend fun offer(
        frames: List<ByteArray>,
        kind: TransmitKind,
        cause: Cause? = null,
        lifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS,
        hold: TransmitQueue.Hold? = null,
    ): List<Ticket>? {
        require(lifetimeMillis in 1..MAX_LIFETIME_MILLIS) { "A frame's lifetime is between 1 ms and a minute" }
        val delay = jitter(kind.jitterMs)
        val completions = mutex.withLock {
            if (!accepting) return@withLock null
            // Read under the lock: read before waiting for it, it could be a time already past.
            val now = clockMillis()
            val items = queue.offer(
                frames.map { TransmitQueue.Request(it, kind, cause, now + delay, now + lifetimeMillis) }, now, hold
            ) ?: return@withLock null
            items.map { item -> CompletableDeferred<Outcome>().also { tickets[item] = it } }
        } ?: return null
        wake.trySend(Unit)
        return completions.map(::Ticket)
    }

    suspend fun hold(micros: Long, places: Int, lifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS): TransmitQueue.Hold? {
        require(lifetimeMillis in 1..MAX_LIFETIME_MILLIS) { "A hold's lifetime is between 1 ms and a minute" }
        return mutex.withLock {
            if (!accepting) return@withLock null
            val now = clockMillis()
            queue.hold(micros, places, now, now + lifetimeMillis)
        }?.also { wake.trySend(Unit) }
    }

    suspend fun release(hold: TransmitQueue.Hold) {
        mutex.withLock { queue.release(hold) }
    }

    private suspend fun work() {
        try {
            while (true) {
                // A stop is noticed between steps and never inside one: a frame taken from the queue
                // is in flight until its outcome is recorded, and a stop that fell between the two
                // would leave it in flight for good. The waits below notice it too, except that
                // `receive` hands over a wake-up that is already there without looking: hence here.
                currentCoroutineContext().ensureActive()
                val lookAgainAt = withContext(NonCancellable) { step() }
                if (lookAgainAt == null) {
                    wake.receive()
                } else {
                    // At least a millisecond unless something is offered meanwhile: whatever a step
                    // returns, the worker must not become a loop that never lets go of its thread.
                    withTimeoutOrNull((lookAgainAt - clockMillis()).coerceAtLeast(MIN_PAUSE_MILLIS)) { wake.receive() }
                }
            }
        } finally {
            withContext(NonCancellable) { shutDown() }
        }
    }

    /** The worker is gone: nothing is accepted any more and whatever still waits is answered. */
    private suspend fun shutDown() {
        val open = mutex.withLock {
            accepting = false
            queue.clear()
            tickets.values.toList().also { tickets.clear() }
        }
        open.forEach { it.complete(Outcome.CANCELLED) }
    }

    /** Sends at most one frame. Returns when to look again, or null to wait until something is offered. */
    private suspend fun step(): Long? {
        val next = mutex.withLock { queue.next(clockMillis()) }
        next.expired.forEach { complete(it, Outcome.EXPIRED) }
        val item = next.send ?: return next.waitUntilMillis

        // Looked at again at the moment of handing over: the worker may have been kept from running
        // since the frame was taken.
        val handedOverAt = clockMillis()
        val unsent = when {
            !radio.isReady -> Outcome.FAILED
            item.expiresAtMillis <= handedOverAt -> Outcome.EXPIRED
            else -> null
        }
        if (unsent != null) {
            mutex.withLock { queue.notTransmitted(item) }
            complete(item, unsent)
            return handedOverAt
        }

        val sent = try {
            radio.send(item.frame)
        } catch (e: Exception) {
            false
        }
        // An attempt is charged whether or not the driver says it worked: it may have been on the air.
        // When the frame went on the air is only known to be before the driver answered, and whether
        // the driver waited for it to leave is not known at all: counted from its answer, the frame
        // has certainly left by then, and the next one is not handed over before. One millisecond
        // more because the clock's milliseconds are rounded down.
        val offTheAirAt = clockMillis() + (item.airtimeMicros + 999) / 1_000 + CLOCK_TICK_MILLIS
        mutex.withLock { queue.transmitted(item, offTheAirAt) }
        delay((offTheAirAt - clockMillis()).coerceAtLeast(0))
        complete(item, if (sent) Outcome.SENT else Outcome.FAILED)
        return offTheAirAt
    }

    private suspend fun complete(item: TransmitQueue.Item, outcome: Outcome) {
        mutex.withLock { tickets.remove(item) }?.complete(outcome)
    }

    private companion object {
        /** How long a frame may wait to be sent before it is dropped: the life of a LoRa handshake. */
        const val DEFAULT_LIFETIME_MILLIS = 15_000L
        const val MAX_LIFETIME_MILLIS = 60_000L
        const val CLOCK_TICK_MILLIS = 1L
        const val MIN_PAUSE_MILLIS = 1L
    }
}
