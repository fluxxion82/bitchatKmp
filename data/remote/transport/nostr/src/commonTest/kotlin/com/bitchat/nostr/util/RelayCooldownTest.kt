package com.bitchat.nostr.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** How often something is done for a relay, and what happens to a request that comes too soon. */
class RelayCooldownTest {
    private val time = TestTimeSource()
    private val cooldown = RelayCooldown(interval = 1.minutes, timeSource = time)

    @Test
    fun `the first request for a relay need not wait`() {
        assertEquals(Duration.ZERO, cooldown.request(RELAY))
    }

    @Test
    fun `while one request waits another is not needed`() {
        assertEquals(Duration.ZERO, cooldown.request(RELAY))

        assertNull(cooldown.request(RELAY))
        time += 5.minutes
        assertNull(cooldown.request(RELAY), "it waits until it is started, however long that takes")
    }

    @Test
    fun `a request that comes too soon is told how long to wait`() {
        cooldown.request(RELAY)
        cooldown.started(RELAY)

        time += 20.seconds
        assertEquals(40.seconds, cooldown.request(RELAY))
    }

    @Test
    fun `the interval runs from when the last one started`() {
        cooldown.request(RELAY)
        time += 30.seconds
        cooldown.started(RELAY)

        time += 59.seconds
        assertEquals(1.seconds, cooldown.request(RELAY))
        cooldown.started(RELAY)
        time += 60.seconds
        assertEquals(Duration.ZERO, cooldown.request(RELAY), "a whole interval later there is nothing to wait for")
    }

    @Test
    fun `relays do not wait for each other`() {
        cooldown.request(RELAY)
        cooldown.started(RELAY)

        assertEquals(Duration.ZERO, cooldown.request(OTHER_RELAY))
        assertNull(cooldown.request(OTHER_RELAY))
        assertEquals(1.minutes, cooldown.request(RELAY))
    }

    private companion object {
        const val RELAY = "wss://relay.example"
        const val OTHER_RELAY = "wss://other.example"
    }
}
