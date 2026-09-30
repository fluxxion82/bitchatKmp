package com.bitchat.repo.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepeatedLogLimiterTest {
    private var now = 0L
    private val limiter = RepeatedLogLimiter(intervalMillis = 600_000) { now }

    @Test fun aLinePolledEveryFiveSecondsIsLoggedOncePerInterval() {
        assertEquals("no location fix: unavailable", limiter.next("no location fix: unavailable"))
        var logged = 0
        repeat(119) {
            now += 5_000
            if (limiter.next("no location fix: unavailable") != null) logged++
        }
        assertEquals(0, logged, "ten minutes of polls at 5 s: nothing more")
        now += 5_000
        assertEquals("no location fix: unavailable (119 more since)", limiter.next("no location fix: unavailable"))
    }

    @Test fun aChangedLineIsLoggedAtOnce() {
        limiter.next("no location fix: unavailable")
        now += 5_000
        assertNull(limiter.next("no location fix: unavailable"))
        assertEquals("no location fix: timed out", limiter.next("no location fix: timed out"))
        assertEquals("no location fix: unavailable", limiter.next("no location fix: unavailable"))
    }
}
