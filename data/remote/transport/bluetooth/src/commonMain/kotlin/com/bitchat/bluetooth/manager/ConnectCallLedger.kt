package com.bitchat.bluetooth.manager

/**
 * Counts dials whose `Connect` timed out or that the bus refused, and decides when the app should end
 * so that systemd restarts it: a run of at least [EXIT_AFTER_REFUSALS] refusals whose first is at
 * least [EXIT_AFTER_MS] old. Only an answer from bluetoothd ends a run. Not thread-safe; times are
 * from a monotonic clock.
 */
class ConnectCallLedger {
    var timedOut: Int = 0
        private set
    var busRefused: Int = 0
        private set

    private var firstRefusal: Long? = null
    private var refusalsInRun = 0
    private var silentBluezUntil = 0L

    fun onDial(call: ConnectCall, now: Long) {
        when (call) {
            ConnectCall.TIMED_OUT -> timedOut++
            ConnectCall.BUS_REFUSED -> {
                busRefused++
                if (refusalsInRun == 0) firstRefusal = now
                refusalsInRun++
            }
            ConnectCall.BLUEZ_ANSWERED -> {
                firstRefusal = null
                refusalsInRun = 0
            }
            ConnectCall.OTHER -> Unit
        }
    }

    fun onBluezSilent(now: Long) {
        silentBluezUntil = now + SILENT_BLUEZ_REST_MS
    }

    fun exitDue(now: Long): Boolean =
        refusalsInRun >= EXIT_AFTER_REFUSALS &&
            now - (firstRefusal ?: return false) >= EXIT_AFTER_MS &&
            now >= silentBluezUntil

    companion object {
        const val EXIT_AFTER_REFUSALS = 3
        const val EXIT_AFTER_MS = 60_000L
        const val SILENT_BLUEZ_REST_MS = 30_000L
    }
}

fun connectCallsText(timedOut: Int, released: Int, busRefused: Int): String =
    " timed-out=$timedOut released=$released bus-refused=$busRefused"
