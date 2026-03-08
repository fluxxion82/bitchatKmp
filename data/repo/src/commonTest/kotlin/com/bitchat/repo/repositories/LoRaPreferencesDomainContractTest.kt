package com.bitchat.repo.repositories

import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.local.prefs.LoRaPreferences
import kotlin.test.Test
import kotlin.test.assertNotNull

class LoRaPreferencesDomainContractTest {
    @Test
    fun loraPreferencesUsesDomainEnums() {
        val setRegion: (LoRaPreferences, LoRaRegion) -> Unit = LoRaPreferences::setLoRaRegion
        val setPower: (LoRaPreferences, LoRaTxPower) -> Unit = LoRaPreferences::setTxPower
        val setProtocol: (LoRaPreferences, LoRaProtocolType) -> Unit = LoRaPreferences::setLoRaProtocol

        assertNotNull(setRegion)
        assertNotNull(setPower)
        assertNotNull(setProtocol)
    }
}
