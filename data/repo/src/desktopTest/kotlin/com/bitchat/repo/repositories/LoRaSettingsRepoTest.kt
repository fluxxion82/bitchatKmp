package com.bitchat.repo.repositories

import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.local.prefs.LoRaPreferences
import com.bitchat.lora.LoRaProtocolManager
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import com.bitchat.lora.LoRaProtocolType as RadioProtocol

class LoRaSettingsRepoTest {
    private val preferences = mockk<LoRaPreferences> {
        every { isLoRaEnabled() } returns true
        every { getLoRaRegion() } returns LoRaRegion.US_915
        every { getTxPower() } returns LoRaTxPower.MEDIUM
        every { isShowLoRaPeersEnabled() } returns true
        every { getLoRaProtocol() } returns LoRaProtocolType.MESHCORE
    }

    private fun repo(vararg registered: RadioProtocol) = LoRaSettingsRepo(
        preferences,
        mockk<LoRaProtocolManager> { every { availableTypes } returns registered.toSet() },
    )

    @Test
    fun `offers the protocols the app registered`() {
        assertEquals(
            listOf(LoRaProtocolType.BITCHAT, LoRaProtocolType.MESHTASTIC),
            repo(RadioProtocol.MESHTASTIC, RadioProtocol.BITCHAT).getAvailableLoRaProtocols(),
        )
        assertEquals(emptyList(), LoRaSettingsRepo(preferences).getAvailableLoRaProtocols())
    }

    @Test
    fun `a saved protocol the app cannot run reads as the fallback`() {
        assertEquals(
            LoRaProtocolType.BITCHAT,
            repo(RadioProtocol.BITCHAT, RadioProtocol.MESHTASTIC).getLoRaSettings().protocol,
        )
    }

    @Test
    fun `a saved protocol the app can run is kept`() {
        assertEquals(
            LoRaProtocolType.MESHCORE,
            repo(RadioProtocol.BITCHAT, RadioProtocol.MESHTASTIC, RadioProtocol.MESHCORE).getLoRaSettings().protocol,
        )
        assertEquals(LoRaProtocolType.MESHCORE, LoRaSettingsRepo(preferences).getLoRaSettings().protocol)
    }
}
