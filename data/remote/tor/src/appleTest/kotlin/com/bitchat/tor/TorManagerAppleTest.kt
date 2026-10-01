package com.bitchat.tor

import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.tor.native.ARTI_STATUS_ERROR
import com.bitchat.tor.native.ARTI_STATUS_INITIALIZING
import com.bitchat.tor.native.ARTI_STATUS_READY
import com.bitchat.tor.native.ARTI_STATUS_SOCKS_LISTENING
import com.bitchat.tor.native.ARTI_STATUS_STOPPED
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.usleep
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Stands in for the Arti C ABI with the Rust wrapper's contract: [stopGeneration] reports STOPPED
 * synchronously from inside the call, like `stop_locked` does, and every status reaches the manager
 * only through the sink it installed.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FakeArtiNative(
    private val startResult: Int = 0,
    private val stopBlockMillis: Long = 0,
) : ArtiNative {
    @Volatile private var sink: ArtiStatusSink? = null
    val startGenerations = mutableListOf<ULong>()
    val requestedPorts = mutableListOf<Int>()
    val stopGenerations = mutableListOf<ULong>()
    @Volatile var stopEntered = false

    val currentGeneration: ULong get() = startGenerations.last()

    override fun setStatusSink(sink: ArtiStatusSink) {
        this.sink = sink
    }

    override fun start(dataDir: String, requestedPort: Int, generation: ULong): Int {
        startGenerations += generation
        requestedPorts += requestedPort
        return startResult
    }

    override fun stopGeneration(generation: ULong): Int {
        stopEntered = true
        stopGenerations += generation
        if (stopBlockMillis > 0) usleep((stopBlockMillis * 1_000).toUInt())
        emit(ARTI_STATUS_STOPPED, 0, generation, "Arti stopped")
        return 0
    }

    fun emit(state: Int, port: Int, generation: ULong, message: String = "status $state") {
        checkNotNull(sink) { "the manager never installed its status sink" }
            .onStatus(state, port, generation, message)
    }
}

@OptIn(ExperimentalForeignApi::class)
class TorManagerAppleTest {

    private fun tempDir(): String =
        NSTemporaryDirectory() + "bitchat-tor-test-" + Random.nextLong().toString(16)

    private suspend fun TorManager.awaitState(state: TorState, limit: Duration = 2.seconds) =
        withTimeout(limit) { statusFlow.first { it.state == state } }

    private suspend fun TorManager.awaitLogLine(line: String, limit: Duration = 2.seconds) =
        withTimeout(limit) { statusFlow.first { it.lastLogLine == line } }

    private suspend fun FakeArtiNative.bringUp(manager: TorManager, port: Int): ULong {
        manager.start()
        val generation = currentGeneration
        emit(ARTI_STATUS_INITIALIZING, 0, generation, "Bootstrapping Arti")
        emit(ARTI_STATUS_SOCKS_LISTENING, port, generation, "SOCKS listener bound")
        emit(ARTI_STATUS_READY, port, generation, "Arti ready")
        manager.awaitState(TorState.RUNNING)
        return generation
    }

    @Test
    fun ready_sets_port_readiness_and_route_generation() = runBlocking {
        val fake = FakeArtiNative()
        val manager = TorManager(tempDir(), fake)

        manager.start()
        val generation = fake.currentGeneration
        assertEquals(listOf(0), fake.requestedPorts, "the port is chosen by Arti (requested_port 0)")
        assertTrue(generation > 0UL)
        assertEquals(TorState.STARTING, manager.statusFlow.value.state)
        assertFalse(manager.isProxyReady())

        fake.emit(ARTI_STATUS_INITIALIZING, 0, generation, "Bootstrapping Arti")
        fake.emit(ARTI_STATUS_SOCKS_LISTENING, 41234, generation, "SOCKS listener bound")
        val listening = manager.awaitState(TorState.BOOTSTRAPPING)
        assertEquals(41234, listening.socksPort)
        assertFalse(manager.isProxyReady(), "a bound listener is not ready until Arti says READY")
        assertNull(manager.getSocksProxyAddress())
        assertEquals(0L, listening.routeGeneration, "no route is owned before READY")

        fake.emit(ARTI_STATUS_READY, 41234, generation, "Arti ready")
        val ready = manager.awaitState(TorState.RUNNING)
        assertTrue(manager.isProxyReady())
        assertEquals("127.0.0.1" to 41234, manager.getSocksProxyAddress())
        assertEquals(41234, ready.socksPort)
        assertEquals(generation.toLong(), ready.routeGeneration)
        assertEquals(TorMode.ON, ready.mode)
        assertTrue(ready.running)
        assertEquals(100, ready.bootstrapPercent)
        assertNull(ready.errorMessage)
        manager.destroy()
    }

    @Test
    fun stop_clears_port_readiness_and_route_generation() = runBlocking {
        val fake = FakeArtiNative()
        val manager = TorManager(tempDir(), fake)
        val generation = fake.bringUp(manager, 41235)

        manager.stop()

        assertEquals(listOf(generation), fake.stopGenerations)
        val stopped = manager.statusFlow.value
        assertEquals(TorState.OFF, stopped.state)
        assertEquals(TorMode.OFF, stopped.mode)
        assertFalse(stopped.running)
        assertEquals(0, stopped.socksPort)
        assertEquals(0L, stopped.routeGeneration)
        assertFalse(manager.isProxyReady())
        assertNull(manager.getSocksProxyAddress())
        manager.destroy()
    }

    @Test
    fun error_clears_port_readiness_and_route_generation() = runBlocking {
        val fake = FakeArtiNative()
        val manager = TorManager(tempDir(), fake)
        val generation = fake.bringUp(manager, 41236)

        fake.emit(ARTI_STATUS_ERROR, 0, generation, "SOCKS accept failed: synthetic")

        val failed = manager.awaitState(TorState.ERROR)
        assertEquals("SOCKS accept failed: synthetic", failed.errorMessage)
        assertFalse(failed.running)
        assertEquals(0, failed.socksPort)
        assertEquals(0L, failed.routeGeneration)
        assertFalse(manager.isProxyReady())
        assertNull(manager.getSocksProxyAddress())
        manager.destroy()
    }

    @Test
    fun a_failed_start_call_reports_error() = runBlocking {
        val fake = FakeArtiNative(startResult = -3)
        val manager = TorManager(tempDir(), fake)

        manager.start()

        val failed = manager.awaitState(TorState.ERROR)
        assertEquals("Arti start call failed: -3", failed.errorMessage)
        assertFalse(manager.isProxyReady())
        manager.destroy()
    }

    @Test
    fun stale_generation_events_are_ignored() = runBlocking {
        val fake = FakeArtiNative()
        val manager = TorManager(tempDir(), fake)
        val stale = fake.bringUp(manager, 41237)
        manager.stop()
        manager.start()
        val current = fake.currentGeneration

        // A READY from the stopped generation, then a marker for the current one. Events are applied
        // in order, so once the marker shows the stale READY has been seen and must have been dropped.
        fake.emit(ARTI_STATUS_SOCKS_LISTENING, 41237, stale, "stale listener")
        fake.emit(ARTI_STATUS_READY, 41237, stale, "stale ready")
        fake.emit(ARTI_STATUS_INITIALIZING, 0, current, "marker")
        val status = manager.awaitLogLine("marker")

        assertEquals(TorState.STARTING, status.state)
        assertEquals(0, status.socksPort)
        assertEquals(0L, status.routeGeneration)
        assertFalse(manager.isProxyReady())
        assertNull(manager.getSocksProxyAddress())
        manager.destroy()
    }

    @Test
    fun stop_then_start_yields_a_new_generation() = runBlocking {
        val fake = FakeArtiNative()
        val manager = TorManager(tempDir(), fake)
        val first = fake.bringUp(manager, 41238)

        manager.stop()
        val second = fake.bringUp(manager, 41239)

        assertTrue(second > first, "generation $second must be newer than $first")
        assertEquals(second.toLong(), manager.statusFlow.value.routeGeneration)
        assertEquals("127.0.0.1" to 41239, manager.getSocksProxyAddress())
        manager.destroy()
    }

    @Test
    fun start_twice_ignores_the_pre_stop_for_the_new_generation() = runBlocking {
        val fake = FakeArtiNative()
        val manager = TorManager(tempDir(), fake)
        val first = fake.bringUp(manager, 41240)

        manager.start()
        val second = fake.currentGeneration
        assertTrue(second > first)

        // arti_start(second) pre-stops leftovers but tags that STOPPED with second, then begins
        // second's ordinary lifecycle. It must not leave the manager OFF between those events.
        fake.emit(ARTI_STATUS_STOPPED, 0, second, "pre-stop leftovers")
        // The status sink only enqueues. A task behind it on the same serial worker is a consumer
        // barrier, so this observes the swallowed STOPPED rather than the status from start().
        withContext(artiLifecycleWorker) { }
        assertEquals(TorState.STARTING, manager.statusFlow.value.state)
        fake.emit(ARTI_STATUS_INITIALIZING, 0, second, "Bootstrapping Arti")
        fake.emit(ARTI_STATUS_SOCKS_LISTENING, 41241, second, "SOCKS listener bound")
        fake.emit(ARTI_STATUS_READY, 41241, second, "Arti ready")

        val ready = manager.awaitState(TorState.RUNNING)
        assertEquals(second.toLong(), ready.routeGeneration)
        assertEquals(41241, ready.socksPort)
        manager.destroy()
    }

    @Test
    fun main_queue_stays_responsive_while_a_native_stop_blocks() {
        assertTrue(NSThread.isMainThread, "this test pumps the main run loop, so it must run on the main thread")
        val fake = FakeArtiNative(stopBlockMillis = 2_000)
        val manager = TorManager(tempDir(), fake)
        runBlocking { fake.bringUp(manager, 41240) }

        // Production calls start/stop from the facade's contexts, which are all the main queue on Apple.
        val mainQueueScope = CoroutineScope(defaultContextFacade.io + SupervisorJob())
        val stopped = CompletableDeferred<Result<Unit>>()
        mainQueueScope.launch { stopped.complete(runCatching { manager.stop() }) }
        val dispatched = TimeSource.Monotonic.markNow()
        var pingLatency: Duration? = null
        dispatch_async(dispatch_get_main_queue()) { pingLatency = dispatched.elapsedNow() }

        pumpMainQueue(limit = 3.seconds) { pingLatency != null }
        val latency = assertNotNull(pingLatency, "the main queue never ran the ping within 3 s")
        assertTrue(latency < 200.milliseconds, "a main-queue block waited $latency behind the native stop")

        pumpMainQueue(limit = 5.seconds) { stopped.isCompleted }
        assertTrue(stopped.isCompleted, "stop() did not finish after the native stop returned")
        stopped.getCompleted().getOrThrow()
        assertTrue(fake.stopEntered)
        assertEquals(TorState.OFF, manager.statusFlow.value.state)
        mainQueueScope.cancel()
        manager.destroy()
    }

    private fun pumpMainQueue(limit: Duration, until: () -> Boolean) {
        val started = TimeSource.Monotonic.markNow()
        while (!until() && started.elapsedNow() < limit) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.005))
        }
    }
}
