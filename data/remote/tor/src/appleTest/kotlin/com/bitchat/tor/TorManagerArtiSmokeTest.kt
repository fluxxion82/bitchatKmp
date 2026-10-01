package com.bitchat.tor

import com.bitchat.domain.tor.model.TorState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSTemporaryDirectory
import platform.posix.getenv
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Bootstraps the real, statically linked Arti through the production binding. Needs the network and
 * a couple of minutes at most, so it only runs with `TOR_INTEGRATION=1` in the environment (the
 * module's build file forwards it, also into the simulator) and is not part of any gate.
 */
@OptIn(ExperimentalForeignApi::class)
class TorManagerArtiSmokeTest {

    @Test
    fun real_arti_reaches_ready_on_a_dynamic_port_and_stops() {
        if (getenv("TOR_INTEGRATION")?.toKString() != "1") {
            println("TorManagerArtiSmokeTest: skipped - set TOR_INTEGRATION=1 to bootstrap the real Arti (network)")
            return
        }
        runBlocking {
            val dataDir = NSTemporaryDirectory() + "bitchat-tor-smoke-" + Random.nextLong().toString(16)
            val manager = TorManager(dataDir)
            val started = TimeSource.Monotonic.markNow()

            manager.start()
            val outcome = withTimeout(120.seconds) {
                manager.statusFlow.first { it.state == TorState.RUNNING || it.state == TorState.ERROR }
            }

            assertEquals(TorState.RUNNING, outcome.state, "Arti did not reach READY: ${outcome.errorMessage}")
            assertTrue(outcome.socksPort > 0, "READY must carry the port Arti bound")
            assertTrue(outcome.routeGeneration > 0, "READY must publish the native generation")
            assertTrue(manager.isProxyReady())
            assertEquals("127.0.0.1" to outcome.socksPort, manager.getSocksProxyAddress())
            println(
                "TorManagerArtiSmokeTest: READY on 127.0.0.1:${outcome.socksPort} generation " +
                    "${outcome.routeGeneration} after ${started.elapsedNow()} (data dir $dataDir)"
            )

            val stopStarted = TimeSource.Monotonic.markNow()
            manager.stop()
            val stopped = manager.statusFlow.value
            println("TorManagerArtiSmokeTest: stopped in ${stopStarted.elapsedNow()}: ${stopped.state} ${stopped.lastLogLine}")
            assertEquals(TorState.OFF, stopped.state, stopped.errorMessage)
            assertFalse(manager.isProxyReady())
            assertNull(manager.getSocksProxyAddress())
            assertEquals(0L, stopped.routeGeneration)
            manager.destroy()
        }
    }
}
