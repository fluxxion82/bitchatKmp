package com.bitchat.viewmodel.settings

import app.cash.turbine.test
import com.bitchat.domain.app.DisableBackgroundMode
import com.bitchat.domain.app.EnableBackgroundMode
import com.bitchat.domain.app.GetAppTheme
import com.bitchat.domain.app.GetBackgroundMode
import com.bitchat.domain.app.SetAppTheme
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.model.BackgroundMode
import com.bitchat.domain.lora.GetLoRaSettings
import com.bitchat.domain.lora.SetLoRaEnabled
import com.bitchat.domain.lora.SetLoRaRegion
import com.bitchat.domain.lora.SetLoRaTxPower
import com.bitchat.domain.lora.SetShowLoRaPeers
import com.bitchat.domain.lora.SwitchLoRaProtocol
import com.bitchat.domain.lora.model.LoRaSettings
import com.bitchat.domain.nostr.GetPowSettings
import com.bitchat.domain.nostr.SetPowSettings
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.tor.DisableTor
import com.bitchat.domain.tor.EnableTor
import com.bitchat.domain.tor.GetTorAvailability
import com.bitchat.domain.tor.ObserveRequestedTorMode
import com.bitchat.domain.tor.GetTorStatus
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.viewmodel.BaseViewModelTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On a desktop host with no Arti library the Tor switch used to stay enabled and on, and the one
 * message explaining why Tor could not run reached no part of the UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTorTest : BaseViewModelTest() {

    private val unavailableDetail =
        "Tor unavailable: native library libarti_desktop.so not found on java.library.path=/nowhere. " +
                "Build it with data/remote/tor/native/build-desktop.sh, or leave Tor off."

    private val getTorStatus = mockk<GetTorStatus>()
    private val observeRequestedTorMode = mockk<ObserveRequestedTorMode>()
    private val getTorAvailability = mockk<GetTorAvailability>()
    private val enableTor = mockk<EnableTor>(relaxed = true)
    private val disableTor = mockk<DisableTor>(relaxed = true)

    private suspend fun buildViewModel(
        torAvailability: TorAvailability,
        torMode: TorMode,
        torStatus: TorStatus,
    ): SettingsViewModel {
        coEvery { getTorAvailability(Unit) } returns torAvailability
        coEvery { observeRequestedTorMode(Unit) } returns MutableStateFlow(torMode)
        coEvery { getTorStatus(Unit) } returns flowOf(torStatus)

        val getAppTheme = mockk<GetAppTheme>()
        coEvery { getAppTheme(Unit) } returns flowOf(AppTheme.SYSTEM)
        val getPowSettings = mockk<GetPowSettings>()
        coEvery { getPowSettings() } returns flowOf(PowSettings())
        val getBackgroundMode = mockk<GetBackgroundMode>()
        coEvery { getBackgroundMode(Unit) } returns flowOf(BackgroundMode.OFF)
        val getLoRaSettings = mockk<GetLoRaSettings>()
        coEvery { getLoRaSettings(Unit) } returns LoRaSettings()

        return SettingsViewModel(
            setAppTheme = mockk<SetAppTheme>(relaxed = true),
            getAppTheme = getAppTheme,
            setPowSettings = mockk<SetPowSettings>(relaxed = true),
            getPowSettings = getPowSettings,
            getTorStatus = getTorStatus,
            observeRequestedTorMode = observeRequestedTorMode,
            enableTor = enableTor,
            disableTor = disableTor,
            getTorAvailability = getTorAvailability,
            getBackgroundMode = getBackgroundMode,
            enableBackgroundMode = mockk<EnableBackgroundMode>(relaxed = true),
            disableBackgroundMode = mockk<DisableBackgroundMode>(relaxed = true),
            showBackgroundModeSetting = false,
            getLoRaSettings = getLoRaSettings,
            setLoRaEnabled = mockk<SetLoRaEnabled>(relaxed = true),
            setLoRaRegion = mockk<SetLoRaRegion>(relaxed = true),
            setLoRaTxPower = mockk<SetLoRaTxPower>(relaxed = true),
            setShowLoRaPeers = mockk<SetShowLoRaPeers>(relaxed = true),
            switchLoRaProtocol = mockk<SwitchLoRaProtocol>(relaxed = true),
        )
    }

    @Test
    fun `tor is reported unavailable when the native library is missing`() = runTest {
        val viewModel = buildViewModel(
            torAvailability = TorAvailability.NATIVE_LIBRARY_MISSING,
            torMode = TorMode.OFF,
            torStatus = TorStatus(
                state = TorState.ERROR,
                lastLogLine = "Tor unavailable: native library libarti_desktop.so not found",
                errorMessage = unavailableDetail,
            ),
        )

        viewModel.state.test {
            val settled = awaitStateWith { !it.torAvailable && it.torErrorMessage != null }

            assertFalse(settled.torAvailable, "the Tor switch has to be disabled, not just off")
            assertFalse(settled.torNetworkEnabled)
            assertEquals(unavailableDetail, settled.torErrorMessage)
            assertTrue(
                settled.torErrorMessage!!.contains("build-desktop.sh"),
                "the message shown has to say what to do about it"
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The state a fresh desktop start really lands in, and the one that used to lose the message:
     * the switch is disabled because no Arti library loaded, so `TorManager.start()` is never
     * called and nothing ever moves the status out of OFF. `TorManager.jvm.kt` therefore seeds
     * `errorMessage` at construction; if that seeding is removed, `torErrorMessage` is null here
     * and the settings screen falls back to the generic "tor not available in this build".
     */
    @Test
    fun `the reason from a never-started manager reaches the settings state`() = runTest {
        val viewModel = buildViewModel(
            torAvailability = TorAvailability.NATIVE_LIBRARY_MISSING,
            torMode = TorMode.OFF,
            // Exactly TorManager.jvm.kt's initial TorStatus on a host with no libarti_desktop:
            // nothing started, nothing failed, OFF - with the reason already in place.
            torStatus = TorStatus(
                mode = TorMode.OFF,
                state = TorState.OFF,
                running = false,
                bootstrapPercent = 0,
                lastLogLine = "Tor unavailable: native library libarti_desktop.so not found",
                errorMessage = unavailableDetail,
            ),
        )

        viewModel.state.test {
            val settled = awaitStateWith { !it.torAvailable && it.torErrorMessage != null }

            assertEquals(
                unavailableDetail,
                settled.torErrorMessage,
                "without this the settings screen shows the generic build message instead"
            )
            assertTrue(
                settled.torErrorMessage!!.contains("build-desktop.sh"),
                "the message shown has to say what to do about it"
            )
            // Both places that render it are driven off this: the line under the switch, and the
            // Tor status card, which SettingsContent gates on torErrorMessage != null.
            assertFalse(settled.torAvailable)
            assertFalse(settled.torNetworkEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tor is reported available when the library loaded`() = runTest {
        val viewModel = buildViewModel(
            torAvailability = TorAvailability.AVAILABLE,
            torMode = TorMode.ON,
            torStatus = TorStatus(
                state = TorState.RUNNING,
                running = true,
                bootstrapPercent = 100,
                lastLogLine = "Tor connection established to relay.primal.net:443",
            ),
        )

        viewModel.state.test {
            // Availability and requested intent now arrive from two different collectors, so the
            // settled state has to wait for both rather than for availability alone.
            val settled = awaitStateWith {
                it.torAvailable && it.torRunning && it.requestedTorMode == TorMode.ON
            }

            assertTrue(settled.torNetworkEnabled)
            assertEquals(null, settled.torErrorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tor is reported unavailable when this platform cannot route through a proxy`() = runTest {
        // iOS, macOS desktop and the embedded Pi build: Arti loads and would bootstrap happily,
        // but the Darwin and Curl engines ignore the SOCKS proxy, so the repository reports
        // everything off and the switch must not be offered.
        val viewModel = buildViewModel(
            torAvailability = TorAvailability.NO_PROXY_SUPPORT,
            torMode = TorMode.OFF,
            torStatus = TorStatus(),
        )

        viewModel.state.test {
            val settled = awaitStateWith { it.torAvailability == TorAvailability.NO_PROXY_SUPPORT }

            assertFalse(settled.torAvailable, "a working TorManager must not make the switch live")
            assertFalse(settled.torNetworkEnabled, "an on-looking switch would read as protection")
            assertFalse(settled.torRunning)
            assertEquals(0, settled.torBootstrapPercent)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the two unavailable reasons stay distinguishable in state`() = runTest {
        // They render different explanations, so the state has to carry which one it is: blaming a
        // missing library on iOS would send the user hunting for a library that is right there.
        val noProxy = buildViewModel(
            torAvailability = TorAvailability.NO_PROXY_SUPPORT,
            torMode = TorMode.OFF,
            torStatus = TorStatus(),
        )
        noProxy.state.test {
            val settled = awaitStateWith { it.torAvailability == TorAvailability.NO_PROXY_SUPPORT }
            assertEquals(
                null,
                settled.torErrorMessage,
                "there is no Tor error here - nothing was started - so only the platform string shows"
            )
            cancelAndIgnoreRemainingEvents()
        }

        val noLibrary = buildViewModel(
            torAvailability = TorAvailability.NATIVE_LIBRARY_MISSING,
            torMode = TorMode.OFF,
            torStatus = TorStatus(state = TorState.ERROR, errorMessage = unavailableDetail),
        )
        noLibrary.state.test {
            val settled = awaitStateWith { it.torErrorMessage != null }
            assertEquals(TorAvailability.NATIVE_LIBRARY_MISSING, settled.torAvailability)
            assertEquals(unavailableDetail, settled.torErrorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<com.bitchat.viewvo.settings.SettingsState>.awaitStateWith(
        predicate: (com.bitchat.viewvo.settings.SettingsState) -> Boolean
    ): com.bitchat.viewvo.settings.SettingsState {
        repeat(10) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
        error("state never satisfied the predicate")
    }

    @Test
    fun `the switch can be turned off on a host where tor cannot run`() = runTest {
        /*
         * The trap. With no native library the stored intent was ON, the switch was disabled, and
         * the toggle handler tested availability for both directions -- so this call early-returned
         * and there was no path in the whole application that could set the mode back to off.
         */
        val viewModel = buildViewModel(
            torAvailability = TorAvailability.NATIVE_LIBRARY_MISSING,
            torMode = TorMode.ON,
            torStatus = TorStatus(state = TorState.ERROR, errorMessage = unavailableDetail),
        )
        instantExecutorRule.scheduler.runCurrent()

        viewModel.onTorNetworkToggled(false)
        instantExecutorRule.scheduler.runCurrent()

        coVerify(exactly = 1) { disableTor(Unit) }
    }

    @Test
    fun `turning it on is still refused where tor cannot run`() = runTest {
        val viewModel = buildViewModel(
            torAvailability = TorAvailability.NATIVE_LIBRARY_MISSING,
            torMode = TorMode.OFF,
            torStatus = TorStatus(state = TorState.ERROR, errorMessage = unavailableDetail),
        )
        instantExecutorRule.scheduler.runCurrent()

        viewModel.onTorNetworkToggled(true)
        instantExecutorRule.scheduler.runCurrent()

        coVerify(exactly = 0) { enableTor(Unit) }
    }

    @Test
    fun `the switch follows requested intent, not whether tor managed to start`() = runTest {
        // Bound to the effective mode, a failed start rendered this unchecked while it was also
        // disabled, so the user could neither see nor express their own choice.
        val viewModel = buildViewModel(
            torAvailability = TorAvailability.NATIVE_LIBRARY_MISSING,
            torMode = TorMode.ON,
            torStatus = TorStatus(state = TorState.ERROR, errorMessage = unavailableDetail),
        )
        instantExecutorRule.scheduler.runCurrent()

        assertTrue(viewModel.state.value.torNetworkEnabled)
        assertEquals(TorMode.ON, viewModel.state.value.requestedTorMode)
    }

    @Test
    fun `a stored ON is not displayed as on where the engine cannot proxy at all`() = runTest {
        // Different from a missing library: here Tor could bootstrap and still protect nothing, so
        // showing the switch on would read as protection that does not exist.
        val viewModel = buildViewModel(
            torAvailability = TorAvailability.NO_PROXY_SUPPORT,
            torMode = TorMode.ON,
            torStatus = TorStatus(),
        )
        instantExecutorRule.scheduler.runCurrent()

        assertFalse(viewModel.state.value.torNetworkEnabled)
        assertEquals(TorMode.ON, viewModel.state.value.requestedTorMode, "the intent still stands")
    }
}
