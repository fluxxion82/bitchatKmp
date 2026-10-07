package com.bitchat.local.prefs

import com.bitchat.domain.lora.model.LoRaBandwidth
import com.bitchat.local.prefs.impl.LocalLoRaPreferences
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalLoRaPreferencesTest {
    @Test
    fun bandwidthDefaultsTo125KhzWhenNothingIsStored() {
        val preferences = preferences()

        assertEquals(LoRaBandwidth.KHZ_125, preferences.getBandwidth())
    }

    @Test
    fun bandwidthRoundTripsEachValue() {
        LoRaBandwidth.entries.forEach { bandwidth ->
            val settings = PropertiesSettings(Properties())
            val preferences = preferences(settings)

            preferences.setBandwidth(bandwidth)

            assertEquals(bandwidth, preferences(settings).getBandwidth())
        }
    }

    @Test
    fun unknownStoredBandwidthDefaultsTo125Khz() {
        val settings = PropertiesSettings(Properties()).apply {
            putString("lora_bandwidth", "NOT_A_BANDWIDTH")
        }
        val preferences = preferences(settings)

        assertEquals(LoRaBandwidth.KHZ_125, preferences.getBandwidth())
    }

    private fun preferences(settings: Settings = PropertiesSettings(Properties())): LocalLoRaPreferences =
        LocalLoRaPreferences(object : Settings.Factory {
            override fun create(name: String?): Settings = settings
        })
}
