package com.bitchat.client

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** A route admission token. A route change makes every older token unusable. */
class RouteLease internal constructor(internal val value: Long)

/** A transport survived its bounded retirement window; publishing a new route is unsafe. */
class RouteRetirementException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Serializes route changes with outbound work.
 *
 * Admission happens before a request or socket attempt starts. Revocation advances the generation,
 * cancels every admitted coroutine, then waits for each to finish. This prevents a direct call
 * which started before Tor was requested from completing after the new policy was published.
 */
class RouteGenerations {
    private val lock = Mutex()
    private val transitionLock = Mutex()
    @Volatile private var generation = 0L
    private val active = mutableSetOf<Job>()
    private val failedRetirements = mutableListOf<suspend () -> Unit>()
    private var admissionOpen = completedGate()

    suspend fun admit(): RouteLease {
        while (true) {
            val (lease, closedGate) = lock.withLock {
                if (admissionOpen.isCompleted) {
                    RouteLease(generation) to null
                } else {
                    null to admissionOpen
                }
            }
            if (lease != null) return lease
            closedGate!!.await()
        }
    }

    fun isCurrent(lease: RouteLease): Boolean = lease.value == generation

    suspend fun <T> track(lease: RouteLease, block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job] ?: error("Route work needs a Job")
        lock.withLock {
            check(isCurrent(lease)) { "Route generation was revoked" }
            active += job
        }
        return try {
            block()
        } finally {
            // A cancelled tracked call still owns a slot until it is removed. Do not make that
            // cleanup depend on acquiring this mutex from a cancelled context.
            withContext(NonCancellable) {
                lock.withLock { active -= job }
            }
        }
    }

    /**
     * Retains a failed direct shutdown until an ON transition can retry it.
     *
     * A Tor-routed socket cannot expose a direct connection after its route is revoked, so its
     * failed engine teardown must never prevent either policy from recovering.
     */
    suspend fun retainFailedRetirement(
        blocksTorEnable: Boolean = true,
        retirement: suspend () -> Unit,
    ) {
        if (!blocksTorEnable) return
        withContext(NonCancellable) {
            lock.withLock { failedRetirements += retirement }
        }
    }

    /**
     * Closes admission, drains old work, publishes the new routing policy, then reopens it.
     *
     * New work cannot acquire a post-revocation direct lease before an ON policy is visible.
     */
    suspend fun transition(
        waitForDirectRetirements: Boolean = true,
        publishPolicy: suspend () -> Unit,
    ) = transitionLock.withLock {
        val caller = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            val (jobs, closedGate) = lock.withLock {
                // A tracked outbound operation cannot also become the transition owner: joining
                // itself would deadlock policy settlement. Production transitions run outside
                // route work; reject accidental misuse before closing admission.
                check(caller !in active) { "Route work cannot transition its own route" }
                val gate = CompletableDeferred<Unit>()
                admissionOpen = gate
                generation += 1
                active.toList() to gate
            }
            jobs.forEach { it.cancel() }
            try {
                jobs.joinAll()
                if (waitForDirectRetirements) retryFailedRetirements()
                publishPolicy()
            } finally {
                lock.withLock {
                    if (admissionOpen === closedGate) closedGate.complete(Unit)
                }
            }
        }
    }

    suspend fun revokeAndJoin() = transition {}

    private suspend fun retryFailedRetirements() {
        val retirements = lock.withLock {
            failedRetirements.toList().also { failedRetirements.clear() }
        }
        for ((index, retirement) in retirements.withIndex()) {
            try {
                retirement()
            } catch (error: Throwable) {
                // The snapshot was removed before retrying so newly-failed transport owners can
                // register concurrently. Put back the failed owner *and every unattempted one*;
                // dropping the tail would let a later ON publish with a direct socket alive.
                lock.withLock { failedRetirements.addAll(0, retirements.drop(index)) }
                throw RouteRetirementException("Outbound transport retirement failed", error)
            }
        }
    }

    private fun completedGate() = CompletableDeferred<Unit>().also { it.complete(Unit) }
}
