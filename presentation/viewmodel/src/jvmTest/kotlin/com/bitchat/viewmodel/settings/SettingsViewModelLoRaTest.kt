package com.bitchat.viewmodel.settings

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
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaSettings
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.nostr.GetPowSettings
import com.bitchat.domain.nostr.SetPowSettings
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.tor.DisableTor
import com.bitchat.domain.tor.EnableTor
import com.bitchat.domain.tor.GetTorAvailability
import com.bitchat.domain.tor.GetTorStatus
import com.bitchat.domain.tor.ObserveRequestedTorMode
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.viewmodel.BaseViewModelTest
import com.bitchat.viewvo.settings.LoRaSwitchStatus
import com.bitchat.viewvo.settings.LoRaSettingsOperation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelLoRaTest : BaseViewModelTest() {
    private val switchProtocol = mockk<SwitchLoRaProtocol>()
    private val changeRegion = mockk<SetLoRaRegion>()
    private val changePower = mockk<SetLoRaTxPower>()

    private fun buildViewModel(protocol: LoRaProtocolType = LoRaProtocolType.MESHCORE): SettingsViewModel {
        val theme = mockk<GetAppTheme> { coEvery { this@mockk.invoke(Unit) } returns flowOf(AppTheme.SYSTEM) }
        val pow = mockk<GetPowSettings> { coEvery { this@mockk.invoke() } returns flowOf(PowSettings()) }
        val background = mockk<GetBackgroundMode> { coEvery { this@mockk.invoke(Unit) } returns flowOf(BackgroundMode.OFF) }
        val lora = mockk<GetLoRaSettings> { coEvery { this@mockk.invoke(Unit) } returns LoRaSettings(protocol = protocol) }
        val torStatus = mockk<GetTorStatus> { coEvery { this@mockk.invoke(Unit) } returns flowOf(TorStatus()) }
        val torMode = mockk<ObserveRequestedTorMode> { coEvery { this@mockk.invoke(Unit) } returns flowOf(TorMode.OFF) }
        val torAvailable = mockk<GetTorAvailability> { coEvery { this@mockk.invoke(Unit) } returns TorAvailability.NO_PROXY_SUPPORT }
        return SettingsViewModel(
            setAppTheme = mockk<SetAppTheme>(relaxed = true), getAppTheme = theme,
            setPowSettings = mockk<SetPowSettings>(relaxed = true), getPowSettings = pow,
            getTorStatus = torStatus, observeRequestedTorMode = torMode,
            enableTor = mockk<EnableTor>(relaxed = true), disableTor = mockk<DisableTor>(relaxed = true),
            getTorAvailability = torAvailable, getBackgroundMode = background,
            enableBackgroundMode = mockk<EnableBackgroundMode>(relaxed = true),
            disableBackgroundMode = mockk<DisableBackgroundMode>(relaxed = true),
            showBackgroundModeSetting = false, getLoRaSettings = lora,
            setLoRaEnabled = mockk<SetLoRaEnabled>(relaxed = true),
            setLoRaRegion = changeRegion,
            setLoRaTxPower = changePower,
            setShowLoRaPeers = mockk<SetShowLoRaPeers>(relaxed = true),
            switchLoRaProtocol = switchProtocol,
        ).also { instantExecutorRule.scheduler.runCurrent() }
    }

    @Test
    fun `loading a saved choice does not report a successful connection`() = runTest {
        val viewModel = buildViewModel()
        assertEquals(LoRaProtocolType.MESHCORE, viewModel.state.value.loraProtocol)
        assertEquals(LoRaSwitchStatus.IDLE, viewModel.state.value.loraSwitchStatus)
        assertNull(viewModel.state.value.loraSwitchError)
    }

    @Test
    fun `failed switch shows progress then failure with selected preference and retry guidance`() = runTest {
        val completion = CompletableDeferred<Boolean>()
        coEvery { switchProtocol(LoRaProtocolType.MESHTASTIC) } coAnswers { completion.await() }
        val viewModel = buildViewModel()

        viewModel.onLoRaProtocolSelected(LoRaProtocolType.MESHTASTIC)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaSwitchStatus.SWITCHING, viewModel.state.value.loraSwitchStatus)
        assertEquals(LoRaProtocolType.MESHTASTIC, viewModel.state.value.loraProtocol)

        completion.complete(false)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaSwitchStatus.FAILED, viewModel.state.value.loraSwitchStatus)
        assertEquals(LoRaProtocolType.MESHTASTIC, viewModel.state.value.loraProtocol)
        assertTrue(assertNotNull(viewModel.state.value.loraSwitchError).contains("retry", ignoreCase = true))
    }

    @Test
    fun `explicit retry clears failure and only reports readiness after success`() = runTest {
        coEvery { switchProtocol(LoRaProtocolType.MESHCORE) } returnsMany listOf(false, true)
        val viewModel = buildViewModel()
        viewModel.onLoRaProtocolSelected(LoRaProtocolType.MESHCORE)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaSwitchStatus.FAILED, viewModel.state.value.loraSwitchStatus)

        viewModel.onLoRaProtocolSelected(LoRaProtocolType.MESHCORE)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaSwitchStatus.READY, viewModel.state.value.loraSwitchStatus)
        assertNull(viewModel.state.value.loraSwitchError)
        coVerify(exactly = 2) { switchProtocol(LoRaProtocolType.MESHCORE) }
    }

    @Test
    fun `exception becomes actionable failure and permits a later attempt`() = runTest {
        coEvery { switchProtocol(LoRaProtocolType.MESHCORE) } throws IllegalStateException("systemctl failed")
        val viewModel = buildViewModel()
        viewModel.onLoRaProtocolSelected(LoRaProtocolType.MESHCORE)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaSwitchStatus.FAILED, viewModel.state.value.loraSwitchStatus)
        assertNotNull(viewModel.state.value.loraSwitchError)
        coEvery { switchProtocol(LoRaProtocolType.MESHCORE) } returns true
        viewModel.onLoRaProtocolSelected(LoRaProtocolType.MESHCORE)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaSwitchStatus.READY, viewModel.state.value.loraSwitchStatus)
        assertNull(viewModel.state.value.loraSwitchError)
    }

    @Test
    fun `cancellation is not converted to readiness or a radio failure`() = runTest {
        coEvery { switchProtocol(LoRaProtocolType.MESHCORE) } throws CancellationException("ViewModel cleared")
        val viewModel = buildViewModel()
        viewModel.onLoRaProtocolSelected(LoRaProtocolType.MESHCORE)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaSwitchStatus.IDLE, viewModel.state.value.loraSwitchStatus)
        assertNull(viewModel.state.value.loraSwitchError)
    }

    @Test
    fun `region failure retains preference and distinguishes unapplied daemon settings`() = runTest {
        coEvery { changeRegion(any()) } returns false
        val viewModel = buildViewModel()
        viewModel.onLoRaRegionSelected(LoRaRegion.EU_868)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaRegion.EU_868, viewModel.state.value.loraRegion)
        assertEquals(LoRaSettingsOperation.REGION, viewModel.state.value.loraOperation)
        assertEquals(LoRaSwitchStatus.FAILED, viewModel.state.value.loraSwitchStatus)
        assertTrue(assertNotNull(viewModel.state.value.loraSwitchError).contains("not applied"))
        assertTrue(assertNotNull(viewModel.state.value.loraSwitchError).contains("MeshCore"))
    }

    @Test
    fun `power update reports progress then failure and can be retried`() = runTest {
        val first = CompletableDeferred<Boolean>()
        coEvery { changePower(any()) } coAnswers { first.await() }
        val viewModel = buildViewModel(LoRaProtocolType.BITCHAT)
        viewModel.onLoRaTxPowerSelected(LoRaTxPower.LOW)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaTxPower.LOW, viewModel.state.value.loraTxPower)
        assertEquals(LoRaSwitchStatus.SWITCHING, viewModel.state.value.loraSwitchStatus)
        first.complete(false)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaSwitchStatus.FAILED, viewModel.state.value.loraSwitchStatus)

        coEvery { changePower(any()) } returns true
        viewModel.onLoRaRetry()
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaSettingsOperation.TX_POWER, viewModel.state.value.loraOperation)
        assertEquals(LoRaSwitchStatus.READY, viewModel.state.value.loraSwitchStatus)
        assertNull(viewModel.state.value.loraSwitchError)
        coVerify(exactly = 0) { switchProtocol(any()) }
    }

    @Test
    fun `region cancellation clears progress without converting it into failure`() = runTest {
        coEvery { changeRegion(any()) } throws CancellationException("Cancelled")
        val viewModel = buildViewModel(LoRaProtocolType.BITCHAT)
        viewModel.onLoRaRegionSelected(LoRaRegion.EU_868)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaRegion.EU_868, viewModel.state.value.loraRegion)
        assertEquals(LoRaSwitchStatus.IDLE, viewModel.state.value.loraSwitchStatus)
        assertNull(viewModel.state.value.loraSwitchError)
    }

    @Test
    fun `queued selections cannot publish a completed state for an unfinished switch`() = runTest {
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        coEvery { switchProtocol(LoRaProtocolType.MESHTASTIC) } coAnswers { first.await() }
        coEvery { switchProtocol(LoRaProtocolType.BITCHAT) } coAnswers { second.await() }
        val viewModel = buildViewModel()
        viewModel.onLoRaProtocolSelected(LoRaProtocolType.MESHTASTIC)
        viewModel.onLoRaProtocolSelected(LoRaProtocolType.BITCHAT)
        instantExecutorRule.scheduler.runCurrent()
        coVerify(exactly = 0) { switchProtocol(LoRaProtocolType.BITCHAT) }

        first.complete(true)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaProtocolType.BITCHAT, viewModel.state.value.loraProtocol)
        assertEquals(LoRaSwitchStatus.SWITCHING, viewModel.state.value.loraSwitchStatus)

        second.complete(false)
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(LoRaSwitchStatus.FAILED, viewModel.state.value.loraSwitchStatus)
    }
}
