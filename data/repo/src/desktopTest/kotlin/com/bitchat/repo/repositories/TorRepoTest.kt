package com.bitchat.repo.repositories

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.local.prefs.TorPreferences
import com.bitchat.tor.TorManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
        preferences: TorPreferences = mockk(relaxed = true),
        engineSupportsTorProxy: Boolean = true,
    ) = TorRepo(
        torManager = manager,
        torPreferences = preferences,
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
    private class InMemoryTorPreferences(initial: TorMode = TorMode.OFF) : TorPreferences {
        var stored: TorMode = initial
            private set
        var writes: Int = 0
            private set

        override fun setTorMode(mode: TorMode) {
            stored = mode
            writes++
        }

        override fun getTorMode(): TorMode = stored
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
        val preferences = InMemoryTorPreferences(TorMode.ON)
        val torManager = manager(
            TorStatus(state = TorState.ERROR, errorMessage = "no native library"),
            available = false,
        )
        coEvery { torManager.start() } returns Unit
        val torRepo = repo(torManager, preferences)

        torRepo.enable()

        // Writing OFF here would mean that repairing the install, or just restarting after a
        // transient failure, leaves Tor silently disabled with nothing to explain it.
        assertEquals(0, preferences.writes, "a failed start must not rewrite the user's intent")
        assertEquals(TorMode.ON, preferences.stored)
        assertEquals(TorMode.ON, torRepo.getStoredTorMode(), "the user's intent must survive")
        // ...but the switch still has to drop back: Tor is not on.
        assertEquals(TorMode.OFF, torRepo.getTorMode())
        coVerify { eventBus.update(TorEvent.ModeChanged) }
    }

    @Test
    fun `the next launch retries the stored ON after a failed start`() = runTest {
        val preferences = InMemoryTorPreferences(TorMode.ON)
        val broken = manager(TorStatus(state = TorState.ERROR), available = false)
        coEvery { broken.start() } returns Unit
        repo(broken, preferences).enable()

        // A new process, same preferences, a repaired installation. This is what TorAppInitializer
        // reads, and it has to still say ON.
        val healthy = healthyManager()
        val afterRestart = repo(healthy, preferences)

        assertEquals(TorMode.ON, afterRestart.getStoredTorMode())
        afterRestart.enable()
        coVerify(exactly = 1) { healthy.start() }
        assertEquals(TorMode.ON, afterRestart.getTorMode())
    }

    @Test
    fun `enable persists ON once tor actually starts`() = runTest {
        val preferences = InMemoryTorPreferences(TorMode.OFF)
        val torManager = manager(TorStatus(state = TorState.STARTING))
        coEvery { torManager.start() } returns Unit

        val torRepo = repo(torManager, preferences)
        torRepo.enable()

        assertEquals(TorMode.ON, preferences.stored, "a start that worked is worth remembering")
        assertEquals(TorMode.ON, torRepo.getStoredTorMode())
        assertEquals(TorMode.ON, torRepo.getTorMode())
    }

    @Test
    fun `switching tor off clears the stored intent`() = runTest {
        val preferences = InMemoryTorPreferences(TorMode.ON)
        val torManager = healthyManager()

        val torRepo = repo(torManager, preferences)
        torRepo.disable()

        // The user said off, so the intent really does change here - unlike a failed start.
        assertEquals(TorMode.OFF, preferences.stored)
        assertEquals(TorMode.OFF, torRepo.getStoredTorMode())
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
        val preferences = InMemoryTorPreferences(TorMode.ON)
        val torRepo = repo(healthyManager(), preferences, engineSupportsTorProxy = false)

        assertEquals(TorMode.OFF, torRepo.getTorMode(), "a switch shown as on would read as protection")
        // Still the user's intent, kept for the day this profile runs a build that can proxy.
        assertEquals(TorMode.ON, torRepo.getStoredTorMode())
        assertEquals(0, preferences.writes)
    }

    @Test
    fun `enable neither starts tor nor touches the preference when the engine cannot proxy`() = runTest {
        val preferences = InMemoryTorPreferences(TorMode.ON)
        val torManager = healthyManager()

        repo(torManager, preferences, engineSupportsTorProxy = false).enable()

        coVerify(exactly = 0) { torManager.start() }
        // Rewriting it would surprise the user the day they run a build that can proxy.
        assertEquals(0, preferences.writes)
        assertEquals(TorMode.ON, preferences.stored)
    }

    @Test
    fun `enable still starts and persists on a jvm host whose engine proxies`() = runTest {
        val preferences = InMemoryTorPreferences(TorMode.OFF)
        val torManager = healthyManager()

        val torRepo = repo(torManager, preferences, engineSupportsTorProxy = true)
        torRepo.enable()

        coVerify(exactly = 1) { torManager.start() }
        assertEquals(TorMode.ON, preferences.stored)
        assertEquals(TorAvailability.AVAILABLE, torRepo.torAvailability())
        assertEquals(TorMode.ON, torRepo.getTorMode())
        assertTrue(torRepo.isProxyReady())
        assertTrue(torRepo.getTorStatus().running)
    }
}
