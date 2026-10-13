package com.bitchat.domain.lora

import com.bitchat.domain.base.model.Outcome
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaSettings
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.lora.model.failure.LoRaFailure
import com.bitchat.domain.lora.repository.LoRaSettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwitchLoRaProtocolTest {
    private class Settings : LoRaSettingsRepository {
        var value = LoRaSettings()
        override fun getLoRaSettings() = value
        override fun setLoRaEnabled(enabled: Boolean) { value = value.copy(enabled = enabled) }
        override fun setLoRaRegion(region: LoRaRegion) { value = value.copy(region = region) }
        override fun setLoRaTxPower(power: LoRaTxPower) { value = value.copy(txPower = power) }
        override fun setShowLoRaPeers(show: Boolean) { value = value.copy(showPeers = show) }
        override fun setLoRaProtocol(protocol: LoRaProtocolType) { value = value.copy(protocol = protocol) }
        override fun getAvailableLoRaProtocols() = LoRaProtocolType.entries
    }

    @Test
    fun `failed startup returns failure and keeps selected protocol for next boot`() = runTest {
        val settings = Settings()
        val chat = mockk<ChatRepository>()
        coEvery { chat.switchLoRaProtocol(LoRaProtocolType.MESHCORE) } coAnswers {
            assertEquals(LoRaProtocolType.MESHCORE, settings.value.protocol)
            Outcome.Error(LoRaFailure.NotApplied)
        }

        val result = SwitchLoRaProtocol(settings, chat)(LoRaProtocolType.MESHCORE)

        assertEquals(Outcome.Error(LoRaFailure.NotApplied), result)
        assertEquals(LoRaProtocolType.MESHCORE, settings.value.protocol)
    }

    @Test
    fun `ready switch returns success`() = runTest {
        val settings = Settings()
        val chat = mockk<ChatRepository>()
        coEvery { chat.switchLoRaProtocol(LoRaProtocolType.MESHTASTIC) } returns Outcome.Success(Unit)

        val result = SwitchLoRaProtocol(settings, chat)(LoRaProtocolType.MESHTASTIC)

        assertEquals(Outcome.Success(Unit), result)
        assertEquals(LoRaProtocolType.MESHTASTIC, settings.value.protocol)
    }

    @Test
    fun `cancelled switch propagates cancellation while retaining preference`() = runTest {
        val settings = Settings()
        val chat = mockk<ChatRepository>()
        coEvery { chat.switchLoRaProtocol(LoRaProtocolType.MESHCORE) } throws CancellationException("Screen closed")

        assertFailsWith<CancellationException> {
            SwitchLoRaProtocol(settings, chat)(LoRaProtocolType.MESHCORE)
        }
        assertEquals(LoRaProtocolType.MESHCORE, settings.value.protocol)
    }
}
