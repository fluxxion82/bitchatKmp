package com.bitchat.bluetooth.manager

/**
 * Where BlueZ's answer to RegisterAdvertisement leaves the advertisement.
 *
 * bluetoothd answers only once the kernel has added the instance, and the kernel runs its management
 * commands one at a time: a pending LE connect (one a previous process of this app left behind, say)
 * holds them for about 21 s. So the answer can come long after start-up stopped waiting, and it is
 * still the answer. A request that ended with no answer at all (NoReply: bluetoothd left the bus,
 * or the connection closed) is made again, at most [maxRequests] times in all. Asking again while
 * BlueZ still holds a request gets AlreadyExists at once, which says nothing about the kernel.
 *
 * org.bluez.Error.Failed means the kernel or the controller would not add the instance this time. The
 * terminal UI board got it at the first start after a deploy on 2026-10-13, and the next start
 * registered in 87 ms. BlueZ forgets the client at once, so the request is made again after each of
 * [failedRetryDelaysMs]; the defaults add up to 42 s, more than the longest failed connect (34 s) a
 * previous process can leave behind. Any other refusal is final. A delay cannot be negative. A deadline
 * or an elapsed time too large for a Long is capped at Long.MAX_VALUE; the deadline falls due there.
 *
 * Pure bookkeeping: the caller makes the D-Bus calls and holds the lock. Times are milliseconds from
 * a clock that only goes forward.
 */
class AdvertisementRegistration(
    private val maxRequests: Int = 3,
    failedRetryDelaysMs: List<Long> = listOf(2_000L, 10_000L, 30_000L),
) {
    // A copy, checked as the copy: the caller's list may change afterwards.
    private val failedRetryDelaysMs = failedRetryDelaysMs.toList().also { delays ->
        require(delays.all { it >= 0L }) { "a retry delay cannot be negative: $delays" }
    }

    sealed interface Next {
        /** BlueZ has the advertisement, [afterMs] after the first request; [late] once start-up had stopped waiting. */
        data class Registered(val afterMs: Long, val late: Boolean) : Next

        /** The request ended without an answer: make it again. */
        data object AskAgain : Next

        /** BlueZ refused with org.bluez.Error.Failed: make the request again once [delayMs] has passed ([retryDue]). */
        data class RetryAfter(val delayMs: Long) : Next

        /** Nothing is registered and nothing more is asked. */
        data class GaveUp(val reason: String) : Next

        /** Not the answer to a request that stands. */
        data object Ignore : Next
    }

    private enum class State { IDLE, ASKING, WAITING_TO_RETRY, REGISTERED, GAVE_UP }

    private var state = State.IDLE
    private var firstAskedAt = 0L
    private var requests = 0
    private var failedAnswers = 0
    private var retryAt = 0L
    private var late = false

    /** A request is out, or the next one is waiting for its delay to pass. */
    val isAsking: Boolean get() = state == State.ASKING || state == State.WAITING_TO_RETRY
    val isRegistered: Boolean get() = state == State.REGISTERED

    /** The next request waits for [retryDue]. */
    val isWaitingToRetry: Boolean get() = state == State.WAITING_TO_RETRY

    /** The first request was sent at [now]. */
    fun asked(now: Long) {
        state = State.ASKING
        firstAskedAt = now
        requests = 1
        failedAnswers = 0
        late = false
    }

    /** The outstanding request ended at [now]: [errorName] is null for a method return. */
    fun answered(errorName: String?, now: Long): Next {
        if (state != State.ASKING) return Next.Ignore
        return when (errorName) {
            null, ALREADY_EXISTS -> {
                state = State.REGISTERED
                Next.Registered(elapsedSince(firstAskedAt, now), late)
            }
            NO_REPLY -> if (requests < maxRequests) {
                requests++
                Next.AskAgain
            } else {
                state = State.GAVE_UP
                Next.GaveUp("no answer to $requests requests")
            }
            FAILED -> if (failedAnswers < failedRetryDelaysMs.size) {
                val delayMs = failedRetryDelaysMs[failedAnswers++]
                state = State.WAITING_TO_RETRY
                // delayMs is not negative, so a deadline below now is a sum that wrapped.
                retryAt = (now + delayMs).let { if (it < now) Long.MAX_VALUE else it }
                Next.RetryAfter(delayMs)
            } else {
                state = State.GAVE_UP
                Next.GaveUp(errorName)
            }
            else -> {
                state = State.GAVE_UP
                Next.GaveUp(errorName)
            }
        }
    }

    // The clock only goes forward, so a negative difference is one that wrapped.
    private fun elapsedSince(then: Long, now: Long): Long = (now - then).let { if (it < 0L) Long.MAX_VALUE else it }

    /** True once, when the delay [Next.RetryAfter] asked for is over: the caller makes the request now. */
    fun retryDue(now: Long): Boolean {
        if (state != State.WAITING_TO_RETRY || now < retryAt) return false
        state = State.ASKING
        return true
    }

    /** The request [Next.AskAgain] called for could not be sent. */
    fun couldNotAskAgain() {
        if (state == State.ASKING) state = State.GAVE_UP
    }

    /** Start-up goes on without the answer, which is still taken when it comes. False if it has come. */
    fun stopWaiting(): Boolean {
        if (!isAsking) return false
        late = true
        return true
    }

    /**
     * Forget the registration; true when BlueZ must be asked to unregister. That is anything asked
     * for: BlueZ holds a client from the moment it reads the request, long before it answers, and
     * UnregisterAdvertisement also ends one still being added. One it never held answers
     * DoesNotExist, which costs nothing.
     */
    fun stop(): Boolean {
        val asked = state != State.IDLE
        state = State.IDLE
        requests = 0
        failedAnswers = 0
        return asked
    }

    private companion object {
        const val ALREADY_EXISTS = "org.bluez.Error.AlreadyExists"
        const val FAILED = "org.bluez.Error.Failed"
        const val NO_REPLY = "org.freedesktop.DBus.Error.NoReply"
    }
}
