package com.bitchat.client.websocket

import com.bitchat.client.websocket.DroppedFrameLog.Reason.BACKLOG_FULL
import com.bitchat.client.websocket.DroppedFrameLog.Reason.FRAME_TOO_LARGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** A relay can have frames dropped as fast as its socket carries them; the log must not keep up. */
class DroppedFrameLogTest {
    private val time = TestTimeSource()
    private val log = DroppedFrameLog(interval = 1.minutes, timeSource = time)

    @Test
    fun `the first dropped frame of a relay is reported at once`() {
        assertEquals(
            "WebSocket: dropped 1 frame(s) from $RELAY (1 over its backlog limit, 0 over the frame size limit; 1 from this relay so far)",
            log.dropped(RELAY, BACKLOG_FULL),
        )
    }

    @Test
    fun `a flood is one line a minute that says how many and why`() {
        assertNotNull(log.dropped(RELAY, FRAME_TOO_LARGE))

        repeat(4_000) { assertNull(log.dropped(RELAY, BACKLOG_FULL)) }
        repeat(2) { assertNull(log.dropped(RELAY, FRAME_TOO_LARGE)) }
        time += 59.seconds
        assertNull(log.dropped(RELAY, BACKLOG_FULL))
        time += 1.seconds

        assertEquals(
            "WebSocket: dropped 4004 frame(s) from $RELAY (4002 over its backlog limit, 2 over the frame size limit; 4005 from this relay so far)",
            log.dropped(RELAY, BACKLOG_FULL),
        )
        assertNull(log.dropped(RELAY, BACKLOG_FULL))
    }

    @Test
    fun `one relay's flood does not silence another relay's first report`() {
        assertNotNull(log.dropped(RELAY, BACKLOG_FULL))
        repeat(100) { log.dropped(RELAY, BACKLOG_FULL) }

        val line = assertNotNull(log.dropped(OTHER_RELAY, BACKLOG_FULL))

        assertEquals(true, OTHER_RELAY in line && "1 from this relay so far" in line, line)
        assertNull(log.dropped(OTHER_RELAY, BACKLOG_FULL))
    }

    private companion object {
        const val RELAY = "wss://relay.example"
        const val OTHER_RELAY = "wss://other.example"
    }
}
