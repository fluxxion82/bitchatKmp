package com.bitchat.lora.bitchat.transmit

import com.bitchat.lora.bitchat.BitChatRadio
import com.bitchat.lora.bitchat.transmit.LoRaTransmitter.Outcome
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.LoRaEvent
import com.bitchat.lora.radio.airtimeMicros
import com.bitchat.lora.radio.airtimeMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoRaTransmitterTest {
    private val config = LoRaConfig()
    private val full = ByteArray(237)

    /** Answers at once, as a radio does that only takes the frame over: the worker has to wait out the airtime. */
    private class Radio(private val clock: () -> Long) : BitChatRadio {
        override val events = MutableSharedFlow<LoRaEvent>()
        override var isReady = true
        val calls = mutableListOf<Pair<Long, ByteArray>>()
        var result = true
        var failure: Exception? = null
        override fun configure(config: LoRaConfig) = true
        override fun startReceiving() = Unit
        override fun send(data: ByteArray): Boolean {
            calls += clock() to data
            failure?.let { throw it }
            return result
        }
        override suspend fun shutdown() = Unit
    }

    // The worker runs in the test's background scope, which only moves while the test waits for
    // something: awaiting a ticket is what lets the clock run on.
    private fun TestScope.radio() = Radio { testScheduler.currentTime }

    private fun TestScope.transmitter(radio: Radio, jitter: (LongRange) -> Long = { it.first }) =
        LoRaTransmitter(radio, { testScheduler.currentTime }, jitter)

    @Test
    fun framesAskedForAtTheSameMomentGoOutOneAfterAnotherEachAfterTheOneBeforeHasLeftTheAir() = runTest {
        val radio = radio()
        val transmitter = transmitter(radio)
        transmitter.start(config, backgroundScope)
        val outcomes = listOf(
            async { transmitter.offer(listOf(ByteArray(22)), TransmitKind.PUBLIC_MESSAGE)!!.single().await() },
            async { transmitter.offer(listOf(full), TransmitKind.PUBLIC_MESSAGE)!!.single().await() },
            async { transmitter.offer(listOf(ByteArray(22)), TransmitKind.HEARTBEAT)!!.single().await() },
        )
        assertEquals(List(3) { Outcome.SENT }, outcomes.map { it.await() })
        assertEquals(3, radio.calls.size)
        radio.calls.zipWithNext().forEach { (earlier, later) ->
            assertTrue(later.first >= earlier.first + config.airtimeMs(earlier.second.size))
        }
        transmitter.stop()
    }

    @Test
    fun aTicketIsAnsweredOnlyOnceItsFrameHasLeftTheAir() = runTest {
        val radio = radio()
        val transmitter = transmitter(radio)
        transmitter.start(config, backgroundScope)
        val sending = async { transmitter.offer(listOf(full), TransmitKind.PUBLIC_MESSAGE)!!.single().await() }
        // Its airtime and the one millisecond the clock may be behind by.
        advanceTimeBy(config.airtimeMs(237) + 1)
        assertEquals(1, radio.calls.size)
        assertTrue(sending.isActive)
        runCurrent()
        assertEquals(Outcome.SENT, sending.await())
        transmitter.stop()
    }

    @Test
    fun anAttemptTheRadioReportsAsFailedIsChargedAllTheSame() = runTest {
        val radio = radio().apply { result = false }
        val transmitter = transmitter(radio)
        transmitter.start(config, backgroundScope)
        val tickets = transmitter.offer(List(3) { full }, TransmitKind.PUBLIC_MESSAGE)!!
        assertEquals(List(3) { Outcome.FAILED }, tickets.map { it.await() })
        assertEquals(3, radio.calls.size)
        assertNull(transmitter.offer(listOf(full), TransmitKind.PUBLIC_MESSAGE))
        transmitter.stop()
    }

    @Test
    fun aRadioThatThrowsCostsThatFrameItsChargeAndNotTheWorker() = runTest {
        val radio = radio().apply { failure = IllegalStateException("SPI") }
        val transmitter = transmitter(radio)
        transmitter.start(config, backgroundScope)
        val thrown = transmitter.offer(List(2) { full }, TransmitKind.PUBLIC_MESSAGE)!!
        assertEquals(List(2) { Outcome.FAILED }, thrown.map { it.await() })
        radio.failure = null
        val after = assertNotNull(transmitter.offer(listOf(full), TransmitKind.PUBLIC_MESSAGE)).single()
        assertEquals(Outcome.SENT, after.await())
        assertNull(transmitter.offer(listOf(full), TransmitKind.PUBLIC_MESSAGE))
        transmitter.stop()
    }

    @Test
    fun aRadioThatIsNotReadyFailsTheFrameWithoutCharge() = runTest {
        val radio = radio().apply { isReady = false }
        val transmitter = transmitter(radio)
        transmitter.start(config, backgroundScope)
        val tickets = transmitter.offer(List(3) { full }, TransmitKind.PUBLIC_MESSAGE)!!
        assertEquals(List(3) { Outcome.FAILED }, tickets.map { it.await() })
        assertTrue(radio.calls.isEmpty())

        radio.isReady = true
        val again = assertNotNull(transmitter.offer(List(3) { full }, TransmitKind.PUBLIC_MESSAGE))
        assertEquals(List(3) { Outcome.SENT }, again.map { it.await() })
        transmitter.stop()
    }

    @Test
    fun aFrameStillWaitingWhenItsLifetimeEndsIsNeverSentAndGivesItsTimeBack() = runTest {
        val radio = radio()
        // The longest delay a private message can draw, and a lifetime shorter than it.
        val transmitter = transmitter(radio) { it.last }
        transmitter.start(config, backgroundScope)
        val tickets = transmitter.offer(List(3) { full }, TransmitKind.PRIVATE_MESSAGE, lifetimeMillis = 1_000)!!
        assertEquals(List(3) { Outcome.EXPIRED }, tickets.map { it.await() })
        assertTrue(radio.calls.isEmpty())
        assertEquals(1_000, testScheduler.currentTime)
        assertNotNull(transmitter.offer(List(3) { full }, TransmitKind.PUBLIC_MESSAGE))
        transmitter.stop()
    }

    @Test
    fun aFrameIsNotSentBeforeItsDelayHasPassed() = runTest {
        val radio = radio()
        val transmitter = transmitter(radio) { it.last }
        transmitter.start(config, backgroundScope)
        transmitter.offer(listOf(ByteArray(22)), TransmitKind.PRIVATE_MESSAGE)
        advanceTimeBy(TransmitKind.PRIVATE_MESSAGE.jitterMs.last)
        assertTrue(radio.calls.isEmpty())
        runCurrent()
        assertEquals(TransmitKind.PRIVATE_MESSAGE.jitterMs.last, radio.calls.single().first)
        transmitter.stop()
    }

    @Test
    fun stoppingSendsNothingMoreCancelsWhatWaitedAndKeepsWhatWasSpent() = runTest {
        val radio = radio()
        val transmitter = transmitter(radio)
        transmitter.start(config, backgroundScope)
        val tickets = transmitter.offer(List(3) { full }, TransmitKind.PUBLIC_MESSAGE)!!
        runCurrent()
        assertEquals(1, radio.calls.size)

        // The first frame is on the air: the stop waits for it and takes the other two back.
        transmitter.stop()
        assertEquals(listOf(Outcome.SENT, Outcome.CANCELLED, Outcome.CANCELLED), tickets.map { it.await() })
        assertEquals(1, radio.calls.size)
        assertNull(transmitter.offer(listOf(ByteArray(1)), TransmitKind.PUBLIC_MESSAGE))

        // Started again: one full frame is spent, so two more fit in this minute and three do not.
        transmitter.start(config, backgroundScope)
        assertNull(transmitter.offer(List(3) { full }, TransmitKind.PUBLIC_MESSAGE))
        assertNotNull(transmitter.offer(List(2) { full }, TransmitKind.PUBLIC_MESSAGE))
        transmitter.stop()
    }

    @Test
    fun aStopIsNoticedBetweenTwoFramesEvenWithAWakeUpAlreadyWaiting() = runTest {
        val radio = radio()
        val transmitter = transmitter(radio)
        transmitter.start(config, backgroundScope)
        val first = transmitter.offer(listOf(full), TransmitKind.PUBLIC_MESSAGE)!!.single()
        runCurrent()
        assertEquals(1, radio.calls.size)
        // Offered while the first frame is on the air: its wake-up is there before the worker waits.
        val second = transmitter.offer(listOf(full), TransmitKind.PUBLIC_MESSAGE)!!.single()

        transmitter.stop()
        assertEquals(listOf(Outcome.SENT, Outcome.CANCELLED), listOf(first.await(), second.await()))
        assertEquals(1, radio.calls.size)
    }

    @Test
    fun aWorkerCancelledWhileItsFrameIsOnTheAirStillRecordsThatFrame() = runTest {
        val radio = radio()
        val transmitter = transmitter(radio)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        transmitter.start(config, scope)
        val tickets = transmitter.offer(List(3) { full }, TransmitKind.PUBLIC_MESSAGE)!!
        runCurrent()
        assertEquals(1, radio.calls.size)

        scope.cancel()
        advanceUntilIdle()
        assertEquals(listOf(Outcome.SENT, Outcome.CANCELLED, Outcome.CANCELLED), tickets.map { it.await() })
        assertEquals(1, radio.calls.size)

        // Nobody called stop: the worker cleaned up as it ended, and a new one can be started.
        transmitter.start(config, backgroundScope)
        assertNull(transmitter.offer(List(3) { full }, TransmitKind.PUBLIC_MESSAGE))
        assertNotNull(transmitter.offer(List(2) { full }, TransmitKind.PUBLIC_MESSAGE))
        transmitter.stop()
    }

    @Test
    fun aWorkerWhoseScopeIsCancelledAnswersWhatWaitedAndTakesNothingMore() = runTest {
        val radio = radio()
        val transmitter = transmitter(radio) { it.last }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        transmitter.start(config, scope)
        val waiting = transmitter.offer(listOf(ByteArray(22)), TransmitKind.PRIVATE_MESSAGE)!!.single()
        runCurrent()
        scope.cancel()
        assertEquals(Outcome.CANCELLED, withTimeout(5_000) { waiting.await() })
        assertNull(transmitter.offer(listOf(ByteArray(22)), TransmitKind.PUBLIC_MESSAGE))
        assertTrue(radio.calls.isEmpty())

        // Started in a scope that is already cancelled: the same, as soon as it has run.
        transmitter.start(config, scope)
        runCurrent()
        assertNull(transmitter.offer(listOf(ByteArray(22)), TransmitKind.PUBLIC_MESSAGE))

        // And it can be started again in a live one.
        transmitter.start(config, backgroundScope)
        val sent = assertNotNull(transmitter.offer(listOf(ByteArray(22)), TransmitKind.PUBLIC_MESSAGE)).single()
        assertEquals(Outcome.SENT, sent.await())
        transmitter.stop()
    }

    @Test
    fun aFrameWhoseTimeRunsOutWhileSomethingElseIsOfferedIsStillAnswered() = runTest {
        val radio = radio()
        val transmitter = transmitter(radio) { it.last }
        transmitter.start(config, backgroundScope)
        val expiring = transmitter.offer(listOf(ByteArray(22)), TransmitKind.PRIVATE_MESSAGE, lifetimeMillis = 1)!!.single()
        runCurrent()
        // The clock is at the frame's last moment and the worker has not been woken for it yet: an
        // offer made right now must not make the frame vanish without its ticket being answered.
        advanceTimeBy(1)
        transmitter.offer(listOf(ByteArray(22)), TransmitKind.PUBLIC_MESSAGE)
        assertEquals(Outcome.EXPIRED, withTimeout(5_000) { expiring.await() })
        transmitter.stop()
    }

    @Test
    fun aFrameWhoseTimeRunsOutBeforeItIsHandedOverIsNotSent() = runTest {
        // Every look at the clock finds it 600 ms later: the frame is taken from the queue in time
        // (at 600 of its 1,000 ms) and would be handed to the radio too late (at 1,200).
        var now = 0L
        val radio = Radio { now }
        val transmitter = LoRaTransmitter(radio, { now.also { now += 600 } }) { it.first }
        transmitter.start(config, backgroundScope)
        val ticket = transmitter.offer(listOf(full), TransmitKind.PUBLIC_MESSAGE, lifetimeMillis = 1_000)!!.single()
        assertEquals(Outcome.EXPIRED, withTimeout(5_000) { ticket.await() })
        assertTrue(radio.calls.isEmpty())
        transmitter.stop()
    }

    @Test
    fun aLifetimeIsBetweenAMillisecondAndAMinute() = runTest {
        val transmitter = transmitter(radio())
        transmitter.start(config, backgroundScope)
        for (lifetime in listOf(0L, -1L, 60_001L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                transmitter.offer(listOf(full), TransmitKind.PUBLIC_MESSAGE, lifetimeMillis = lifetime)
            }
            assertFailsWith<IllegalArgumentException> { transmitter.hold(1, 1, lifetimeMillis = lifetime) }
        }
        assertNotNull(transmitter.offer(listOf(full), TransmitKind.PUBLIC_MESSAGE, lifetimeMillis = 60_000))
        transmitter.stop()
    }

    @Test
    fun aHoldKeepsTimeForFramesOfferedOutOfItLater() = runTest {
        val radio = radio()
        val transmitter = transmitter(radio)
        transmitter.start(config, backgroundScope)
        val hold = assertNotNull(transmitter.hold(2 * config.airtimeMicros(237), places = 2))
        // With two full frames held back, two more do not fit.
        assertNull(transmitter.offer(List(2) { full }, TransmitKind.PUBLIC_MESSAGE))
        val held = assertNotNull(transmitter.offer(listOf(full), TransmitKind.LOCAL_HANDSHAKE_OPENING, hold = hold)).single()
        assertEquals(Outcome.SENT, held.await())
        transmitter.release(hold)
        assertNotNull(transmitter.offer(List(2) { full }, TransmitKind.PUBLIC_MESSAGE))
        transmitter.stop()
    }
}
