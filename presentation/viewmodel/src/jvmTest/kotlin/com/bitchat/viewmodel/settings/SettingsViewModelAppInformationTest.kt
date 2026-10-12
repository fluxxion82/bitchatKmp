package com.bitchat.viewmodel.settings

import com.bitchat.domain.app.DisableBackgroundMode
import com.bitchat.domain.app.EnableBackgroundMode
import com.bitchat.domain.app.GetAppTheme
import com.bitchat.domain.app.ObserveBackgroundMode
import com.bitchat.domain.app.SetAppTheme
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.model.BackgroundMode
import com.bitchat.domain.initialization.GetAppInformation
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.domain.lora.GetAvailableLoRaProtocols
import com.bitchat.domain.lora.GetLoRaSettings
import com.bitchat.domain.lora.SetLoRaEnabled
import com.bitchat.domain.lora.SetLoRaRegion
import com.bitchat.domain.lora.SetLoRaTxPower
import com.bitchat.domain.lora.SetShowLoRaPeers
import com.bitchat.domain.lora.SwitchLoRaProtocol
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaSettings
import com.bitchat.domain.nostr.GetPowSettings
import com.bitchat.domain.nostr.SetPowSettings
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.tor.DisableTor
import com.bitchat.domain.tor.EnableTor
import com.bitchat.domain.tor.GetTorAvailability
import com.bitchat.domain.tor.ObserveTorStatus
import com.bitchat.domain.tor.ObserveRequestedTorMode
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.viewmodel.BaseViewModelTest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * The Settings screens say which build is running, and they take it from the app's own
 * [AppInformation]: neither of the two boards can be told from the other on its own screen
 * otherwise, and the version used to be a literal in the view model that matched no build at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelAppInformationTest : BaseViewModelTest() {

    /** What `bitchat-tui --version` prints, and what the embedded build puts in `additionalInfo`. */
    private val identityLine =
        "bitchat-tui 1.0.0 (65d65087cd41, main, dirty, release, built 2026-09-30T10:11:12-07:00)"

    private fun buildViewModel(
        appInformation: AppInformation,
        backgroundMode: Flow<BackgroundMode> = flowOf(BackgroundMode.OFF),
        showBackgroundModeSetting: Boolean = false,
    ): SettingsViewModel {
        val theme = mockk<GetAppTheme> { coEvery { this@mockk.invoke(Unit) } returns AppTheme.SYSTEM }
        val pow = mockk<GetPowSettings> { coEvery { this@mockk.invoke(Unit) } returns PowSettings() }
        val background = mockk<ObserveBackgroundMode> { coEvery { this@mockk.invoke(Unit) } returns backgroundMode }
        val lora = mockk<GetLoRaSettings> { coEvery { this@mockk.invoke(Unit) } returns LoRaSettings() }
        val torStatus = mockk<ObserveTorStatus> { coEvery { this@mockk.invoke(Unit) } returns flowOf(TorStatus()) }
        val torMode = mockk<ObserveRequestedTorMode> { coEvery { this@mockk.invoke(Unit) } returns flowOf(TorMode.OFF) }
        val torAvailable = mockk<GetTorAvailability> { coEvery { this@mockk.invoke(Unit) } returns TorAvailability.AVAILABLE }
        return SettingsViewModel(
            setAppTheme = mockk<SetAppTheme>(relaxed = true), getAppTheme = theme,
            setPowSettings = mockk<SetPowSettings>(relaxed = true), getPowSettings = pow,
            observeTorStatus = torStatus, observeRequestedTorMode = torMode,
            enableTor = mockk<EnableTor>(relaxed = true), disableTor = mockk<DisableTor>(relaxed = true),
            getTorAvailability = torAvailable, observeBackgroundMode = background,
            enableBackgroundMode = mockk<EnableBackgroundMode>(relaxed = true),
            disableBackgroundMode = mockk<DisableBackgroundMode>(relaxed = true),
            showBackgroundModeSetting = showBackgroundModeSetting, getLoRaSettings = lora,
            setLoRaEnabled = mockk<SetLoRaEnabled>(relaxed = true),
            setLoRaRegion = mockk<SetLoRaRegion>(relaxed = true),
            setLoRaTxPower = mockk<SetLoRaTxPower>(relaxed = true),
            setShowLoRaPeers = mockk<SetShowLoRaPeers>(relaxed = true),
            switchLoRaProtocol = mockk<SwitchLoRaProtocol>(relaxed = true),
            getAvailableLoRaProtocols = mockk<GetAvailableLoRaProtocols> { coEvery { this@mockk.invoke(Unit) } returns LoRaProtocolType.entries },
            getAppInformation = GetAppInformation(appInformation),
        ).also { instantExecutorRule.scheduler.runCurrent() }
    }

    private fun information(name: String, additionalInfo: String) = AppInformation(
        version = Version(name = name, build = "65d65087cd41", additionalInfo = additionalInfo),
        versionCode = 1,
        id = "com.bitchat.tui",
        debug = false,
    )

    @Test
    fun `the background switch appears with the saved value and not before it is known`() = runTest {
        val saved = MutableSharedFlow<BackgroundMode>(extraBufferCapacity = 1)
        val viewModel = buildViewModel(information("1.0.0", identityLine), saved, showBackgroundModeSetting = true)
        assertFalse(viewModel.state.value.showBackgroundModeSetting)

        saved.tryEmit(BackgroundMode.ON)
        instantExecutorRule.scheduler.runCurrent()

        assertTrue(viewModel.state.value.showBackgroundModeSetting)
        assertTrue(viewModel.state.value.backgroundModeEnabled)
    }

    @Test
    fun `an embedded build's identity reaches the settings state as it was written`() = runTest {
        val state = buildViewModel(information("1.0.0", identityLine)).state.value

        assertEquals("1.0.0", state.appVersion)
        assertEquals(identityLine, state.buildIdentity)
    }

    @Test
    fun `the version is the build's own, not a literal`() = runTest {
        // "1.5.1" is what the view model used to say whatever was running.
        val state = buildViewModel(information("2.7.3", identityLine)).state.value

        assertEquals("2.7.3", state.appVersion)
    }

    @Test
    fun `a build with no identity has none to show`() = runTest {
        assertNull(buildViewModel(information("1.0.0", "")).state.value.buildIdentity)
        assertNull(buildViewModel(information("1.0.0", "  ")).state.value.buildIdentity)
    }
}
