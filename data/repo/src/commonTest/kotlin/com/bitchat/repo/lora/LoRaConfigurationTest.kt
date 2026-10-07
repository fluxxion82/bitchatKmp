package com.bitchat.repo.lora

import com.bitchat.domain.lora.model.LoRaBandwidth
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.local.prefs.LoRaPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class LoRaConfigurationTest {
    @Test
    fun defaultBandwidthIs125Khz() {
        val config = loRaConfiguration(LoRaRegion.US_915, LoRaTxPower.MEDIUM)

        assertEquals(125_000L, config.bandwidth)
    }

    @Test
    fun eachBandwidthValueReachesTheRadioConfigurationWithoutChangingTheRfProfile() {
        LoRaBandwidth.entries.forEach { bandwidth ->
            val config = loRaConfiguration(LoRaRegion.EU_868, LoRaTxPower.LOW, bandwidth)

            assertEquals(bandwidth.hz, config.bandwidth)
            assertEquals(868_125_000L, config.frequency)
            assertEquals(9, config.spreadingFactor)
            assertEquals(0x12, config.syncWord)
            assertEquals(10, config.txPower)
        }
    }

    @Test
    fun savedBandwidthIsUsedForTheRadioConfiguration() {
        val preferences = TestLoRaPreferences(bandwidth = LoRaBandwidth.KHZ_500)

        assertEquals(500_000L, preferences.toLoRaConfiguration().bandwidth)
    }

    private class TestLoRaPreferences(
        private val bandwidth: LoRaBandwidth,
    ) : LoRaPreferences {
        override fun isLoRaEnabled() = true
        override fun setLoRaEnabled(enabled: Boolean) = Unit
        override fun getLoRaRegion() = LoRaRegion.US_915
        override fun setLoRaRegion(region: LoRaRegion) = Unit
        override fun getTxPower() = LoRaTxPower.MEDIUM
        override fun setTxPower(power: LoRaTxPower) = Unit
        override fun getBandwidth() = bandwidth
        override fun setBandwidth(bandwidth: LoRaBandwidth) = Unit
        override fun isShowLoRaPeersEnabled() = true
        override fun setShowLoRaPeersEnabled(enabled: Boolean) = Unit
        override fun getLoRaProtocol() = LoRaProtocolType.BITCHAT
        override fun setLoRaProtocol(protocol: LoRaProtocolType) = Unit
    }
}
