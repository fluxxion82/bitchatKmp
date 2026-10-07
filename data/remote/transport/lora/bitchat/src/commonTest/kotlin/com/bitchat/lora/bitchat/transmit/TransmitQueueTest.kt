package com.bitchat.lora.bitchat.transmit

import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.airtimeMicros
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Sizes used below, at SF9 and 125 kHz: 1 byte is 103 ms on the air, 22 bytes 206 ms, 92 bytes (a
 * delivery acknowledgement) 513 ms, 131 bytes (the answer to a handshake) 697 ms, 237 bytes (a full
 * frame) 1,168 ms.
 */
class TransmitQueueTest {
    private val config = LoRaConfig()
    private val ack = TransmitKind.DELIVERY_ACK

    private fun queue(config: LoRaConfig = this.config) = TransmitQueue().apply { configure(config) }

    private fun request(
        bytes: Int = 22,
        kind: TransmitKind = TransmitKind.PUBLIC_MESSAGE,
        cause: Cause? = null,
        notBefore: Long = 0,
        expires: Long = 15_000,
    ) = TransmitQueue.Request(ByteArray(bytes), kind, cause, notBefore, expires)

    private fun peer(name: String) = Cause.Validated(name)

    /** Takes the next frame and records it as sent, off the air at [end]. */
    private fun TransmitQueue.send(now: Long, end: Long = now): TransmitQueue.Item =
        assertNotNull(next(now).send, "nothing to send at $now").also { transmitted(it, end) }

    @Test
    fun laterMessagesOfALocalHandshakeOnlyLeaveAHold() {
        val queue = queue()
        val kinds = listOf(
            TransmitKind.LOCAL_HANDSHAKE_OPENING,
            TransmitKind.LOCAL_HANDSHAKE_ANSWER,
            TransmitKind.LOCAL_HANDSHAKE_FINAL,
        )
        kinds.forEach { kind -> assertNull(queue.offer(listOf(request(kind = kind)), 0)) }

        val hold = assertNotNull(queue.hold(config.airtimeMicros(22) * 3, 3, 0, 15_000))
        kinds.forEach { kind -> assertNotNull(queue.offer(listOf(request(kind = kind)), 0, hold)) }
    }

    @Test
    fun everyLimitFollowsTheBandwidthAndThreeFullFramesAMinuteFitAtEach() {
        val wide = queue(LoRaConfig(bandwidth = 500_000L))
        val narrow = queue()
        assertEquals(4_000_000L, narrow.limitMicros(Ledger.LOCAL_ORIGIN))
        assertEquals(1_200_000L, narrow.limitMicros(Ledger.REMOTE_SOLICITED))
        assertEquals(300_000L, narrow.limitMicros(Ledger.BACKGROUND))
        assertEquals(750_000L, narrow.causeLimitMicros(Cause.Unauthenticated))
        assertEquals(600_000L, narrow.causeLimitMicros(peer("a")))
        for (ledger in Ledger.entries) assertEquals(narrow.limitMicros(ledger) / 4, wide.limitMicros(ledger))
        assertEquals(187_500L, wide.causeLimitMicros(Cause.Unauthenticated))
        assertEquals(150_000L, wide.causeLimitMicros(peer("a")))

        for (queue in listOf(narrow, wide)) {
            repeat(3) { assertNotNull(queue.offer(listOf(request(237)), 0)) }
            assertNull(queue.offer(listOf(request(237)), 0))
        }
    }

    @Test
    fun theLimitsAdmitWhatTheyWereSizedForAtEveryBandwidthTheAppConfigures() {
        for (bandwidth in listOf(125_000L, 250_000L, 500_000L)) {
            val config = LoRaConfig(bandwidth = bandwidth)
            fun onAir(bytes: Int) = config.airtimeMicros(bytes)
            val queue = queue(config)
            // The largest heartbeat: 5 bytes of frame, 9 of payload, 24 of nickname.
            assertTrue(onAir(38) <= queue.limitMicros(Ledger.BACKGROUND))
            // A handshake the user started (its opening and the larger of what can follow) and a full message.
            assertTrue(onAir(67) + maxOf(onAir(99), onAir(131)) + onAir(237) <= queue.limitMicros(Ledger.LOCAL_ORIGIN))
            // One answer to a stranger's handshake a minute, one acknowledgement per peer a minute.
            assertTrue(onAir(131) <= queue.causeLimitMicros(Cause.Unauthenticated))
            assertTrue(onAir(92) <= queue.causeLimitMicros(peer("a")))
            // A recovery: its opening before anything is authenticated, its last message for the peer.
            assertTrue(onAir(67) <= queue.causeLimitMicros(Cause.Unauthenticated))
            assertTrue(onAir(99) <= queue.causeLimitMicros(peer("a")))
            assertTrue(onAir(67) + onAir(99) <= queue.limitMicros(Ledger.REMOTE_SOLICITED))
        }
    }

    @Test
    fun aChargeCountsUntilSixtySecondsAfterTheTransmissionEnded() {
        val queue = queue()
        queue.offer(List(3) { request(237) }, 0)
        repeat(3) { queue.send(now = 0, end = 1_000) }
        assertEquals(3 * config.airtimeMicros(237), queue.countedMicros(Ledger.LOCAL_ORIGIN, 60_999))
        assertNull(queue.offer(listOf(request(237)), 60_999))
        assertNotNull(queue.offer(listOf(request(237)), 61_000))
        assertEquals(0L, queue.countedMicros(Ledger.LOCAL_ORIGIN, 61_000))
    }

    @Test
    fun noLedgerLendsTimeToAnother() {
        val localFull = queue()
        assertNotNull(localFull.offer(List(3) { request(237) }, 0))
        assertNull(localFull.offer(listOf(request(237)), 0))
        assertNotNull(localFull.offer(listOf(request(22, TransmitKind.HEARTBEAT)), 0))
        assertNotNull(localFull.offer(listOf(request(22, ack, Cause.Unauthenticated)), 0))

        val remoteFull = queue()
        assertNotNull(remoteFull.offer(listOf(request(92, ack, peer("a"))), 0))
        assertNotNull(remoteFull.offer(listOf(request(92, ack, peer("b"))), 0))
        assertNull(remoteFull.offer(listOf(request(22, ack, peer("c"))), 0))
        assertNotNull(remoteFull.offer(listOf(request(237)), 0))
        assertNotNull(remoteFull.offer(listOf(request(22, TransmitKind.HEARTBEAT)), 0))

        val backgroundFull = queue()
        assertNotNull(backgroundFull.offer(listOf(request(22, TransmitKind.HEARTBEAT)), 0))
        assertNull(backgroundFull.offer(listOf(request(22, TransmitKind.HEARTBEAT)), 0))
        assertNotNull(backgroundFull.offer(listOf(request(237)), 0))
        assertNotNull(backgroundFull.offer(listOf(request(22, ack, peer("a"))), 0))
    }

    @Test
    fun aLedgerHasItsOwnPlacesAndTheyRunOutBeforeItsTime() {
        val queue = queue()
        repeat(8) { assertNotNull(queue.offer(listOf(request(1)), 0)) }
        assertNull(queue.offer(listOf(request(1)), 0))
        // The answers' places are another six, untouched by the eight above.
        repeat(6) { index -> assertNotNull(queue.offer(listOf(request(1, ack, peer("$index"))), 0)) }
        assertNull(queue.offer(listOf(request(1, ack, peer("seventh"))), 0))
        assertEquals(8, queue.queuedPlaces(Ledger.LOCAL_ORIGIN))
        assertEquals(6, queue.queuedPlaces(Ledger.REMOTE_SOLICITED))
    }

    @Test
    fun aListIsAdmittedWholeOrNotAtAll() {
        val queue = queue()
        assertNull(queue.offer(List(4) { request(237) }, 0))
        assertEquals(0, queue.queuedPlaces(Ledger.LOCAL_ORIGIN))
        assertEquals(0L, queue.reservedMicros(Ledger.LOCAL_ORIGIN))
        assertNotNull(queue.offer(List(3) { request(237) }, 0))

        // Refused by a rule about one cause only: nothing of the list is kept either.
        val answers = queue()
        assertNull(answers.offer(List(3) { request(1, ack, peer("a")) }, 0))
        assertEquals(0, answers.queuedPlaces(Ledger.REMOTE_SOLICITED))
        assertEquals(0L, answers.reservedMicros(peer("a")))
    }

    @Test
    fun framesThatAreNoFrameAreRefusedAndACauseBelongsToAnswersOnly() {
        val queue = queue()
        assertNull(queue.offer(listOf(request(0)), 0))
        assertNull(queue.offer(listOf(request(238)), 0))
        assertNotNull(queue.offer(listOf(request(237)), 0))
        assertFailsWith<IllegalArgumentException> { queue.offer(listOf(request(22, ack, cause = null)), 0) }
        assertFailsWith<IllegalArgumentException> {
            queue.offer(listOf(request(22, TransmitKind.PUBLIC_MESSAGE, Cause.Unauthenticated)), 0)
        }
    }

    @Test
    fun whatUnauthenticatedPacketsCauseHasASmallerLimitOfItsOwn() {
        val queue = queue()
        assertNotNull(queue.offer(listOf(request(131, TransmitKind.HANDSHAKE_ANSWER, Cause.Unauthenticated)), 0))
        // 697 + 103 ms is within the ledger's 1,200 and beyond the 750 for unauthenticated causes.
        assertNull(queue.offer(listOf(request(1, TransmitKind.HANDSHAKE_ANSWER, Cause.Unauthenticated)), 0))
        assertNotNull(queue.offer(listOf(request(1, ack, peer("a"))), 0))
        // Still so once it has been sent, for a minute.
        repeat(2) { queue.send(now = 0, end = 697) }
        assertNull(queue.offer(listOf(request(1, TransmitKind.HANDSHAKE_ANSWER, Cause.Unauthenticated)), 60_696))
        assertNotNull(queue.offer(listOf(request(1, TransmitKind.HANDSHAKE_ANSWER, Cause.Unauthenticated)), 60_697))
    }

    @Test
    fun everyValidatedPeerHasItsOwnLimit() {
        val queue = queue()
        assertNotNull(queue.offer(listOf(request(92, ack, peer("a"))), 0))
        // 513 + 103 ms is within the ledger's 1,200 and beyond one peer's 600.
        assertNull(queue.offer(listOf(request(1, ack, peer("a"))), 0))
        assertNotNull(queue.offer(listOf(request(92, ack, peer("b"))), 0))
        assertEquals(config.airtimeMicros(92), queue.reservedMicros(peer("a")))
    }

    @Test
    fun oneCauseTakesAtMostTwoPlacesWaitingOrInFlight() {
        for (cause in listOf(peer("a"), Cause.Unauthenticated)) {
            val queue = queue()
            repeat(2) { assertNotNull(queue.offer(listOf(request(1, ack, cause)), 0)) }
            assertNull(queue.offer(listOf(request(1, ack, cause)), 0))
            // Another cause is not held up by it.
            assertNotNull(queue.offer(listOf(request(1, ack, peer("other"))), 0))
            // With one of the two handed to the radio it still has both places...
            val inFlight = assertNotNull(queue.next(0).send)
            assertEquals(cause, inFlight.cause)
            assertNull(queue.offer(listOf(request(1, ack, cause)), 0))
            // ...and one again once that frame's outcome is known.
            queue.transmitted(inFlight, 0)
            assertNotNull(queue.offer(listOf(request(1, ack, cause)), 0))
        }
    }

    @Test
    fun theLedgersAreServedInTurn() {
        val queue = queue()
        val heartbeat = queue.offer(listOf(request(kind = TransmitKind.HEARTBEAT)), 0)!!.single()
        val answer = queue.offer(listOf(request(kind = ack, cause = peer("a"))), 0)!!.single()
        val (first, second) = queue.offer(List(2) { request() }, 0)!!
        assertSame(first, queue.send(0))
        assertSame(answer, queue.send(0))
        assertSame(heartbeat, queue.send(0))
        assertSame(second, queue.send(0))
        val idle = queue.next(0)
        assertNull(idle.send)
        assertNull(idle.waitUntilMillis)
    }

    @Test
    fun withinALedgerPriorityDecidesAndThenTheOrderOfAdmission() {
        val queue = queue()
        val public = queue.offer(listOf(request(1, TransmitKind.PUBLIC_MESSAGE)), 0)!!.single()
        val openingHold = assertNotNull(queue.hold(config.airtimeMicros(1), 1, 0, 15_000))
        val opening = queue.offer(listOf(request(1, TransmitKind.LOCAL_HANDSHAKE_OPENING)), 0, openingHold)!!.single()
        val firstMessage = queue.offer(listOf(request(1, TransmitKind.PRIVATE_MESSAGE)), 0)!!.single()
        val secondMessage = queue.offer(listOf(request(1, TransmitKind.PRIVATE_MESSAGE)), 0)!!.single()
        val finalHold = assertNotNull(queue.hold(config.airtimeMicros(1), 1, 0, 15_000))
        val final = queue.offer(listOf(request(1, TransmitKind.LOCAL_HANDSHAKE_FINAL)), 0, finalHold)!!.single()
        assertEquals(listOf(final, firstMessage, secondMessage, opening, public), List(5) { queue.send(0) })
    }

    @Test
    fun amongEqualAnswersTheCauseServedLongestAgoGoesFirst() {
        val queue = queue()
        queue.offer(listOf(request(1, ack, peer("a"))), 0)
        queue.send(now = 0, end = 5)
        val secondForA = queue.offer(listOf(request(1, ack, peer("a"))), 10)!!.single()
        val firstForB = queue.offer(listOf(request(1, ack, peer("b"))), 10)!!.single()
        // Admitted later, and still first: b has had nothing yet.
        assertSame(firstForB, queue.send(now = 10, end = 20))
        val secondForB = queue.offer(listOf(request(1, ack, peer("b"))), 20)!!.single()
        assertSame(secondForA, queue.send(now = 20, end = 30))
        assertSame(secondForB, queue.send(now = 30, end = 40))
    }

    @Test
    fun aFrameInItsDelayHoldsNothingUpAndTheWaitEndsWhenItsDelayDoes() {
        val queue = queue()
        val delayed = queue.offer(listOf(request(1, TransmitKind.PRIVATE_MESSAGE, notBefore = 10)), 0)!!.single()
        val ready = queue.offer(listOf(request(1, TransmitKind.PUBLIC_MESSAGE)), 0)!!.single()
        assertSame(ready, queue.send(0))
        val waiting = queue.next(9)
        assertNull(waiting.send)
        assertEquals(10L, waiting.waitUntilMillis)
        assertSame(delayed, queue.send(10))
    }

    @Test
    fun aFrameNotSentByItsExpiryIsDroppedAndGivesBackItsTimeAndPlace() {
        val queue = queue()
        val items = queue.offer(List(3) { request(237, notBefore = 2_000, expires = 1_000) }, 0)!!
        assertNull(queue.offer(listOf(request(237)), 999))
        assertEquals(1_000L, queue.next(999).waitUntilMillis)
        // Past their time and not yet reported: they keep what they hold until `next` reports them, so
        // that no frame is ever dropped without its owner being told.
        assertNull(queue.offer(listOf(request(237)), 1_000))
        val next = queue.next(1_000)
        assertEquals(items, next.expired)
        assertNull(next.send)
        assertEquals(0L, queue.reservedMicros(Ledger.LOCAL_ORIGIN))
        assertEquals(0, queue.queuedPlaces(Ledger.LOCAL_ORIGIN))
        assertNotNull(queue.offer(List(3) { request(237) }, 1_000))
    }

    @Test
    fun theFrameInFlightStillCountsUntilItsOutcomeIsKnown() {
        val queue = queue()
        queue.offer(List(3) { request(237) }, 0)
        val inFlight = assertNotNull(queue.next(0).send)
        assertEquals(2, queue.queuedPlaces(Ledger.LOCAL_ORIGIN))
        assertNull(queue.offer(listOf(request(237)), 0))
        assertFailsWith<IllegalStateException> { queue.next(0) }

        // Never handed to the radio: free at once.
        queue.notTransmitted(inFlight)
        assertNotNull(queue.offer(listOf(request(237)), 0))

        // Handed to the radio: a charge from then on.
        val sent = queue.send(now = 0, end = 1_169)
        assertEquals(sent.airtimeMicros, queue.countedMicros(Ledger.LOCAL_ORIGIN, 1_169))
        assertNull(queue.offer(listOf(request(237)), 1_169))
    }

    @Test
    fun aHoldTakesTimeAndPlacesFromWhatTheUserSendsAndFromNothingElse() {
        val full = config.airtimeMicros(237)
        val queue = queue()
        assertNull(queue.hold(4_000_001, 1, 0, 15_000))
        assertNull(queue.hold(1, 9, 0, 15_000))
        // Nothing can be held for nothing: a hold without a place would be outside every limit.
        assertFailsWith<IllegalArgumentException> { queue.hold(0, 1, 0, 15_000) }
        assertFailsWith<IllegalArgumentException> { queue.hold(1, 0, 0, 15_000) }
        val hold = assertNotNull(queue.hold(2 * full, 2, 0, 15_000))
        assertEquals(2 * full, queue.reservedMicros(Ledger.LOCAL_ORIGIN))
        // 4,000 ms less two full frames has no room for two more, and eight places less two leave six.
        assertNull(queue.offer(List(2) { request(237) }, 0))
        assertNull(queue.offer(List(7) { request(1) }, 0))
        // So large that added to what is already held it would wrap around to a negative number.
        assertNull(queue.hold(Long.MAX_VALUE, 1, 0, 15_000))
        assertNull(queue.hold(1, Int.MAX_VALUE, 0, 15_000))
        // A second hold is measured against the first: two more full frames do not fit, nor a ninth place.
        assertNull(queue.hold(2 * full, 1, 0, 15_000))
        assertNull(queue.hold(1, 7, 0, 15_000))
        assertNotNull(queue.offer(List(6) { request(1) }, 0))
        assertNull(queue.hold(1, 1, 0, 15_000))
        assertNotNull(queue.offer(listOf(request(22, ack, peer("a"))), 0))
        assertNotNull(queue.offer(listOf(request(22, TransmitKind.HEARTBEAT)), 0))
        queue.release(hold)
        assertEquals(6 * config.airtimeMicros(1), queue.reservedMicros(Ledger.LOCAL_ORIGIN))
    }

    @Test
    fun framesOutOfAHoldUseItUpAndNeverMoreThanIt() {
        val full = config.airtimeMicros(237)
        val queue = queue()
        val hold = assertNotNull(queue.hold(2 * full, 3, 0, 15_000))
        assertNull(queue.offer(listOf(request(22, ack, peer("a"))), 0, hold))
        assertNull(queue.offer(List(4) { request(1) }, 0, hold))
        val first = queue.offer(listOf(request(237, TransmitKind.LOCAL_HANDSHAKE_OPENING)), 0, hold)!!.single()
        // The ledger's total has not moved: the frame's time came out of the hold.
        assertEquals(2 * full, queue.reservedMicros(Ledger.LOCAL_ORIGIN))
        // One full frame of time and two places are left of it.
        assertNull(queue.offer(listOf(request(237), request(1)), 0, hold))
        assertNull(queue.offer(List(3) { request(1) }, 0, hold))

        // Released: what is left comes back, the frame queued out of it stays and is charged once.
        queue.release(hold)
        assertEquals(full, queue.reservedMicros(Ledger.LOCAL_ORIGIN))
        assertNull(queue.offer(listOf(request(1)), 0, hold))
        assertSame(first, queue.send(now = 0, end = 1_169))
        assertEquals(full, queue.countedMicros(Ledger.LOCAL_ORIGIN, 1_169))
        assertEquals(0L, queue.reservedMicros(Ledger.LOCAL_ORIGIN))
    }

    @Test
    fun aHoldIsGoneOnceItsLastPlaceIsUsed() {
        val queue = queue()
        repeat(3) {
            val hold = assertNotNull(queue.hold(config.airtimeMicros(22) + 1, 1, 0, 15_000))
            assertEquals(1, queue.holdCount())
            assertNotNull(queue.offer(listOf(request(22)), 0, hold))
            // Its place is the frame's now; the microsecond it had left is free again.
            assertEquals(0, queue.holdCount())
            assertNull(queue.offer(listOf(request(1)), 0, hold))
            queue.notTransmitted(assertNotNull(queue.next(0).send))
            assertEquals(0L, queue.reservedMicros(Ledger.LOCAL_ORIGIN))
        }
    }

    @Test
    fun aHoldThatExpiresGivesBackWhatIsLeftOfIt() {
        val full = config.airtimeMicros(237)
        val queue = queue()
        val hold = assertNotNull(queue.hold(3 * full, 3, 0, expiresAtMillis = 5_000))
        assertNull(queue.offer(listOf(request(237)), 4_999))
        assertEquals(5_000L, queue.next(4_999).waitUntilMillis)
        assertNotNull(queue.offer(List(3) { request(237) }, 5_000))
        assertNull(queue.offer(listOf(request(1)), 5_000, hold))
    }

    @Test
    fun clearingDropsWhatWaitsAndKeepsWhatWasSpent() {
        val queue = queue()
        queue.offer(List(3) { request(237) }, 0)
        queue.send(now = 0, end = 1_169)
        queue.hold(1, 1, 1_169, 15_000)
        val dropped = queue.clear()
        assertEquals(2, dropped.size)
        assertEquals(0L, queue.reservedMicros(Ledger.LOCAL_ORIGIN))
        assertEquals(0, queue.queuedPlaces(Ledger.LOCAL_ORIGIN))

        // A restart starts from what is already spent.
        queue.configure(config)
        assertNotNull(queue.offer(List(2) { request(237) }, 1_169))
        assertNull(queue.offer(listOf(request(237)), 1_169))
    }

    @Test
    fun reconfiguringWhileSomethingWaitsIsRefused() {
        val queue = queue()
        queue.offer(listOf(request()), 0)
        assertFailsWith<IllegalStateException> { queue.configure(config) }
    }

    /**
     * Whatever is offered, held, released, left to expire, refused by the radio or sent late: in no
     * 60 seconds is more time spent on the air than each limit allows. The time on the air is what
     * the radio does (the frame's airtime from the moment it is handed over), not what was charged.
     */
    @Test
    fun noMinuteOnTheAirExceedsAnyLimitUnderARandomWorkload() {
        class Sent(val startMillis: Long, val item: TransmitQueue.Item) {
            fun microsWithin(fromMillis: Long, toMillis: Long): Long {
                val start = startMillis * 1_000
                return (minOf(toMillis * 1_000, start + item.airtimeMicros) - maxOf(fromMillis * 1_000, start))
                    .coerceAtLeast(0)
            }
        }
        val peers = List(4) { peer("peer-$it") }
        val localKinds = TransmitKind.entries.filter { it.ledger == Ledger.LOCAL_ORIGIN }

        for (bandwidth in listOf(125_000L, 250_000L, 500_000L)) for (seed in 1..6) {
            val config = LoRaConfig(bandwidth = bandwidth)
            val queue = queue(config)
            val random = Random(seed)
            val sent = mutableListOf<Sent>()
            val holds = mutableListOf<TransmitQueue.Hold>()
            var now = 0L

            while (now < 10 * 60_000) {
                now += random.nextLong(0, 300)
                when (random.nextInt(12)) {
                    0 -> queue.hold(random.nextLong(1, 1_500_000), random.nextInt(1, 4), now, now + random.nextLong(1, 20_000))
                        ?.let { holds += it }
                    1 -> holds.randomOrNull(random)?.let { queue.release(it); holds -= it }
                    2, 3 -> holds.randomOrNull(random)?.let { hold ->
                        queue.offer(listOf(request(random.nextInt(1, 238), localKinds.random(random), null, now, now + 15_000)), now, hold)
                    }
                    else -> {
                        val kind = TransmitKind.entries.random(random)
                        val cause = when {
                            kind.ledger != Ledger.REMOTE_SOLICITED -> null
                            random.nextBoolean() -> Cause.Unauthenticated
                            else -> peers.random(random)
                        }
                        // Heartbeats are small and single, or none would ever fit their ledger.
                        val small = kind == TransmitKind.HEARTBEAT
                        queue.offer(
                            List(if (small) 1 else random.nextInt(1, 4)) {
                                request(
                                    random.nextInt(1, if (small) 39 else 238), kind, cause,
                                    notBefore = now + random.nextLong(0, 1_500), expires = now + random.nextLong(1, 15_000)
                                )
                            },
                            now
                        )
                    }
                }
                if (random.nextInt(3) == 0) continue
                val item = queue.next(now).send ?: continue
                if (random.nextInt(20) == 0) {
                    queue.notTransmitted(item)
                    continue
                }
                // Now and then the driver answers later than the frame took.
                val end = now + (item.airtimeMicros + 999) / 1_000 + if (random.nextInt(4) == 0) random.nextLong(0, 40) else 0
                queue.transmitted(item, end)
                sent += Sent(now, item)
                now = end
            }

            assertTrue(sent.size > 50, "the workload sent only ${sent.size} frames")
            for (ledger in Ledger.entries) assertTrue(sent.any { it.item.kind.ledger == ledger })
            val windowEnds = sent.flatMap { record ->
                val end = record.startMillis + (record.item.airtimeMicros + 999) / 1_000
                listOf(end, record.startMillis + 60_000, end + 59_999)
            }
            for (windowEnd in windowEnds) {
                fun onAir(counts: (TransmitQueue.Item) -> Boolean) =
                    sent.filter { counts(it.item) }.sumOf { it.microsWithin(windowEnd - 60_000, windowEnd) }
                for (ledger in Ledger.entries) {
                    assertTrue(onAir { it.kind.ledger == ledger } <= queue.limitMicros(ledger), "$ledger at $windowEnd")
                }
                for (cause in peers + Cause.Unauthenticated) {
                    assertTrue(onAir { it.cause == cause } <= queue.causeLimitMicros(cause), "$cause at $windowEnd")
                }
                assertTrue(onAir { true } <= Ledger.entries.sumOf { queue.limitMicros(it) })
            }
        }
    }
}
