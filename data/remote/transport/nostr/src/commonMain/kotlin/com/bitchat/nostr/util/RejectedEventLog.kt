package com.bitchat.nostr.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Counts the events each relay sent that failed verification, and says so at a bounded rate.
 *
 * A relay checks ids and signatures before it stores or forwards an event, so one that delivers
 * an event which does not verify is broken or hostile, and it can send them as fast as its socket
 * carries. The first from a relay is reported at once; after that there is at most one line per
 * relay every [interval], saying how many were dropped since the last. Nothing of the event is in
 * the line: its fields are whatever the relay chose to put there.
 *
 * There is one entry per relay URL, and those come from this app's own relay list, never from a peer.
 */
internal class RejectedEventLog(
    private val interval: Duration = 1.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Tally(var total: Long = 0, var unreported: Long = 0, var lastReport: TimeMark? = null)

    private val lock = SynchronizedObject()
    private val tallies = mutableMapOf<String, Tally>()

    /** Counts one rejected event from [relayUrl] and returns the line to log, if one is due. */
    fun rejected(relayUrl: String): String? = synchronized(lock) {
        val tally = tallies.getOrPut(relayUrl) { Tally() }
        tally.total++
        tally.unreported++
        if (tally.lastReport?.let { it.elapsedNow() < interval } == true) return@synchronized null

        val line = "NostrRelay: dropped ${tally.unreported} event(s) from $relayUrl that failed id or signature " +
            "verification (${tally.total} from this relay so far)"
        tally.unreported = 0
        tally.lastReport = timeSource.markNow()
        line
    }
}
