package com.bitchat.client.websocket

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Says that a relay's frames were dropped, at a bounded rate: a relay that sends more than it is
 * allowed can do so as fast as its socket carries. The first drop from a relay is reported at
 * once; after that there is at most one line per relay every [interval], with the count since the
 * last. Nothing of a frame is in the line.
 *
 * There is one entry per relay URL, and those come from this app's own relay list.
 */
@OptIn(InternalCoroutinesApi::class)
internal class DroppedFrameLog(
    private val interval: Duration = 1.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    enum class Reason { BACKLOG_FULL, FRAME_TOO_LARGE }

    private class Tally(var backlogFull: Long = 0, var tooLarge: Long = 0, var total: Long = 0, var lastReport: TimeMark? = null)

    private val lock = SynchronizedObject()
    private val tallies = mutableMapOf<String, Tally>()

    /** Counts one dropped frame from [url] and returns the line to log, if one is due. */
    fun dropped(url: String, reason: Reason): String? = synchronized(lock) {
        val tally = tallies.getOrPut(url) { Tally() }
        tally.total++
        when (reason) {
            Reason.BACKLOG_FULL -> tally.backlogFull++
            Reason.FRAME_TOO_LARGE -> tally.tooLarge++
        }
        if (tally.lastReport?.let { it.elapsedNow() < interval } == true) return@synchronized null

        val line = "WebSocket: dropped ${tally.backlogFull + tally.tooLarge} frame(s) from $url " +
            "(${tally.backlogFull} over its backlog limit, ${tally.tooLarge} over the frame size limit; " +
            "${tally.total} from this relay so far)"
        tally.backlogFull = 0
        tally.tooLarge = 0
        tally.lastReport = timeSource.markNow()
        line
    }
}
