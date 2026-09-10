package com.bitchat.repo.repositories

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.domain.tor.MutableRequestedTorIntent
import com.bitchat.tor.TorManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TorRepoTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val contextFacade = object : CoroutinesContextFacade {
        override val io: CoroutineContext = dispatcher
        override val main: CoroutineContext = dispatcher
        override val default: CoroutineContext = dispatcher
        override val unconfined: CoroutineContext = dispatcher
    }

    private val scopeFacade = object : CoroutineScopeFacade {
        override val applicationScope: CoroutineScope = TestScope(dispatcher)
        override val connectivityEventScope: CoroutineScope = applicationScope
        override val bluetoothScope: CoroutineScope = applicationScope
        override val nostrScope: CoroutineScope = applicationScope
    }

    private val eventBus = mockk<TorEventBus>(relaxed = true).also {
        every { it.events() } returns emptyFlow()
    }

    private fun repo(
        manager: TorManager,
        intent: MutableRequestedTorIntent = InMemoryIntent(),
        engineSupportsTorProxy: Boolean = true,
    ) = TorRepo(
        torManager = manager,
        requestedIntent = intent,
        coroutinesContextFacade = contextFacade,
        coroutineScopeFacade = scopeFacade,
        torEventBus = eventBus,
        engineSupportsTorProxy = engineSupportsTorProxy,
    )

    /** A Tor that is running perfectly - the state Arti reaches on iOS and on the Pi today. */
    private fun healthyManager(): TorManager = manager(
        TorStatus(
            mode = TorMode.ON,
            state = TorState.RUNNING,
            running = true,
            bootstrapPercent = 100,
            lastLogLine = "Arti: SOCKS proxy listening on 127.0.0.1:9050",
        ),
        available = true,
    ).also { every { it.isProxyReady() } returns true }

    /**
     * Persistence that really persists, so the stored-intent assertions below mean something: with
     * a relaxed mock, "the preference still says ON" cannot be told apart from "nothing was ever
     * stored".
     */
    private class InMemoryIntent(initial: TorMode = TorMode.OFF) : MutableRequestedTorIntent {
        private val flow = MutableStateFlow(initial)
        var writes: Int = 0
            private set

        val stored: TorMode get() = flow.value
        override val current: TorMode get() = flow.value
        override val updates: StateFlow<TorMode> get() = flow

        override fun set(mode: TorMode) {
            flow.value = mode
            writes++
        }
    }

    private fun manager(
        status: TorStatus,
        available: Boolean = true,
    ): TorManager = mockk<TorManager>(relaxed = true).also {
        every { it.statusFlow } returns MutableStateFlow(status)
        every { it.isAvailable } returns available
    }

    @Test
    fun `an error state keeps the managers own line instead of a relay log line`() = runTest {
        val detail = "Tor unavailable: native library libarti_desktop.so not found on java.library.path=/nowhere"
        val torRepo = repo(
            manager(
                TorStatus(
                    state = TorState.ERROR,
                    lastLogLine = "Tor unavailable: native library libarti_desktop.so not found",
                    errorMessage = detail,
                ),
                available = false,
            )
        )

        // Exactly what NostrRelay used to feed in while Tor was dead and traffic went out direct.
        torRepo.recordExternalLogLine("Tor connection established to relay.primal.net:443")

        val status = torRepo.getTorStatus()

        assertEquals(TorState.ERROR, status.state)
        assertEquals(detail, status.errorMessage)
        assertFalse(
            status.lastLogLine.contains("Tor connection established"),
            "the relay line masked the real error: '${status.lastLogLine}'"
        )
        assertTrue(status.lastLogLine.startsWith("Tor unavailable: native library"))
    }

    @Test
    fun `a relay log line still wins while tor is healthy`() = runTest {
        val torRepo = repo(
            manager(TorStatus(state = TorState.RUNNING, running = true, bootstrapPercent = 100))
        )

        torRepo.recordExternalLogLine("Tor connection established to relay.primal.net:443")

        assertEquals(
            "Tor connection established to relay.primal.net:443",
            torRepo.getTorStatus().lastLogLine
        )
    }

    @Test
    fun `a failed start reports OFF without erasing the stored ON`() = runTest {
        val intent = InMemoryIntent(TorMode.ON)
        val torManager = manager(
            TorStatus(state = TorState.ERROR, errorMessage = "no native library"),
            available = false,
        )
        coEvery { torManager.start() } returns Unit
        val torRepo = repo(torManager, intent)

        torRepo.enable()

        // Writing OFF here would mean that repairing the install, or just restarting after a
        // transient failure, leaves Tor silently disabled with nothing to explain it.
        assertEquals(0, intent.writes, "a failed start must not rewrite the user's intent")
        assertEquals(TorMode.ON, intent.stored)
        assertEquals(TorMode.ON, torRepo.getStoredTorMode(), "the user's intent must survive")
        // ...but the switch still has to drop back: Tor is not on.
        assertEquals(TorMode.OFF, torRepo.getTorMode())
        coVerify { eventBus.update(TorEvent.ModeChanged) }
    }

    @Test
    fun `the next launch retries the stored ON after a failed start`() = runTest {
        val intent = InMemoryIntent(TorMode.ON)
        val broken = manager(TorStatus(state = TorState.ERROR), available = false)
        coEvery { broken.start() } returns Unit
        repo(broken, intent).enable()

        // A new process, same preferences, a repaired installation. This is what TorAppInitializer
        // reads, and it has to still say ON.
        val healthy = healthyManager()
        val afterRestart = repo(healthy, intent)

        assertEquals(TorMode.ON, afterRestart.getStoredTorMode())
        afterRestart.enable()
        coVerify(exactly = 1) { healthy.start() }
        assertEquals(TorMode.ON, afterRestart.getTorMode())
    }

    @Test
    fun `a successful start does not write the intent`() = runTest {
        /*
         * enable() used to persist ON on success, which made intent a result rather than a
         * request. The use case publishes it before the native call now; a start completing must
         * never write it, or a bootstrap that outlives the user switching off would restore ON.
         */
        val intent = InMemoryIntent(TorMode.ON)
        val torManager = manager(TorStatus(state = TorState.STARTING))
        coEvery { torManager.start() } returns Unit

        val torRepo = repo(torManager, intent)
        torRepo.enable()

        assertEquals(0, intent.writes, "start completion must not write intent")
        assertEquals(TorMode.ON, torRepo.getTorMode())
    }

    @Test
    fun `a start that finishes after the user switched off stops tor again`() = runTest {
        /*
         * The zombie. Removing the ON write alone fixes the preference but leaves Arti bootstrapped
         * and RUNNING with the intent off - isProxyReady() true and relay lines claiming traffic
         * goes through Tor. start() blocks for the length of a bootstrap, which is ample time.
         */
        val intent = InMemoryIntent(TorMode.ON)
        val torManager = manager(TorStatus(state = TorState.STARTING))
        coEvery { torManager.start() } answers { intent.set(TorMode.OFF) }

        repo(torManager, intent).enable()

        coVerify(exactly = 1) { torManager.stop() }
    }

    @Test
    fun `a start that finishes while the user still wants tor is left running`() = runTest {
        val intent = InMemoryIntent(TorMode.ON)
        val torManager = manager(TorStatus(state = TorState.STARTING))
        coEvery { torManager.start() } returns Unit

        repo(torManager, intent).enable()

        coVerify(exactly = 0) { torManager.stop() }
    }

    @Test
    fun `switching tor off clears the stored intent`() = runTest {
        val intent = InMemoryIntent(TorMode.ON)
        val torManager = healthyManager()

        val torRepo = repo(torManager, intent)
        torRepo.disable()

        // Intent is written by DisableTor before this runs, so that turning off takes effect at
        // once rather than whenever a stop that may block finally returns.
        assertEquals(0, intent.writes, "the native stop must not write intent either")
        coVerify(exactly = 1) { torManager.stop() }
    }

    @Test
    fun `availability comes from the manager when the engine can proxy`() = runTest {
        assertEquals(
            TorAvailability.NATIVE_LIBRARY_MISSING,
            repo(manager(TorStatus(), available = false)).torAvailability()
        )
        assertEquals(
            TorAvailability.AVAILABLE,
            repo(manager(TorStatus(), available = true)).torAvailability()
        )
    }

    @Test
    fun `an engine that cannot proxy makes tor unavailable however healthy tor is`() = runTest {
        val torRepo = repo(healthyManager(), engineSupportsTorProxy = false)

        assertEquals(TorAvailability.NO_PROXY_SUPPORT, torRepo.torAvailability())
        assertFalse(
            torRepo.torAvailability().isAvailable,
            "the switch must be disabled: a bootstrapped Tor protects nothing here"
        )
    }

    @Test
    fun `the reason distinguishes a missing library from an engine that cannot proxy`() = runTest {
        // Same broken manager, two different builds: the user has to be told the right thing.
        val brokenLibrary = manager(TorStatus(), available = false)

        assertEquals(
            TorAvailability.NATIVE_LIBRARY_MISSING,
            repo(brokenLibrary, engineSupportsTorProxy = true).torAvailability()
        )
        assertEquals(
            TorAvailability.NO_PROXY_SUPPORT,
            repo(brokenLibrary, engineSupportsTorProxy = false).torAvailability(),
            "on Apple and Linux the library loads fine, so blaming it would send the user hunting"
        )
    }

    @Test
    fun `nothing is reported as running when the engine cannot proxy`() = runTest {
        val torRepo = repo(healthyManager(), engineSupportsTorProxy = false)

        val status = torRepo.getTorStatus()

        assertFalse(status.running, "the header dot and the status card read this")
        assertEquals(0, status.bootstrapPercent)
        assertEquals(TorState.OFF, status.state)
        assertEquals(TorMode.OFF, status.mode)
        assertFalse(torRepo.isProxyReady())
        assertEquals(null, torRepo.getSocksProxyAddress())
    }

    @Test
    fun `a stored ON reads as OFF without being rewritten when the engine cannot proxy`() = runTest {
        val intent = InMemoryIntent(TorMode.ON)
        val torRepo = repo(healthyManager(), intent, engineSupportsTorProxy = false)

        assertEquals(TorMode.OFF, torRepo.getTorMode(), "a switch shown as on would read as protection")
        // Still the user's intent, kept for the day this profile runs a build that can proxy.
        assertEquals(TorMode.ON, torRepo.getStoredTorMode())
        assertEquals(0, intent.writes)
    }

    @Test
    fun `enable neither starts tor nor touches the preference when the engine cannot proxy`() = runTest {
        val intent = InMemoryIntent(TorMode.ON)
        val torManager = healthyManager()

        repo(torManager, intent, engineSupportsTorProxy = false).enable()

        coVerify(exactly = 0) { torManager.start() }
        // Rewriting it would surprise the user the day they run a build that can proxy.
        assertEquals(0, intent.writes)
        assertEquals(TorMode.ON, intent.stored)
    }

    @Test
    fun `enable still starts on a jvm host whose engine proxies`() = runTest {
        // Intent is already ON here because the use case publishes it before calling enable().
        val intent = InMemoryIntent(TorMode.ON)
        val torManager = healthyManager()

        val torRepo = repo(torManager, intent, engineSupportsTorProxy = true)
        torRepo.enable()

        coVerify(exactly = 1) { torManager.start() }
        assertEquals(0, intent.writes, "starting must not write intent")
        assertEquals(TorAvailability.AVAILABLE, torRepo.torAvailability())
        assertEquals(TorMode.ON, torRepo.getTorMode())
        assertTrue(torRepo.isProxyReady())
        assertTrue(torRepo.getTorStatus().running)
    }
}
