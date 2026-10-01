package com.bitchat.tor

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.tor.native.ARTI_STATUS_ERROR
import com.bitchat.tor.native.ARTI_STATUS_INITIALIZING
import com.bitchat.tor.native.ARTI_STATUS_READY
import com.bitchat.tor.native.ARTI_STATUS_SOCKS_LISTENING
import com.bitchat.tor.native.ARTI_STATUS_STOPPED
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [TorManager] over [FakeArtiNative] when Arti cannot read its saved state: the 2026-09-30 Orange Pi
 * failure, on the platform that shares its lifecycle ABI. The state tree is real ([ArtiStateFixture]),
 * so moves, survivors and stale directories are checked on disk.
 */
@OptIn(ExperimentalForeignApi::class)
class TorManagerStateRecoveryAppleTest {
    private val fixture = ArtiStateFixture()

    @AfterTest
    fun cleanUp() = fixture.delete()

    /** A fresh latch per manager: the process-wide one would let only the first test reset. */
    private fun manager(fake: FakeArtiNative, latch: ArtiRecoveryLatch = ArtiRecoveryLatch()): TorManager {
        val dataDir = fixture.dataDir.toString()
        return TorManager(dataDir, fake, ArtiStateRecovery(dataDir, latch))
    }

    /** The sink only enqueues; an empty task on the serial worker runs after everything queued before it. */
    private suspend fun drain() = withContext(artiLifecycleWorker) { }

    private suspend fun TorManager.awaitState(state: TorState) =
        withTimeout(2.seconds) { statusFlow.first { it.state == state } }

    @Test
    fun corrupt_state_is_moved_aside_once_and_arti_is_restarted_with_a_truthful_status() = runBlocking {
        val fake = FakeArtiNative()
        val manager = manager(fake)
        manager.start()
        val failed = fake.currentGeneration

        fake.emit(ARTI_STATUS_ERROR, 0, failed, CORRUPT_STATE_ERROR)
        drain()

        assertEquals(2, fake.startGenerations.size, "Arti must be started again: ${fake.startGenerations}")
        val retried = fake.currentGeneration
        assertTrue(retried > failed, "the retry is a new generation, $retried after $failed")
        assertEquals(listOf(0, 0), fake.requestedPorts)
        assertFalse(SystemFileSystem.exists(fixture.stateDir), "the unreadable state is out of Arti's way")
        assertEquals(1, fixture.staleDirectories().size, "moved aside exactly once")
        fixture.assertUntouched()

        // Restarting, which is the truth: not running, no port, no route - and the card says why.
        val restarting = manager.statusFlow.value
        assertEquals(TorState.STARTING, restarting.state)
        assertEquals(TorMode.ON, restarting.mode)
        assertFalse(restarting.running)
        assertEquals(0, restarting.socksPort)
        assertEquals(0L, restarting.routeGeneration)
        assertFalse(manager.isProxyReady())
        assertNull(manager.getSocksProxyAddress())
        assertContains(assertNotNull(restarting.errorMessage), "reset")

        // arti_start pre-stops the failed generation and tags that STOPPED with the new number; the
        // restart notice survives it and Arti's own progress until Tor is actually up.
        fake.emit(ARTI_STATUS_STOPPED, 0, retried, "Arti stopped")
        fake.emit(ARTI_STATUS_INITIALIZING, 0, retried, "Bootstrapping Arti")
        drain()
        val bootstrapping = manager.statusFlow.value
        assertEquals(TorState.STARTING, bootstrapping.state)
        assertEquals(TorMode.ON, bootstrapping.mode)
        assertContains(assertNotNull(bootstrapping.errorMessage), "reset")

        fake.emit(ARTI_STATUS_SOCKS_LISTENING, 41250, retried, "SOCKS listener bound")
        fake.emit(ARTI_STATUS_READY, 41250, retried, "Arti ready")
        val ready = manager.awaitState(TorState.RUNNING)
        assertEquals(retried.toLong(), ready.routeGeneration)
        assertNull(ready.errorMessage)
        assertEquals("127.0.0.1" to 41250, manager.getSocksProxyAddress())
        manager.destroy()
    }

    @Test
    fun a_second_corrupt_state_error_is_surfaced_without_another_move_or_restart() = runBlocking {
        val fake = FakeArtiNative()
        val manager = manager(fake)
        manager.start()
        fake.emit(ARTI_STATUS_ERROR, 0, fake.currentGeneration, CORRUPT_STATE_ERROR)
        drain()
        val retried = fake.currentGeneration
        // The restarted Arti writes fresh state, and still cannot read it.
        ArtiStateFixture.write(fixture.guards, ArtiStateFixture.NEW_GUARDS)

        fake.emit(ARTI_STATUS_ERROR, 0, retried, CORRUPT_STATE_ERROR)
        val failed = manager.awaitState(TorState.ERROR)

        assertEquals(2, fake.startGenerations.size, "no third start: ${fake.startGenerations}")
        assertEquals(ArtiStateFixture.NEW_GUARDS, ArtiStateFixture.read(fixture.guards), "no second move")
        assertEquals(1, fixture.staleDirectories().size)
        val error = assertNotNull(failed.errorMessage)
        assertContains(error, CORRUPT_STATE_ERROR)
        assertContains(error, "already reset")
        assertFalse(failed.running)
        assertEquals(0L, failed.routeGeneration)
        assertFalse(manager.isProxyReady())
        fixture.assertUntouched()
        manager.destroy()
    }

    @Test
    fun unrelated_errors_neither_move_the_state_nor_restart_arti() = runBlocking {
        val latch = ArtiRecoveryLatch()
        for (message in UNRELATED_ERRORS) {
            val fake = FakeArtiNative()
            val manager = manager(fake, latch)
            manager.start()

            fake.emit(ARTI_STATUS_ERROR, 0, fake.currentGeneration, message)
            val failed = manager.awaitState(TorState.ERROR)

            assertEquals(message, failed.errorMessage)
            assertEquals(1, fake.startGenerations.size, "no restart for: $message")
            assertFalse(manager.isProxyReady())
            manager.destroy()
        }
        assertEquals(ArtiStateFixture.OLD_GUARDS, ArtiStateFixture.read(fixture.guards))
        assertEquals(emptyList(), fixture.staleDirectories())
        fixture.assertUntouched()
    }

    @Test
    fun a_corrupt_state_error_from_a_stopped_generation_changes_nothing() = runBlocking {
        val fake = FakeArtiNative()
        val manager = manager(fake)
        manager.start()
        val stopped = fake.currentGeneration
        manager.stop()

        fake.emit(ARTI_STATUS_ERROR, 0, stopped, CORRUPT_STATE_ERROR)
        drain()

        assertEquals(1, fake.startGenerations.size, "a stopped Tor stays stopped")
        assertEquals(TorState.OFF, manager.statusFlow.value.state)
        assertTrue(SystemFileSystem.exists(fixture.stateDir))
        assertEquals(emptyList(), fixture.staleDirectories())
        fixture.assertUntouched()
        manager.destroy()
    }
}
