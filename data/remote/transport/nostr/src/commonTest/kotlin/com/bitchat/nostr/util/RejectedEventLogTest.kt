package com.bitchat.nostr.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** A relay can send events that do not verify as fast as its socket carries; the log must not keep up. */
class RejectedEventLogTest {
    private val time = TestTimeSource()
    private val log = RejectedEventLog(interval = 1.minutes, timeSource = time)

    @Test
    fun `the first rejected event from a relay is reported at once`() {
        val line = assertNotNull(log.rejected(RELAY))

        assertTrue(RELAY in line, line)
        assertTrue("dropped 1 event(s)" in line, line)
    }

    @Test
    fun `a flood from one relay is one line a minute with the count`() {
        assertNotNull(log.rejected(RELAY))

        repeat(4_999) { assertNull(log.rejected(RELAY)) }
        time += 59.seconds
        assertNull(log.rejected(RELAY))
        time += 1.seconds
        val line = assertNotNull(log.rejected(RELAY))

        assertTrue("dropped 5001 event(s)" in line, line)
        assertTrue("5002 from this relay so far" in line, line)
        assertNull(log.rejected(RELAY))
    }

    @Test
    fun `one relay's flood does not silence another relay's first report`() {
        assertNotNull(log.rejected(RELAY))
        repeat(100) { log.rejected(RELAY) }

        val line = assertNotNull(log.rejected(OTHER_RELAY))

        assertTrue(OTHER_RELAY in line, line)
        assertEquals(null, log.rejected(OTHER_RELAY))
    }

    private companion object {
        const val RELAY = "wss://relay.example"
        const val OTHER_RELAY = "wss://other.example"
    }
}
