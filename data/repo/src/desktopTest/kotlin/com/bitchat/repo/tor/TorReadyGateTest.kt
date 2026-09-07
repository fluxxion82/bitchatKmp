package com.bitchat.repo.tor

import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.tor.TorManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The gate used to block the send path for the full 30 s timeout on every host where Tor is
 * permanently broken: it collected a StateFlow that never completes, and `return@collect` only
 * left the lambda instead of ending the collection.
 *
 * These tests measure the scheduler's *virtual* clock, which is what `withTimeoutOrNull` runs on
 * under `runTest`. A wall-clock assertion would pass against the old code, because runTest skips
 * the 30 s delay instantly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TorReadyGateTest {

    private fun managerWith(
        status: TorStatus,
        available: Boolean = true,
        proxyReady: Boolean = false,
    ): TorManager = mockk<TorManager>().also { manager ->
        every { manager.statusFlow } returns MutableStateFlow(status)
        every { manager.isAvailable } returns available
        every { manager.isProxyReady() } returns proxyReady
    }

    @Test
    fun `returns without waiting when the native tor library is missing`() = runTest {
        val manager = managerWith(
            status = TorStatus(
                state = TorState.ERROR,
                errorMessage = "Tor unavailable: native library libarti_desktop.so not found"
            ),
            available = false,
        )

        val start = testScheduler.currentTime
        val ready = awaitTorReady(manager, log = {})

        assertFalse(ready)
        assertNoWait(start)
    }

    @Test
    fun `returns without waiting when tor is already in error state`() = runTest {
        val manager = managerWith(
            status = TorStatus(state = TorState.ERROR, errorMessage = "Start failed: 1"),
            available = true,
        )

        val start = testScheduler.currentTime
        val ready = awaitTorReady(manager, log = {})

        assertFalse(ready)
        assertNoWait(start)
    }

    @Test
    fun `returns without waiting when tor is off`() = runTest {
        val manager = managerWith(status = TorStatus(state = TorState.OFF))

        val start = testScheduler.currentTime

        assertFalse(awaitTorReady(manager, log = {}))
        assertNoWait(start)
    }

    @Test
    fun `returns without waiting when there is no tor manager at all`() = runTest {
        val start = testScheduler.currentTime

        assertFalse(awaitTorReady(null, log = {}))
        assertNoWait(start)
    }

    @Test
    fun `reports ready when the proxy is already up`() = runTest {
        val manager = managerWith(
            status = TorStatus(state = TorState.RUNNING, running = true, bootstrapPercent = 100),
            proxyReady = true,
        )

        val start = testScheduler.currentTime

        assertTrue(awaitTorReady(manager, log = {}))
        assertNoWait(start)
    }

    @Test
    fun `returns without waiting when this build cannot route through a socks proxy`() = runTest {
        // Exactly the Pi's state on 2026-09-06: Arti bootstrapped, SOCKS listening on 9050, and a
        // Curl engine that was never told about it.
        val manager = managerWith(
            status = TorStatus(state = TorState.RUNNING, running = true, bootstrapPercent = 100),
            proxyReady = true,
        )

        val start = testScheduler.currentTime
        val ready = awaitTorReady(manager, engineSupportsTorProxy = false, log = {})

        assertFalse(ready, "the send path must not be told the traffic is protected")
        assertNoWait(start)
    }

    @Test
    fun `stops waiting the moment a bootstrapping tor fails`() = runTest {
        val statuses = MutableStateFlow(
            TorStatus(state = TorState.BOOTSTRAPPING, bootstrapPercent = 75)
        )
        val manager = mockk<TorManager>().also {
            every { it.statusFlow } returns statuses
            every { it.isAvailable } returns true
            every { it.isProxyReady() } returns false
        }

        val waiting = async { awaitTorReady(manager, timeout = 30.seconds, log = {}) }
        runCurrent()
        assertTrue(waiting.isActive, "a bootstrapping Tor is worth waiting for")

        advanceTimeBy(200.milliseconds)
        statuses.value = TorStatus(state = TorState.ERROR, errorMessage = "guard unusable")
        runCurrent()

        assertFalse(waiting.await())
        // 200 ms of genuine bootstrapping, not the 30 s timeout.
        assertTrue(
            testScheduler.currentTime < 1_000,
            "should have given up on the error, waited ${testScheduler.currentTime} ms"
        )
    }

    @Test
    fun `wakes up as soon as the proxy becomes usable`() = runTest {
        val statuses = MutableStateFlow(TorStatus(state = TorState.STARTING))
        var proxyReady = false
        val manager = mockk<TorManager>().also {
            every { it.statusFlow } returns statuses
            every { it.isAvailable } returns true
            every { it.isProxyReady() } answers { proxyReady }
        }

        val waiting = async { awaitTorReady(manager, timeout = 30.seconds, log = {}) }
        runCurrent()
        assertTrue(waiting.isActive)
        val readyAt = testScheduler.currentTime

        proxyReady = true
        statuses.value = TorStatus(state = TorState.RUNNING, running = true, bootstrapPercent = 100)
        runCurrent()

        // Completed *by* runCurrent, not merely eventually: awaiting a coroutine that is still
        // parked would advance the virtual clock to the 30 s timeout and still pass.
        assertTrue(waiting.isCompleted, "the send must resume on the state change, not on a timeout")
        assertEquals(readyAt, testScheduler.currentTime, "no virtual time may pass waiting for it")
        assertTrue(waiting.await())
    }

    @Test
    fun `stops waiting the moment tor is switched off`() = runTest {
        // Someone flips the settings switch off while a message is queued behind the gate. That is
        // a terminal answer; without OFF in the predicate the send sat here for the full 30 s.
        val statuses = MutableStateFlow(TorStatus(state = TorState.STARTING))
        val manager = mockk<TorManager>().also {
            every { it.statusFlow } returns statuses
            every { it.isAvailable } returns true
            every { it.isProxyReady() } returns false
        }

        val waiting = async { awaitTorReady(manager, timeout = 30.seconds, log = {}) }
        runCurrent()
        assertTrue(waiting.isActive, "a starting Tor is worth waiting for")

        advanceTimeBy(200.milliseconds)
        statuses.value = TorStatus(state = TorState.OFF)
        runCurrent()

        assertTrue(waiting.isCompleted, "OFF is terminal: nothing more is coming")
        assertFalse(waiting.await())
        assertTrue(
            testScheduler.currentTime < 1_000,
            "should have given up when Tor went off, waited ${testScheduler.currentTime} ms"
        )
    }

    private fun TestScope.assertNoWait(startMillis: Long) {
        val waited = testScheduler.currentTime - startMillis
        assertTrue(waited < 1_000, "the send path must not stall, waited $waited ms")
    }
}
