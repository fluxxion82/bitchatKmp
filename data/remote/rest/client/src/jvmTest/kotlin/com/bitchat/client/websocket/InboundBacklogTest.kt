package com.bitchat.client.websocket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The account of what one relay has in flight; the client keeps one per relay. */
class InboundBacklogTest {
    @Test
    fun `room is taken per frame and per byte until either limit is reached`() {
        val backlog = InboundBacklog(InboundLimits(maxFramesInFlight = 3, maxBytesInFlight = 100, maxFrameBytes = 60))

        assertTrue(backlog.take(40))
        assertTrue(backlog.take(60))
        assertFalse(backlog.take(1), "the bytes are used up")
        assertTrue(backlog.take(0), "a frame with nothing in it still fits")
        assertFalse(backlog.take(0), "the frames are used up")

        assertEquals(InboundState(framesInFlight = 3, bytesInFlight = 100, framesDropped = 2, callbacksWaiting = 0), backlog.state(0))
    }

    @Test
    fun `room given back can be taken again`() {
        val backlog = InboundBacklog(InboundLimits(maxFramesInFlight = 2, maxBytesInFlight = 100, maxFrameBytes = 100))
        backlog.take(70)
        backlog.take(30)
        assertFalse(backlog.take(10))

        backlog.giveBack(30)

        assertFalse(backlog.take(31), "one byte more than came back")
        assertTrue(backlog.take(30))
        assertEquals(InboundState(framesInFlight = 2, bytesInFlight = 100, framesDropped = 2, callbacksWaiting = 5), backlog.state(5))
    }

    @Test
    fun `a frame over the size limit is refused whatever room there is and takes none`() {
        val backlog = InboundBacklog(InboundLimits(maxFramesInFlight = 10, maxBytesInFlight = 1_000, maxFrameBytes = 100))

        assertTrue(backlog.refusesSize(101))
        assertFalse(backlog.refusesSize(100), "exactly the limit is allowed")

        assertEquals(InboundState(framesInFlight = 0, bytesInFlight = 0, framesDropped = 1, callbacksWaiting = 0), backlog.state(0))
    }

    @Test
    fun `the limits a relay gets by default`() {
        val limits = InboundLimits()

        assertEquals(4_096, limits.maxFramesInFlight)
        assertEquals(4L * 1024 * 1024, limits.maxBytesInFlight)
        assertEquals(512 * 1024, limits.maxFrameBytes)
    }
}
