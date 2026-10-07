package com.bitchat.repo.lora

import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaBandwidth
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.local.prefs.LoRaPreferences
import com.bitchat.lora.radio.LoRaConfig

/** Existing BitChat RF profile, shared by startup, explicit selection and reconfiguration. */
internal fun loRaConfiguration(
    region: LoRaRegion,
    txPower: LoRaTxPower,
    bandwidth: LoRaBandwidth = LoRaBandwidth.KHZ_125,
): LoRaConfig = LoRaConfig(
    frequency = when (region) {
        LoRaRegion.US_915, LoRaRegion.AU_915 -> 915_125_000L
        LoRaRegion.EU_868 -> 868_125_000L
        LoRaRegion.AS_923 -> 923_125_000L
    },
    txPower = txPower.dBm,
    bandwidth = bandwidth.hz,
    syncWord = if (region == LoRaRegion.EU_868) 0x12 else 0xBC,
    spreadingFactor = 9,
)

internal fun LoRaPreferences.toLoRaConfiguration(): LoRaConfig =
    loRaConfiguration(getLoRaRegion(), getTxPower(), getBandwidth())
