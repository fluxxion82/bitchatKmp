package com.bitchat.bluetooth.manager

/**
 * Where BlueZ's answer to RegisterAdvertisement leaves the advertisement.
 *
 * bluetoothd answers only once the kernel has added the instance, and the kernel runs its management
 * commands one at a time: a pending LE connect (one a previous process of this app left behind, say)
 * holds them for about 21 s. So the answer can come long after start-up stopped waiting, and it is
 * still the answer. Only a request that ended with no answer at all (NoReply: bluetoothd left the bus,
 * or the connection closed) is made again, at most [maxRequests] times in all. Asking again while
 * BlueZ still holds a request gets AlreadyExists at once, which says nothing about the kernel.
 *
 * Pure bookkeeping: the caller makes the D-Bus calls and holds the lock. Times are milliseconds from
 * a clock that only goes forward.
 */
class AdvertisementRegistration(private val maxRequests: Int = 3) {

    sealed interface Next {
        /** BlueZ has the advertisement, [afterMs] after the first request; [late] once start-up had stopped waiting. */
        data class Registered(val afterMs: Long, val late: Boolean) : Next

        /** The request ended without an answer: make it again. */
        data object AskAgain : Next

        /** Nothing is registered and nothing more is asked. */
        data class GaveUp(val reason: String) : Next

        /** Not the answer to a request that stands. */
        data object Ignore : Next
    }

    private enum class State { IDLE, ASKING, REGISTERED, GAVE_UP }

    private var state = State.IDLE
    private var firstAskedAt = 0L
    private var requests = 0
    private var late = false

    val isAsking: Boolean get() = state == State.ASKING
    val isRegistered: Boolean get() = state == State.REGISTERED

    /** The first request was sent at [now]. */
    fun asked(now: Long) {
        state = State.ASKING
        firstAskedAt = now
        requests = 1
        late = false
    }

    /** The outstanding request ended at [now]: [errorName] is null for a method return. */
    fun answered(errorName: String?, now: Long): Next {
        if (state != State.ASKING) return Next.Ignore
        return when (errorName) {
            null, ALREADY_EXISTS -> {
                state = State.REGISTERED
                Next.Registered(now - firstAskedAt, late)
            }
            NO_REPLY -> if (requests < maxRequests) {
                requests++
                Next.AskAgain
            } else {
                state = State.GAVE_UP
                Next.GaveUp("no answer to $requests requests")
            }
            else -> {
                state = State.GAVE_UP
                Next.GaveUp(errorName)
            }
        }
    }

    /** The request [Next.AskAgain] called for could not be sent. */
    fun couldNotAskAgain() {
        if (state == State.ASKING) state = State.GAVE_UP
    }

    /** Start-up goes on without the answer, which is still taken when it comes. False if it has come. */
    fun stopWaiting(): Boolean {
        if (state != State.ASKING) return false
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
        return asked
    }

    private companion object {
        const val ALREADY_EXISTS = "org.bluez.Error.AlreadyExists"
        const val NO_REPLY = "org.freedesktop.DBus.Error.NoReply"
    }
}
