package com.bitchat.repo.utils

import kotlin.time.TimeSource

/**
 * Keeps a log line that repeats on a timer (a five-second poll) from filling the log: a line is let
 * through when it differs from the previous one, and a repeat only once [intervalMillis] have
 * passed since it was last let through, saying how many repeats were held back meanwhile.
 */
internal class RepeatedLogLimiter(
    private val intervalMillis: Long,
    private val now: () -> Long = monotonicMillis(),
) {
    private var last: String? = null
    private var lastAt = 0L
    private var held = 0

    /** The line to log for [message] now, or null to log nothing. */
    fun next(message: String): String? {
        val at = now()
        if (message == last && at - lastAt < intervalMillis) {
            held++
            return null
        }
        val line = if (message == last && held > 0) "$message ($held more since)" else message
        last = message
        lastAt = at
        held = 0
        return line
    }
}

private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
